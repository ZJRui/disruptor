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
 * Callback interface to be implemented for processing events as they become available in the {@link RingBuffer}
 *
 * @param <T> event implementation storing the data for sharing during exchange or parallel coordination of an event.
 * @see BatchEventProcessor#setExceptionHandler(ExceptionHandler) if you want to handle exceptions propagated out of the handler.
 */
public interface EventHandler<T>
{
    /**
     * Called when a publisher has published an event to the {@link RingBuffer}.  The {@link BatchEventProcessor} will
     * read messages from the {@link RingBuffer} in batches, where a batch is all of the events available to be
     * processed without having to wait for any new event to arrive.  This can be useful for event handlers that need
     * to do slower operations like I/O as they can group together the data from multiple events into a single
     * operation.  Implementations should ensure that the operation is always performed when endOfBatch is true as
     * the time between that message and the next one is indeterminate.
     *
     * 当发布者将事件发布到 RingBuffer 时调用。 BatchEventProcessor 将从 RingBuffer 中批量读取消息，
     * 其中一个批次是所有可处理的事件，而无需等待任何新事件的到达。 这对于需要执行较慢操作（如 I/O）的事件处理程序很有用，
     * 因为它们可以将来自多个事件的数据组合到一个操作中。 实现应确保在 endOfBatch 为真时始终执行操作，因为该消息与下一条消息之间的时间是不确定的。
     * 参数：
     * 事件——发布到 RingBuffer
     * sequence – 正在处理的事件
     * endOfBatch – 指示这是否是来自 RingBuffer 的批次中的最后一个事件的标志
     *=============
     * event表示消费到的本次事件的主体，在例子里也就是StringEvent
     * sequence表示消费到的本次事件对应的sequence
     * endOfBatch表示消费到的本次事件是否是这个批次中的最后一个
     *
     *
     * @param event      published to the {@link RingBuffer}
     * @param sequence   of the event being processed
     * @param endOfBatch flag to indicate if this is the last event in a batch from the {@link RingBuffer}
     * @throws Exception if the EventHandler would like the exception handled further up the chain.
     */
    void onEvent(T event, long sequence, boolean endOfBatch) throws Exception;
}
