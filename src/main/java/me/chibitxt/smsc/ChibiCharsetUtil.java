package me.chibitxt.smsc;

import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ChibiCharsetUtil {
  static public byte[] getEndianBytes(byte [] sourceBytes, ByteOrder destByteOrder) {
    ByteBuffer sourceByteBuffer = ByteBuffer.wrap(sourceBytes);
    ByteBuffer destByteBuffer = ByteBuffer.allocate(sourceBytes.length);

    destByteBuffer.order(destByteOrder);
    while(sourceByteBuffer.hasRemaining()) {
      destByteBuffer.putShort(sourceByteBuffer.getShort());
    }

    return destByteBuffer.array();
  }

  static public ByteOrder getByteOrder(String smppServerName) {
    ByteOrder destByteOrder;

    int convertToLittleEndian = Integer.parseInt(
      System.getProperty(smppServerName + "_SMPP_MT_UCS2_LITTLE_ENDIANNESS", "1")
    );

    if(convertToLittleEndian == 1) {
        destByteOrder = ByteOrder.LITTLE_ENDIAN;
    } else {
        destByteOrder = ByteOrder.BIG_ENDIAN;
    }

    return destByteOrder;
  }
}
