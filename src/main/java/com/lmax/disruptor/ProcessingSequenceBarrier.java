/*
 * Copyright 2011 LMAX Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lmax.disruptor;


/**
 * {@link SequenceBarrier} handed out for gating {@link EventProcessor}s on a cursor sequence and optional dependent {@link EventProcessor}(s),
 * using the given WaitStrategy.
 */
final class ProcessingSequenceBarrier implements SequenceBarrier
{
    /**
     * 使用给定的 WaitStrategy，SequenceBarrier 分发给游标序列上的事件处理器和可选的依赖事件处理器。
     */
    private final WaitStrategy waitStrategy;
    /**
     *  //这个域可能指向一个序列组。
     */
    private final Sequence dependentSequence;
    private volatile boolean alerted = false;
    private final Sequence cursorSequence;
    private final Sequencer sequencer;

    ProcessingSequenceBarrier(
            final Sequencer sequencer,
            final WaitStrategy waitStrategy,
            final Sequence cursorSequence,
            final Sequence[] dependentSequences)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        /**
         *
         * 注意cursorSequence和dependentSequence的区别
         *
         *    第一个BatchEventProcessor
         *        // Construct 2 batch event processors.
         *         DynamicHandler handler1 = new DynamicHandler();
         *         BatchEventProcessor<StubEvent> processor1 =
         *                 new BatchEventProcessor<>(ringBuffer, ringBuffer.newBarrier(), handler1);
         *
         *   第二个BatchEventProcessor
         *
         *    BatchEventProcessor<StubEvent> processor2 =
         *    new BatchEventProcessor<>(ringBuffer, ringBuffer.newBarrier(processor1.getSequence()), handler2);
         *
         * 一般我们要为每一个BatchEventProcessor对象创建一个SequenceBarrier, 创建SequenceBarrier对象的方式就是 RingBuffer.newBarrier，
         * newBarrier方法中接收的参数  是多个Sequence，这些Sequence对象会被作为 SequenceBarrier对象中的dependentSequence.
         *
         * 而在RingBuffer的newBarrier方法内部 会交给RingBuffer对象中的Sequencer对象来创建 SequenceBarrier，在创建SequenceBarrier对象的时候
         *
         * Sequencer对象会将其内部属性Sequence cursor 传给SequenceBarrier对象的cursorSequence属性
         *
         * 也就是说： 多个不同的SequenceBarrier对象的cursorSequence属性是相同的，他们都是RingBuffer的Sequencer对象的Sequence cursor对象，用来记录RIngBuffer中生产者写入数据的位置。
         *
         * dependentSequence是当前SequenceBarrier所依赖的 消费者的Sequence对象。
         *
         * 只有当前SequenceBarrier 所依赖的消费者 消费完了事件 当前的SequenceBarrier才可以通知其 管控的一批BatchEventProcessor 对事件进行消费。
         *
         *
         * 而且SequenceBarrier 只能有一个 依赖的 Sequence，也就意味着只能依赖一个BatchEventProcessor消费者。
         *
         */
        this.cursorSequence = cursorSequence;
        if (0 == dependentSequences.length)
        {
            /**
             *  如果dependentSequence长度为0，意味着 当前SequenceBarrier 所管理的一批消费者BatchEventProcessor 不依赖任何其他消费者，他只取决于RingBuffer对象中是否有数据可以消费
             *  实际上就是取决于RingBuffer对象中的Sequencer对象 中的Sequence cursor属性， 因此这里将SequenceBarrier的dependentSequence属性设置为 传递过来的Sequencer对象中的cursor属性。
             */
            dependentSequence = cursorSequence;
        }
        else
        {
            dependentSequence = new FixedSequenceGroup(dependentSequences);

        }
    }

    @Override
    public long waitFor(final long sequence)
            throws AlertException, InterruptedException, TimeoutException
    {
        checkAlert();
        /**
         *     //然后根据等待策略来等待可用的序列值。
         */
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);

        if (availableSequence < sequence)
        {
            /**
             * /如果可用的序列值小于给定的序列，那么直接返回。
             */

            return availableSequence;

        }
        //否则，要返回能安全使用的最大的序列值。
        /**
         * 获取最大的可用的已经发布的Sequence，可能比sequence小。
         * 会在多生产者中出现，当生产者1获取到序号13，生产者2获取到14，生产者1没有发布， 生产者2发布，会导致获取的可用序号为12，而sequence为13
         *
         *
         * *       cursorSequence对象 是 RingBuffer对象中的Sequencer对象的cursor属性引用的对象，他代表着生产真在RingBuffer中写入数据的最后的位置。
         *          *  正常情况下 生产者首先 声名 对RingBuffer中某个位置的占领，其实就是通过Sequencer的next方法获取  对RingBuffer中某个位置的使用权。
         *          *  在 单生产者模式下，SingleProducerSequencer 对象中通过 nextValue维持当前 生产者获取到在RingBuffer中的位置。 next方法中将nextValue
         *          *  作为返回值交给Producer，然后Producer通过这个位置获取到对应位置的对象，将数据写入然后发布。 而且一般情况下SingleProducerSequencer的next方法中
         *          *  是不会对Sequencer对象的cursor进行修改，只有生产者在publish的时候才会更改Sequencer对象中的cursor。
         *          *
         *          *  但是在多生产者模式中  我们要考虑的是 多个线程同时执行next方法 声明获取 RingBuffer中的某些位置，一旦我们将这个位置交给了这个生产者，则
         *          *  需要保证这个位置不会被其他线程重复使用。 因此你胡看到在MultiProducerSequencer的next方法中 会对生产者游标cursor直接进行修改。
         *          *  也就是先进行 long current = cursor.getAndAdd(n); 这会 使用cas来更新cursor中保存的值。从而获得占用。 这种方式会存在两个问题：
         *          *  （1）增加了cursor， 我们需要判断当前生产者获取到的位置 是否能够写入数据。 这个逻辑在单个生产者中也会校验
         *          *  （2）即便假设 当前生产者增加cursor后获取到的位置可以写入数据，但是 在next方法返回之前 生产者并未真实写入数据，而仅仅是增加了cursor。
         *          *  而在单生产者模式中，是先写入数据，然后通过publish方法增加cursor。  那么多生产者模式下 ，先增加了cursor 然后过了一段时间才将数据写入，然后才publish。
         *          *  这个会不会对消费者产生影响呢？  因为在消费者 中 会使用cursor.get() 来判断  消费者期望的位置是否 小于生产者的位置，如果消费者期望的位置 小于生产者的位置
         *          *  则 表示消费者可以 消费数据了。但是实际上这个数据还尚未写入。 因此在多生产者模式中 cursor不可以被消费者用来判读该位置是否有数据可消费。
         *          *
         *          * 因此在ProcessingSequenceBarrier对象的waitFor方法中，一方面 会使用waitStrategy的waitFor方法判断 对于当前消费者是否有可用数据消费，
         *          * 另一方面会通过SequenceBarrier对象中持有的sequencer对象的getHighestPublishedSequence方法来判断 位置是否可用。 在MultiProducerSequencer对象中是通过
         *          * 判断消费者期望消费的位置 是否在MultiProducerSequencer的availableBuffer 数组中被存储了，因为在MultiProducerSequencer写完数据 publish sequence的时候会将这个
         *          * 位置写入availableBuffer数组中。
         *          *
         *
         */
        return sequencer.getHighestPublishedSequence(sequence, availableSequence);

    }

    @Override
    public long getCursor()
    {
        return dependentSequence.get();
    }

    @Override
    public boolean isAlerted()
    {
        return alerted;
    }

    @Override
    public void alert()
    {
        /**
         * disruptor shutdown的时候 会调用每个BatchEventProcessor的halt方法。
         * 而每个halt方法内部会执行 SequenceBarrier的alert，alert内部会使用waitStrategy唤醒所有等待的消费者线程。
         *
         * 同时SequenceBarrier内部会设置alert属性为true。 然后唤醒所有消费者线程，消费者线程执行 SequenceBarrier.waitFor的时候
         *
         * SequenceBarrier对象户检查自身属性alert如果为true 就抛出异常，从而导致 消费者线程的退出。
         *
         */
        alerted = true;
        waitStrategy.signalAllWhenBlocking();
    }

    @Override
    public void clearAlert()
    {
        alerted = false;
    }

    @Override
    public void checkAlert() throws AlertException
    {
        if (alerted)
        {
            throw AlertException.INSTANCE;
        }
    }
}