package me.chibitxt.smsc;

import com.cloudhopper.smpp.pdu.DeliverSm;
import com.cloudhopper.smpp.pdu.PduResponse;

import com.cloudhopper.smpp.SmppConstants;
import com.cloudhopper.smpp.tlv.Tlv;
import com.cloudhopper.smpp.tlv.TlvConvertException;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.charset.Charset;
import com.cloudhopper.smpp.util.SmppUtil;
import com.cloudhopper.smpp.util.DeliveryReceipt;

import com.cloudhopper.commons.gsm.DataCoding;
import com.cloudhopper.commons.gsm.GsmUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.joda.time.DateTimeZone;

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
    byte characterEncoding = DataCoding.parse(pduRequest.getDataCoding()).getCharacterEncoding();
    String smppServerId = client.getSmppServerId();

    Charset charset;

    if(characterEncoding == DataCoding.CHAR_ENC_UCS2) {
      if(ChibiUtil.getBooleanProperty(smppServerId + "_SMPP_MO_UCS2_LITTLE_ENDIANNESS", "0")) {
        charset = CharsetUtil.CHARSET_UCS_2LE;
      } else {
        charset = CharsetUtil.CHARSET_UCS_2;
      }
    } else {
      charset = CharsetUtil.CHARSET_UTF_8;
    }

    String sourceAddress = pduRequest.getSourceAddress().getAddress();
    String destAddress = pduRequest.getDestAddress().getAddress();
    byte [] fullMessageBytes = pduRequest.getShortMessage();
    byte [] messageBytes = fullMessageBytes;

    // handle CMSC
    // Field 4 (1 octet): 00-FF, CSMS reference number, must be same for all the SMS parts in the CSMS
    // Field 5 (1 octet): 00-FF, total number of parts.
    // Field 6 (1 octet): 00-FF, this part's number in the sequence.

    // See also
    // https://github.com/twitter/cloudhopper-commons/blob/master/ch-commons-gsm/src/main/java/com/cloudhopper/commons/gsm/GsmUtil.java
    // http://en.wikipedia.org/wiki/Concatenated_SMS

    // set up csms defaults
    int csmsReferenceNum = 0;
    int csmsNumParts = 1;
    int csmsSeqNum = 1;

    if(SmppUtil.isUserDataHeaderIndicatorEnabled(pduRequest.getEsmClass())) {
      byte [] userDataHeader = GsmUtil.getShortMessageUserDataHeader(fullMessageBytes);
      messageBytes = GsmUtil.getShortMessageUserData(fullMessageBytes);

      // (byte & 0xFF) converts byte to unsigned int
      csmsReferenceNum = userDataHeader[3] & 0xFF;
      csmsNumParts = userDataHeader[4] & 0xFF;
      csmsSeqNum = userDataHeader[5] & 0xFF;
    }

    client.moMessageReceived(
      sourceAddress,
      destAddress,
      CharsetUtil.decode(messageBytes, charset),
      csmsReferenceNum,
      csmsNumParts,
      csmsSeqNum
    );
  }

  private void handleDeliveryReceipt(OutboundClient client, DeliverSm pduRequest) {
    Tlv tlvReceiptedMsgId = pduRequest.getOptionalParameter(SmppConstants.TAG_RECEIPTED_MSG_ID);
    Tlv tlvMessageState = pduRequest.getOptionalParameter(SmppConstants.TAG_MSG_STATE);
    if (tlvReceiptedMsgId != null && tlvMessageState != null) {
      try {
        String smscIdentifier = tlvReceiptedMsgId.getValueAsString();
        String deliveryStatus = getDeliveryStatus(tlvMessageState.getValueAsByte());
        client.deliveryReceiptReceived(smscIdentifier, deliveryStatus);
      } catch(TlvConvertException e) {
        logger.error("Error while converting TLV", e);
      }
    } else {
      String shortMessage = CharsetUtil.decode(pduRequest.getShortMessage(), CharsetUtil.CHARSET_UTF_8);
      try {
        int deliveryReceiptMessageIdRadix = Integer.parseInt(
          System.getProperty(client.getSmppServerId() + "_SMPP_DELIVERY_RECEIPT_MESSAGE_ID_RADIX", "10")
        );

        // don't care about the time zone here
        DeliveryReceipt dlr = DeliveryReceipt.parseShortMessage(shortMessage, DateTimeZone.UTC, false);

        String smscIdentifier = new java.math.BigInteger(dlr.getMessageId(), deliveryReceiptMessageIdRadix).toString();

        String deliveryStatus = getDeliveryStatus(dlr.getState());
        client.deliveryReceiptReceived(smscIdentifier, deliveryStatus);
      } catch(Exception e) {
        logger.error("Error while parsing Delivery Receipt", e);
      }
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
