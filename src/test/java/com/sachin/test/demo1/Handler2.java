package com.sachin.test.demo1;

import com.lmax.disruptor.EventHandler;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class Handler2 implements EventHandler<TradeEvent>
{
    @Override
    public void onEvent(final TradeEvent event, final long sequence, final boolean endOfBatch) throws Exception
    {

        final String threadName = Thread.currentThread().getId() + Thread.currentThread().getName();
        String id=event.getId();
        System.out.println(String.format("%s threadName %s ,订单%s发送到Kafka系统", this.getClass().getSimpleName(), threadName, id));

    }
}
