package com.sachin.test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Author Sachin
 * @Date 2021/11/29
 **/
public class Trade
{

    private String id;//ID
    private String name;
    private double price;//金额
    private AtomicInteger count = new AtomicInteger(0);

    public double getPrice()
    {
        return price;
    }

    public void setPrice(final double price)
    {
        this.price = price;
    }

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

    public AtomicInteger getCount()
    {
        return count;
    }

    public void setCount(final AtomicInteger count)
    {
        this.count = count;
    }
}
