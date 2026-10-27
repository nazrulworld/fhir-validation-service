package nzi.fhir.validator.core.npm;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import nzi.fhir.validator.core.config.ApplicationConfig;
import nzi.fhir.validator.web.ProblemException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Downloads package archives without server-side request forgery (FR-013, research R9): https only, host must be on
 * the configured allowlist, every resolved address must be public, the connection goes to the checked address,
 * redirects are followed manually (at most 3, each re-checked), and the body is capped while streaming.
 * <p>
 * {@code allowInsecure} (dev profile only) permits http and private/loopback addresses; the allowlist still applies.
 */
public class SafePackageFetcher {
    private static final int MAX_REDIRECTS = 3;

    /** Host name resolution, injectable for tests. */
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    private final WorkerExecutor resolverPool;
    private final HttpClient client;
    private final List<String> allowedHosts;
    private final long timeoutMs;
    private final long maxBytes;
    private final boolean allowInsecure;
    private final Resolver resolver;

    public SafePackageFetcher(Vertx vertx, WorkerExecutor resolverPool, List<String> allowedHosts, long timeoutMs,
                              long maxBytes, boolean allowInsecure, Resolver resolver) {
        this.resolverPool = resolverPool;
        this.client = vertx.createHttpClient(new HttpClientOptions().setForceSni(true).setVerifyHost(true));
        this.allowedHosts = allowedHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).toList();
        this.timeoutMs = timeoutMs;
        this.maxBytes = maxBytes;
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    public static SafePackageFetcher create(Vertx vertx, WorkerExecutor resolverPool, ApplicationConfig config) {
        return new SafePackageFetcher(vertx, resolverPool, config.packageSourceHosts(), config.packageFetchTimeoutMs(),
                config.packageMaxCompressedMb() * 1024L * 1024L, config.packageAllowInsecureSources(),
                host -> Arrays.asList(InetAddress.getAllByName(host)));
    }

    public Future<Buffer> fetch(String url) {
        return fetch(url, Promise.<Void>promise().future());
    }

    /**
     * @param cancelled when it completes, an in-flight request is reset (e.g. another registry already answered)
     */
    public Future<Buffer> fetch(String url, Future<Void> cancelled) {
        return fetch(url, MAX_REDIRECTS, cancelled);
    }

    private Future<Buffer> fetch(String url, int redirectsLeft, Future<Void> cancelled) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Future.failedFuture(notAllowed("The URL is not valid"));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = scheme.equals("https");
        if (!https && !(allowInsecure && scheme.equals("http"))) {
            return Future.failedFuture(notAllowed("Only https package sources are allowed"));
        }
        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || !allowedHosts.contains(host)) {
            return Future.failedFuture(notAllowed("Host is not an approved package source (FV_PACKAGE_SOURCES)"));
        }
        int port = uri.getPort() > 0 ? uri.getPort() : (https ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());

        return resolverPool.<InetAddress>executeBlocking(() -> {
            List<InetAddress> addresses;
            try {
                addresses = resolver.resolve(host);
            } catch (UnknownHostException e) {
                throw notAllowed("Host cannot be resolved");
            }
            if (addresses.isEmpty()) {
                throw notAllowed("Host cannot be resolved");
            }
            if (!allowInsecure) {
                for (InetAddress address : addresses) {
                    if (isNonPublic(address)) {
                        throw notAllowed("Host resolves to a non-public address");
                    }
                }
            }
            return addresses.get(0);
        }, false)
                // The fetch timeout covers name resolution too, including any wait for a free package worker.
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .compose(address -> request(uri, host, port, path, https, address, redirectsLeft, cancelled));
    }

    private Future<Buffer> request(URI uri, String host, int port, String path, boolean https, InetAddress address,
                                   int redirectsLeft, Future<Void> cancelled) {
        if (cancelled.isComplete()) {
            return Future.failedFuture(new ProblemException(404, "package-not-found", "Fetch cancelled"));
        }
        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.GET)
                .setServer(SocketAddress.inetSocketAddress(port, address.getHostAddress()))
                .setHost(host)
                .setPort(port)
                .setURI(path)
                .setSsl(https)
                .setFollowRedirects(false)
                .setTimeout(timeoutMs);
        return client.request(options).compose(req -> {
            cancelled.onComplete(v -> req.reset());
            return req.send();
        }).compose(resp -> {
            io.vertx.core.http.HttpClientRequest req = resp.request();
            int status = resp.statusCode();
            if (status >= 300 && status < 400 && resp.getHeader("Location") != null) {
                req.reset();
                if (redirectsLeft <= 0) {
                    return Future.failedFuture(notAllowed("Too many redirects"));
                }
                return fetch(uri.resolve(resp.getHeader("Location")).toString(), redirectsLeft - 1, cancelled);
            }
            if (status != 200) {
                req.reset();
                return Future.failedFuture(status == 404
                        ? new ProblemException(404, "package-not-found", "The package source answered 404")
                        : new ProblemException(400, "invalid-request", "The package source answered HTTP " + status));
            }
            String length = resp.getHeader("Content-Length");
            if (length != null) {
                long declared;
                try {
                    declared = Long.parseLong(length.trim());
                } catch (NumberFormatException e) {
                    req.reset();
                    return Future.failedFuture(invalidResponse());
                }
                if (declared > maxBytes) {
                    req.reset();
                    return Future.failedFuture(tooLarge());
                }
            }
            Promise<Buffer> body = Promise.promise();
            Buffer data = Buffer.buffer();
            resp.handler(chunk -> {
                if (data.length() + chunk.length() > maxBytes) {
                    body.tryFail(tooLarge());
                    req.reset();
                } else {
                    data.appendBuffer(chunk);
                }
            });
            resp.exceptionHandler(body::tryFail);
            resp.endHandler(v -> body.tryComplete(data));
            return body.future();
        }).recover(err -> {
            // A response the HTTP decoder rejects (e.g. a malformed Content-Length) is the source's fault, not ours.
            // Connection failures and timeouts stay server-side errors (dependency failure).
            if (err instanceof IllegalArgumentException || err instanceof io.netty.handler.codec.DecoderException) {
                return Future.failedFuture(invalidResponse());
            }
            return Future.failedFuture(err);
        });
    }

    private static ProblemException invalidResponse() {
        return new ProblemException(400, "invalid-request", "The package source sent an invalid HTTP response");
    }

    static boolean isNonPublic(InetAddress a) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            return (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
        }
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && second >= 64 && second <= 127); // 0.0.0.0/8, 100.64.0.0/10 (CGNAT)
        }
        return false;
    }

    private static ProblemException notAllowed(String detail) {
        return new ProblemException(400, "package-source-not-allowed", detail);
    }

    private ProblemException tooLarge() {
        return new ProblemException(413, "package-too-large", "The download exceeds " + maxBytes / (1024 * 1024) + " MB");
    }
}
