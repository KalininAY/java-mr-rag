package com.example.multi.service;

import com.example.multi.model.UserProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * Fetches and caches {@link UserProfile} objects from a remote API.
 * Uses OkHttp for HTTP, Jackson for JSON deserialisation, and Guava
 * for an in-memory LRU cache.
 */
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, UserProfile> cache;

    public UserService(String baseUrl) {
        this.baseUrl      = baseUrl;
        this.httpClient   = new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.cache        = CacheBuilder.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /**
     * Returns a {@link UserProfile} for the given userId, using the cache
     * when possible and falling back to a remote HTTP call.
     *
     * @param userId the user identifier
     * @return an {@link Optional} containing the profile, or empty on error
     */
    public Optional<UserProfile> getProfile(String userId) {
        UserProfile cached = cache.getIfPresent(userId);
        if (cached != null) {
            log.debug("Cache hit for userId={}", userId);
            return Optional.of(cached);
        }

        String url = baseUrl + "/users/" + userId;
        Request request = new Request.Builder().url(url).build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Unexpected response {} for userId={}", response.code(), userId);
                return Optional.empty();
            }
            String body   = response.body().string();
            UserProfile profile = objectMapper.readValue(body, UserProfile.class);
            cache.put(userId, profile);
            log.info("Fetched profile for userId={}", userId);
            return Optional.of(profile);
        } catch (IOException e) {
            log.error("Failed to fetch profile for userId={}: {}", userId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Evicts a single entry from the in-memory cache. */
    public void evict(String userId) {
        cache.invalidate(userId);
        log.debug("Evicted cache entry for userId={}", userId);
    }

    /** Returns the current number of entries in the cache. */
    public long cacheSize() {
        return cache.size();
    }
}
