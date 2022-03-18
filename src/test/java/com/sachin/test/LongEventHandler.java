package com.sachin.test;

import com.lmax.disruptor.EventHandler;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LongEventHandler implements EventHandler<LongEvent>

{


    @Override
    public void onEvent(final LongEvent event, final long sequence, final boolean endOfBatch) throws Exception
    {
        String threadName = Thread.currentThread().getName() + Thread.currentThread().getId();
        System.out.println("线程：" + threadName + "消费了消息：" + event.getValue());
        System.out.println("消费结束");
        Thread.sleep(1000 * 10);//等待十秒钟

    }
}
