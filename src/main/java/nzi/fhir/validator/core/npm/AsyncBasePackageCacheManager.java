package nzi.fhir.validator.core.npm;

import org.apache.commons.lang3.Validate;
import org.hl7.fhir.utilities.Utilities;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Future;
import io.vertx.core.CompositeFuture;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClient;

import javax.annotation.Nonnull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public abstract class AsyncBasePackageCacheManager implements IAsyncPackageCacheManager {
    protected final List<PackageServer> packageServers;
    private static final Logger logger = LoggerFactory.getLogger(AsyncBasePackageCacheManager.class);
    protected WebClient webClient;

    public AsyncBasePackageCacheManager(List<PackageServer> packageServers, WebClient webClient) {
        this.packageServers = packageServers;
        this.webClient = webClient;
    }

    public List<PackageServer> getPackageServers() {
        return packageServers;
    }

    /**
     * Add a package server that can be used to fetch remote packages
     */
    public void addPackageServer(@Nonnull PackageServer thePackageServer) {
        Validate.notNull(thePackageServer, "thePackageServer must not be null or empty");
        if (!packageServers.contains(thePackageServer)) {
            packageServers.add(thePackageServer);
        }
    }

    @Override
    public Future<String> getPackageUrl(String packageIdAndVersion) {
        Validate.notEmpty(packageIdAndVersion, "packageIdAndVersion must not be null or empty");
        
        // Try each server to find a valid package URL
        List<Future<String>> futures = new ArrayList<>();

        // Calculate hash position once
        int hashIndex = packageIdAndVersion.indexOf('#');
        String id = hashIndex >= 0 ? packageIdAndVersion.substring(0, hashIndex) : packageIdAndVersion;
        String version = hashIndex >= 0 ? packageIdAndVersion.substring(hashIndex + 1) : null;

        for (PackageServer server : getPackageServers()) {
            String url = version != null ?
                Utilities.pathURL(server.getUrl(), id, version) :
                Utilities.pathURL(server.getUrl(), id);

            futures.add(verifyPackageUrl(url, server.getUrl()));
        }

        return CompositeFuture.any(new ArrayList<Future>(futures))
            .map(cf -> {
                for (int i = 0; i < futures.size(); i++) {
                    String url = cf.resultAt(i);
                    if (url != null) {
                        return url;
                    }
                }
                logger.debug("No valid package URL found for {}", packageIdAndVersion);
                return null;
            });
    }

    @Override
    public Future<String> getPackageId(String canonicalUrl) {
        Validate.notEmpty(canonicalUrl, "canonicalUrl must not be null or empty");
        
        List<Future<String>> futures = new ArrayList<>();
        for (PackageServer server : getPackageServers()) {
            String url = Utilities.pathURL(server.getUrl(), "catalog") + 
                    "?pkgcanonical=" + 
                    URLEncoder.encode(canonicalUrl, StandardCharsets.UTF_8);
            futures.add(searchPackageId(url, server.getUrl()));
        }
        
        return CompositeFuture.any((List) futures)
            .map(cf -> {
                for (int i = 0; i < futures.size(); i++) {
                    String id = cf.resultAt(i);
                    if (id != null) {
                        return id;
                    }
                }
                logger.debug("No package ID found for canonical URL: {}", canonicalUrl);
                return null;
            });
}

    // protected abstract Future<NpmPackage> loadPackageFromCacheOnlyAsync(String id, String version);

    // protected abstract Future<NpmPackage> addPackageToCache(String id, String version, ByteArrayInputStream stream, String source);

    // protected abstract Future<NpmPackage> loadPackageImpl(String id, String version);

    private Future<String> verifyPackageUrl(String url, String serverUrl) {
        return webClient.getAbs(url)
            .putHeader("Accept", "application/json")
            .send()
            .map(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return url;
                }
                logger.warn("Failed to verify package URL {} on {}: status code {}", 
                    url, serverUrl, response.statusCode());
                throw new RuntimeException(
                    String.format("Invalid status code: %d", response.statusCode()));
            })
        .recover(e -> {
            String errorContext = String.format("Failed to verify package URL %s on %s", url, serverUrl);
            logger.error(errorContext + ": {}", e.getMessage(), e);
            return Future.failedFuture(new RuntimeException(errorContext, e));
        });
}

    private Future<String> searchPackageId(String url, String serverUrl) {
        return webClient.getAbs(url)
            .putHeader("Accept", "application/json")
            .send()
            .compose(response -> {
                if (response.statusCode() == 200) {
                    JsonArray json = response.bodyAsJsonArray();
                    for (Object obj : json) {
                        if (obj instanceof JsonObject) {
                            String id = ((JsonObject) obj).getString("name", 
                                ((JsonObject) obj).getString("Name"));
                            if (id != null) {
                                return Future.succeededFuture(id);
                            }
                        }
                    }
                }
                return Future.succeededFuture(null);
            })
            .otherwise(e -> {
                logger.info("Failed to search canonical {} on {}: {}", url, serverUrl, e.getMessage());
                return null;
            });
    }

    protected Future<NpmPackage> fetchPackage(String id, String version) {
        List<Future<NpmPackage>> futures = new ArrayList<>();
        for (PackageServer server : getPackageServers()) {
            futures.add(fetchFromServer(server, id, version));
        }
        
        return CompositeFuture.any(new ArrayList<>(futures))
            .map(cf -> {
                for (int i = 0; i < futures.size(); i++) {
                    NpmPackage pkg = cf.resultAt(i);
                    if (pkg != null) {
                        return pkg;
                    }
                }
                return null;
            });
    }

    private Future<NpmPackage> fetchFromServer(PackageServer server, String id, String version) {
        String packageUrl = Utilities.pathURL(server.getUrl(), id, version);
        return webClient.getAbs(packageUrl)
            .send()
            .compose(response -> {
                if (response.statusCode() != 200) {
                    logger.info("Failed to fetch {}#{} from {}: HTTP {}", 
                        id, version, server.getUrl(), response.statusCode());
                    return Future.succeededFuture(null);
                }
                ByteArrayInputStream inputStream = new ByteArrayInputStream(response.body().getBytes());
                try {
                    return addPackageToCache(inputStream);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }
    protected Future<String> getLatestVersion(String id) {
        List<Future<String>> futures = new ArrayList<>();
        for (PackageServer server : getPackageServers()) {
            String versionsUrl = Utilities.pathURL(server.getUrl(), id);
            futures.add(webClient.getAbs(versionsUrl)
                    .putHeader("Accept", "application/json")
                    .send()
                    .compose(response -> {
                        if (response.statusCode() != 200) {
                            logger.info("Failed to fetch versions for {} from {}: HTTP {}", id, server.getUrl(), response.statusCode());
                            return Future.succeededFuture(null);
                        }
                        try {
                            JsonObject json = response.bodyAsJsonObject();
                            JsonObject versions = json.getJsonObject("versions");
                            if (versions == null) {
                                return Future.succeededFuture(null);
                            }
                            String latest = null;
                            for (String v : versions.getMap().keySet()) {
                                if (latest == null || isLaterVersion(v, latest)) {
                                    latest = v;
                                }
                            }
                            return Future.succeededFuture(latest);
                        } catch (Exception e) {
                            logger.error("Failed to parse versions for {}: {}", id, e.getMessage());
                            return Future.succeededFuture(null);
                        }
                    }));
        }
        return CompositeFuture.all(new ArrayList<>(futures))
                .map(composite -> {
                    for (int i = 0; i < futures.size(); i++) {
                        String version = composite.resultAt(i);
                        if (version != null) {
                            return version;
                        }
                    }
                    return null;
                });
    }

    private boolean isLaterVersion(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
            int p1 = Integer.parseInt(parts1[i].replaceAll("[^0-9]", ""));
            int p2 = Integer.parseInt(parts2[i].replaceAll("[^0-9]", ""));
            if (p1 != p2) {
                return p1 > p2;
            }
        }
        return parts1.length > parts2.length;
    }
}