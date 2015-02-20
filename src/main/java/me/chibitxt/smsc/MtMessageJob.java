package me.chibitxt.smsc;

public class MtMessageJob {
  private final String preferredSmppServerName;
  private final String sourceAddress;
  private final String destAddress;
  private final String messageBody;

  public MtMessageJob(final String [] args) {
    this.preferredSmppServerName = args[0];
    this.sourceAddress = args[1];
    this.destAddress = args[2];
    this.messageBody = args[3];
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
