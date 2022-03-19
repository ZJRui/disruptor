/*
 * Copyright 2012 LMAX Ltd.
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

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static java.util.Arrays.copyOf;

/**
 * Provides static methods for managing a {@link SequenceGroup} object.
 */
class SequenceGroups
{
    static <T> void addSequences(
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> updater,
        final Cursored cursor,
        final Sequence... sequencesToAdd)
    {
        long cursorSequence;
        Sequence[] updatedSequences;
        Sequence[] currentSequences;

        do
        {
            /**
             * 第一个参数holder是Sequencer对象。
             * 在AbstractSequencer对象 中执行 addGatingSequences
             *   SequenceGroups.addSequences(this, SEQUENCE_UPDATER, this, gatingSequences);
             * 因为updater 对象 所持有的属性gatingSequences 实际上是AbstractSequencer对象的属性，因此
             * updater需要从Sequencer对象上获取 gatingSequence属性
             */
            currentSequences = updater.get(holder);
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            cursorSequence = cursor.getCursor();

            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd)
            {
                /**
                 * 问题：  上面方法参数中的cursor对象 在AbstractSequencer的addGatingSequences方法中传递过来的是AbstractSequencer对象
                 *
                 * AbstractSequencer虽然实现了Cursor接口，但是其 getCursor内部实现是委托给了 AbstractSequencer对象内部的Sequence cursor属性对象
                 *
                 * cursor对象记录的是生产者在RingBuffer中写入数据的位置。这里为什么 将每一个Sequence的位置设置为cursor对象所指向的位置？
                 *
                 *  在Sequencer对象中 通过一个Sequence数组属性gatingSequences 维护这些Sequence，而且值得注意的是
                 *          * 当这些Sequence对象被添加到数组的时候，Sequencer对象 会将Sequence对象标记的位置 设置为当前Sequencer对象的Sequence cursor属性所
                 *          * 标记的位置。 也就是说假设cursor标记的位置是13 ，意味着生产者写入数据的位置是13，那么这些新添加的消费者的Sequence都会标记为13,13之前的数据
                 *          * 对这些消费者是不可见的。
                 *
                 */
                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences));//死循环更新数组

        cursorSequence = cursor.getCursor();
        for (Sequence sequence : sequencesToAdd)
        {
            sequence.set(cursorSequence);
        }
    }

    static <T> boolean removeSequence(
        final T holder,
        final AtomicReferenceFieldUpdater<T, Sequence[]> sequenceUpdater,
        final Sequence sequence)
    {
        int numToRemove;
        Sequence[] oldSequences;
        Sequence[] newSequences;

        do
        {
            oldSequences = sequenceUpdater.get(holder);

            numToRemove = countMatching(oldSequences, sequence);

            if (0 == numToRemove)
            {
                break;
            }

            final int oldSize = oldSequences.length;
            newSequences = new Sequence[oldSize - numToRemove];

            for (int i = 0, pos = 0; i < oldSize; i++)
            {
                final Sequence testSequence = oldSequences[i];
                if (sequence != testSequence)
                {
                    newSequences[pos++] = testSequence;
                }
            }
        }
        while (!sequenceUpdater.compareAndSet(holder, oldSequences, newSequences));

        return numToRemove != 0;
    }

    private static <T> int countMatching(final T[] values, final T toMatch)
    {
        int numToRemove = 0;
        for (T value : values)
        {
            if (value == toMatch) // Specifically uses identity
            {
                numToRemove++;
            }
        }
        return numToRemove;
    }
}
