package me.chibitxt.smsc;

public class ReconnectionTask implements Runnable {

  private final OutboundClient client;
  private Integer connectionFailedTimes;

  protected ReconnectionTask(OutboundClient client, Integer connectionFailedTimes) {
    this.client = client;
    this.connectionFailedTimes = connectionFailedTimes;
  }

  @Override
  public void run() {
    client.reconnect(connectionFailedTimes);
  }
}
