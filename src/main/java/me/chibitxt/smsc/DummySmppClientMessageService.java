package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.SmppConstants;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.util.SmppUtil;

public class DummySmppClientMessageService implements SmppClientMessageService {

  /** delivery receipt, or MO */
  @Override
  public PduResponse received(OutboundClient client, DeliverSm pduRequest) {
    if (pduRequest.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
      DeliverSm mo = (DeliverSm) pduRequest;
      Address sourceAddress = mo.getSourceAddress();
      Address destAddress = mo.getDestAddress();
      byte dataCoding = mo.getDataCoding();

      if (SmppUtil.isMessageTypeSmscDeliveryReceipt(mo.getEsmClass())) {
        System.out.println("-------------DELIVERY RECEIPT------------");
        // On submit sm response we get a message id from the smsc
        // we should add add this to the queue when we get the response from the smsc
        // here we can enqueue that message id and state
      } else {
        System.out.println("------------MT---------------------------");
      }

      byte[] shortMessage = mo.getShortMessage();

      // Only reverse the bytes if we are UCS-2
      // Need to set the operator name here

      shortMessage = ChibiCharsetUtil.getEndianBytes(
        shortMessage, ChibiCharsetUtil.getByteOrder("SMART")
      );

      String messageText = CharsetUtil.decode(shortMessage, "UCS-2");

      System.out.println(sourceAddress + ", " + destAddress + ", " + messageText);
    }
    return pduRequest.createResponse();
  }
}
