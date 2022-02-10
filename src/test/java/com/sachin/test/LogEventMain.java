package com.sachin.test;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LogEventMain
{
    private static  final AtomicInteger threadCount=new AtomicInteger(0);
    public static void main(String[] args)
    {


        ExecutorService executorService = Executors.newCachedThreadPool();
        LongEventFactory longEventFactory = new LongEventFactory();

        int ringBufferSize=1024*1024;

        Disruptor<LongEvent> disruptor = new Disruptor<LongEvent>(longEventFactory, ringBufferSize, new ThreadFactory()
        {
            @Override
            public Thread newThread(final Runnable r)
            {
                Runnable target;
                Thread thread=new Thread(r);
                thread.setName("disruptor-"+threadCount.incrementAndGet());
                return thread;
            }
        }, ProducerType.SINGLE, new YieldingWaitStrategy());

        //指定消息处理器

        disruptor.handleEventsWith(new LongEventHandler());

        disruptor.start();

        //发布事件
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();

        LogEventProducer producer = new LogEventProducer(ringBuffer);

//        LongEventProducerWithTranslator producerWithTranslator = new LongEventProducerWithTranslator(ringBuffer);
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        for(int i=0;i<100;i++)
        {
            byteBuffer.putLong(0, i);
            producer.onData(byteBuffer);
        }
        disruptor.shutdown();
        executorService.shutdown();


    }
}
