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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;


/**
 * Coordinator for claiming sequences for access to a data structure while tracking dependent {@link Sequence}s.
 * Suitable for use for sequencing across multiple publisher threads.
 *
 * <p>Note on {@link Sequencer#getCursor()}:  With this sequencer the cursor value is updated after the call
 * to {@link Sequencer#next()}, to determine the highest available sequence that can be read, then
 * {@link Sequencer#getHighestPublishedSequence(long, long)} should be used.
 */
public final class MultiProducerSequencer extends AbstractSequencer
{
    private static final VarHandle AVAILABLE_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);

    /**
     * 用来 缓存 该RingBuffer的消费者 最小的消费进度
     */
    private final Sequence gatingSequenceCache = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    // availableBuffer tracks the state of each ringbuffer slot
    // see below for more details on the approach
    /**
     * MultiProducerSequencer内部多了一个availableBuffer，
     * 是一个int型的数组，size大小和RingBuffer的Size一样大，用来追踪Ringbuffer每个槽的状态，
     * 构造MultiProducerSequencer的时候会进行初始化，availableBuffer数组中的每个元素会被初始化成-1。
     */
    private final int[] availableBuffer;
    private final int indexMask;
    private final int indexShift;

    /**
     * Construct a Sequencer with the selected wait strategy and buffer size.
     *
     * @param bufferSize   the size of the buffer that this will sequence over.
     * @param waitStrategy for those waiting on sequences.
     */
    public MultiProducerSequencer(final int bufferSize, final WaitStrategy waitStrategy)
    {
        super(bufferSize, waitStrategy);
        availableBuffer = new int[bufferSize];
        Arrays.fill(availableBuffer, -1);

        indexMask = bufferSize - 1;
        indexShift = Util.log2(bufferSize);
    }

    /**
     * @see Sequencer#hasAvailableCapacity(int)
     */
    @Override
    public boolean hasAvailableCapacity(final int requiredCapacity)
    {
        return hasAvailableCapacity(gatingSequences, requiredCapacity, cursor.get());
    }

    private boolean hasAvailableCapacity(final Sequence[] gatingSequences, final int requiredCapacity, final long cursorValue)
    {
        long wrapPoint = (cursorValue + requiredCapacity) - bufferSize;
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > cursorValue)
        {
            long minSequence = Util.getMinimumSequence(gatingSequences, cursorValue);
            gatingSequenceCache.set(minSequence);

            if (wrapPoint > minSequence)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * @see Sequencer#claim(long)
     */
    @Override
    public void claim(final long sequence)
    {
        cursor.set(sequence);
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
         * getAndAdd 有两个操作，首先获取当前的值 这个值作为返回值，然后将其加n
         *
         * 这个cursor的getAndAdd会使用cas算法对 cursor中维护的value 增加n。
         *
         * 问题： 这里将cursor的值增加了n，那么不会对消费者产生影响吗？ 因为这里将cursor增加了n，但是实际生产者并没有放入数据，
         * 难道消费者不会使用 更改后的cursor中的value吗？
         *在BlockingWaitStrategy的waitFor方法  使用了cursorSequence的get获取生产者放入的位置，这个cursorSequence实际上就是RingBuffer的Sequencer对象的cursor属性
         * 也就是下面的这个cursor。 因此下面的代码 对cursor增加了，但是生产者实际上并未在RingBuffer的对应位置写入数据， 然后 BlockingWaitStrategy 中又使用这个cursor获取到了
         * 错误的位置。在SequenceBarrier的waitFor方法中  一方面胡通过WaitStrategy对象的waitFor方法来判断位置，另一方面 还会通过Sequencer对象的getHighestPublishedSequence方法来
         * 判断消费者可消费的位置。
         *在ProcessingSequenceBarrier的waitFor方法中会 调用sequencer.getHighestPublishedSequence 来获取最大可用位移，这个方法中会通过isAvailable方法
         * 判断  位置i是否 被写入到了availableBuffer 数组中。
         *
         *
         *
         * ========================思考： 这里为什么 对cursor进行修改================
         *  cursorSequence对象 是 RingBuffer对象中的Sequencer对象的cursor属性引用的对象，他代表着生产真在RingBuffer中写入数据的最后的位置。
         *          *  正常情况下 生产者首先 声名 对RingBuffer中某个位置的占领，其实就是通过Sequencer的next方法获取  对RingBuffer中某个位置的使用权。
         *          *  在 单生产者模式下，SingleProducerSequencer 对象中通过 nextValue维持当前 生产者获取到在RingBuffer中的位置。 next方法中将nextValue
         *          *  作为返回值交给Producer，然后Producer通过这个位置获取到对应位置的对象，将数据写入然后发布。 而且一般情况下SingleProducerSequencer的next方法中
         *          *  是不会对Sequencer对象的cursor进行修改，只有生产者在publish的时候才会更改Sequencer对象中的cursor。
         *          *
         *
         *          *  但是在多生产者模式中  我们要考虑的是 多个线程同时执行next方法 声明获取 RingBuffer中的某些位置，一旦我们将这个位置交给了这个生产者，则
         *          *  需要保证这个位置不会被其他线程重复使用。 因此你胡看到在MultiProducerSequencer的next方法中 会对生产者游标cursor直接进行修改。
         *          *  也就是先进行 long current = cursor.getAndAdd(n); 这会 使用cas来更新cursor中保存的值。从而获得占用。 这种方式会存在两个问题：
         *          *  （1）增加了cursor， 我们需要判断当前生产者获取到的位置 是否能够写入数据，主要考虑消费者的消费进度的问题。 这个逻辑在单个生产者中也会校验
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
         *
         *
         */
        long current = cursor.getAndAdd(n);

        /**
         * 因为getAndAdd返回值是 增加n之前的值，因此这里需要将current+n
         */
        long nextSequence = current + n;
        /**
         * 减去一圈，表示这个位置开始到 nextSequence位置 为止的数据是一圈的数据。
         */
        long wrapPoint = nextSequence - bufferSize;
        /**
         *
         */
        long cachedGatingSequence = gatingSequenceCache.get();

        if (wrapPoint > cachedGatingSequence || cachedGatingSequence > current)
        {
            long gatingSequence;
            while (wrapPoint > (gatingSequence = Util.getMinimumSequence(gatingSequences, current)))
            {
                LockSupport.parkNanos(1L); // TODO, should we spin based on the wait strategy?
            }

            /**
             * 主动计算 消费者中 进度最小的进度，以及将这个进度记录到gatingSequenceCache中。
             * 问题： 如果是消费者自己的消费 推动了消费进度，那么消费者会不会主动更新 Sequencer对象中的gatingSequenceCache。
             * 实际上消费者不需要维护gatingSequenceCache。 一般只需要生产者在放入数据的时候 检查。
             *
             * 因为 wrapPoint=current+n-bufferSize， 总是会一直变大， 而gatingSequenceCache要么不变，要么由Sequencer对象自己对其增加。
             * 因此 wrapPoint > gatingSequence 这个条件  总是在 wrapPoint增大到一定程序后被满足。
             *
             */
            gatingSequenceCache.set(gatingSequence);
        }

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

        long current;
        long next;

        do
        {
            current = cursor.get();
            next = current + n;

            if (!hasAvailableCapacity(gatingSequences, n, current))
            {
                throw InsufficientCapacityException.INSTANCE;
            }
        }
        while (!cursor.compareAndSet(current, next));

        return next;
    }

    /**
     * @see Sequencer#remainingCapacity()
     */
    @Override
    public long remainingCapacity()
    {
        long consumed = Util.getMinimumSequence(gatingSequences, cursor.get());
        long produced = cursor.get();
        return getBufferSize() - (produced - consumed);
    }

    /**
     * @see Sequencer#publish(long)
     */
    @Override
    public void publish(final long sequence)
    {
        setAvailable(sequence);
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * @see Sequencer#publish(long, long)
     */
    @Override
    public void publish(final long lo, final long hi)
    {
        for (long l = lo; l <= hi; l++)
        {
            setAvailable(l);
        }
        waitStrategy.signalAllWhenBlocking();
    }

    /**
     * The below methods work on the availableBuffer flag.
     *
     * <p>The prime reason is to avoid a shared sequence object between publisher threads.
     * (Keeping single pointers tracking start and end would require coordination
     * between the threads).
     *
     * <p>--  Firstly we have the constraint that the delta between the cursor and minimum
     * gating sequence will never be larger than the buffer size (the code in
     * next/tryNext in the Sequence takes care of that).
     * -- Given that; take the sequence value and mask off the lower portion of the
     * sequence as the index into the buffer (indexMask). (aka modulo operator)
     * -- The upper portion of the sequence becomes the value to check for availability.
     * ie: it tells us how many times around the ring buffer we've been (aka division)
     * -- Because we can't wrap without the gating sequences moving forward (i.e. the
     * minimum gating sequence is effectively our last available position in the
     * buffer), when we have new data and successfully claimed a slot we can simply
     * write over the top.
     *
     *
     * 以下方法适用于 availableBuffer 标志。
     * 主要原因是避免发布者线程之间共享序列对象。 （保持单个指针跟踪开始和结束需要线程之间的协调）。
     * -- 首先，我们有一个约束，即光标和最小门控序列之间的增量永远不会大于缓冲区大小（序列中的 next/tryNext 中的代码会处理这一点）。
     * -  鉴于; 取序列值并屏蔽序列的下部作为缓冲区的索引（indexMask）。 （又名模运算符）——序列的上半部分成为检查可用性的值。
     * 即：它告诉我们在环形缓冲区周围绕了多少次（又名除法）——因为如果门控序列不向前移动，我们就无法换行（即最小门控序列实际上是
     * 我们在缓冲区中的最后一个可用位置） ，当我们有新数据并成功申请一个插槽时，我们可以简单地在顶部写入。
     *
     */
    private void setAvailable(final long sequence)
    {
        /**
         *   方法中会将当前序列值的可用状态记录到availableBuffer里面，
         *   而记录的这个值其实就是sequence除以bufferSize，也就是当前sequence绕buffer的圈数。
         *
         */
        setAvailableBufferValue(calculateIndex(sequence), calculateAvailabilityFlag(sequence));
    }

    private void setAvailableBufferValue(final int index, final int flag)
    {
        AVAILABLE_ARRAY.setRelease(availableBuffer, index, flag);
    }

    /**
     * @see Sequencer#isAvailable(long)
     */
    @Override
    public boolean isAvailable(final long sequence)
    {
        int index = calculateIndex(sequence);
        int flag = calculateAvailabilityFlag(sequence);
        return (int) AVAILABLE_ARRAY.getAcquire(availableBuffer, index) == flag;
    }

    @Override
    public long getHighestPublishedSequence(final long lowerBound, final long availableSequence)
    {
        /**
         *
         * 如果 一个生产者一次性获取了多个位置  也就是 next(3),返回值是一个序号14.
         *
         *  因此我需要执行
         *  LongEvent longEvent = ringBuffer.get(14);
         *  LongEvent longEvent = ringBuffer.get(13);
         *  LongEvent longEvent = ringBuffer.get(12);
         *  然后针对每一个 sequence （12，13,14）都进行 ringBuffer.publish，
         *
         *  因此，如果我仅对14进行publish，则意味着 isAvailable中使用到的 availableBuffer 数组在14的位置上有设置 flag，但是12,13的位置上没有设置正确的flag。
         *
         *  在在这里 isAvailable(12)的时候就会判断出12位置不可用。
         *
         */
        for (long sequence = lowerBound; sequence <= availableSequence; sequence++)
        {
            if (!isAvailable(sequence))
            {
                return sequence - 1;
            }
        }

        return availableSequence;
    }

    private int calculateAvailabilityFlag(final long sequence)
    {
        return (int) (sequence >>> indexShift);
    }

    private int calculateIndex(final long sequence)
    {
        return ((int) sequence) & indexMask;
    }

    @Override
    public String toString()
    {
        return "MultiProducerSequencer{" +
                "bufferSize=" + bufferSize +
                ", waitStrategy=" + waitStrategy +
                ", cursor=" + cursor +
                ", gatingSequences=" + Arrays.toString(gatingSequences) +
                '}';
    }
}
