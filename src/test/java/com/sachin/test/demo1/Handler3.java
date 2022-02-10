package com.sachin.test.demo1;

import com.lmax.disruptor.EventHandler;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class Handler3 implements EventHandler<TradeEvent>
{
    @Override
    public void onEvent(final TradeEvent event, final long sequence, final boolean endOfBatch) throws Exception
    {
        // 获取当前线程id
        long threadId = Thread.currentThread().getId();
        // 获取订单号
        String id = event.getId();
        System.out.println(String.format("%s：Thread Id %s 订单信息 %s 处理中 ....",
                this.getClass().getSimpleName(), threadId, id));
    }
}
