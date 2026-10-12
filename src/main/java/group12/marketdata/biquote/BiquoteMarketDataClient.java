package group12.marketdata.biquote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group12.Entities.MarketCode;
import group12.marketdata.MarketDataProvider;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.MarketSnapshot;
import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class BiquoteMarketDataClient implements MarketDataProvider {

    private static final String LATEST_QUOTES_PATH = "/api/latest";
    private static final Map<String, String> BIQUOTE_SYMBOLS = Map.of(
            "EUR/USD", "EURUSD",
            "USD/JPY", "USDJPY",
            "GBP/USD", "GBPUSD",
            "AUD/USD", "AUDUSD",
            "USD/CAD", "USDCAD",
            "USD/INR", "USDINR"
    );
    private static final Map<String, String> DB_SYMBOLS = BIQUOTE_SYMBOLS.entrySet()
            .stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BiquoteMarketDataClient.class);

    private final RestClient restClient;

    public BiquoteMarketDataClient(
            @Qualifier("biquoteMarketDataRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    @Override
    public boolean supports(MarketDataRequest request) {
        if (request == null) {
            return false;
        }
        String symbol = normalizeSymbol(request.symbol());
        return !symbol.isBlank()
                && "FOREX".equalsIgnoreCase(request.assetClass())
                && request.marketCode() == MarketCode.FX
                && BIQUOTE_SYMBOLS.containsKey(symbol);
    }

    @Override
    public Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<MarketDataRequest> instruments
    ) {
        if (instruments == null || instruments.isEmpty()) {
            return Map.of();
        }

        Set<String> requestedProviderSymbols = new TreeSet<>();
        for (MarketDataRequest instrument : instruments) {
            String dbSymbol = validateInstrument(instrument);
            requestedProviderSymbols.add(BIQUOTE_SYMBOLS.get(dbSymbol));
        }

        String responseBody = executeRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(LATEST_QUOTES_PATH)
                        .queryParam("symbols", requestedProviderSymbols.toArray())
                        .build())
                .retrieve()
                .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                .body(String.class));

        return mapSnapshots(parseResponse(responseBody), requestedProviderSymbols);
    }

    private Map<String, MarketSnapshot> mapSnapshots(
            JsonNode response,
            Set<String> requestedProviderSymbols
    ) {
        Map<String, MarketSnapshot> snapshots = new LinkedHashMap<>();
        response.properties().forEach(entry -> {
            JsonNode tick = entry.getValue();
            String providerSymbol = responseSymbol(entry.getKey(), tick);
            if (!requestedProviderSymbols.contains(providerSymbol)) {
                return;
            }

            String dbSymbol = DB_SYMBOLS.get(providerSymbol);
            if (dbSymbol == null) {
                return;
            }
            try {
                MarketSnapshot snapshot = mapSnapshot(tick);
                if (snapshot != null) {
                    snapshots.put(dbSymbol, snapshot);
                }
            } catch (MarketDataProviderException exception) {
                LOGGER.warn(
                        "Skipping unusable Biquote market-data snapshot for symbol {}: {}",
                        dbSymbol,
                        exception.getMessage()
                );
            }
        });
        return Map.copyOf(snapshots);
    }

    private MarketSnapshot mapSnapshot(JsonNode tick) {
        if (tick == null || !tick.isObject()) {
            throw invalidQuote();
        }
        if (booleanValue(tick, "stale") || isClosed(tick.get("marketState"))) {
            return null;
        }

        BigDecimal bid = decimalValue(tick, "bid");
        BigDecimal ask = decimalValue(tick, "ask");
        OffsetDateTime quoteAsOf = timestampValue(tick, "timestamp");
        if (!isPositive(bid) || !isPositive(ask) || ask.compareTo(bid) < 0
                || quoteAsOf == null) {
            throw invalidQuote();
        }
        return new MarketSnapshot(
                bid,
                ask,
                null,
                normalizeForPostgres(quoteAsOf),
                null
        );
    }

    private JsonNode parseResponse(String responseBody) {
        if (responseBody == null) {
            throw malformedResponse(null);
        }
        try {
            JsonNode response = RESPONSE_MAPPER.readTree(responseBody);
            if (response == null || !response.isObject()) {
                throw malformedResponse(null);
            }
            return response;
        } catch (JsonProcessingException exception) {
            throw malformedResponse(exception);
        }
    }

    private String validateInstrument(MarketDataRequest instrument) {
        String symbol = instrument == null ? "" : normalizeSymbol(instrument.symbol());
        if (!supports(instrument)) {
            throw new MarketDataProviderException(
                    "Instrument is not supported by the Biquote market-data feed: "
                            + (symbol.isBlank() ? "<missing>" : symbol)
            );
        }
        return symbol;
    }

    private String responseSymbol(String responseKey, JsonNode tick) {
        JsonNode symbol = tick == null ? null : tick.get("symbol");
        if (symbol != null && symbol.isTextual() && !symbol.textValue().isBlank()) {
            return normalizeSymbol(symbol.textValue());
        }
        return normalizeSymbol(responseKey);
    }

    private BigDecimal decimalValue(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private OffsetDateTime timestampValue(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.textValue());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private boolean booleanValue(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        return value != null && value.isBoolean() && value.booleanValue();
    }

    private boolean isClosed(JsonNode marketState) {
        return marketState != null
                && marketState.isTextual()
                && "CLOSED".equalsIgnoreCase(marketState.textValue().trim());
    }

    private boolean isPositive(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    private OffsetDateTime normalizeForPostgres(OffsetDateTime timestamp) {
        OffsetDateTime utc = timestamp.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withNano((utc.getNano() / 1_000) * 1_000);
    }

    private MarketDataProviderException invalidQuote() {
        return new MarketDataProviderException(
                "Market-data provider returned an invalid FX quote"
        );
    }

    private MarketDataProviderException malformedResponse(Throwable cause) {
        String message = "Market-data provider returned a malformed batch response";
        return cause == null
                ? new MarketDataProviderException(message)
                : new MarketDataProviderException(message, cause);
    }

    private <T> T executeRequest(Supplier<T> request) {
        try {
            return request.get();
        } catch (MarketDataException exception) {
            throw exception;
        } catch (ResourceAccessException exception) {
            if (causedByTimeout(exception)) {
                throw new MarketDataProviderException(
                        "Market-data provider request timed out",
                        exception
                );
            }
            throw new MarketDataProviderException(
                    "Market-data provider could not be reached",
                    exception
            );
        } catch (RestClientException exception) {
            throw new MarketDataProviderException(
                    "Market-data provider returned a malformed response",
                    exception
            );
        }
    }

    private boolean causedByTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        throw new MarketDataProviderException("Market-data provider is unavailable");
    }

    private void throwClientFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataProviderException("Market-data provider rejected the request");
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
    }
}
