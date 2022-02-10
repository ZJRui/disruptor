package com.sachin.test;

import com.lmax.disruptor.EventHandler;

import java.util.UUID;

/**
 * @Author Sachin
 * @Date 2021/11/29
 **/
public class TradeHandler implements EventHandler<Trade>
{


    @Override
    public void onEvent(final Trade event, final long sequence, final boolean endOfBatch) throws Exception
    {
        String threadName = Thread.currentThread().getId() + "-" + Thread.currentThread().getName();

        event.setId(UUID.randomUUID().toString());
        System.out.println("线程：" + threadName + "消费了消息" + event.getId());

    }
}
