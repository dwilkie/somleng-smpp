package me.chibitxt.smsc;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.charset.GSMCharset;
import com.cloudhopper.commons.charset.Charset;

import com.cloudhopper.commons.util.*;
import com.cloudhopper.smpp.*;
import com.cloudhopper.smpp.pdu.SubmitSm;
import com.cloudhopper.smpp.pdu.SubmitSmResp;
import com.cloudhopper.smpp.type.*;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;

import java.io.FileInputStream;
import java.util.Properties;

import java.nio.ByteOrder;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.ParseException;

import static net.greghaines.jesque.utils.JesqueUtils.entry;
import static net.greghaines.jesque.utils.JesqueUtils.map;

import net.greghaines.jesque.Config;
import net.greghaines.jesque.ConfigBuilder;
import net.greghaines.jesque.worker.Worker;
import net.greghaines.jesque.worker.WorkerImpl;
import net.greghaines.jesque.worker.WorkerEvent;
import net.greghaines.jesque.worker.WorkerListener;
import net.greghaines.jesque.worker.MapBasedJobFactory;
import net.greghaines.jesque.client.Client;
import net.greghaines.jesque.client.ClientImpl;
import net.greghaines.jesque.Job;

//import net.greghaines.jesque.TestJob;

public class Main {
  public static void main(String[] args) throws IOException, RecoverablePduException, InterruptedException,
      SmppChannelException, UnrecoverablePduException, SmppTimeoutException {

    String smppConfigurationFile = getConfigFile(args);
    loadSystemProperties(smppConfigurationFile);

    DummySmppClientMessageService smppClientMessageService = new DummySmppClientMessageService();

    final String smppServersString = System.getProperty("SMPP_SERVERS", "default");

    final String[] smppServerNames = smppServersString.split(";");

    final Map<String,LoadBalancedList<OutboundClient>> smppServerBalancedLists = new HashMap<String,LoadBalancedList<OutboundClient>>(smppServerNames.length);

    int totalNumOfThreads = 0;

    for (int smppServerCounter = 0; smppServerCounter < smppServerNames.length; smppServerCounter++) {
      String smppServerKey = smppServerNames[smppServerCounter].toUpperCase();
      int numOfThreads = Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_MT_THREAD_SIZE", "1"));

      final LoadBalancedList<OutboundClient> balancedList = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<OutboundClient>());

      for (int threadCounter = 0; threadCounter < numOfThreads; threadCounter++) {
        balancedList.set(createClient(smppClientMessageService, threadCounter, smppServerKey), 1);
        totalNumOfThreads++;
      }
      smppServerBalancedLists.put(smppServerKey, balancedList);
    }

    final ExecutorService executorService = Executors.newFixedThreadPool(totalNumOfThreads);

    final BlockingQueue mtMessageQueue = new LinkedBlockingQueue<String>();
    Config jesqueConfig = setupJesque();
    final Worker jedisWorker = startJesqueWorker(jesqueConfig, mtMessageQueue);
    final Client jedisClient = new ClientImpl(jesqueConfig);
    ShutdownClient shutdownClient = new ShutdownClient(executorService, smppServerBalancedLists, jedisWorker, jedisClient);
    Thread shutdownHook = new Thread(shutdownClient);
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    while (true) {
      // this blocks until there's a job in the queue
      final MtMessageJob job = (MtMessageJob)mtMessageQueue.take();
      final String preferredSmppServerName = job.getPreferredSmppServerName();
      final long messagesToSend = 1;
      final AtomicLong alreadySent = new AtomicLong();
      for (int j = 0; j < totalNumOfThreads; j++) {
        executorService.execute(new Runnable() {
          @Override
          public void run() {
            try {
              long sent = alreadySent.incrementAndGet();
              while (sent <= messagesToSend) {
                final OutboundClient next = smppServerBalancedLists.get(preferredSmppServerName).getNext();
                final SmppSession session = next.getSession();
                if (session != null && session.isBound()) {
                  final int mtMessageExternalId = job.getExternalMessageId();
                  final String mtMessageText = job.getMessageBody();
                  byte[] textBytes;
                  byte dataCoding;

                  if (GSMCharset.canRepresent(mtMessageText)) {
                    textBytes = CharsetUtil.encode(mtMessageText, CharsetUtil.CHARSET_GSM);
                    dataCoding = SmppConstants.DATA_CODING_GSM;
                  } else {
                    textBytes = CharsetUtil.encode(mtMessageText, CharsetUtil.CHARSET_UCS_2);
                    dataCoding = SmppConstants.DATA_CODING_UCS2;

                    textBytes = ChibiCharsetUtil.getEndianBytes(
                      textBytes, ChibiCharsetUtil.getByteOrder(preferredSmppServerName)
                    );
                  }

                  SubmitSm submit = new SubmitSm();
                  int sourceTon = Integer.parseInt(
                    System.getProperty(preferredSmppServerName + "_SMPP_SOURCE_TON", "3")
                  );
                  int sourceNpi = Integer.parseInt(
                    System.getProperty(preferredSmppServerName + "_SMPP_SOURCE_NPI", "0")
                  );

                  final String sourceAddress = job.getSourceAddress();
                  submit.setSourceAddress(new Address((byte) sourceTon, (byte) sourceNpi, sourceAddress));
                  int destTon = Integer.parseInt(
                    System.getProperty(preferredSmppServerName + "_SMPP_DESTINATION_TON", "1")
                  );
                  int destNpi = Integer.parseInt(
                    System.getProperty(preferredSmppServerName + "_SMPP_DESTINATION_NPI", "1")
                  );
                  final String destAddress = job.getDestAddress();
                  submit.setDestAddress(new Address((byte) destTon, (byte) destNpi, destAddress));
                  submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
                  submit.setServiceType(
                    System.getProperty(preferredSmppServerName + "_SMPP_SERVICE_TYPE", "vma")
                  );
                  submit.setDataCoding(dataCoding);
                  submit.setShortMessage(textBytes);
                  final SubmitSmResp submit1 = session.submit(submit, 10000);

                  String mtMessageUpdateStatusWorker = System.getProperty(
                    "SMPP_MT_MESSAGE_UPDATE_STATUS_WORKER"
                  );

                  String mtMessageUpdateStatusQueue = System.getProperty(
                    "SMPP_MT_MESSAGE_UPDATE_STATUS_QUEUE"
                  );

                  final Job job = new Job(
                    mtMessageUpdateStatusWorker,
                    mtMessageExternalId,
                    submit1.getMessageId(),
                    submit1.getCommandStatus() == SmppConstants.STATUS_OK
                  );

                  jedisClient.enqueue(mtMessageUpdateStatusQueue, job);

                  System.out.println("----SUBMIT RESP---");
                  System.out.println(submit1.getMessageId());
                  System.out.println(submit1.getResultMessage());
                  System.out.println(submit1.getCommandStatus());
                  System.out.println(submit1.getCommandStatus() == SmppConstants.STATUS_OK);
                }
                sent = alreadySent.incrementAndGet();
              }
            } catch (Exception e) {
              System.err.println(e.toString());
              return;
            }
          }
        });
      }
    }
  }

  private static final Config setupJesque() {
    final ConfigBuilder configBuilder = new ConfigBuilder();
    String redisUrlKey = System.getProperty("REDIS_PROVIDER", "YOUR_REDIS_PROVIDER");
    try {
      URI redisUrl = new URI(System.getProperty(redisUrlKey, "127.0.0.1"));

      String redisHost = redisUrl.getHost();
      int redisPort = redisUrl.getPort();
      String redisUserInfo = redisUrl.getUserInfo();

      if (redisHost != null) {
        configBuilder.withHost(redisHost);
      }

      if (redisPort > -1) {
        configBuilder.withPort(redisPort);
      }

      if (redisUserInfo != null) {
        configBuilder.withPassword(redisUserInfo.split(":",2)[1]);
      }
    }
    catch (URISyntaxException e) {
      System.err.println(e.toString());
      System.exit(1);
    }

    return configBuilder.build();
  }

  private static final Worker startJesqueWorker(final Config jesqueConfig, final BlockingQueue blockingQueue) {
    final String queueName = System.getProperty("SMPP_MT_MESSAGE_QUEUE", "default");
    final Worker worker = new WorkerImpl(jesqueConfig,
       Arrays.asList(queueName), new MapBasedJobFactory(map(entry(MtMessageJobRunner.class.getSimpleName(), MtMessageJobRunner.class))));
    worker.getWorkerEventEmitter().addListener(new WorkerListener(){
       public void onEvent(WorkerEvent event, Worker worker, String queue, Job job, Object runner, Object result, Throwable t) {
        if (runner instanceof MtMessageJobRunner) {
            ((MtMessageJobRunner) runner).setQueue(blockingQueue);
        }
      }
    }, WorkerEvent.JOB_EXECUTE);

    final Thread workerThread = new Thread(worker);
    workerThread.start();
    return worker;
  }

  private static void loadSystemProperties(String configurationFile) {
    // set up new properties object
    // from the config file
    try {
      FileInputStream propFile = new FileInputStream(configurationFile);
      Properties p = new Properties(System.getProperties());
      p.load(propFile);

      // set the system properties
      System.setProperties(p);

    } catch (IOException e) {
      System.err.println(e.toString());
      System.exit(1);
    }
  }

  private static String getConfigFile(String [] args) {
    // create the command line parser
    CommandLineParser parser = new BasicParser();

    // create Options object
    Options options = new Options();

    Option configOption = OptionBuilder.withLongOpt("config")
                                .withDescription("smpp configuration file")
                                .hasArg()
                                .withArgName("FILE")
                                .isRequired()
                                .create('c');

    options.addOption(configOption);

    String header = "Starts the client with the given configuration file\n\n";
    String footer = "";

    HelpFormatter formatter = new HelpFormatter();

    String smppConfigurationFile = "";

    try {
      CommandLine line = parser.parse(options, args);
      smppConfigurationFile = line.getOptionValue("c");
    }

    catch(ParseException exp) {
      formatter.printHelp("chibi-smpp-client", header, options, footer, true);
      System.exit(1);
    }

    return smppConfigurationFile;
  }

  private static OutboundClient createClient(DummySmppClientMessageService smppClientMessageService, int i, final String smppServerKey) {
    OutboundClient client = new OutboundClient();
    client.initialize(getSmppSessionConfiguration(i, smppServerKey), smppClientMessageService);
    client.scheduleReconnect();
    return client;
  }

  private static SmppSessionConfiguration getSmppSessionConfiguration(int i, final String smppServerKey) {
    SmppSessionConfiguration config = new SmppSessionConfiguration();
    config.setWindowSize(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_WINDOW_SIZE", "5")));
    config.setName(smppServerKey + ".Session." + i);
    config.setType(SmppBindType.TRANSCEIVER);
    config.setHost(System.getProperty(smppServerKey + "_SMPP_HOST", "127.0.0.1"));
    config.setPort(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_PORT", "2776")));
    config.setConnectTimeout(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_CONNECTION_TIMEOUT", "10000")));
    config.setSystemId(System.getProperty(smppServerKey + "_SMPP_SYSTEM_ID", "systemId" + i));
    config.setPassword(System.getProperty(smppServerKey + "_SMPP_PASSWORD", "password"));
    config.getLoggingOptions().setLogBytes(false);
    // to enable monitoring (request expiration)

    config.setRequestExpiryTimeout(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_REQUEST_EXPIRY_TIMEOUT", "30000")));
    config.setWindowMonitorInterval(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_WINDOW_MONITOR_INTERVAL", "15000")));

    config.setCountersEnabled(false);

    return config;
  }
}
