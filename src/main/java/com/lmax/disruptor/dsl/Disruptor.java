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
package com.lmax.disruptor.dsl;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.EventTranslator;
import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.EventTranslatorThreeArg;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.util.Util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A DSL-style API for setting up the disruptor pattern around a ring buffer
 * (aka the Builder pattern).
 *
 * <p>A simple example of setting up the disruptor with two event handlers that
 * must process events in order:
 *
 * <pre>
 * <code>Disruptor&lt;MyEvent&gt; disruptor = new Disruptor&lt;MyEvent&gt;(MyEvent.FACTORY, 32, Executors.newCachedThreadPool());
 * EventHandler&lt;MyEvent&gt; handler1 = new EventHandler&lt;MyEvent&gt;() { ... };
 * EventHandler&lt;MyEvent&gt; handler2 = new EventHandler&lt;MyEvent&gt;() { ... };
 * disruptor.handleEventsWith(handler1);
 * disruptor.after(handler1).handleEventsWith(handler2);
 *
 * RingBuffer ringBuffer = disruptor.start();</code>
 * </pre>
 *
 * @param <T> the type of event used.
 */
public class Disruptor<T>
{
    private final RingBuffer<T> ringBuffer;
    private final ThreadFactory threadFactory;
    private final ConsumerRepository<T> consumerRepository = new ConsumerRepository<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ExceptionHandler<? super T> exceptionHandler = new ExceptionHandlerWrapper<>();

    /**
     * Create a new Disruptor. Will default to {@link com.lmax.disruptor.BlockingWaitStrategy} and
     * {@link ProducerType}.MULTI
     *
     *
     * Disruptor的使用入口。持有RingBuffer、消费者线程池Executor、消费者集合ConsumerRepository等引用。
     *
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer.
     * @param threadFactory  a {@link ThreadFactory} to create threads to for processors.
     */
    public Disruptor(final EventFactory<T> eventFactory, final int ringBufferSize, final ThreadFactory threadFactory)
    {
        this(RingBuffer.createMultiProducer(eventFactory, ringBufferSize), threadFactory);
    }

    /**
     * Create a new Disruptor.
     *
     * @param eventFactory   the factory to create events in the ring buffer.
     * @param ringBufferSize the size of the ring buffer, must be power of 2.
     * @param threadFactory  a {@link ThreadFactory} to create threads for processors.
     * @param producerType   the claim strategy to use for the ring buffer.
     * @param waitStrategy   the wait strategy to use for the ring buffer.
     */
    public Disruptor(
            final EventFactory<T> eventFactory,
            final int ringBufferSize,
            final ThreadFactory threadFactory,
            final ProducerType producerType,
            final WaitStrategy waitStrategy)
    {
        this(
            RingBuffer.create(producerType, eventFactory, ringBufferSize, waitStrategy),
            threadFactory);
    }

    /**
     * Private constructor helper
     */
    private Disruptor(final RingBuffer<T> ringBuffer, final ThreadFactory threadFactory)
    {
        this.ringBuffer = ringBuffer;
        this.threadFactory = threadFactory;
    }

    /**
     * <p>Set up event handlers to handle events from the ring buffer. These handlers will process events
     * as soon as they become available, in parallel.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * 设置事件处理程序以处理来自环形缓冲区的事件。 这些处理程序将在事件可用后立即并行处理。
     * 此方法可用作链的开始。 例如，如果处理程序 A 必须在处理程序 B 之前处理事件：
     * dw.handleEventsWith(A).then(B);
     * 这个调用是累加的，但通常只应在设置 Disruptor 实例时调用一次
     *
     * @param handlers the event handlers that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SuppressWarnings("varargs")
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventHandler<? super T>... handlers)
    {
        //创建数组 term[] terms=new term[]();
        return createEventProcessors(new Sequence[0], handlers);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start these processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the handler <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * <p>Since this is the start of the chain, the processor factories will always be passed an empty <code>Sequence</code>
     * array, so the factory isn't necessary in this case. This method is provided for consistency with
     * {@link EventHandlerGroup#handleEventsWith(EventProcessorFactory...)} and {@link EventHandlerGroup#then(EventProcessorFactory...)}
     * which do have barrier sequences to provide.</p>
     *
     * <p>This call is additive, but generally should only be called once when setting up the Disruptor instance</p>
     *
     * @param eventProcessorFactories the event processor factories to use to create the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    @SafeVarargs
    public final EventHandlerGroup<T> handleEventsWith(final EventProcessorFactory<T>... eventProcessorFactories)
    {
        final Sequence[] barrierSequences = new Sequence[0];
        return createEventProcessors(barrierSequences, eventProcessorFactories);
    }

    /**
     * <p>Set up custom event processors to handle events from the ring buffer. The Disruptor will
     * automatically start this processors when {@link #start()} is called.</p>
     *
     * <p>This method can be used as the start of a chain. For example if the processor <code>A</code> must
     * process events before handler <code>B</code>:</p>
     * <pre><code>dw.handleEventsWith(A).then(B);</code></pre>
     *
     * @param processors the event processors that will process events.
     * @return a {@link EventHandlerGroup} that can be used to chain dependencies.
     */
    public EventHandlerGroup<T> handleEventsWith(final EventProcessor... processors)
    {
        for (final EventProcessor processor : processors)
        {
            consumerRepository.add(processor);
        }

        final Sequence[] sequences = new Sequence[processors.length];
        for (int i = 0; i < processors.length; i++)
        {
            sequences[i] = processors[i].getSequence();
        }

        ringBuffer.addGatingSequences(sequences);

        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }


    /**
     * <p>Specify an exception handler to be used for any future event handlers.</p>
     *
     * <p>Note that only event handlers set up after calling this method will use the exception handler.</p>
     *
     * @param exceptionHandler the exception handler to use for any future {@link EventProcessor}.
     * @deprecated This method only applies to future event handlers. Use setDefaultExceptionHandler instead which applies to existing and new event handlers.
     */
    @Deprecated
    public void handleExceptionsWith(final ExceptionHandler<? super T> exceptionHandler)
    {
        this.exceptionHandler = exceptionHandler;
    }

    /**
     * <p>Specify an exception handler to be used for event handlers and worker pools created by this Disruptor.</p>
     *
     * <p>The exception handler will be used by existing and future event handlers and worker pools created by this Disruptor instance.</p>
     *
     * @param exceptionHandler the exception handler to use.
     */
    @SuppressWarnings("unchecked")
    public void setDefaultExceptionHandler(final ExceptionHandler<? super T> exceptionHandler)
    {
        checkNotStarted();
        if (!(this.exceptionHandler instanceof ExceptionHandlerWrapper))
        {
            throw new IllegalStateException("setDefaultExceptionHandler can not be used after handleExceptionsWith");
        }
        ((ExceptionHandlerWrapper<T>) this.exceptionHandler).switchTo(exceptionHandler);
    }

    /**
     * Override the default exception handler for a specific handler.
     * <pre>disruptorWizard.handleExceptionsIn(eventHandler).with(exceptionHandler);</pre>
     *
     * @param eventHandler the event handler to set a different exception handler for.
     * @return an ExceptionHandlerSetting dsl object - intended to be used by chaining the with method call.
     */
    public ExceptionHandlerSetting<T> handleExceptionsFor(final EventHandler<T> eventHandler)
    {
        return new ExceptionHandlerSetting<>(eventHandler, consumerRepository);
    }

    /**
     * <p>Create a group of event handlers to be used as a dependency.
     * For example if the handler <code>A</code> must process events before handler <code>B</code>:</p>
     *
     * <pre><code>dw.after(A).handleEventsWith(B);</code></pre>
     *
     * @param handlers the event handlers, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventHandler[])},
     *                 that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a dependency barrier over the specified event handlers.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public final EventHandlerGroup<T> after(final EventHandler<T>... handlers)
    {
        final Sequence[] sequences = new Sequence[handlers.length];
        for (int i = 0, handlersLength = handlers.length; i < handlersLength; i++)
        {
            sequences[i] = consumerRepository.getSequenceFor(handlers[i]);
        }

        return new EventHandlerGroup<>(this, consumerRepository, sequences);
    }

    /**
     * Create a group of event processors to be used as a dependency.
     *
     * @param processors the event processors, previously set up with {@link #handleEventsWith(com.lmax.disruptor.EventProcessor...)},
     *                   that will form the barrier for subsequent handlers or processors.
     * @return an {@link EventHandlerGroup} that can be used to setup a {@link SequenceBarrier} over the specified event processors.
     * @see #after(com.lmax.disruptor.EventHandler[])
     */
    public EventHandlerGroup<T> after(final EventProcessor... processors)
    {
        return new EventHandlerGroup<>(this, consumerRepository, Util.getSequencesFor(processors));
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     */
    public void publishEvent(final EventTranslator<T> eventTranslator)
    {
        ringBuffer.publishEvent(eventTranslator);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             A single argument to load into the event
     */
    public <A> void publishEvent(final EventTranslatorOneArg<T, A> eventTranslator, final A arg)
    {
        ringBuffer.publishEvent(eventTranslator, arg);
    }

    /**
     * Publish a batch of events to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg             An array single arguments to load into the events. One Per event.
     */
    public <A> void publishEvents(final EventTranslatorOneArg<T, A> eventTranslator, final A[] arg)
    {
        ringBuffer.publishEvents(eventTranslator, arg);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param <A> Class of the user supplied argument.
     * @param <B> Class of the user supplied argument.
     * @param eventTranslator the translator that will load data into the event.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     */
    public <A, B> void publishEvent(final EventTranslatorTwoArg<T, A, B> eventTranslator, final A arg0, final B arg1)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1);
    }

    /**
     * Publish an event to the ring buffer.
     *
     * @param eventTranslator the translator that will load data into the event.
     * @param <A> Class of the user supplied argument.
     * @param <B> Class of the user supplied argument.
     * @param <C> Class of the user supplied argument.
     * @param arg0            The first argument to load into the event
     * @param arg1            The second argument to load into the event
     * @param arg2            The third argument to load into the event
     */
    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<T, A, B, C> eventTranslator, final A arg0, final B arg1, final C arg2)
    {
        ringBuffer.publishEvent(eventTranslator, arg0, arg1, arg2);
    }

    /**
     * <p>Starts the event processors and returns the fully configured ring buffer.</p>
     *
     * <p>The ring buffer is set up to prevent overwriting any entry that is yet to
     * be processed by the slowest event processor.</p>
     *
     * <p>This method must only be called once after all event processors have been added.</p>
     *
     * 启动事件处理器并返回完全配置的环形缓冲区。
     * 设置环形缓冲区以防止覆盖任何尚未由最慢事件处理器处理的条目。
     * 此方法只能在添加所有事件处理器后调用一次。
     *
     * @return the configured ring buffer.
     */
    public RingBuffer<T> start()
    {
        checkOnlyStartedOnce();
        for (final ConsumerInfo consumerInfo : consumerRepository)
        {
            /**
             * 启动消费者线程
             */
            consumerInfo.start(threadFactory);
        }

        return ringBuffer;
    }

    /**
     * Calls {@link com.lmax.disruptor.EventProcessor#halt()} on all of the event processors created via this disruptor.
     */
    public void halt()
    {
        for (final ConsumerInfo consumerInfo : consumerRepository)
        {
            consumerInfo.halt();
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.  It is critical that publishing to the ring buffer has stopped
     * before calling this method, otherwise it may never return.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     */
    public void shutdown()
    {
        try
        {
            shutdown(-1, TimeUnit.MILLISECONDS);
        }
        catch (final TimeoutException e)
        {
            exceptionHandler.handleOnShutdownException(e);
        }
    }

    /**
     * <p>Waits until all events currently in the disruptor have been processed by all event processors
     * and then halts the processors.</p>
     *
     * <p>This method will not shutdown the executor, nor will it await the final termination of the
     * processor threads.</p>
     *
     * @param timeout  the amount of time to wait for all events to be processed. <code>-1</code> will give an infinite timeout
     * @param timeUnit the unit the timeOut is specified in
     * @throws TimeoutException if a timeout occurs before shutdown completes.
     */
    public void shutdown(final long timeout, final TimeUnit timeUnit) throws TimeoutException
    {
        final long timeOutAt = System.currentTimeMillis() + timeUnit.toMillis(timeout);
        while (hasBacklog())
        {
            if (timeout >= 0 && System.currentTimeMillis() > timeOutAt)
            {
                throw TimeoutException.INSTANCE;
            }
            // Busy spin
        }
        halt();
    }

    /**
     * The {@link RingBuffer} used by this Disruptor.  This is useful for creating custom
     * event processors if the behaviour of {@link BatchEventProcessor} is not suitable.
     *
     * @return the ring buffer used by this Disruptor.
     */
    public RingBuffer<T> getRingBuffer()
    {
        return ringBuffer;
    }

    /**
     * Get the value of the cursor indicating the published sequence.
     *
     * @return value of the cursor for events that have been published.
     */
    public long getCursor()
    {
        return ringBuffer.getCursor();
    }

    /**
     * The capacity of the data structure to hold entries.
     *
     * @return the size of the RingBuffer.
     * @see com.lmax.disruptor.Sequencer#getBufferSize()
     */
    public long getBufferSize()
    {
        return ringBuffer.getBufferSize();
    }

    /**
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event.
     * @return event for the sequence.
     * @see RingBuffer#get(long)
     */
    public T get(final long sequence)
    {
        return ringBuffer.get(sequence);
    }

    /**
     * Get the {@link SequenceBarrier} used by a specific handler. Note that the {@link SequenceBarrier}
     * may be shared by multiple event handlers.
     *
     * @param handler the handler to get the barrier for.
     * @return the SequenceBarrier used by <i>handler</i>.
     */
    public SequenceBarrier getBarrierFor(final EventHandler<T> handler)
    {
        return consumerRepository.getBarrierFor(handler);
    }

    /**
     * Gets the sequence value for the specified event handlers.
     *
     * @param b1 eventHandler to get the sequence for.
     * @return eventHandler's sequence
     */
    public long getSequenceValueFor(final EventHandler<T> b1)
    {
        return consumerRepository.getSequenceFor(b1).get();
    }

    /**
     * Confirms if all messages have been consumed by all event processors
     */
    private boolean hasBacklog()
    {
        final long cursor = ringBuffer.getCursor();

        return consumerRepository.hasBacklog(cursor, false);
    }

    /**
     * Checks if disruptor has been started
     *
     * @return true when start has been called on this instance; otherwise false
     */
    public boolean hasStarted()
    {
      return started.get();
    }

    EventHandlerGroup<T> createEventProcessors(
        final Sequence[] barrierSequences,
        final EventHandler<? super T>[] eventHandlers)
    {
        checkNotStarted();

        /**
         * 用来保存每个消费者的消费进度
         */
        final Sequence[] processorSequences = new Sequence[eventHandlers.length];
        /**
         *
         *Disruptor对象在创建的时候会 创建一个RingBuffer对象，RingBuffer对象内部有一个Sequencer属性，如果是但生产者模式那么这个Sequencer对象就是SingleProducerSequencer。
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
         */
        final SequenceBarrier barrier = ringBuffer.newBarrier(barrierSequences);

        for (int i = 0, eventHandlersLength = eventHandlers.length; i < eventHandlersLength; i++)
        {
            final EventHandler<? super T> eventHandler = eventHandlers[i];

            /**
             * 每一个EventHandler都会被封装成BatchEventProcessor，这个是批量处理的
             *
             * 每一个BatchEventProcessor对象内部都有一个  Sequence sequence对象
             *
             */
            final BatchEventProcessor<T> batchEventProcessor =
                new BatchEventProcessor<>(ringBuffer, barrier, eventHandler);

            if (exceptionHandler != null)
            {
                batchEventProcessor.setExceptionHandler(exceptionHandler);
            }

            /**
             *   // 注册到consumerRepository[详解2]
             */
            consumerRepository.add(batchEventProcessor, eventHandler, barrier);
            /**
             * 获取BatchEventProcessor对象内部的Sequence对象，表示每一个BatchEventProcessor的消费进度
             *
             */
            processorSequences[i] = batchEventProcessor.getSequence();
        }

        /**
         * barrierSequences：依赖的消费进度
         *
         * processorSequence表示 新进消费者进度。
         */
        updateGatingSequencesForNextInChain(barrierSequences, processorSequences);

        return new EventHandlerGroup<>(this, consumerRepository, processorSequences);
    }

    private void updateGatingSequencesForNextInChain(final Sequence[] barrierSequences, final Sequence[] processorSequences)
    {
        if (processorSequences.length > 0)
        {
            /**
             * 新进消费者的消费进度加入到 所有消费者的消费进度数组中。
             */
            ringBuffer.addGatingSequences(processorSequences);

            /**
             * 如果说这个新进消费者是依赖了其他的消费者的，那么把其他的消费者从【所有消费者的消费进度数组】中移除。
             * 这里为什么要移除呢？因为【所有消费者的消费进度数组】主要是用来获取最慢的进度的。那么被依赖的可以不用考虑，
             * 因为它不可能比依赖它的慢。并且让这个数组足够小，可以提升计算最慢进度的性能。
             *
             */
            for (final Sequence barrierSequence : barrierSequences)
            {
                ringBuffer.removeGatingSequence(barrierSequence);
            }
            /**
             * 把被依赖的消费者的endOfChain属性设置为false。 这个endOfChain用来做什么？ 主要是Disruptor在shutdown的时候需要判断是否
             * 所有消费者都已经消费完了， 如果依赖了别人的消费者都消费完了，那么整条链路上一定都消费完了。
             *
             *
             */
            consumerRepository.unMarkEventProcessorsAsEndOfChain(barrierSequences);
        }
    }

    EventHandlerGroup<T> createEventProcessors(
        final Sequence[] barrierSequences, final EventProcessorFactory<T>[] processorFactories)
    {
        final EventProcessor[] eventProcessors = new EventProcessor[processorFactories.length];
        for (int i = 0; i < processorFactories.length; i++)
        {
            eventProcessors[i] = processorFactories[i].createEventProcessor(ringBuffer, barrierSequences);
        }

        return handleEventsWith(eventProcessors);
    }

    private void checkNotStarted()
    {
        if (started.get())
        {
            throw new IllegalStateException("All event handlers must be added before calling starts.");
        }
    }

    private void checkOnlyStartedOnce()
    {
        if (!started.compareAndSet(false, true))
        {
            throw new IllegalStateException("Disruptor.start() must only be called once.");
        }
    }

    @Override
    public String toString()
    {
        return "Disruptor{" +
            "ringBuffer=" + ringBuffer +
            ", started=" + started +
            ", threadFactory=" + threadFactory +
            '}';
    }
}
