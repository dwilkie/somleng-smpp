package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.SmppConstants;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.util.SmppUtil;
import com.cloudhopper.commons.gsm.DataCoding;

public class DummySmppClientMessageService implements SmppClientMessageService {

  /** delivery receipt, or MO */
  @Override
  public PduResponse received(OutboundClient client, DeliverSm pduRequest) {
    if (pduRequest.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
      DeliverSm mo = (DeliverSm) pduRequest;
      Address sourceAddress = mo.getSourceAddress();
      Address destAddress = mo.getDestAddress();
      byte dcs = mo.getDataCoding();

      DataCoding dataCoding = DataCoding.parse(dcs);
      byte characterEncoding = dataCoding.getCharacterEncoding();

      if (SmppUtil.isMessageTypeSmscDeliveryReceipt(mo.getEsmClass())) {
        System.out.println("-------------DELIVERY RECEIPT------------");
        // On submit sm response we get a message id from the smsc
        // we should add add this to the queue when we get the response from the smsc
        // here we can enqueue that message id and state
      } else {
        System.out.println("------------MT---------------------------");

        byte[] shortMessage = mo.getShortMessage();
        String charsetName;

        if(characterEncoding == com.cloudhopper.commons.gsm.DataCoding.CHAR_ENC_UCS2) {
          // add logic to use LE if flag is set
          charsetName = com.cloudhopper.commons.charset.CharsetUtil.NAME_UCS_2;
        } else {
          // add logic here to read FLAG
          // UTF-8 works here because the default charset is ASCII
          // Latin-1 would also work
          charsetName = com.cloudhopper.commons.charset.CharsetUtil.NAME_UTF_8;
        }

        String messageText = CharsetUtil.decode(shortMessage, charsetName);
        System.out.println(sourceAddress + ", " + destAddress + ", " + messageText);
      }
    }
    return pduRequest.createResponse();
  }
}
