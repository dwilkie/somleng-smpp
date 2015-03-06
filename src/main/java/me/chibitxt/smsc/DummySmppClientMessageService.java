package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

import com.cloudhopper.smpp.type.Address;
import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.tlv.TlvConvertException;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.smpp.util.SmppUtil;
import com.cloudhopper.commons.gsm.DataCoding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DummySmppClientMessageService implements SmppClientMessageService {

  private Logger logger = LoggerFactory.getLogger(DummySmppClientMessageService.class);

  /** delivery receipt, or MO */
  @Override
  public PduResponse received(OutboundClient client, DeliverSm pduRequest) {
    if (pduRequest.getCommandId() == SmppConstants.CMD_ID_DELIVER_SM) {
      if (SmppUtil.isMessageTypeSmscDeliveryReceipt(pduRequest.getEsmClass())) {
        handleDeliveryReceipt(client, pduRequest);
      } else {
        handleMoMessage(client, pduRequest);
      }
    }
    return pduRequest.createResponse();
  }

  private void handleMoMessage(OutboundClient client, DeliverSm pduRequest) {
    DeliverSm mo = (DeliverSm) pduRequest;
    Address sourceAddress = mo.getSourceAddress();
    Address destAddress = mo.getDestAddress();
    String smppServerId = client.getSmppServerId();

    byte dcs = mo.getDataCoding();

    DataCoding dataCoding = DataCoding.parse(dcs);
    byte characterEncoding = dataCoding.getCharacterEncoding();

    byte[] shortMessage = mo.getShortMessage();
    String charsetName;

    if(characterEncoding == DataCoding.CHAR_ENC_UCS2) {
      if(ChibiUtil.getBooleanProperty(smppServerId + "_SMPP_MO_UCS2_LITTLE_ENDIANNESS", "0")) {
        charsetName = CharsetUtil.NAME_UCS_2LE;
      } else {
        charsetName = CharsetUtil.NAME_UCS_2;
      }
    } else {
      charsetName = CharsetUtil.NAME_UTF_8;
    }

    String messageText = CharsetUtil.decode(shortMessage, charsetName);
    System.out.println(sourceAddress + ", " + destAddress + ", " + messageText);
  }

  private void handleDeliveryReceipt(OutboundClient client, DeliverSm pduRequest) {
    Tlv tlvReceiptedMsgId = pduRequest.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID);
    Tlv tlvMessageState = pduRequest.getOptionalParameter(SmppConstants.TAG_MSG_STATE);
    try {
      String smscIdentifier = tlvReceiptedMsgId.getValueAsString();
      String deliveryStatus = getDeliveryStatus(tlvMessageState.getValueAsByte());
      client.deliveryReceiptReceived(smscIdentifier, deliveryStatus);
    } catch(TlvConvertException e) {
      logger.warn("Error while converting TLV", e);
    }
  }

  private String getDeliveryStatus(byte deliveryState) {
    String deliveryStatus;
    switch (deliveryState) {
      case SmppConstants.STATE_ENROUTE:        deliveryStatus = "ENROUTE";
                                               break;
      case SmppConstants.STATE_DELIVERED:      deliveryStatus = "DELIVERED";
                                               break;
      case SmppConstants.STATE_EXPIRED:        deliveryStatus = "EXPIRED";
                                               break;
      case SmppConstants.STATE_DELETED:        deliveryStatus = "DELETED";
                                               break;
      case SmppConstants.STATE_UNDELIVERABLE:  deliveryStatus = "UNDELIVERABLE";
                                               break;
      case SmppConstants.STATE_ACCEPTED:       deliveryStatus = "ACCEPTED";
                                               break;
      case SmppConstants.STATE_UNKNOWN:        deliveryStatus = "UNKNOWN";
                                               break;
      case SmppConstants.STATE_REJECTED:       deliveryStatus = "REJECTED";
                                               break;
      default:                                 deliveryStatus = "INVALID";
                                               break;
    }
    return deliveryStatus;
  }
}
