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
import com.lmax.disruptor.LoggerInitializationStress;

public class LoggerInitializationStress_jcstress extends Runner<LoggerInitializationStress_jcstress.Outcome> {

    public LoggerInitializationStress_jcstress(ForkedTestConfig config) {
        super(config);
    }

    @Override
    public TestResult run() {
        Counter<Outcome> results = new Counter<>();

        for (int c = 0; c < config.iters; c++) {
            run(results);

            if (results.count(Outcome.STALE) > 0) {
                forceExit = true;
                break;
            }
        }

        return dump(results);
    }

    @Override
    public void sanityCheck(Counter<Outcome> counter) throws Throwable {
        throw new UnsupportedOperationException();
    }

    @Override
    public ArrayList<CounterThread<Outcome>> internalRun() {
        throw new UnsupportedOperationException();
    }

    private void run(Counter<Outcome> results) {
        long target = System.currentTimeMillis() + config.time;
        while (System.currentTimeMillis() < target) {

            final LoggerInitializationStress test = new LoggerInitializationStress();
            final Holder holder = new Holder();

            Thread t1 = new Thread(new Runnable() {
                public void run() {
                    try {
                        holder.started = true;
                        test.actor();
                    } catch (Exception e) {
                        holder.error = true;
                    }
                    holder.terminated = true;
                }
            });
            t1.setDaemon(true);
            t1.start();

            while (!holder.started) {
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (InterruptedException e) {
                    // do nothing
                }
            }

            try {
                test.signal();
            } catch (Exception e) {
                holder.error = true;
            }

            try {
                t1.join(Math.max(2*config.time, Runner.MIN_TIMEOUT_MS));
            } catch (InterruptedException e) {
                // do nothing
            }

            if (holder.terminated) {
                if (holder.error) {
                    results.record(Outcome.ERROR);
                } else {
                    results.record(Outcome.TERMINATED);
                }
            } else {
                results.record(Outcome.STALE);
                return;
            }
        }
    }

    private static class Holder {
        volatile boolean started;
        volatile boolean terminated;
        volatile boolean error;
    }

    public enum Outcome {
        TERMINATED,
        STALE,
        ERROR,
    }
}
