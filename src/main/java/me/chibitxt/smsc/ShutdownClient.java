package me.chibitxt.smsc;

import java.util.Map;
import java.util.Iterator;
import com.cloudhopper.commons.util.*;

public class ShutdownClient implements Runnable {
  private Map<String,LoadBalancedList<OutboundClient>> balancedLists;
  private java.util.ArrayList<net.greghaines.jesque.worker.Worker> jesqueWorkerList;
  private net.greghaines.jesque.client.ClientPoolImpl jesqueClientPool;

  public ShutdownClient(Map balancedLists, java.util.ArrayList<net.greghaines.jesque.worker.Worker> jesqueWorkerList, net.greghaines.jesque.client.ClientPoolImpl jesqueClientPool) {
    this.balancedLists = balancedLists;
    this.jesqueWorkerList = jesqueWorkerList;
    this.jesqueClientPool = jesqueClientPool;
  }

  public void run() {
    for (int i = 0; i < jesqueWorkerList.size(); i++) {
      final net.greghaines.jesque.worker.Worker jesqueWorker = (net.greghaines.jesque.worker.Worker)jesqueWorkerList.get(i);
      jesqueWorker.end(true);
    }

    jesqueClientPool.end();

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
