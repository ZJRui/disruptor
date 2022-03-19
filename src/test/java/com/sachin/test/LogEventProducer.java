package com.sachin.test;

import com.lmax.disruptor.RingBuffer;

import java.nio.ByteBuffer;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LogEventProducer
{

    private final RingBuffer<LongEvent> ringBuffer;

    public LogEventProducer(RingBuffer<LongEvent> ringBuffer){
        this.ringBuffer = ringBuffer;
    }

    //必须要遵循四个步骤
    public void onData(ByteBuffer byteBuffer)
    {
        long sequence = ringBuffer.next();

        try{
            LongEvent longEvent = ringBuffer.get(sequence);
            longEvent.setValue(byteBuffer.getLong(0));

        }finally
        {
            ringBuffer.publish(sequence);

        }
    }
}
