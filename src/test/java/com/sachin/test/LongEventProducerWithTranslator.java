package com.sachin.test;

import com.lmax.disruptor.EventTranslatorOneArg;
import com.lmax.disruptor.RingBuffer;

import java.nio.ByteBuffer;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LongEventProducerWithTranslator
{

    private final RingBuffer<LongEvent> ringBuffer;

    public LongEventProducerWithTranslator(RingBuffer<LongEvent> ringBuffer)
    {
        this.ringBuffer = ringBuffer;
    }
    private static final EventTranslatorOneArg<LongEvent, ByteBuffer> translator=new EventTranslatorOneArg<LongEvent,ByteBuffer>(){
        @Override
        public void translateTo(final LongEvent event, final long sequence, final ByteBuffer arg0)
        {
            event.setValue(arg0.getLong(0));

        }
    };

    public void onData(ByteBuffer byteBuffer)
    {
        ringBuffer.publishEvent(translator, byteBuffer);
    }
}
