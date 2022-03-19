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
 * Blocking strategy that uses a lock and condition variable for {@link EventProcessor}s waiting on a barrier.

 *
 * <p>This strategy can be used when throughput and low-latency are not as important as CPU resource.
 */
public final class BlockingWaitStrategy implements WaitStrategy
{
    private final Object mutex = new Object();

    @Override
    public long waitFor(final long sequence, final Sequence cursorSequence, final Sequence dependentSequence, final SequenceBarrier barrier)
        throws AlertException, InterruptedException
    {
        long availableSequence;
        /**
         *  cursorSequence对象是SequenceBarrier对象的cursorSequence属性，而这个属性是来自RingBuffer的Sequencer对象属性中的Sequence cursor对象
         *  记录的是RingBuffer 中生产者写入数据的位置。
         *
         *  因此如果 生产者写入数据的位置小于 当前消费者期望读取的位置sequence，则挂起当前消费者线程。
         *
         *  ==================================================================
         *  cursorSequence对象 是 RingBuffer对象中的Sequencer对象的cursor属性引用的对象，他代表着生产真在RingBuffer中写入数据的最后的位置。
         *  正常情况下 生产者首先 声名 对RingBuffer中某个位置的占领，其实就是通过Sequencer的next方法获取  对RingBuffer中某个位置的使用权。
         *  在 单生产者模式下，SingleProducerSequencer 对象中通过 nextValue维持当前 生产者获取到在RingBuffer中的位置。 next方法中将nextValue
         *  作为返回值交给Producer，然后Producer通过这个位置获取到对应位置的对象，将数据写入然后发布。 而且一般情况下SingleProducerSequencer的next方法中
         *  是不会对Sequencer对象的cursor进行修改，只有生产者在publish的时候才会更改Sequencer对象中的cursor。
         *
         *  但是在多生产者模式中  我们要考虑的是 多个线程同时执行next方法 声明获取 RingBuffer中的某些位置，一旦我们将这个位置交给了这个生产者，则
         *  需要保证这个位置不会被其他线程重复使用。 因此你胡看到在MultiProducerSequencer的next方法中 会对生产者游标cursor直接进行修改。
         *  也就是先进行 long current = cursor.getAndAdd(n); 这会 使用cas来更新cursor中保存的值。从而获得占用。 这种方式会存在两个问题：
         *  （1）增加了cursor， 我们需要判断当前生产者获取到的位置 是否能够写入数据。 这个逻辑在单个生产者中也会校验
         *  （2）即便假设 当前生产者增加cursor后获取到的位置可以写入数据，但是 在next方法返回之前 生产者并未真实写入数据，而仅仅是增加了cursor。
         *  而在单生产者模式中，是先写入数据，然后通过publish方法增加cursor。  那么多生产者模式下 ，先增加了cursor 然后过了一段时间才将数据写入，然后才publish。
         *  这个会不会对消费者产生影响呢？  因为在消费者 中 会使用cursor.get() 来判断  消费者期望的位置是否 小于生产者的位置，如果消费者期望的位置 小于生产者的位置
         *  则 表示消费者可以 消费数据了。但是实际上这个数据还尚未写入。 因此在多生产者模式中 cursor不可以被消费者用来判读该位置是否有数据可消费。
         *
         * 因此在ProcessingSequenceBarrier对象的waitFor方法中，一方面 会使用waitStrategy的waitFor方法判断 对于当前消费者是否有可用数据消费，
         * 另一方面会通过SequenceBarrier对象中持有的sequencer对象的getHighestPublishedSequence方法来判断 位置是否可用。 在MultiProducerSequencer对象中是通过
         * 判断消费者期望消费的位置 是否在MultiProducerSequencer的availableBuffer 数组中被存储了，因为在MultiProducerSequencer写完数据 publish sequence的时候会将这个
         * 位置写入availableBuffer数组中。
         *
         *
         *
         */
        if (cursorSequence.get() < sequence)
        {
            synchronized (mutex)
            {
                while (cursorSequence.get() < sequence)
                {
                    barrier.checkAlert();
                    mutex.wait();
                }
            }
        }

        /**
         * 生产者写入数据字后唤醒了消费者， 这个有个小问题： 有没有可能 消费者当前消费的位置是 21，期望位置是26，而生产者写入的位置是23呢？
         * 首先 消费者期望的消费位置是 当前消费者的位置+1得到的，也就是说消费者只会对下一个位置是否可以消费做判断。
         *  这就导致只要有生产者写入消息 就可以立马唤醒 消费者，而且 不需要 重复判断 消费者期望的位置是否大于 生产者放入数据的位置，
         *
         *  这也就是为什么 上面的if 为什么不设置为 while之内。
         *
         *============
         * 判断消费者 所属SequenceBarrier  对象中持有的 dependentSequence 依赖的消费者的消费序号 是否已经对其 期望位置消费完了。
         * 这个地方是一个while循环。
         *
         * 默认情况下如果 当前的SequenceBarrier没有设置任何依赖的消费者，那么其 depenedentSequence 就是RingBuffer对象中的Sequencer对象中的cursor属性对象，也就是RingBuffer的sequence对象。
         *
         */
        while ((availableSequence = dependentSequence.get()) < sequence)
        {
            barrier.checkAlert();
            /**
             * Java 9中引入了Thread.onSpinWait()方法。它是Thread类的静态方法，
             * 可以选择在繁忙的等待循环中调用。它允许JVM在某些系统体系结构上发布处理器指令，以改善此类自旋等待循环中的响应时间，并减少核心线程的功耗。它可以提高Java程序的总体功耗，并允许其他核心线程在相同的功耗范围内以更快的速度执行。
             *自旋等待
             */
            Thread.onSpinWait();
        }

        return availableSequence;
    }

    @Override
    public void signalAllWhenBlocking()
    {
        synchronized (mutex)
        {
            /**
             * 这个地方有个问题。
             *
             *
             *
             */
            mutex.notifyAll();
        }
    }

    @Override
    public String toString()
    {
        return "BlockingWaitStrategy{" +
            "mutex=" + mutex +
            '}';
    }
}
