package com.lmax.disruptor;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

class LhsPadding
{
    /**
     * 7*8=56个字节
     */
    protected byte
        p10, p11, p12, p13, p14, p15, p16, p17,
        p20, p21, p22, p23, p24, p25, p26, p27,
        p30, p31, p32, p33, p34, p35, p36, p37,
        p40, p41, p42, p43, p44, p45, p46, p47,
        p50, p51, p52, p53, p54, p55, p56, p57,
        p60, p61, p62, p63, p64, p65, p66, p67,
        p70, p71, p72, p73, p74, p75, p76, p77;
}

class Value extends LhsPadding
{
    /**
     * 不管是生产者和消费者，他们都会维护一个指向内存块的指针，而这个指针又采用了缓存行填充，以至于生产和消费的同时进行且无需加锁，而他们的指针不会存在被加载到同一缓存行中，因此也就避免了伪共享问题
     *
     * 以64字节的缓存行举例，如果我们要让变量Long a不被其他变量影响，我们只需要在其前后分别添加7个Long 类型的空变量即可。这样就能够保证a变量永远不会和其他变量在同一个缓存行中。那么a变量也就不会因为其变量的修改导致其缓存失效了。
     * Long p1,p2,p3,p4, p5, p6, p7;
     * Long a;
     * Long p8,p9,p10,p11, p12, p13, p14;
     * 这种填充可以解决代码在缓存行为64字节或者32字节的机器下的False Sharing的问题，但是解决不了缓存行为128个字节的机器下的False Sharing的问题。
     *
     * 在Disruptor中就是通过在变量前后填充7个Long类型的冗余变量来解决Long变量的False Sharing的问题。因此它解决不了缓存行为128个字节的机器中的False Long的问题。至于为什么Disruptor不解决128个字节的机器的False Sharing的问题？我想是因为徒增那么多的无用变量会得不偿失吧，再加上现在主流的机器的缓存行应该都还是64个字节的吧。
     * 另外很明显Disruptor也不是把所有的变量的False Sharing的问题都解决了，因为这会导致程序占用内存增加几十甚至几百倍。Disruptor只是对其被频繁操作的变量（高并发访问）进行了冗余自动填充处理。比如：在Disruptor中访问量最高的Sequence类中的Value变量（一个Long类变量）、生产者中的缓存变量以及RingBuffer中的BufferSize等重要变量。
     *
     * 作者：Life_of_Coder
     * 链接：https://juejin.cn/post/6992837850324533262
     * 来源：稀土掘金
     * 著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
     */
    protected long value;
}

class RhsPadding extends Value
{
    protected byte
            /**
             * 7*8=56
             * 56*2=112
             * 112+8=120
             */
        p90, p91, p92, p93, p94, p95, p96, p97,
        p100, p101, p102, p103, p104, p105, p106, p107,
        p110, p111, p112, p113, p114, p115, p116, p117,
        p120, p121, p122, p123, p124, p125, p126, p127,
        p130, p131, p132, p133, p134, p135, p136, p137,
        p140, p141, p142, p143, p144, p145, p146, p147,
        p150, p151, p152, p153, p154, p155, p156, p157;
}

/**
 * Concurrent sequence class used for tracking the progress of
 * the ring buffer and event processors.  Support a number
 * of concurrent operations including CAS and order writes.
 *
 * <p>Also attempts to be more efficient with regards to false
 * sharing by adding padding around the volatile field.
 *
 * 并发序列类，用于跟踪环形缓冲区和事件处理器的进度。支持多项并发操作，包括 CAS 和订单写入。 <p>还尝试通过在 volatile 字段周围添加填充来提高虚假共享的效率。
 *
 * 采用缓存行填充的方式对long类型的一层包装，用以代表事件的序号。通过unsafe的cas方法从而避免了锁的开销
 *
 *  disruptor中较为重要的一个类是Sequence。我们设想下，在disruptor运行过程中，事件发布者(生产者)和事件处理者(消费者)在ringbuffer上相互追逐，由什么来标记它们的相对位置呢？它们根据什么从ringbuffer上发布或者处理事件呢？就是这个Sequence-序列。
 *
 */
public class Sequence extends RhsPadding
{

    static final long INITIAL_VALUE = -1L;
    /**
     * VarHandle 的必要性
     * 随着Java中的并发和并行编程的不断扩大，我们经常会需要对某个类的字段进行原子或有序操作，但是 JVM 对Java开发者所开放的权限非常有限。例如：如果要原子性地增加某个字段的值，到目前为止我们可以使用下面三种方式：
     *
     * 使用AtomicInteger来达到这种效果，这种间接管理方式增加了空间开销，还会导致额外的并发问题；
     * 使用原子性的FieldUpdaters，由于利用了反射机制，操作开销也会更大；
     * 使用sun.misc.Unsafe提供的JVM内置函数API，虽然这种方式比较快，但它会损害安全性和可移植性，当然在实际开发中也很少会这么做。
     * 在 VarHandle 出现之前，这些潜在的问题会随着原子API的不断扩大而越来越遭。VarHandle 的出现替代了java.util.concurrent.atomic和sun.misc.Unsafe的部分操作。并且提供了一系列标准的内存屏障操作，用于更加细粒度的控制内存排序。在安全性、可用性、性能上都要优于现有的API。VarHandle 可以与任何字段、数组元素或静态变量关联，支持在不同访问模型下对这些类型变量的访问，包括简单的 read/write 访问，volatile 类型的 read/write 访问，和 CAS(compare-and-swap)等。
     *
     *
     *====================
     * 以前都是使用Unsafe这个类
     *  private static final Unsafe UNSAFE;
     *    static{
     *         UNSAFE = Util.getUnsafe();
     *         try{
     *             VALUE_OFFSET = UNSAFE.objectFieldOffset(Value.class.getDeclaredField("value"));
     *         }catch (final Exception e){
     *             throw new RuntimeException(e);
     *         }
     *     }
     *通过Unsafe 对属性进行操作，
     * ordered Write :在当前写操作和任意之前的读操作之间加入Store/Store屏障
     * public void set(final long value){
     *     UNSAFE.putOrderedLong(this, VALUE_OFFSET, value);
     * }
     * Unsafe要求操作的属性为volatile ，也就是如下：
     *  protected volatile long value;
     *
     *
     */
    private static final VarHandle VALUE_FIELD;

    static
    {
        try
        {
            /**
             * Lookup是MethodHandles的内部类，位于java.lang.invoke包中，它是一个用于创建方法和变量句柄的工厂
             */
            VALUE_FIELD = MethodHandles.lookup().in(Sequence.class)
                    .findVarHandle(Sequence.class, "value", long.class);
        }
        catch (final Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a sequence initialised to -1.
     */
    public Sequence()
    {
        this(INITIAL_VALUE);
    }

    /**
     * Create a sequence with a specified initial value.
     *
     * @param initialValue The initial value for this sequence.
     */
    public Sequence(final long initialValue)
    {
        VarHandle.releaseFence();
        this.value = initialValue;
    }

    /**
     * Perform a volatile read of this sequence's value.
     *
     * @return The current value of the sequence.
     */
    public long get()
    {
        long value = this.value;
        VarHandle.acquireFence();
        return value;
    }

    /**
     * Perform an ordered write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * store.
     *
     * @param value The new value for the sequence.
     */
    public void set(final long value)
    {
        VarHandle.releaseFence();
        this.value = value;
    }

    /**
     * Performs a volatile write of this sequence.  The intent is
     * a Store/Store barrier between this write and any previous
     * write and a Store/Load barrier between this write and any
     * subsequent volatile read.
     *
     * @param value The new value for the sequence.
     */
    public void setVolatile(final long value)
    {
        /**
         * 如果使用Unsafe可以如下操作
         * UNSAFE.putLongVolatile(this, VALUE_OFFSET, value);
         *
         */
        VarHandle.releaseFence();
        this.value = value;
        VarHandle.fullFence();
    }

    /**
     * Perform a compare and set operation on the sequence.
     *
     * @param expectedValue The expected current value.
     * @param newValue      The value to update to.
     * @return true if the operation succeeds, false otherwise.
     */
    public boolean compareAndSet(final long expectedValue, final long newValue)
    {
        return VALUE_FIELD.compareAndSet(this, expectedValue, newValue);
    }

    /**
     * Atomically increment the sequence by one.
     *
     * @return The value after the increment
     */
    public long incrementAndGet()
    {
        return addAndGet(1);
    }

    /**
     * Atomically add the supplied value.
     *
     * @param increment The value to add to the sequence.
     * @return The value after the increment.
     */
    public long addAndGet(final long increment)
    {
        return (long) VALUE_FIELD.getAndAdd(this, increment) + increment;
    }

    /**
     * Perform an atomic getAndAdd operation on the sequence.
     *
     * @param increment The value to add to the sequence.
     * @return the value before increment
     */
    public long getAndAdd(final long increment)
    {
        return (long) VALUE_FIELD.getAndAdd(this, increment);
    }

    @Override
    public String toString()
    {
        return Long.toString(get());
    }
}