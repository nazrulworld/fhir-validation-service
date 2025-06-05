package nzi.fhir.validator.web.service;

import ca.uhn.fhir.sl.cache.Cache;
import ca.uhn.fhir.sl.cache.caffeine.CacheProvider;

public class CachingService {

    private static final long DEFAULT_CACHE_KEY_EXPIRATION_TIME_MS = 1000*60*60*24; // 24 hours

    private final Cache cacheDelegator;

    private CachingService(long expirationTimeMs) {
        this.cacheDelegator = new CacheProvider<String, Object>().create(expirationTimeMs);
    }

    public static CachingService create(){
        return create(DEFAULT_CACHE_KEY_EXPIRATION_TIME_MS);
    }
    public static CachingService create(long expirationTimeMs){
        return new CachingService(expirationTimeMs);
    }
    public void put(String key, Object value){
        cacheDelegator.put(key, value);
    }
    public Object get(String key){
        return cacheDelegator.getIfPresent(key);
    }
    public Cache getCacheDelegator(){
        return cacheDelegator;
    }
    public void remove(String key){
        cacheDelegator.invalidate(key);
    }
}
