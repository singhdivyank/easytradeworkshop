package com.dynatrace.easytrade.bitcoinpaymentservice;

import com.dynatrace.easytrade.bitcoinpaymentservice.models.FeatureFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP client for feature-flag-service. Hardened against the flag service's observed
 * high tail latency (~979ms p95/p99 in production): both connect and request use a bounded
 * timeout, and any failure or timeout is treated as "flag disabled / use default" rather
 * than propagating an exception into the payment hot path.
 */
public class FeatureFlagClient {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(500);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String featureFlagServiceUrl = System.getenv("FEATURE_FLAG_SERVICE_PROTOCOL") + "://"
            + System.getenv("FEATURE_FLAG_SERVICE_BASE_URL") + ":" + System.getenv("FEATURE_FLAG_SERVICE_PORT")
            + "/v1/flags/";

    public FeatureFlag getFlag(String flagId) {
        logger.debug("Getting feature flag with id: {}", flagId);

        // deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(featureFlagServiceUrl + flagId))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), FeatureFlag.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while reading feature flag '{}', treating as disabled", flagId);
            return disabledFlag(flagId);
        } catch (Exception e) {
            // Never let a slow or failing flag service stall the payment path — fall back to disabled.
            logger.warn("Failed to read feature flag '{}' ({}), treating as disabled", flagId, e.getMessage());
            return disabledFlag(flagId);
        }
    }

    private FeatureFlag disabledFlag(String flagId) {
        return new FeatureFlag(flagId, false, flagId, "fallback (flag service unavailable)", false, null);
    }
}
