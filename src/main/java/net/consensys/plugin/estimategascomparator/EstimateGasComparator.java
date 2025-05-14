package net.consensys.plugin.estimategascomparator;

import com.google.auto.service.AutoService;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.datatypes.Transaction;
import org.hyperledger.besu.plugin.BesuPlugin;
import org.hyperledger.besu.plugin.ServiceManager;
import org.hyperledger.besu.plugin.data.AddedBlockContext;
import org.hyperledger.besu.plugin.services.BesuEvents;
import org.hyperledger.besu.plugin.services.PicoCLIOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@AutoService(BesuPlugin.class)
public class EstimateGasComparator implements BesuPlugin, BesuEvents.TransactionAddedListener, BesuEvents.BlockAddedListener {
  private static final Marker ESTIMATE_GAS_CALL = MarkerFactory.getMarker("ESTIMATE_GAS_CALL");
  private static final Marker CONFIRMED_GAS_USED = MarkerFactory.getMarker("CONFIRMED_GAS_USED");
  private final static Logger log = LoggerFactory.getLogger(EstimateGasComparator.class);
  private final static AtomicLong id = new AtomicLong();
  private final static HttpClient httpClient = HttpClient.newBuilder().build();
  @CommandLine.Option(
      names = {"--plugin-ceg-endpoints"},
      paramLabel = "<MAP<NAME,URI>>",
      split = ",",
      description =
          "A comma separated list of endpoints")
  private final Map<String, URI> endpointUrlsByName = Map.of();
  private ServiceManager serviceManager;
  private final SequencedMap<Hash, Transaction> pendingTransactionsByHash = new LinkedHashMap<>();
  private final ConcurrentMap<Hash, List<GasEstimation>> gasEstimationsByTx = new ConcurrentHashMap<>();
  private volatile boolean stopped = false;

  @Override
  public void register(final ServiceManager serviceManager) {
    this.serviceManager = serviceManager;
    var cliOptions = serviceManager.getService(PicoCLIOptions.class).orElseThrow();

    cliOptions.addPicoCLIOptions("ceg", this);
  }

  @Override
  public void start() {

    var besuEvents = serviceManager.getService(BesuEvents.class).orElseThrow();

    besuEvents.addTransactionAddedListener(this);
    besuEvents.addBlockAddedListener(this);

    Executors.newSingleThreadExecutor().submit(() -> {
      while (!stopped) {
        try {
          var tx = getPendingTransaction();

          if (tx.isEmpty()) {
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              break;
            }
          } else {
            compareEstimateGas(tx.get());
          }
        } catch (Exception e) {
          log.error("Error comparing estimate gas", e);
        }
      }
    });
  }

  private void compareEstimateGas(final Transaction tx) {
    var reqBody = createRequest(tx);

    log.trace("Request {} from tx {}", reqBody, tx);

    var responses = endpointUrlsByName.values().parallelStream().map(uri ->
        HttpRequest.newBuilder()
            .uri(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
            .build()
    ).map(httpRequest -> {
      try {
        log.atDebug().setMessage("Calling {} for tx {}").addArgument(() -> getClient(httpRequest.uri())).addArgument(tx::getHash).log();
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(getClient(httpRequest.uri()), e);
      }
    }).map(response -> parseResponse(reqBody, response, tx))
        .sorted(Comparator.comparing(GasEstimation::client)).toList();

    var first = responses.get(0);
    log.info("\n>\t{}\t{}\t{}", tx.getHash(), first.client, first.gasEstimation);

    responses.stream().skip(1)
        .filter(r -> r.gasEstimation != first.gasEstimation)
        .forEach(r ->
            log.warn("\n!\t{}\t{}\t{} != {}", tx.getHash(), r.client, r.gasEstimation, first.gasEstimation)
        );

    gasEstimationsByTx.put(tx.getHash(), responses);
  }

  private String getClient(final URI uri) {
    return endpointUrlsByName.entrySet().stream().filter(e -> e.getValue().equals(uri)).findFirst().map(Map.Entry::getKey).orElseThrow();
  }

  private GasEstimation parseResponse(final String reqBody, final HttpResponse<String> response, final Transaction transaction) {
    var respBody = response.body().trim();
    long gasEstimation;
    if (respBody.contains("result")) {
      var idx1 = respBody.indexOf("\"result\"");
      var idx2 = respBody.indexOf('"', idx1 + "\"result\"".length() + 1);
      var idx3 = respBody.indexOf('"', idx2 + 1);
      var gasEstimationHex = respBody.substring(idx2 + 1, idx3).substring(2);
      gasEstimation = Long.parseLong(gasEstimationHex, 16);
    } else {
      gasEstimation = -1;
    }
    var client = getClient(response.request().uri());
    log.atInfo()
        .addMarker(ESTIMATE_GAS_CALL)
        .setMessage("tx {} client {} gas estimation {} request {} response {}")
        .addArgument(transaction::getHash)
        .addArgument(() -> getClient(response.request().uri()))
        .addArgument(gasEstimation)
        .addArgument(reqBody)
        .addArgument(respBody)
        .addKeyValue("tx", transaction.getHash())
        .addKeyValue("request", reqBody.trim())
        .addKeyValue("response", respBody.trim())
        .addKeyValue("client", client)
        .addKeyValue("gasEstimation", gasEstimation)
        .log();

    return new GasEstimation(getClient(response.request().uri()), gasEstimation, gasEstimation == -1 ? respBody : null);
  }

  private String createRequest(final Transaction tx) {
    var sb = new StringBuilder("""
        "from":"%s","value":"%s","data":"%s" """.formatted(
        tx.getSender().toHexString(),
        tx.getValue().toShortHexString(),
        tx.getPayload().toShortHexString()
    ));
    tx.getTo().ifPresent(to -> sb.append(",\"to\":\"" + to.toHexString() + "\""));
    tx.getGasPrice().ifPresent(gasPrice -> sb.append(",\"gasPrice\":\"" + gasPrice.toShortHexString() + "\""));
    tx.getMaxFeePerGas().ifPresent(quantity -> sb.append(",\"maxFeePerGas\":\"" + quantity.toShortHexString() + "\""));
    tx.getMaxPriorityFeePerGas().ifPresent(quantity -> sb.append(",\"maxPriorityFeePerGas\":\"" + quantity.toShortHexString() + "\""));
    tx.getMaxFeePerBlobGas().ifPresent(quantity -> sb.append(",\"maxFeePerBlobGas\":\"" + quantity.toShortHexString() + "\""));

    return """
        {"jsonrpc":"2.0","method":"eth_estimateGas","id":%d,"params": [{%s}]}""".formatted(id.incrementAndGet(), sb.toString());
  }

  private synchronized Optional<Transaction> getPendingTransaction() {
    log.debug("Selecting last pending transaction of {}", pendingTransactionsByHash.size());
    return pendingTransactionsByHash.isEmpty() ? Optional.empty() : Optional.of(pendingTransactionsByHash.pollLastEntry().getValue());
  }

  @Override
  public void stop() {
    stopped = true;
  }

  @Override
  public synchronized void onTransactionAdded(final Transaction transaction) {
    pendingTransactionsByHash.put(transaction.getHash(), transaction);
  }

  @Override
  public synchronized void onBlockAdded(final AddedBlockContext addedBlockContext) {
    var confirmedTxs = addedBlockContext.getBlockBody().getTransactions();
    var receipts = addedBlockContext.getTransactionReceipts();

    long cumlativeGasUsed = 0;

    for (int i = 0; i < confirmedTxs.size(); i++) {
      var ctx = confirmedTxs.get(i);
      pendingTransactionsByHash.remove(ctx.getHash());
      var estimations = gasEstimationsByTx.remove(ctx.getHash());
      var receipt = receipts.get(i);
      if(estimations != null) {
        var gasUsed = receipt.getCumulativeGasUsed() - cumlativeGasUsed;


        log.info("Confirmed tx {} with gas used {}, estimations: {}", ctx.getHash(), gasUsed, estimations);

        estimations.stream()
            .peek(e -> log.atInfo()
                .addMarker(CONFIRMED_GAS_USED)
                .addKeyValue("tx", ctx.getHash())
                .addKeyValue("client", e.client)
                .addKeyValue("gasEstimation", e.gasEstimation)
                .addKeyValue("gasUsed", gasUsed)
                .addKeyValue("diff", e.gasEstimation - gasUsed)
                .addKeyValue("errorResponse", e.errorResponse == null ? "" : e.errorResponse)
                .log()
            )
            .filter(e -> e.gasEstimation < gasUsed)
            .forEach(e -> log.warn("{} under estimated {} < gas used {} for tx {}. Other client estimates: {}",
                e.client,
                e.gasEstimation,
                gasUsed,
                ctx.getHash(),
                estimations.stream().filter(e2 -> !e2.client.equals(e.client)).toList()));
      }
      cumlativeGasUsed = receipt.getCumulativeGasUsed();
    }
  }

  record GasEstimation(String client, long gasEstimation, String errorResponse) {
    @Override
    public String toString() {
      return client + "=" + gasEstimation + (errorResponse != null ? " (" + errorResponse : ")");
    }
  }
}
