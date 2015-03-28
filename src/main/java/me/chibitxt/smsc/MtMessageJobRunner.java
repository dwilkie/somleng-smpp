package me.chibitxt.smsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class MtMessageJobRunner implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(MtMessageJobRunner.class);
  private final String arg1;
  private final String arg2;
  private final String arg3;
  private final String arg4;
  private final String arg5;
  private BlockingQueue queue;

  public MtMessageJobRunner(final String arg1, final String arg2, final String arg3, final String arg4, final String arg5) {
    this.arg1 = arg1;
    this.arg2 = arg2;
    this.arg3 = arg3;
    this.arg4 = arg4;
    this.arg5 = arg5;
  }

  public void setQueue(final BlockingQueue q) { queue = q; }

  public void run() {
    try { queue.put(produce()); }
    catch (InterruptedException ex) { log.error( "failed!", ex ); }
  }

  private MtMessageJob produce() {
    final String[] args = {arg1, arg2, arg3, arg4, arg5};
    return new MtMessageJob(args);
  }
}
