package com.sachin.test;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LogEventMain
{
    private static final AtomicInteger threadCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception
    {


        LongEventFactory longEventFactory = new LongEventFactory();

        int ringBufferSize = 8;

        Disruptor<LongEvent> disruptor = new Disruptor<LongEvent>(longEventFactory, ringBufferSize, new ThreadFactory()
        {
            @Override
            public Thread newThread(final Runnable r)
            {
                Runnable target;
                Thread thread = new Thread(r);
                thread.setName("custom-disruptor-" + threadCount.incrementAndGet());
                return thread;
            }
        }, ProducerType.MULTI, new YieldingWaitStrategy());

        //指定消息处理器

        disruptor.handleEventsWith(new LongEventHandler());

        disruptor.start();

        //发布事件
        RingBuffer<LongEvent> ringBuffer = disruptor.getRingBuffer();

        CountDownLatch countDownLatch = new CountDownLatch(1);
        for (int i = 0; i < 3; i++)
        {
            new Thread(new Runnable()
            {
                @Override
                public void run()
                {

                    Thread thread = Thread.currentThread();
                    thread.setName("Producer-thread，num-" + threadCount.incrementAndGet());
                    LogEventMain.createProducerAndSendData(ringBuffer, countDownLatch);

                }
            }).start();

        }

//        createProducerAndSendData(ringBuffer);
        Thread.sleep(1000);
        countDownLatch.countDown();
        System.in.read();
        disruptor.shutdown();


    }

    private static void createProducerAndSendData(RingBuffer<LongEvent> ringBuffer, CountDownLatch countDownLatch)
    {
        LogEventProducer producer = new LogEventProducer(ringBuffer);

//        LongEventProducerWithTranslator producerWithTranslator = new LongEventProducerWithTranslator(ringBuffer);
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        try
        {
            countDownLatch.await();
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }


        for (int i = 0; i < 100; i++)
        {
            byteBuffer.putLong(0, i);
            producer.onData(byteBuffer);
        }
    }
}
