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

import java.util.concurrent.atomic.AtomicInteger;

import static com.lmax.disruptor.RewindAction.REWIND;


/**
 * Convenience class for handling the batching semantics of consuming entries from a {@link RingBuffer}
 * and delegating the available events to an {@link EventHandler}.
 *
 * <p>If the {@link EventHandler} also implements {@link LifecycleAware} it will be notified just after the thread
 * is started and just before the thread is shutdown.
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class BatchEventProcessor<T>
    implements EventProcessor
{
    private static final int IDLE = 0;
    /**
     * halt：停止
     */
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private ExceptionHandler<? super T> exceptionHandler;
    private final DataProvider<T> dataProvider;
    /**
     *
     *
     *
     */
    private final SequenceBarrier sequenceBarrier;
    private final EventHandler<? super T> eventHandler;
    private final Sequence  sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);
    private final TimeoutHandler timeoutHandler;
    private final BatchStartAware batchStartAware;
    private BatchRewindStrategy batchRewindStrategy = new SimpleBatchRewindStrategy();
    private int retriesAttempted = 0;

    /**
     * Construct a {@link EventProcessor} that will automatically track the progress by updating its sequence when
     * the {@link EventHandler#onEvent(Object, long, boolean)} method returns.
     *
     * @param dataProvider    to which events are published.
     * @param sequenceBarrier on which it is waiting.
     * @param eventHandler    is the delegate to which events are dispatched.
     */
    public BatchEventProcessor(
        final DataProvider<T> dataProvider,
        final SequenceBarrier sequenceBarrier,
        final EventHandler<? super T> eventHandler)
    {
        this.dataProvider = dataProvider;
        this.sequenceBarrier = sequenceBarrier;
        this.eventHandler = eventHandler;

        if (eventHandler instanceof SequenceReportingEventHandler)
        {
            ((SequenceReportingEventHandler<?>) eventHandler).setSequenceCallback(sequence);
        }

        batchStartAware =
            (eventHandler instanceof BatchStartAware) ? (BatchStartAware) eventHandler : null;
        timeoutHandler =
            (eventHandler instanceof TimeoutHandler) ? (TimeoutHandler) eventHandler : null;
    }

    @Override
    public Sequence getSequence()
    {
        return sequence;
    }

    /**
     * Disruptor shutdown的时候会执行 BatchEventProcessor的halt方法
     */
    @Override
    public void halt()
    {
        running.set(HALTED);
        /**
         * disruptor shutdown的时候 会调用每个BatchEventProcessor的halt方法。
         * 而每个halt方法内部会执行 SequenceBarrier的alert，alert内部会使用waitStrategy唤醒所有等待的消费者线程。
         *
         * 同时SequenceBarrier内部会设置alert属性为true。 然后唤醒所有消费者线程，消费者线程执行 SequenceBarrier.waitFor的时候
         *
         * SequenceBarrier对象户检查自身属性alert如果为true 就抛出异常，从而导致 消费者线程的退出。
         *
         */
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning()
    {
        return running.get() != IDLE;
    }

    /**
     * Set a new {@link ExceptionHandler} for handling exceptions propagated out of the {@link BatchEventProcessor}.
     *
     * @param exceptionHandler to replace the existing exceptionHandler.
     */
    public void setExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        if (null == exceptionHandler)
        {
            throw new NullPointerException();
        }

        this.exceptionHandler = exceptionHandler;
    }

    /**
     * Set a new {@link BatchRewindStrategy} for customizing how to handle a {@link RewindableException}
     * Which can include whether the batch should be rewound and reattempted,
     * or simply thrown and move on to the next sequence
     * the default is a {@link SimpleBatchRewindStrategy} which always rewinds
     * @param batchRewindStrategy to replace the existing rewindStrategy.
     */
    public void setRewindStrategy(final BatchRewindStrategy batchRewindStrategy)
    {
        if (null == batchRewindStrategy)
        {
            throw new NullPointerException();
        }

        this.batchRewindStrategy = batchRewindStrategy;
    }

    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run()
    {
        /***
         *
         * Disruptor对象在创建的时候会 创建一个RingBuffer对象，RingBuffer对象内部有一个Sequencer属性，如果是但生产者模式那么这个Sequencer对象就是SingleProducerSequencer。
         * Sequencer对象从父类AbstractSequencer中继承了一个Sequence属性cursor，这个Sequence cursor属性就是用来记录生产者在RingBuffer中写入数据的位置。
         * 同时我们在创建Disruptor对象的时候会指定 当RingBuffer中没有数据的时候消费者的行为策略，这个WaitStrategy对象也被保存在了 RingBuffer对象的Sequencer对象中。
         * 也就是RingBuffer的Sequencer对象同时 持有Sequence cursor属性记录生产者写入数据位置和waitStrategy控制消费者的行为。
         *
         *
         *
         * disruptor的handleEventsWith方法接收多个EventHandler， 针对这批EventHandler对象 会使用RingBuffer对象的newBarrier方法创建一个SequenceBarrier对象。
         * RingBuffer本身有将创建SequenceBarrier对象的任务交给了Sequencer对象，因此在SingleProducerSequencer对象的newBarrier方法中创建了一个SequenceBarrier对象。 注意在创建SequenceBarrier对象的时候将  Sequencer对象中用来记录生产者放入数据位置的Sequence cursor对象 和waitStrategy对象都传递给了waitStrategy对象。
         *
         * 然后针对每一个EventHandler都创建一个BatchEventProcessor对象，同时将创建的SequenceBarrier对象交给BatchEventProcessor。
         *
         * 当Disruptor执行start的时候 会为每一个BatchEventProcessor对象启动一个线程，线程会执行BatchEventProcessor的run方法。
         * 每一个BatchEventProcessor对象本质上都是一个消费者，而且BatchEventProcessor对象在被创建的时候会为其创建一个Sequence对象，用来记录该消费者在RingBuffer中消费的位置，BatchEventProcessor对象的run方法中会先根据其自身的Sequence对象记录的位置+1 作为其期望从RingBuffer中读取数据的位置，但是BatchEventProcessor不会直接取数据，因为RingBuffer中可能没有数据。
         * BatchEventProcessor对象创建的时候保存了SequenceBarrier对象，因此他通过 SequenceBarrier对象的waitFor方法 确定他能读取数据的位置 如下：
         *       final long availableSequence = sequenceBarrier.waitFor(nextSequence);
         * nextSequence=BatchEventProcessor对象的sequence.get() + 1L;
         *
         * SequenceBarrier对象的waitFor方法中 又会使用自身持有的waitStrategy对象的waitFor方法获取当前消费者能够读取的位置。
         * long availableSequence = waitStrategy.waitFor(sequence, cursorSequence, dependentSequence, this);
         * 需要注意的是SequenceBarrier将自身保存的cursorSequence对象传递给了waitStrategy，这个cursorSequence是SequenceBarrier被创建的时候 RingBuffer中的Sequencer对象中的cursor属性对应的Sequence对象，是用来记录RingBuffer中生产者放入数据的位置的。
         * 也就说waitStrategy的waitFor方法的第一个参数是消费者期望读取的位置，第二个参数cursorSequence是记录RIngBuffer中生产者放入数据的位置的Sequence，第四个参数this是SequenceBarrier对象。
         *
         * 在BlockingWaitStrategy 对象的waitFor方法中，他首先使用cursorSequence.get得到RingBuffer中目前生产者写入数据的位置，如果这个位置小于 消费者期望读取的位置，则使用BlockingWaitStrategy对象中的 object锁对象挂起当前消费者线程。
         * 那么当ringbuffer发布数据的时候必然也要通过BlockingWaitStrategy对象中的object锁对象来唤醒所有的消费者线程。
         *
         * 那么Ringbuffer如何告知WaitStrategy呢，RingBuffer的publish方法内 通过sequencer对象publish，sequencer对象内持有waitStrategy对象的引用，因此可以使用waitStrategy对象来唤醒消费者线程。
         *
         *
         * 需要注意的是：disruptor对象的handleEventsWith方法每次都会创建一个SequenceBarrier对象，这个SequenceBarrier对象被这批BatchEventProcessor对象共用，这批BatchEventProcessor对象用SequenceBarrier来挂起自己，然后RingBuffer通过Sequencer中的waitStrategy来唤醒消费者。
         *
         *
         */
        int witnessValue = running.compareAndExchange(IDLE, RUNNING);
        if (witnessValue == IDLE) // Successful CAS
        {
            sequenceBarrier.clearAlert();

            notifyStart();
            try
            {
                if (running.get() == RUNNING)
                {
                    processEvents();
                }
            }
            finally
            {
                notifyShutdown();
                running.set(IDLE);
            }
        }
        else
        {
            if (witnessValue == RUNNING)
            {
                throw new IllegalStateException("Thread is already running");
            }
            else
            {
                earlyExit();
            }
        }
    }

    private void processEvents()
    {
        T event = null;
        /**
         * 成员变量sequence维护该Processor的消费进度
         *
         */
        long nextSequence = sequence.get() + 1L;

        while (true)
        {
            final long startOfBatchSequence = nextSequence;
            try
            {
                try
                {

                    /**
                     *  以nextSequence作为底线，去获取最大的可用sequence（也就是已经被publish的sequence）
                     *
                     */
                    final long availableSequence = sequenceBarrier.waitFor(nextSequence);
                    /**
                     * 如果获取到的Sequence大于nextSequence，则说明有可以消费的event。
                     * 从nextSequence（包含） 到availableSequence（包含）这一段的事件作为同一个批次。
                     *
                     */
                    if (batchStartAware != null && availableSequence >= nextSequence)
                    {
                        batchStartAware.onBatchStart(availableSequence - nextSequence + 1);
                    }

                    while (nextSequence <= availableSequence)
                    {
                        event = dataProvider.get(nextSequence);
                        /**
                         * 调用BatchEventProcessor中注册的 事件处理函数
                         */
                        eventHandler.onEvent(event, nextSequence, nextSequence == availableSequence);
                        nextSequence++;
                    }

                    retriesAttempted = 0;
                    /**
                     * 消费完一批之后，一次性更新消费进度
                     *
                     */
                    sequence.set(availableSequence);
                }
                catch (final RewindableException e)
                {
                    if (this.batchRewindStrategy.handleRewindException(e, ++retriesAttempted) == REWIND)
                    {
                        nextSequence = startOfBatchSequence;
                    }
                    else
                    {
                        retriesAttempted = 0;
                        throw e;
                    }
                }
            }
            catch (final TimeoutException e)
            {
                /**
                 * waitFor超时的场景
                 */
                notifyTimeout(sequence.get());
            }
            catch (final AlertException ex)
            {
                if (running.get() != RUNNING)
                {
                    break;
                }
            }

            catch (final Throwable ex)
            {
                /**
                 *    // 消费过程中如果抛出异常，表面上看会更新消费进度，也就是说没有补偿机制。但实际上默认的策略是会抛异常的，消费线程会直接结束掉
                 */
                handleEventException(ex, nextSequence, event);
                sequence.set(nextSequence);
                nextSequence++;
            }
        }
    }

    private void earlyExit()
    {
        notifyStart();
        notifyShutdown();
    }

    private void notifyTimeout(final long availableSequence)
    {
        try
        {
            if (timeoutHandler != null)
            {
                timeoutHandler.onTimeout(availableSequence);
            }
        }
        catch (Throwable e)
        {
            handleEventException(e, availableSequence, null);
        }
    }

    /**
     * Notifies the EventHandler when this processor is starting up.
     */
    private void notifyStart()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onStart();
            }
            catch (final Throwable ex)
            {
                handleOnStartException(ex);
            }
        }
    }

    /**
     * Notifies the EventHandler immediately prior to this processor shutting down.
     */
    private void notifyShutdown()
    {
        if (eventHandler instanceof LifecycleAware)
        {
            try
            {
                ((LifecycleAware) eventHandler).onShutdown();
            }
            catch (final Throwable ex)
            {
                handleOnShutdownException(ex);
            }
        }
    }

    /**
     * Delegate to {@link ExceptionHandler#handleEventException(Throwable, long, Object)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleEventException(final Throwable ex, final long sequence, final T event)
    {
        getExceptionHandler().handleEventException(ex, sequence, event);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnStartException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnStartException(final Throwable ex)
    {
        getExceptionHandler().handleOnStartException(ex);
    }

    /**
     * Delegate to {@link ExceptionHandler#handleOnShutdownException(Throwable)} on the delegate or
     * the default {@link ExceptionHandler} if one has not been configured.
     */
    private void handleOnShutdownException(final Throwable ex)
    {
        getExceptionHandler().handleOnShutdownException(ex);
    }

    private ExceptionHandler<? super T> getExceptionHandler()
    {
        ExceptionHandler<? super T> handler = exceptionHandler;
        return handler == null ? ExceptionHandlers.defaultHandler() : handler;
    }
}