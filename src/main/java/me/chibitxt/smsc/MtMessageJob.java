package me.chibitxt.smsc;

public class MtMessageJob implements Comparable<MtMessageJob> {
  private final int priority;
  private final String externalMessageId;
  private String preferredSmppServerName;
  private final String sourceAddress;
  private final String destAddress;
  private final String messageBody;

  public MtMessageJob(final String priority, final String externalMessageId, String preferredSmppServerName, final String sourceAddress, final String destAddress, final String messageBody) {
    this.priority = Integer.parseInt(priority);
    this.externalMessageId = externalMessageId;
    this.preferredSmppServerName = preferredSmppServerName;
    this.sourceAddress = sourceAddress;
    this.destAddress = destAddress;
    this.messageBody = messageBody;
  }

  public int getPriority() {
    return priority;
  }

  public int getExternalMessageId() {
    return Integer.parseInt(externalMessageId);
  }

  public String getPreferredSmppServerName() {
    return preferredSmppServerName.toUpperCase();
  }

  public String getSourceAddress() {
    return sourceAddress;
  }

  public String getDestAddress() {
    return destAddress;
  }

  public String getMessageBody() {
    return messageBody;
  }

  public void setPreferredSmppServerName(String preferredSmppServerName) {
    this.preferredSmppServerName = preferredSmppServerName;
  }

  public int compareTo(MtMessageJob compareMtMessageJob) {
    return compareMtMessageJob.getPriority() - this.priority;
  }
}
