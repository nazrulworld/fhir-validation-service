package nzi.fhir.validator.core.terminology;

import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.util.concurrent.TimeUnit;

/**
 * The synchronous HTTP client of every outbound terminology request (spec 007 FR-011, FR-019, FR-019a, research R11):
 * redirects are never followed, every address is checked by the {@link NetworkGuard} DNS resolver, no cookies, and the
 * connect and read timeouts of each request are {@code min(timeout, what is left of the request's terminology budget)}.
 * Synchronous because terminology calls happen inside HAPI validation on a worker thread (waiting on a Vert.x future
 * there would need {@code .get()}, which constitution Principle I forbids).
 */
final class GuardedHttp {
    private GuardedHttp() {
    }

    static HttpClient client(NetworkGuard guard, long timeoutMs, int maxPerRoute, int maxTotal) {
        int timeout = (int) Math.min(Integer.MAX_VALUE, timeoutMs);
        PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                        .register("http", PlainConnectionSocketFactory.getSocketFactory())
                        .register("https", SSLConnectionSocketFactory.getSocketFactory())
                        .build(),
                null, null, guard, 5, TimeUnit.MINUTES);
        connections.setMaxTotal(maxTotal);
        connections.setDefaultMaxPerRoute(maxPerRoute);
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableCookieManagement()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout)
                        .setConnectionRequestTimeout(timeout).build())
                // Runs on the calling (validation worker) thread, before the connection is used.
                .addInterceptorFirst((org.apache.http.HttpRequestInterceptor) (request, context) -> {
                    long left = Math.min(timeoutMs, TerminologyBudget.remainingMs());
                    int ms = (int) Math.max(1, Math.min(Integer.MAX_VALUE, left));
                    HttpClientContext c = HttpClientContext.adapt(context);
                    c.setRequestConfig(RequestConfig.copy(c.getRequestConfig()).setConnectTimeout(ms).setSocketTimeout(ms).build());
                })
                .setUserAgent("fhir-validation-service")
                .build();
    }
}
