package com.sachin.test;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import com.lmax.disruptor.YieldingWaitStrategy;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @Author Sachin
 * @Date 2021/11/29
 **/
public class TestMain
{

    public static void main(String[] args) throws  Exception
    {
        int BUFFER_SIZE=1024;
        int TRADE_NUMBER=4;

        final RingBuffer<Trade> ringBuffer = RingBuffer.createSingleProducer(new EventFactory<Trade>()
        {
            @Override
            public Trade newInstance()
            {
                return new Trade();
            }

        }, BUFFER_SIZE, new YieldingWaitStrategy());

        //创建一个固定数量的线程池，这个线程池执行什么任务呢？从下面来看  我们将消息处理器添加到了线程池中； 然后创建了一个 Callable 来生产 Event ，将这个callable也加入到了线程池中
        ExecutorService executorService = Executors.newFixedThreadPool(TRADE_NUMBER);

        SequenceBarrier sequenceBarrier = ringBuffer.newBarrier();
        //创建消息处理器， 这个消息处理器本质上是一个 Runnable
        BatchEventProcessor<Trade> transProcessor = new BatchEventProcessor<Trade>(ringBuffer, sequenceBarrier, new TradeHandler());

        //把消费者的位置信息引用注入到生产者， 如果只有一个消费者的情况可以省略
        ringBuffer.addGatingSequences(transProcessor.getSequence());
        //把消息处理器提交到线程池
        executorService.submit(transProcessor);


        Future<Void> feature = executorService.submit(new Callable<Void>()
        {
            @Override
            public Void call() throws Exception
            {
                long seq;
                for(int i=0;i<10;i++)
                {
                    //占个坑--RingBuffer 一个可用区域
                    seq = ringBuffer.next();
                    ringBuffer.get(seq).setPrice(Math.random() * 999);
                    //发布这个区块的数据使handler可见
                    ringBuffer.publish(seq);

                }
                return null;
            }
        });
        //等待 消息生产者将消息发布完成。
        feature.get();
        Thread.sleep(1000);
        //通知事件处理器（消息处理器） 可以结束了 并不是马上结束
        transProcessor.halt();
        executorService.shutdown();
    }

}
