package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

public interface SmppClientMessageService {
  PduResponse received(OutboundClient client, DeliverSm deliverSm);
}
