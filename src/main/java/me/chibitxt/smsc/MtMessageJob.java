package me.chibitxt.smsc;

public class MtMessageJob {
  private final String externalMessageId;
  private final String preferredSmppServerName;
  private final String sourceAddress;
  private final String destAddress;
  private final String messageBody;

  public MtMessageJob(final String [] args) {
    this.externalMessageId = args[0];
    this.preferredSmppServerName = args[1];
    this.sourceAddress = args[2];
    this.destAddress = args[3];
    this.messageBody = args[4];
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
}
