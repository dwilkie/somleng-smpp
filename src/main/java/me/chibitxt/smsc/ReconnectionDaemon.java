package me.chibitxt.smsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * #schedule cannot spawn more threads than corePoolSize, so the blocking work is done by separate executor
 */
public class ReconnectionDaemon {

  private static final Logger log = LoggerFactory.getLogger(ReconnectionDaemon.class);

  private static final ReconnectionDaemon RECONNECTION_DAEMON = new ReconnectionDaemon("0,5,15");
  private static final long KEEP_ALIVE_TIME = 60L;

  private final String[] reconnectionPeriods;

  private final ThreadPoolExecutor executor;
  private final ScheduledExecutorService scheduledExecutorService;

  public ReconnectionDaemon(String reconnectionPeriods) {
    this.reconnectionPeriods = reconnectionPeriods.split(",");
    scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(getThreadFactory("ReconnectionSchedulerDaemon-"));

    executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, KEEP_ALIVE_TIME, TimeUnit.SECONDS,
        new SynchronousQueue<Runnable>(), getThreadFactory("ReconnectionExecutorDaemon-"));
  }

  private ThreadFactory getThreadFactory(final String name) {
    return new ThreadFactory() {

      private AtomicInteger sequence = new AtomicInteger(0);

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName(name + sequence.getAndIncrement());
        return t;
      }
    };
  }

  public static ReconnectionDaemon getInstance() {
    return RECONNECTION_DAEMON;
  }

  public void scheduleReconnect(OutboundClient outboundClient, Integer failureCount,
                  ReconnectionTask reconnectionTask) {
     long delay = getReconnectionPeriod(failureCount);
    log.info("Scheduling reconnect for {} in {} seconds", outboundClient, delay);
    scheduledExecutorService.schedule(new ScheduledTask(reconnectionTask), delay,
        TimeUnit.SECONDS);
  }

  private long getReconnectionPeriod(Integer failureCount) {
    String reconnectionPeriod;
    if (reconnectionPeriods.length > failureCount) {
      reconnectionPeriod = reconnectionPeriods[failureCount];
    } else {
      reconnectionPeriod = reconnectionPeriods[reconnectionPeriods.length - 1];
    }
    return Long.parseLong(reconnectionPeriod);
  }

  @PreDestroy
  public void shutdown() {
    executor.shutdown();
    scheduledExecutorService.shutdown();
  }

  private class ScheduledTask implements Runnable {

    private final Runnable task;

    public ScheduledTask(Runnable task) {
      this.task = task;
    }

    @Override
    public void run() {
      executor.execute(task);
    }
  }
}
