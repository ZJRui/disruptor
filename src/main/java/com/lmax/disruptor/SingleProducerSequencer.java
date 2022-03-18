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
import java.util.concurrent.locks.LockSupport;

abstract class SingleProducerSequencerPad extends AbstractSequencer
{
    protected byte
        p10, p11, p12, p13, p14, p15, p16, p17,
        p20, p21, p22, p23, p24, p25, p26, p27,
        p30, p31, p32, p33, p34, p35, p36, p37,
        p40, p41, p42, p43, p44, p45, p46, p47,
        p50, p51, p52, p53, p54, p55, p56, p57,
        p60, p61, p62, p63, p64, p65, p66, p67,
        p70, p71, p72, p73, p74, p75, p76, p77;

    SingleProducerSequencerPad(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }
}

abstract class SingleProducerSequencerFields extends SingleProducerSequencerPad
{
    SingleProducerSequencerFields(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * Set to -1 as sequence starting point
     */
    long nextValue = Sequence.INITIAL_VALUE;
    long cachedValue = Sequence.INITIAL_VALUE;
}

/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Not safe for use from multiple threads as it does not implement any barriers.
 *
 * <p>* Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#publish(long)} is made.
 */

public final class SingleProducerSequencer extends SingleProducerSequencerFields
{
    protected byte
        p10, p11, p12, p13, p14, p15, p16, p17,
        p20, p21, p22, p23, p24, p25, p26, p27,
        p30, p31, p32, p33, p34, p35, p36, p37,
        p40, p41, p42, p43, p44, p45, p46, p47,
        p50, p51, p52, p53, p54, p55, p56, p57,
        p60, p61, p62, p63, p64, p65, p66, p67,
        p70, p71, p72, p73, p74, p75, p76, p77;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public SingleProducerSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(requiredCapacity, false);
    }

    private boolean hasAvailableCapacity(final int requiredCapacity, final boolean doStore)
    {
        /**
         * Sequencer负责生产者对RingBuffer的控制， 包括查询是否有写入空间、申请空间、发布事件并唤醒消费者等。
         *
         */
        long nextValue = this.nextValue;

        long wrapPoint = (nextValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = this.cachedValue;

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {
            if (doStore)
            {
                /**
                 * 问题：这个地方为什么要主动更新？
                 */
                cursor.setVolatile(nextValue);  // StoreLoad fence
            }

            long minSequence = Util.getMinimumSequence(gatingSequences, nextValue);
            this.cachedValue = minSequence;

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#next()
     */
    @Override
    public long next()
    {

        return next(1);
    }

    /**
     * @see Sequencer#next(int)
     */
    @Override
    public long next(final int n)
    {
        if (n < 1 || n > bufferSize)
        {
            throw new IllegalArgumentException("n must be > 0 and < bufferSize");
        }

        /**
         *
         * 在这个next方法中 nextSequence表示 本次生产者 会将数据放置到 这个位置。 而方法返回之前会将nextSequence交给nextValue
         * 因此nextValue表示 上次生产者放入数据的最后的位置。  nextValue的最开始值是-1，  nextSequence=nextValue+n 最小值是0，
         * 因此nextValue=7 表示环形数组位置7已经被上次生产者放入了数据·
         *
         */
        long nextValue = this.nextValue;

        /**
         * nextSequence表示本次需要申请的最大sequence
         */
        long nextSequence = nextValue + n;
        /**
         *  wrapPoint表示申请的序列绕一圈以后的位置。
         *  nextValue=7，如果本次申请5个位置，那么 nextSequence=12， warpPoint=12-8=4，也就是本次生产者将会使用位置为 8,0,1,2,3的位置放入数据
         *  因此wrapPoint表示 本次生产者 将数据放入到环形数组中 下一个可用的位置。     如果有消费者的最小消费到了4的位置，则不会影响本次生产者放入数据。
         *  如果消费者的最小消费位置是3，也就是满足了wrapPoint > cachedGatingSequence 则表示本次生产者放入数据会存在覆盖问题。
         *
         */
        long wrapPoint = nextSequence - bufferSize;
        /**
         * 事件处理着处理到的序列值
         *
         */
        long cachedGatingSequence = this.cachedValue;

        /**
         *
         * cachedGatingSequence > nextValue 判断的是最慢消费进度超过了我们即将要申请的sequence。乍一看这应该是不可能的吧，
         * 都还没申请到该sequence怎么可能消费到呢？找了些资料，发现确实是存在该场景的：RingBuffer提供了一个叫resetTo的方法，可以重置当前已申请sequence为一个指定值并publish出去：
         *
         * ======
         * 1.事件发布者要申请的序列值大于事件处理者当前的序列值且事件发布者要申请的序列值减去环的长度要小于事件处理者的序列值。
         *2.满足(1)，可以申请给定的序列。
         * 3.不满足(1)，就需要查看一下当前事件处理者的最小的序列值(可能有多个事件处理者)。如果最小序列值大于等于 当前事件处理者的最小序列值大了一圈，那就不能申请了序列(申请了就会被覆盖)，
         *
         *
         *   // 序列值初始值是 -1 ，只有wrapPoint 大于 cachedGatingSequence 将发生绕环行为，生产者超一圈从后方追上消费者，生产者覆盖未消费的情况。
         *  // 没有空坑位，将进入循环等待。
         *  比如 size是8，  当前是nextValue 是7， 申请的数量是5，那么7+5=12-8=4， 如果有一个消费者的最小位置 cachedgatingSequence=2，那么显然本次生产者若是放入数据就会覆盖最小位置的消费者
         */
        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > nextValue)
        {

            /**
             * wrapPoint > cachedGatingSequence 代表 绕一圈并且位置大于时间矗立着处理到的序列。
             *
             * cachedGatingSequence> nextValue 说明时间发布者的位置位于时间处理者的屁股后面
             *
             *
             * 在这个方法的最后面 将 nextSequence赋值给了nextValue，且nextSequence作为返回值 返回，因为nextSequence表示 本次生产者将数据放入的位置。
             * 因此nextValue表示 上一次生产者将数据放入的最后的位置。 nextValue=7 表示 7的位置已经有了数据，nextValue的最开始是-1，因此 nextSequence=nextValue+n， 从而nextSequence是从0 开始的，
             * 也就是nextSequence=0，表示本次生产者会将数据写入数组0的位置。 nextValue=7，表示生产者将数据写入了数组位置为7的位置。
             *
             * 问题：这个地方不太理解，为什么需要将cursor设置为 nextValue呢？ 毕竟在RingBuffer 进行publishEvent的时候会自动进行设置cursor
             *
             */
            cursor.setVolatile(nextValue);  // StoreLoad fence

            long minSequence;
            /**
             * 如上所述，如果warpPoint 大于最小的消费者的消费位置，或者 大于 上一次 生产者放入数据的位置（nextValue），
             * 那么就意味着本次生产者放入数据将会产生覆盖的问题。
             *
             * 只有当消费者消费，向前移动后，才能跳出循环。
             * 每次重新获取消费者序列最小值进行轮询判断。
             * nextValue 表示上一次 生产者放入数据的最后的位置。 wrapPoint表示 本次生产者将数据放入的最后位置的下一位。
             * 比如nextValue=7，本次放入5个数据，则wrapPoint=7+5-8=4，实际放入的位置是8,0,1,2,3。 如果本次放入的数据再多些导致
             * wrapPoint=8，则意味着本次放入数据的最后的位置就是7，显然nextValue=7，这就存在数据覆盖的问题。 因此wrapPoint大于nextValue就会存在覆盖问题
             *
             */
            while (wrapPoint > (minSequence = Util.getMinimumSequence(gatingSequences, nextValue)))
            {
                /**
                 * 当wrapPoint小于上面的条件的时候 就意味着： 本次生产者写入数据的最后位置小于 最小生产者的消费位置。
                 */
                LockSupport.parkNanos(1L); // TODO: Use waitStrategy to spin?
            }

            /**
             * cachedValue 表示消费到最小的value ，表示事件消费者序列。
             *
             */
            this.cachedValue = minSequence;
        }

        /**
         * 表示事件发布者序列
         *
         */
        this.nextValue = nextSequence;

        return nextSequence;
    }

    /**
     * @see Sequencer#tryNext()
     */
    @Override
    public long tryNext() throws InsufficientCapacityException
    {
        return tryNext(1);
    }

    /**
     * @see Sequencer#tryNext(int)
     */
    @Override
    public long tryNext(final int n) throws InsufficientCapacityException
    {
        if (n < 1)
        {
            throw new IllegalArgumentException("n must be > 0");
        }

        if (!hasAvailableCapacity(n, true))
        {
            throw InsufficientCapacityException.INSTANCE;
        }

        long nextSequence = this.nextValue += n;

        return nextSequence;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long nextValue = this.nextValue;

        long consumed = Util.getMinimumSequence(gatingSequences, nextValue);
        long produced = nextValue;
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(final long sequence)
    {
        this.nextValue = sequence;
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {

        /**
         * cursor表示 生产者放入消息的位置。
         *
         */
        cursor.set(sequence);
        /**
         * 唤醒消费者
         */
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(final long lo, final long hi)
    {
        publish(hi);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(final long sequence)
    {
        final long currentSequence = cursor.get();
        return sequence <= currentSequence && sequence > currentSequence - bufferSize;
    }

    @Override
    public long getHighestPublishedSequence(final long lowerBound, final long availableSequence)
    {
        return availableSequence;
    }

    @Override
    public String toString()
    {
        return "SingleProducerSequencer{" +
                "bufferSize=" + bufferSize +
                ", waitStrategy=" + waitStrategy +
                ", cursor=" + cursor +
                ", gatingSequences=" + Arrays.toString(gatingSequences) +
                '}';
    }
}
