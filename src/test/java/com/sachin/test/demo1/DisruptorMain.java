package com.sachin.test.demo1;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class DisruptorMain
{
    private static final AtomicInteger threadCount=new AtomicInteger(0);

    public static void main(String[] args)
    {


        final int bufferSize=1024;
        final int size=0;

        ExecutorService executorService = Executors.newFixedThreadPool(8);

        Disruptor<TradeEvent> disruptor = new Disruptor<TradeEvent>(() ->
        {
            return new TradeEvent();
        },
                bufferSize,
                new ThreadFactory()
                {
                    @Override
                    public Thread newThread(final Runnable r)
                    {
                        Runnable target;
                        Thread thread=new Thread(r);
                        thread.setName("disruptor-" + threadCount.incrementAndGet());
                        return thread;
                    }
                },
                ProducerType.SINGLE,
                new BusySpinWaitStrategy()
        );

        //菱形方案，c1 和c2并发执行之后交给C3执行

    }

    private void executeC1AndC2ThenC3(Disruptor<TradeEvent> disruptor)
    {
        EventHandlerGroup<TradeEvent> tradeEventEventHandlerGroup = disruptor.handleEventsWith(new Handler1(), new Handler2());
        tradeEventEventHandlerGroup.then(new Handler3());
    }
}
