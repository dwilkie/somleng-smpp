package me.chibitxt.smsc;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.cloudhopper.commons.charset.GSMCharset;
import com.cloudhopper.commons.charset.Charset;

import com.cloudhopper.commons.gsm.GsmUtil;

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
import java.util.Random;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
  private static final Logger logger = LoggerFactory.getLogger(Main.class);

  public static void main(String[] args) throws IOException, RecoverablePduException, InterruptedException,
      SmppChannelException, UnrecoverablePduException, SmppTimeoutException, URISyntaxException {

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

    // Number of threads
    final int numMtThreads = Integer.parseInt(
      System.getProperty("SMPP_NUM_MT_THREADS", "10")
    );

    // SMSCs to connect to
    final String smppServersString = System.getProperty("SMPP_SERVERS", "default");

    DummySmppClientMessageService smppClientMessageService = new DummySmppClientMessageService();
    final String[] smppServerNames = smppServersString.split(";");

    final Map<String,LoadBalancedList<OutboundClient>> smppServerBalancedLists = new HashMap<String,LoadBalancedList<OutboundClient>>(smppServerNames.length);

    final net.greghaines.jesque.Config jesqueConfig = setupJesque();

    for (int smppServerCounter = 0; smppServerCounter < smppServerNames.length; smppServerCounter++) {
      String smppServerKey = smppServerNames[smppServerCounter].toUpperCase();
      int numOfConnections = Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_NUM_CONNECTIONS", "1"));

      final LoadBalancedList<OutboundClient> balancedList = LoadBalancedLists.synchronizedList(new RoundRobinLoadBalancedList<OutboundClient>());

      for (int connectionCounter = 0; connectionCounter < numOfConnections; connectionCounter++) {
        balancedList.set(
          createClient(
            smppClientMessageService,
            connectionCounter,
            smppServerKey,
            jesqueConfig,
            deliveryReceiptUpdateStatusWorker,
            deliveryReceiptUpdateStatusQueue,
            moMessageReceivedWorker,
            moMessageReceivedQueue
          ),
        1);
      }
      smppServerBalancedLists.put(smppServerKey, balancedList);
    }

    final ExecutorService executorService = Executors.newFixedThreadPool(numMtThreads);

    final BlockingQueue mtMessageQueue = new LinkedBlockingQueue<String>();

    final net.greghaines.jesque.worker.Worker jesqueMtWorker = startJesqueWorker(
      jesqueConfig,
      mtMessageQueue
    );

    ShutdownClient shutdownClient = new ShutdownClient(
      executorService,
      smppServerBalancedLists,
      jesqueMtWorker
    );

    Thread shutdownHook = new Thread(shutdownClient);
    Runtime.getRuntime().addShutdownHook(shutdownHook);

    final int numMtMessageUpdateStatusRetries = Integer.parseInt(
      System.getProperty("SMPP_MT_MESSAGE_UPDATE_STATUS_RETRIES", "5")
    );

    for (int j = 0; j < numMtThreads; j++) {
      executorService.execute(new Runnable() {
        @Override
        public void run() {
          while(true) {
            MtMessageJob mtMessageJob = null;
            try {
              // this blocks until there's a job in the queue
              mtMessageJob = (MtMessageJob)mtMessageQueue.take();
              final String preferredSmppServerName = mtMessageJob.getPreferredSmppServerName();

              final OutboundClient next = smppServerBalancedLists.get(preferredSmppServerName).getNext();
              final SmppSession session = next.getSession();

              if (session != null && session.isBound()) {
                final int mtMessageExternalId = mtMessageJob.getExternalMessageId();
                final String mtMessageText = mtMessageJob.getMessageBody();
                byte[] textBytes;

                // default data coding to UCS2
                byte dataCoding = SmppConstants.DATA_CODING_UCS2;

                Charset destCharset;

                if (GSMCharset.canRepresent(mtMessageText)) {
                  if(ChibiUtil.getBooleanProperty(preferredSmppServerName + "_SMPP_SUPPORTS_GSM", "1")) {
                    destCharset = CharsetUtil.CHARSET_GSM;
                    dataCoding = SmppConstants.DATA_CODING_GSM;
                  }
                  else {
                    java.nio.charset.CharsetEncoder asciiEncoder;
                    asciiEncoder = java.nio.charset.Charset.forName("US-ASCII").newEncoder();

                    if(asciiEncoder.canEncode(mtMessageText)) {
                      // encode as ASCII and use set data-coding to Default SMSC Alphabet
                      dataCoding = SmppConstants.DATA_CODING_DEFAULT;
                      destCharset = CharsetUtil.CHARSET_UTF_8; // same as ascii
                    } else {
                      destCharset = CharsetUtil.CHARSET_UCS_2;
                    }
                  }
                } else {
                  destCharset = CharsetUtil.CHARSET_UCS_2;
                }

                if(destCharset == CharsetUtil.CHARSET_UCS_2) {
                  if(
                    ChibiUtil.getBooleanProperty(
                      preferredSmppServerName + "_SMPP_MT_UCS2_LITTLE_ENDIANNESS", "0"
                    )
                  ) {
                    destCharset = CharsetUtil.CHARSET_UCS_2LE;
                  } else {
                    destCharset = CharsetUtil.CHARSET_UCS_2;
                  }
                }

                textBytes = CharsetUtil.encode(mtMessageText, destCharset);

                final String sourceAddress = mtMessageJob.getSourceAddress();
                final String destAddress = mtMessageJob.getDestAddress();

                SubmitSmResp submitSmResponse = new SubmitSmResp();

                // http://stackoverflow.com/questions/21098643/smpp-submit-long-message-and-message-split
                // 160 * 7bits == 140 Bytes (7 bit character encoding)
                // 140 * 8bits == 140 Bytes (8 bit character encoding)
                // 70 * 16bits == 140 Bytes (16 bit character encoding)

                // generate new reference number
                byte[] referenceNum = new byte[1];
                new Random().nextBytes(referenceNum);

                byte[][] byteMessagesArray = GsmUtil.createConcatenatedBinaryShortMessages(
                  textBytes,
                  referenceNum[0]
                );

                if(byteMessagesArray != null) {
                  for (int i = 0; i < byteMessagesArray.length; i++) {
                    SubmitSm submit = setupMtMessage(
                      preferredSmppServerName,
                      sourceAddress,
                      destAddress,
                      byteMessagesArray[i],
                      dataCoding
                    );
                    submit.setEsmClass(SmppConstants.ESM_CLASS_UDHI_MASK);
                    submitSmResponse = session.submit(submit, 10000);
                  }
                } else {
                  SubmitSm submit = setupMtMessage(
                    preferredSmppServerName,
                    sourceAddress,
                    destAddress,
                    textBytes,
                    dataCoding
                  );
                  submitSmResponse = session.submit(submit, 10000);
                }

                final net.greghaines.jesque.Job mtMessageUpdateStatusJob = new net.greghaines.jesque.Job(
                  mtMessageUpdateStatusWorker,
                  preferredSmppServerName,
                  mtMessageExternalId,
                  submitSmResponse.getMessageId(),
                  submitSmResponse.getCommandStatus() == SmppConstants.STATUS_OK
                );

                mtMessageUpdateStatusJob.setUnknownField("retry", numMtMessageUpdateStatusRetries);
                mtMessageUpdateStatusJob.setUnknownField("dead", false);
                mtMessageUpdateStatusJob.setUnknownField("queue", mtMessageUpdateStatusQueue);

                final net.greghaines.jesque.client.Client jesqueMtClient = new net.greghaines.jesque.client.ClientImpl(
                  jesqueConfig,
                  true
                );

                jesqueMtClient.enqueue(mtMessageUpdateStatusQueue, mtMessageUpdateStatusJob);
                jesqueMtClient.end();

                logger.info("Successfully sent MT and recorded response");
              }
              else {
                logger.info("No session or session unbound. Waiting 5 seconds then re-enqueuing the job");
                Thread.sleep(5000); // Wait 5 seconds
                mtMessageQueue.put(mtMessageJob);
              }
            } catch (Exception e) {
              if(mtMessageJob != null) {
                try {
                  java.io.StringWriter sw = new java.io.StringWriter();
                  e.printStackTrace(new java.io.PrintWriter(sw));
                  logger.warn("Exception raised while trying to send MT. Waiting 5 seconds then re-enqueuing the job. " + e + " " + sw.toString());
                  Thread.sleep(5000); // Wait 5 seconds
                  mtMessageQueue.put(mtMessageJob);
                } catch (InterruptedException ex) {
                  logger.error("Failed to re-add job to blocking queue", ex);
                }
              }
              return;
            }
          }
        }
      });
    }
  }

  private static SubmitSm setupMtMessage(String preferredSmppServerName, String sourceAddress, String destAddress, byte [] textBytes, byte dataCoding) throws SmppInvalidArgumentException {
    SubmitSm submit = new SubmitSm();
    submit.setDataCoding(dataCoding);
    submit.setShortMessage(textBytes);

    int sourceTon = Integer.parseInt(
      System.getProperty(preferredSmppServerName + "_SMPP_SOURCE_TON", "3")
    );

    int sourceNpi = Integer.parseInt(
      System.getProperty(preferredSmppServerName + "_SMPP_SOURCE_NPI", "0")
    );

    submit.setSourceAddress(new Address((byte) sourceTon, (byte) sourceNpi, sourceAddress));

    int destTon = Integer.parseInt(
      System.getProperty(preferredSmppServerName + "_SMPP_DESTINATION_TON", "1")
    );

    int destNpi = Integer.parseInt(
      System.getProperty(preferredSmppServerName + "_SMPP_DESTINATION_NPI", "1")
    );

    submit.setDestAddress(new Address((byte) destTon, (byte) destNpi, destAddress));

    submit.setRegisteredDelivery(SmppConstants.REGISTERED_DELIVERY_SMSC_RECEIPT_REQUESTED);
    String serviceType = System.getProperty(preferredSmppServerName + "_SMPP_SERVICE_TYPE");

    if(serviceType != null) {
      submit.setServiceType(serviceType);
    }

    return submit;
  }

  private static URI getRedisUri() throws URISyntaxException {
    return new URI(getRedisUrl());
  }

  private static String getRedisUrl() {
    return System.getProperty(
      System.getProperty("REDIS_PROVIDER", "YOUR_REDIS_PROVIDER"),
      "127.0.0.1"
    );
  }

  private static String getRedisHost(URI redisUri) {
    return redisUri.getHost();
  }

  private static String getRedisUserInfo(URI redisUri) {
    return redisUri.getUserInfo();
  }

  private static int getRedisPort(URI redisUri) {
    return redisUri.getPort();
  }

  private static String getRedisPassword(String redisUserInfo) {
    return redisUserInfo.split(":", 2)[1];
  }

  private static final net.greghaines.jesque.Config setupJesque() throws URISyntaxException {
    final net.greghaines.jesque.ConfigBuilder configBuilder = new net.greghaines.jesque.ConfigBuilder();
    URI redisUri = getRedisUri();

    String redisHost = getRedisHost(redisUri);
    int redisPort = getRedisPort(redisUri);
    String redisUserInfo = getRedisUserInfo(redisUri);

    configBuilder.withNamespace("");

    if (redisHost != null) {
      configBuilder.withHost(redisHost);
    }

    if (redisPort > -1) {
      configBuilder.withPort(redisPort);
    }

    if (redisUserInfo != null) {
      configBuilder.withPassword(getRedisPassword(redisUserInfo));
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

  private static void loadSystemProperties(String configurationFile) throws IOException {
    // set up new properties object
    // from the config file
    FileInputStream propFile = new FileInputStream(configurationFile);
    Properties p = new Properties(System.getProperties());
    p.load(propFile);

    // set the system properties
    System.setProperties(p);
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

    String bindType = System.getProperty(smppServerKey + "_SMPP_BIND_TYPE", "TRANSCEIVER");

    if(bindType == "TRANSCEIVER") {
      config.setType(SmppBindType.TRANSCEIVER);
    } else {
      config.setType(SmppBindType.TRANSMITTER);
    }

    String systemType = System.getProperty(smppServerKey + "_SMPP_SYSTEM_TYPE");

    if(systemType != null) {
      config.setSystemType(systemType);
    }

    config.setBindTimeout(Integer.parseInt(System.getProperty(smppServerKey + "_SMPP_BIND_TIMEOUT", "5000")));
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
