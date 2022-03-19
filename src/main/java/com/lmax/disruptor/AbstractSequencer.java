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

import com.lmax.disruptor.util.Util;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Base class for the various sequencer types (single/multi).  Provides
 * common functionality like the management of gating sequences (add/remove) and
 * ownership of the current cursor.
 */
public abstract class AbstractSequencer implements Sequencer
{
    private static final AtomicReferenceFieldUpdater<AbstractSequencer, Sequence[]> SEQUENCE_UPDATER =
        AtomicReferenceFieldUpdater.newUpdater(AbstractSequencer.class, Sequence[].class, "gatingSequences");

    protected final int bufferSize;
    protected final WaitStrategy waitStrategy;
    /**
     * Cursor 是Sequencer的成员变量。
     * Sequencer有两种： SingleProducerSequencer和MultiProducerSequencer。
     *
     * 对于SingleProducerSequencer来说，cursor表示RingBuffer上当前已发布的最大Sequence。 这个位置上已经放入了数据。 sequence的值可以大于BufferSize
     * 对于MultiProducerSequencer来说，cursor是RingBuffer上当前已经申请的最大的Sequence。
     *
     */
    protected final Sequence cursor = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    /**
     * 注意针对 该属性的是更新 使用了AtomicReferenceFieldUpdater
     *
     * AtomicReferenceFieldUpdater是基于反射的工具类，用来将指定类型的指定的volatile引用字段进行原子更新，对应的原子引用字段不能是private的
     *
     * 这个属性是什么意思呢？
     * 在Disruptor的handleEventsWith方法中会为每一个EventHandler创建一个BatchEventProcessor对象，这个BatchEventProcessor对象
     * 内部有一个Sequence对象，表示当前消费者的消费进度。
     * 当前类AbstractSequencer对象通过 cursor属性记录生产者 放入数据的位置，
     * 但是 生产者 能不能 往某一个位置放入数据 需要看 是不是所有的消费者都 已经消费完了这个位置的数据
     *
     * 因此gatingSequence保存了所有消费者的消费位置， 生产者写入的位置一定是大于消费者的位置的，
     * 但是生产写申请写入的位置-bufferSize， 如果有消费者的位置小于这个值 则意味着不能写入，否则会导致某些消费者消费
     * 不到 （生产写申请写入的位置-bufferSize）位置的数据
     *
     */
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Create with the specified buffer size and wait strategy.
     *
     * @param bufferSize   The total number of entries, must be a positive power of 2.
     * @param waitStrategy The wait strategy used by this sequencer
     */
    public AbstractSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        this.bufferSize = bufferSize;
        this.waitStrategy = waitStrategy;
    }

    /**
     * @see Sequencer#getCursor()
     */
    @Override
    public final long getCursor()
    {
        return cursor.get();
    }

    /**
     * @see Sequencer#getBufferSize()
     */
    @Override
    public final int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * @see Sequencer#addGatingSequences(Sequence...)
     *
     * 添加一些追踪序列到当前实例，添加过程是原子的。
     * 这些控制序列一般是其他组件的序列，当前实例可以通过这些序列来查看其他组件的序列使用情况
     *
     *
     */
    @Override
    public final void addGatingSequences(final Sequence... gatingSequences)
    {
        /**
         *
         * 注意第一个参数和第三个参数
         *
         * 第三个参数 this 表示的是Cursor对象
         *  在Sequencer对象中 通过一个Sequence数组属性gatingSequences 维护这些Sequence，而且值得注意的是
         *          * 当这些Sequence对象被添加到数组的时候，Sequencer对象 会将Sequence对象标记的位置 设置为当前Sequencer对象的Sequence cursor属性所
         *          * 标记的位置。 也就是说假设cursor标记的位置是13 ，意味着生产者写入数据的位置是13，那么这些新添加的消费者的Sequence都会标记为13,13之前的数据
         *          * 对这些消费者是不可见的。
         *
         */
        SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
    }

    /**
     * @see Sequencer#removeGatingSequence(Sequence)
     */
    @Override
    public boolean removeGatingSequence(final Sequence sequence)
    {
        return SequenceGroups.removeSequence(this, SEQUENCE_UPDATER, sequence);
    }

    /**
     * @see Sequencer#getMinimumSequence()
     */
    @Override
    public long getMinimumSequence()
    {
        return Util.getMinimumSequence(gatingSequences, cursor.get());
    }

    /**
     * @see Sequencer#newBarrier(Sequence...)
     */
    @Override
    public SequenceBarrier newBarrier(final Sequence... sequencesToTrack)
    {

        return new ProcessingSequenceBarrier(this, waitStrategy, cursor, sequencesToTrack);
    }

    /**
     * Creates an event poller for this sequence that will use the supplied data provider and
     * gating sequences.
     *
     * @param dataProvider    The data source for users of this event poller
     * @param gatingSequences Sequence to be gated on.
     * @return A poller that will gate on this ring buffer and the supplied sequences.
     */
    @Override
    public <T> EventPoller<T> newPoller(final DataProvider<T> dataProvider, final Sequence... gatingSequences)
    {
        return EventPoller.newInstance(dataProvider, this, new Sequence(), cursor, gatingSequences);
    }

    @Override
    public String toString()
    {
        return "AbstractSequencer{" +
            "waitStrategy=" + waitStrategy +
            ", cursor=" + cursor +
            ", gatingSequences=" + Arrays.toString(gatingSequences) +
            '}';
    }
}