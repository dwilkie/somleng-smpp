package me.chibitxt.smsc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class MtMessageJobRunner implements Runnable {
  private static final Logger logger = LoggerFactory.getLogger(MtMessageJobRunner.class);
  private final String priority;
  private final String externalMessageId;
  private final String preferredSmppServerName;
  private final String sourceAddress;
  private final String destAddress;
  private final String messageBody;
  private BlockingQueue queue;

  public MtMessageJobRunner(final String externalMessageId, final String preferredSmppServerName, final String sourceAddress, final String destAddress, final String messageBody) {
    this("0", externalMessageId, preferredSmppServerName, sourceAddress, destAddress, messageBody);
  }

  public MtMessageJobRunner(final String priority, final String externalMessageId, final String preferredSmppServerName, final String sourceAddress, final String destAddress, final String messageBody) {
    this.priority = priority;
    this.externalMessageId = externalMessageId;
    this.preferredSmppServerName = preferredSmppServerName;
    this.sourceAddress = sourceAddress;
    this.destAddress = destAddress;
    this.messageBody = messageBody;
  }

  public void setQueue(final BlockingQueue q) { queue = q; }

  public void run() {
    try {
      queue.put(produce());
      logger.info("Added job to blocking queue");
    }
    catch(InterruptedException ex) {
      logger.error("failed!", ex );
    }
  }

  private MtMessageJob produce() {
    return new MtMessageJob(
      priority,
      externalMessageId,
      preferredSmppServerName,
      sourceAddress,
      destAddress,
      messageBody
    );
  }
}
