package com.sachin.test.demo1;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author Sachin
 * @Date 2021/11/30
 **/
public class TradeEvent
{
    private static final long serialVersionUID = -4708842802680762399L;
    /**
     * 订单号
     */
    private String id;
    /**
     * 名称
     */
    private String name;
    /**
     * 价格
     */
    private double price;

    private AtomicInteger count = new AtomicInteger(0);

    public String getId()
    {
        return id;
    }

    public void setId(final String id)
    {
        this.id = id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(final String name)
    {
        this.name = name;
    }

    public double getPrice()
    {
        return price;
    }

    public void setPrice(final double price)
    {
        this.price = price;
    }

    public AtomicInteger getCount()
    {
        return count;
    }

    public void setCount(final AtomicInteger count)
    {
        this.count = count;
    }
}
