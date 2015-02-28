package me.chibitxt.smsc;

import java.util.concurrent.ExecutorService;
import java.util.Map;
import java.util.Iterator;
import com.cloudhopper.commons.util.*;
import net.greghaines.jesque.worker.Worker;
import net.greghaines.jesque.client.Client;

public class ShutdownClient implements Runnable {
  private ExecutorService executorService;
  private Map<String,LoadBalancedList<OutboundClient>> balancedLists;
  private Worker jedisWorker;
  private Client jedisClient;

  public ShutdownClient(ExecutorService executorService, Map balancedLists, Worker jedisWorker, Client jedisClient) {
    this.executorService = executorService;
    this.balancedLists = balancedLists;
    this.jedisWorker = jedisWorker;
    this.jedisClient = jedisClient;
  }

  public void run() {
    jedisWorker.end(true);
    jedisClient.end();

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
