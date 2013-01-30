package org.elasticsearch.plugin.degraphmalizer;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.DelayQueue;

/**
 * This class handles Change instances. The class can be configured via elasticsearch.yml (see README.md for
 * more information). The Updater manages a queue of Change objects, executes HTTP requests for these
 * changes and retries changes when HTTP requests fail.
 */
public class Updater implements Runnable {
    private static final ESLogger LOG = Loggers.getLogger(Updater.class);

    private final BlockingQueue<DelayedImpl<Change>> queue = new DelayQueue<DelayedImpl<Change>>();
    private final HttpClient httpClient = new DefaultHttpClient();

    private final String uriScheme;
    private final String uriHost;
    private final int uriPort;
    private final long retryDelayOnFailureInMillis;

    private String index;

    private boolean shutdownInProgress = false;

    public Updater(String index, String uriScheme, String uriHost, int uriPort, long retryDelayOnFailureInMillis) {
        this.index = index;
        this.uriScheme = uriScheme;
        this.uriHost = uriHost;
        this.uriPort = uriPort;
        this.retryDelayOnFailureInMillis = retryDelayOnFailureInMillis;

        LOG.info("Updater instantiated for index {}. Updates will be sent to {}://{}:{}. Retry delay on failure is {} milliseconds.", index, uriScheme, uriHost, uriPort, retryDelayOnFailureInMillis);
    }

    public void start() {
        new Thread(this).start();
    }

    public void shutdown() {
        shutdownInProgress = true;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public void flushQueue() {
        queue.clear();
    }

    public void run() {
        try {
            boolean done = false;
            while (!done) {
                final Change change = queue.take().thing();
                perform(change);

                if (shutdownInProgress && queue.isEmpty()) {
                    done = true;
                }
            }

            httpClient.getConnectionManager().shutdown();
            LOG.info("Updater stopped for index {}.", index);
        } catch (InterruptedException e) {
            LOG.warn("Interrupted while waiting!"); // TODO: ??? (DGM-23)
        } catch (Exception e) {
            LOG.error("Updater for index {} stopped with exception: {}", index, e);
        }
    }

    public void add(final Change change) {
        queue.add(DelayedImpl.immediate(change));
        LOG.trace("Received {}", change);
    }


    private void perform(final Change change) {
        LOG.debug("Attempting to perform {}", change);

        final HttpRequestBase request = toRequest(change);

        try {
            final HttpResponse response = httpClient.execute(request);

            if (!isSuccessful(response)) {
                LOG.warn("Request {} {} was not successful. Response status code: {}.", request.getMethod(), request.getURI(), response.getStatusLine().getStatusCode());
                retry(change); // TODO: retry until infinity? (DGM-23)
            } else {
                LOG.debug("Change performed: {}", change);
            }

            try {
                EntityUtils.consume(response.getEntity());
            } finally {
                request.releaseConnection();
            }
        } catch (IOException e) {
            LOG.warn("Error executing request {} {}: {}", request.getMethod(), request.getURI(), e.getMessage());
            retry(change); // TODO: retry until infinity? (DGM-23)
        }
    }

    private HttpRequestBase toRequest(final Change change) {
        final HttpRequestBase request;

        final Action action = change.action();
        switch (action) {
            case UPDATE:
                request = new HttpGet(buildURI(change));
                break;
            case DELETE:
                request = new HttpDelete(buildURI(change));
                break;
            default:
                throw new RuntimeException("Unknown action " + action + " for " + change + " on index " + index);
        }

        return request;
    }

    private URI buildURI(final Change change) {
        final String type = change.type();
        final String id = change.id();
        final long version = change.version();

        final String path = String.format("/%s/%s/%s/%d", index, type, id, version);

        try {
            return new URIBuilder()
                    .setScheme(uriScheme)
                    .setHost(uriHost)
                    .setPort(uriPort)
                    .setPath(path)
                    .build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Unexpected error building uri for change " + change + " on index " + index, e);
        }
    }

    private boolean isSuccessful(final HttpResponse response) {
        final int statusCode = response.getStatusLine().getStatusCode();
        return statusCode == 200;
    }

    private void retry(final Change change) {
        final DelayedImpl<Change> delayedChange = new DelayedImpl<Change>(change, retryDelayOnFailureInMillis);
        queue.add(delayedChange);
        LOG.debug("Retrying change {} on index {} in {} milliseconds", change, index, retryDelayOnFailureInMillis);
    }
}
