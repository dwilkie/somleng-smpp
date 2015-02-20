package me.chibitxt.smsc;

import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.SmppSessionConfiguration;

public abstract class Client {

  protected volatile SmppSession smppSession;

  public SmppSessionConfiguration getConfiguration() {
    return smppSession.getConfiguration();
  }

  public boolean isConnected() {
    SmppSession session = smppSession;
    if (session != null) {
      return session.isBound();
    }
    return false;
  }

  public SmppSession getSession() {
    return smppSession;
  }
}
