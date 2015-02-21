package me.chibitxt.smsc;

import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Iterator;
import com.cloudhopper.commons.util.*;
import net.greghaines.jesque.worker.Worker;

public class ShutdownClient implements Runnable {
  private ExecutorService executorService;
  private Map<String,LoadBalancedList<OutboundClient>> balancedLists;
  private Worker worker;

  public ShutdownClient(ExecutorService executorService, Map balancedLists, Worker worker) {
    this.executorService = executorService;
    this.balancedLists = balancedLists;
    this.worker = worker;
  }

  public void run() {
    worker.end(true);

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
