package me.chibitxt.smsc;

import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Iterator;
import com.cloudhopper.commons.util.*;

public class ShutdownClient implements Runnable {
  private ExecutorService executorService;
  private Map<String,LoadBalancedList<OutboundClient>> balancedLists;

  public ShutdownClient(ExecutorService executorService, Map balancedLists) {
    this.executorService = executorService;
    this.balancedLists = balancedLists;
  }

  public void run() {
    executorService.shutdownNow();
    ReconnectionDaemon.getInstance().shutdown();

    Iterator it = balancedLists.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry)it.next();
      LoadBalancedList<OutboundClient> list = (LoadBalancedList<OutboundClient>)pair.getValue();
      for (LoadBalancedList.Node<OutboundClient> node : list.getValues()) {
        node.getValue().shutdown();
      }
      it.remove(); // avoids a ConcurrentModificationException
    }
  }
}
