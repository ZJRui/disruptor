package com.sachin.test.demo1;

import com.lmax.disruptor.EventHandler;

/**
 * @Author Sachin
 * @Date 2021/11/30
 * 模拟将数据保存到数据库中
 **/
public class Handler1 implements EventHandler<TradeEvent>
{
    @Override
    public void onEvent(final TradeEvent event, final long sequence, final boolean endOfBatch) throws Exception
    {
        String id = event.getId();
        String threadName = Thread.currentThread().getId() + Thread.currentThread().getName();
        Thread.sleep(30);
        System.out.println(String.format("%s: threadId %s 订单%s保存到数据库", this.getClass().getSimpleName(), threadName, id));
    }
}
