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

public class Main {
  public static void main(String[] args) throws IOException, RecoverablePduException, InterruptedException,
      SmppChannelException, UnrecoverablePduException, SmppTimeoutException {

    // Configuration

    String smppConfigurationFile = getConfigFile(args);
    loadSystemProperties(smppConfigurationFile);

    // External Queues and Workers

    // MT Message Updater
    final String mtMessageUpdateStatusWorker = System.getProperty(
      "SMPP_MT_MESSAGE_UPDATE_STATUS_WORKER"
    );

    final String mtMessageUpdateStatusQueue = System.getProperty(
      "SMPP_MT_MESSAGE_UPDATE_STATUS_QUEUE"
    );

    // Delivery Receipt Updater
    final String deliveryReceiptUpdateStatusWorker = System.getProperty(
      "SMPP_DELIVERY_RECEIPT_UPDATE_STATUS_WORKER"
    );

    final String deliveryReceiptUpdateStatusQueue = System.getProperty(
      "SMPP_DELIVERY_RECEIPT_UPDATE_STATUS_QUEUE"
    );

    // Mo Message Receiver
    final String moMessageReceivedWorker = System.getProperty(
      "SMPP_MO_MESSAGE_RECEIVED_WORKER"
    );

    final String moMessageReceivedQueue = System.getProperty(
      "SMPP_MO_MESSAGE_RECEIVED_QUEUE"
    );

    // SMSCs to connect to
    final String smppServersString = System.getProperty("SMPP_SERVERS", "default");

    DummySmppClientMessageService smppClientMessageService = new DummySmppClientMessageService();
    final String[] smppServerNames = smppServersString.split(";");

    final Map<String,LoadBalancedList<OutboundClient>> smppServerBalancedLists = new HashMap<String,LoadBalancedList<OutboundClient>>(smppServerNames.length);

    net.greghaines.jesque.Config jesqueConfig = setupJesque();

    int totalNumOfThreads = 0;

    for (int smppServerCounter = 0; smppServerCounter < smppServerNames.length; smppServerCounter++) {
      String smppServerKey = smppServerNames[smppServerCounter].toUpperCase();
      int numOfThreads = Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_MT_THREAD_SIZE", "1"));

      final LoadBalancedList<OutboundClient> balancedList = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<OutboundClient>());

      for (int threadCounter = 0; threadCounter < numOfThreads; threadCounter++) {
        balancedList.set(
          createClient(
            smppClientMessageService,
            threadCounter,
            smppServerKey,
            jesqueConfig,
            deliveryReceiptUpdateStatusWorker,
            deliveryReceiptUpdateStatusQueue,
            moMessageReceivedWorker,
            moMessageReceivedQueue
          ),
        1);
        totalNumOfThreads++;
      }
      smppServerBalancedLists.put(smppServerKey, balancedList);
    }

    final ExecutorService executorService = Executors.newFixedThreadPool(totalNumOfThreads);

    final BlockingQueue mtMessageQueue = new LinkedBlockingQueue<String>();

    final net.greghaines.jesque.client.Client jesqueMtClient = new net.greghaines.jesque.client.ClientImpl(
      jesqueConfig,
      true
    );

    final net.greghaines.jesque.worker.Worker jesqueMtWorker = startJesqueWorker(
      jesqueConfig,
      mtMessageQueue
    );

    ShutdownClient shutdownClient = new ShutdownClient(
      executorService,
      smppServerBalancedLists,
      jesqueMtWorker,
      jesqueMtClient
    );

    Thread shutdownHook = new Thread(shutdownClient);
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    while(true) {
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
                  Charset destCharset;

                  if (GSMCharset.canRepresent(mtMessageText)) {
                    destCharset = CharsetUtil.CHARSET_GSM;
                    dataCoding = SmppConstants.DATA_CODING_GSM;
                  } else {
                    dataCoding = SmppConstants.DATA_CODING_UCS2;
                    if(ChibiUtil.getBooleanProperty(preferredSmppServerName + "_SMPP_MT_UCS2_LITTLE_ENDIANNESS", "0")) {
                      destCharset = CharsetUtil.CHARSET_UCS_2LE;
                    } else {
                      destCharset = CharsetUtil.CHARSET_UCS_2;
                    }
                  }

                  textBytes = CharsetUtil.encode(mtMessageText, destCharset);

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

                  String serviceType = System.getProperty(preferredSmppServerName + "_SMPP_SERVICE_TYPE");

                  if(serviceType != null) {
                    submit.setServiceType(serviceType);
                  }

                  submit.setDataCoding(dataCoding);
                  submit.setShortMessage(textBytes);
                  final SubmitSmResp submit1 = session.submit(submit, 10000);

                  final net.greghaines.jesque.Job job = new net.greghaines.jesque.Job(
                    mtMessageUpdateStatusWorker,
                    preferredSmppServerName,
                    mtMessageExternalId,
                    submit1.getMessageId(),
                    submit1.getCommandStatus() == SmppConstants.STATUS_OK
                  );

                  jesqueMtClient.enqueue(mtMessageUpdateStatusQueue, job);
                  sent = alreadySent.incrementAndGet();
                }
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

  private static final net.greghaines.jesque.Config setupJesque() {
    final net.greghaines.jesque.ConfigBuilder configBuilder = new net.greghaines.jesque.ConfigBuilder();
    String redisUrlKey = System.getProperty("REDIS_PROVIDER", "YOUR_REDIS_PROVIDER");
    try {
      URI redisUrl = new URI(System.getProperty(redisUrlKey, "127.0.0.1"));

      String redisHost = redisUrl.getHost();
      int redisPort = redisUrl.getPort();
      String redisUserInfo = redisUrl.getUserInfo();

      configBuilder.withNamespace("");

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

  private static final net.greghaines.jesque.worker.Worker startJesqueWorker(final net.greghaines.jesque.Config jesqueConfig, final BlockingQueue blockingQueue) {
    final String queueName = System.getProperty("SMPP_MT_MESSAGE_QUEUE", "default");
    final net.greghaines.jesque.worker.Worker worker = new net.greghaines.jesque.worker.WorkerImpl(
      jesqueConfig,
      Arrays.asList(queueName),
      new net.greghaines.jesque.worker.MapBasedJobFactory(
        net.greghaines.jesque.utils.JesqueUtils.map(
          net.greghaines.jesque.utils.JesqueUtils.entry(
            MtMessageJobRunner.class.getSimpleName(),
            MtMessageJobRunner.class
          )
        )
      )
    );

    worker.getWorkerEventEmitter().addListener(
      new net.greghaines.jesque.worker.WorkerListener() {
        public void onEvent(net.greghaines.jesque.worker.WorkerEvent event, net.greghaines.jesque.worker.Worker worker, String queue, net.greghaines.jesque.Job job, Object runner, Object result, Throwable t) {
          if (runner instanceof MtMessageJobRunner) {
            ((MtMessageJobRunner) runner).setQueue(blockingQueue);
          }
        }
      },
      net.greghaines.jesque.worker.WorkerEvent.JOB_EXECUTE
    );

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

  private static OutboundClient createClient(DummySmppClientMessageService smppClientMessageService, int i, final String smppServerKey, net.greghaines.jesque.Config jesqueConfig, String deliveryReceiptUpdateStatusWorker, String deliveryReceiptUpdateStatusQueue, String moMessageReceivedWorker, String moMessageReceivedQueue) {
    OutboundClient client = new OutboundClient();

    client.setSmppServerId(smppServerKey);

    client.setDeliveryReceiptUpdateStatusWorker(deliveryReceiptUpdateStatusWorker);
    client.setDeliveryReceiptUpdateStatusQueue(deliveryReceiptUpdateStatusQueue);

    client.setMoMessageReceivedWorker(moMessageReceivedWorker);
    client.setMoMessageReceivedQueue(moMessageReceivedQueue);

    client.setJesqueClient(
      new net.greghaines.jesque.client.ClientImpl(
        jesqueConfig,
        true
      )
    );
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
