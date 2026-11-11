package dev.somnia.skyblock;

import io.reactivex.Flowable;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.BatchRequest;
import org.web3j.protocol.core.BatchResponse;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.events.Notification;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * JSON-RPC transport over several Shannon endpoints. Every call goes to the active endpoint; on a network
 * error, a timeout, an HTTP error or a JSON-RPC "busy" error (-32603 internal / -32604 rate limit / -32029)
 * the same request is retried on the next endpoint. After a switch the primary is tried again 5 minutes later.
 * A signed transaction resent to another endpoint keeps its hash, so a retry never makes a second transaction.
 */
final class RpcFailover implements Web3jService {
    private static final long BACK_TO_PRIMARY_MS = 300_000L;

    private final List<String> urls;
    private final List<HttpService> services = new ArrayList<>();
    private final Logger log;
    private volatile int active;
    private volatile long switchedAt;

    RpcFailover(List<String> urls, Logger log) {
        this.urls = List.copyOf(urls);
        this.log = log;
        for (String url : this.urls) {
            services.add(new HttpService(url));
        }
    }

    String activeUrl() {
        return urls.get(active);
    }

    private int start() {
        if (active != 0 && System.currentTimeMillis() - switchedAt > BACK_TO_PRIMARY_MS) {
            active = 0;
        }
        return active;
    }

    private void moveTo(int index, String why) {
        if (index != active) {
            log.warning("RPC " + urls.get(active) + " failed (" + why + "), switching to " + urls.get(index));
            active = index;
            switchedAt = System.currentTimeMillis();
        }
    }

    private static boolean busy(Response<?> response) {
        if (response == null || !response.hasError()) {
            return false;
        }
        int code = response.getError().getCode();
        return code == -32603 || code == -32604 || code == -32029 || code == 429;
    }

    @Override
    public <T extends Response> T send(Request request, Class<T> responseType) throws IOException {
        int first = start();
        IOException last = null;
        T busyResponse = null;
        for (int step = 0; step < services.size(); step++) {
            int index = (first + step) % services.size();
            try {
                T response = services.get(index).send(request, responseType);
                if (busy(response) && step + 1 < services.size()) {
                    busyResponse = response;
                    log.warning("RPC " + urls.get(index) + " busy on " + request.getMethod() + ": " + response.getError().getMessage());
                    continue;
                }
                if (index != active) {
                    moveTo(index, last == null ? "busy" : last.getClass().getSimpleName());
                }
                return response;
            } catch (IOException | RuntimeException exception) {
                last = exception instanceof IOException io ? io : new IOException(exception);
            }
        }
        if (busyResponse != null) {
            return busyResponse;
        }
        throw last == null ? new IOException("No RPC endpoint answered") : last;
    }

    @Override
    public <T extends Response> CompletableFuture<T> sendAsync(Request request, Class<T> responseType) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, responseType);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public BatchResponse sendBatch(BatchRequest batch) throws IOException {
        int first = start();
        IOException last = null;
        for (int step = 0; step < services.size(); step++) {
            int index = (first + step) % services.size();
            try {
                BatchResponse response = services.get(index).sendBatch(batch);
                moveTo(index, "batch");
                return response;
            } catch (IOException exception) {
                last = exception;
            }
        }
        throw last == null ? new IOException("No RPC endpoint answered") : last;
    }

    @Override
    public CompletableFuture<BatchResponse> sendBatchAsync(BatchRequest batch) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sendBatch(batch);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    @Override
    public <T extends Notification<?>> Flowable<T> subscribe(Request request, String unsubscribeMethod, Class<T> responseType) {
        throw new UnsupportedOperationException("Subscriptions need a WebSocket endpoint");
    }

    @Override
    public void close() throws IOException {
        for (HttpService service : services) {
            service.close();
        }
    }
}
