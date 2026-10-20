package group12.marketdata.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group12.marketdata.exception.MarketDataConfigurationException;
import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.Supplier;

@Component
public class AlpacaMarketStatusClient {

    private static final String API_KEY_HEADER = "APCA-API-KEY-ID";
    private static final String SECRET_KEY_HEADER = "APCA-API-SECRET-KEY";
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final AlpacaProperties properties;

    public AlpacaMarketStatusClient(
            @Qualifier("alpacaTradingRestClient") RestClient restClient,
            AlpacaProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isIexOpen() {
        validateCredentials();
        String responseBody = executeRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v2/clock").build())
                .header(API_KEY_HEADER, properties.getApiKey())
                .header(SECRET_KEY_HEADER, properties.getSecretKey())
                .retrieve()
                .onStatus(this::isAuthenticationFailure, this::throwAuthenticationFailure)
                .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                .body(String.class));
        return parseIsOpen(responseBody);
    }

    private boolean parseIsOpen(String responseBody) {
        if (responseBody == null) {
            throw malformedResponse(null);
        }
        try {
            JsonNode response = RESPONSE_MAPPER.readTree(responseBody);
            if (response == null || !response.isObject()) {
                throw malformedResponse(null);
            }
            JsonNode open = response.get("is_open");
            if (open == null || !open.isBoolean()) {
                throw malformedResponse(null);
            }
            return open.booleanValue();
        } catch (JsonProcessingException exception) {
            throw malformedResponse(exception);
        }
    }

    private MarketDataProviderException malformedResponse(Throwable cause) {
        String message = "Market-status provider returned a malformed response";
        return cause == null
                ? new MarketDataProviderException(message)
                : new MarketDataProviderException(message, cause);
    }

    private void validateCredentials() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new MarketDataConfigurationException(
                    "Alpaca market-data credentials are not configured"
            );
        }
    }

    private <T> T executeRequest(Supplier<T> request) {
        try {
            return request.get();
        } catch (MarketDataException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (causedByTimeout(exception)) {
                throw new MarketDataProviderException(
                        "Market-status provider request timed out",
                        exception
                );
            }
            throw new MarketDataProviderException(
                    "Market-status provider could not be reached",
                    exception
            );
        } catch (RestClientException exception) {
            throw new MarketDataProviderException(
                    "Market-status provider returned a malformed response",
                    exception
            );
        }
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isAuthenticationFailure(HttpStatusCode status) {
        return status.value() == 401 || status.value() == 403;
    }

    private void throwAuthenticationFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataProviderException(
                "Market-status provider authentication or authorization failed"
        );
    }

    private void throwRateLimitFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataRateLimitException();
    }

    private void throwServerFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataProviderException("Market-status provider is unavailable");
    }

    private void throwClientFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataProviderException("Market-status provider rejected the request");
    }
}