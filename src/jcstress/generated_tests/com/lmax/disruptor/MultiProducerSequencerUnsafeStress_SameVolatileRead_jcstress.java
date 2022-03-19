package com.lmax.disruptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jcstress.infra.runners.ForkedTestConfig;
import org.openjdk.jcstress.infra.collectors.TestResult;
import org.openjdk.jcstress.infra.runners.Runner;
import org.openjdk.jcstress.infra.runners.WorkerSync;
import org.openjdk.jcstress.util.Counter;
import org.openjdk.jcstress.os.AffinitySupport;
import org.openjdk.jcstress.vm.AllocProfileSupport;
import org.openjdk.jcstress.infra.runners.FootprintEstimator;
import org.openjdk.jcstress.infra.runners.VoidThread;
import org.openjdk.jcstress.infra.runners.LongThread;
import org.openjdk.jcstress.infra.runners.CounterThread;
import com.lmax.disruptor.MultiProducerSequencerUnsafeStress.SameVolatileRead;
import org.openjdk.jcstress.infra.results.ZZ_Result;

public final class MultiProducerSequencerUnsafeStress_SameVolatileRead_jcstress extends Runner<ZZ_Result> {

    volatile WorkerSync workerSync;
    SameVolatileRead[] gs;
    ZZ_Result[] gr;

    public MultiProducerSequencerUnsafeStress_SameVolatileRead_jcstress(ForkedTestConfig config) {
        super(config);
    }

    @Override
    public void sanityCheck(Counter<ZZ_Result> counter) throws Throwable {
        sanityCheck_API(counter);
        sanityCheck_Footprints(counter);
    }

    private void sanityCheck_API(Counter<ZZ_Result> counter) throws Throwable {
        final SameVolatileRead s = new SameVolatileRead();
        final ZZ_Result r = new ZZ_Result();
        VoidThread a0 = new VoidThread() { protected void internalRun() {
            s.actor1();
        }};
        VoidThread a1 = new VoidThread() { protected void internalRun() {
            s.actor2(r);
        }};
        a0.start();
        a1.start();
        a0.join();
        if (a0.throwable() != null) {
            throw a0.throwable();
        }
        a1.join();
        if (a1.throwable() != null) {
            throw a1.throwable();
        }
        counter.record(r);
    }

    private void sanityCheck_Footprints(Counter<ZZ_Result> counter) throws Throwable {
        config.adjustStrides(new FootprintEstimator() {
          public void runWith(int size, long[] cnts) {
            long time1 = System.nanoTime();
            long alloc1 = AllocProfileSupport.getAllocatedBytes();
            SameVolatileRead[] ls = new SameVolatileRead[size];
            ZZ_Result[] lr = new ZZ_Result[size];
            for (int c = 0; c < size; c++) {
                SameVolatileRead s = new SameVolatileRead();
                ZZ_Result r = new ZZ_Result();
                lr[c] = r;
                ls[c] = s;
            }
            LongThread a0 = new LongThread() { public long internalRun() {
                long a1 = AllocProfileSupport.getAllocatedBytes();
                for (int c = 0; c < size; c++) {
                    ls[c].actor1();
                }
                long a2 = AllocProfileSupport.getAllocatedBytes();
                return a2 - a1;
            }};
            LongThread a1 = new LongThread() { public long internalRun() {
                long a1 = AllocProfileSupport.getAllocatedBytes();
                for (int c = 0; c < size; c++) {
                    ls[c].actor2(lr[c]);
                }
                long a2 = AllocProfileSupport.getAllocatedBytes();
                return a2 - a1;
            }};
            a0.start();
            a1.start();
            try {
                a0.join();
                cnts[0] += a0.result();
            } catch (InterruptedException e) {
            }
            try {
                a1.join();
                cnts[0] += a1.result();
            } catch (InterruptedException e) {
            }
            for (int c = 0; c < size; c++) {
                counter.record(lr[c]);
            }
            long time2 = System.nanoTime();
            long alloc2 = AllocProfileSupport.getAllocatedBytes();
            cnts[0] += alloc2 - alloc1;
            cnts[1] += time2 - time1;
        }});
    }

    @Override
    public ArrayList<CounterThread<ZZ_Result>> internalRun() {
        gs = new SameVolatileRead[config.minStride];
        gr = new ZZ_Result[config.minStride];
        for (int c = 0; c < config.minStride; c++) {
            gs[c] = new SameVolatileRead();
            gr[c] = new ZZ_Result();
        }
        workerSync = new WorkerSync(false, 2, config.spinLoopStyle);

        control.isStopped = false;

        ArrayList<CounterThread<ZZ_Result>> threads = new ArrayList<>(2);
        threads.add(new CounterThread<ZZ_Result>() { public Counter<ZZ_Result> internalRun() {
            return task_actor1();
        }});
        threads.add(new CounterThread<ZZ_Result>() { public Counter<ZZ_Result> internalRun() {
            return task_actor2();
        }});

        for (CounterThread t : threads) {
            t.start();
        }

        if (config.time > 0) {
            try {
                TimeUnit.MILLISECONDS.sleep(config.time);
            } catch (InterruptedException e) {
            }
        }

        control.isStopped = true;

        return threads;
    }

    private void jcstress_consume(Counter<ZZ_Result> cnt, int a) {
        SameVolatileRead[] ls = gs;
        ZZ_Result[] lr = gr;
        int len = ls.length;
        int left = a * len / 2;
        int right = (a + 1) * len / 2;
        for (int c = left; c < right; c++) {
            ZZ_Result r = lr[c];
            SameVolatileRead s = ls[c];
            ls[c] = new SameVolatileRead();
            cnt.record(r);
            r.r1 = false;
            r.r2 = false;
        }
    }

    private void jcstress_update(WorkerSync sync) {
        SameVolatileRead[] ls = gs;
        ZZ_Result[] lr = gr;
        int len = ls.length;

        int newLen = sync.updateStride ? Math.max(config.minStride, Math.min(len * 2, config.maxStride)) : len;

        if (newLen > len) {
            ls = Arrays.copyOf(ls, newLen);
            lr = Arrays.copyOf(lr, newLen);
            for (int c = len; c < newLen; c++) {
                ls[c] = new SameVolatileRead();
                lr[c] = new ZZ_Result();
            }
            gs = ls;
            gr = lr;
         }

        workerSync = new WorkerSync(control.isStopped, 2, config.spinLoopStyle);
    }

    private void jcstress_sink(int v) {};
    private void jcstress_sink(short v) {};
    private void jcstress_sink(byte v) {};
    private void jcstress_sink(char v) {};
    private void jcstress_sink(long v) {};
    private void jcstress_sink(float v) {};
    private void jcstress_sink(double v) {};
    private void jcstress_sink(Object v) {};

    private Counter<ZZ_Result> task_actor1() {
        Counter<ZZ_Result> counter = new Counter<>();
        AffinitySupport.bind(config.actorMap[0]);
        while (true) {
            WorkerSync sync = workerSync;
            if (sync.stopped) {
                return counter;
            }
            sync.preRun();
            run_actor1(gs, gr);
            sync.postRun();
            jcstress_consume(counter, 0);
            if (sync.tryStartUpdate()) {
                jcstress_update(sync);
            }
            sync.postUpdate();
        }
    }

    private void run_actor1(SameVolatileRead[] gs, ZZ_Result[] gr) {
        SameVolatileRead[] ls = gs;
        ZZ_Result[] lr = gr;
        int size = ls.length;
        for (int c = 0; c < size; c++) {
            SameVolatileRead s = ls[c];
            s.actor1();
        }
    }

    private Counter<ZZ_Result> task_actor2() {
        Counter<ZZ_Result> counter = new Counter<>();
        AffinitySupport.bind(config.actorMap[1]);
        while (true) {
            WorkerSync sync = workerSync;
            if (sync.stopped) {
                return counter;
            }
            sync.preRun();
            run_actor2(gs, gr);
            sync.postRun();
            jcstress_consume(counter, 1);
            if (sync.tryStartUpdate()) {
                jcstress_update(sync);
            }
            sync.postUpdate();
        }
    }

    private void run_actor2(SameVolatileRead[] gs, ZZ_Result[] gr) {
        SameVolatileRead[] ls = gs;
        ZZ_Result[] lr = gr;
        int size = ls.length;
        for (int c = 0; c < size; c++) {
            SameVolatileRead s = ls[c];
            ZZ_Result r = lr[c];
            jcstress_sink(r.jcstress_trap);
            s.actor2(r);
        }
    }

}
