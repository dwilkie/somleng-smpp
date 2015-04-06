package me.chibitxt.smsc;

import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Iterator;
import com.cloudhopper.commons.util.*;

public class ShutdownClient implements Runnable {
  private ExecutorService executorService;
  private Map<String,LoadBalancedList<OutboundClient>> balancedLists;
  private java.util.ArrayList<net.greghaines.jesque.worker.Worker> jedisWorkerList;

  public ShutdownClient(ExecutorService executorService, Map balancedLists, java.util.ArrayList<net.greghaines.jesque.worker.Worker> jedisWorkerList) {
    this.executorService = executorService;
    this.balancedLists = balancedLists;
    this.jedisWorkerList = jedisWorkerList;
  }

  public void run() {
    for (int i = 0; i < jedisWorkerList.size(); i++) {
      final net.greghaines.jesque.worker.Worker jedisWorker = (net.greghaines.jesque.worker.Worker)jedisWorkerList.get(i);
      jedisWorker.end(true);
    }

    executorService.shutdownNow();
    ReconnectionDaemon.getInstance().shutdown();

    Iterator it = balancedLists.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry pair = (Map.Entry)it.next();
      LoadBalancedList<OutboundClient> list = (LoadBalancedList<OutboundClient>)pair.getValue();
      for (LoadBalancedList.Node<OutboundClient> node : list.getValues()) {
        OutboundClient value = node.getValue();
        value.shutdown();
      }
      it.remove(); // avoids a ConcurrentModificationException
    }
  }
}
