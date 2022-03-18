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
        long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
        if (availableSequence < sequence)
        {

            return availableSequence;

        }

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