package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

public class DummySmppClientMessageService implements SmppClientMessageService {

  /** delivery receipt, or MO */
  @Override
  public PduResponse received(OutboundClient client, DeliverSm deliverSm) {
    System.out.println("------------------RECEIVED Delivery receipt or MO-----------------");
    return deliverSm.createResponse();
  }
}
