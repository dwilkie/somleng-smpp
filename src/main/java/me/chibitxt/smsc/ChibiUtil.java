package me.chibitxt.smsc;

public class ChibiUtil {
  public static boolean getBooleanProperty(String key, String defaultValue) {
    int value = Integer.parseInt(
      System.getProperty(key, defaultValue)
    );

    boolean property;

    if(value == 1) {
      property = true;
    } else {
      property = false;
    }

    return property;
  }
}
