package com.sachin.test;

import com.lmax.disruptor.EventFactory;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class LongEventFactory implements EventFactory
{
    @Override
    public Object newInstance()
    {
        return new LongEvent();
    }
}
