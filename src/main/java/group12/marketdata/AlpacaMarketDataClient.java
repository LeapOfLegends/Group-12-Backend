package group12.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group12.marketdata.exception.MarketDataConfigurationException;
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
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
// handles the retrieval of market data snapshots from alpaca by building/sending HTTP requests, deserializing responses, and returning data
public class AlpacaMarketDataClient implements MarketDataProvider {

    private static final String API_KEY_HEADER = "APCA-API-KEY-ID";
    private static final String SECRET_KEY_HEADER = "APCA-API-SECRET-KEY";
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AlpacaMarketDataClient.class);

    private final RestClient restClient;
    private final AlpacaProperties properties;

    public AlpacaMarketDataClient(
            @Qualifier("alpacaMarketDataRestClient") RestClient restClient,
            AlpacaProperties properties
    ) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean supports(MarketDataRequest instrument) {
        if (instrument == null) {
            return false;
        }
        String symbol = normalizeSymbol(instrument.symbol());
        return !symbol.isBlank()
                && "Equity".equalsIgnoreCase(instrument.assetClass())
                && "USD".equalsIgnoreCase(instrument.currency())
                && properties.getSupportedUsEquitySymbols().contains(symbol);
    }

    @Override
    public Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<MarketDataRequest> instruments
    ) {
        if (instruments == null || instruments.isEmpty()) {
            return Map.of();
        }
        validateConfiguration();

        Set<String> uniqueSymbols = instruments.stream()
                .map(this::validateInstrument)
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        if (uniqueSymbols.size() > 50) {
            throw new MarketDataConfigurationException(
                    "A batch market-data request cannot contain more than 50 symbols"
            );
        }

        String symbols = String.join(",", uniqueSymbols);
        String responseBody = executeRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v2/stocks/snapshots")
                        .queryParam("symbols", symbols)
                        .queryParam("feed", properties.getFeed())
                        .build())
                .header(API_KEY_HEADER, properties.getApiKey())
                .header(SECRET_KEY_HEADER, properties.getSecretKey())
                .retrieve()
                .onStatus(this::isAuthenticationFailure, this::throwAuthenticationFailure)
                .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                .body(String.class));

        JsonNode response = parseResponse(responseBody);

        Map<String, MarketSnapshot> snapshots = new LinkedHashMap<>();
        Set<String> returnedSymbols = new LinkedHashSet<>();
        response.properties().forEach(entry -> {
            String responseSymbol = entry.getKey();
            JsonNode data = entry.getValue();
            String symbol = normalizeSymbol(responseSymbol);
            if (!uniqueSymbols.contains(symbol)) {
                return;
            }
            returnedSymbols.add(symbol);
            try {
                snapshots.put(symbol, mapSnapshot(symbol, data));
            } catch (MarketDataProviderException exception) {
                LOGGER.warn(
                        "Skipping unusable market-data snapshot for symbol {}: {}",
                        symbol,
                        exception.getMessage()
                );
            }
        });
        uniqueSymbols.stream()
                .filter(symbol -> !returnedSymbols.contains(symbol))
                .forEach(symbol -> LOGGER.warn(
                        "Skipping unusable market-data snapshot for symbol {}: "
                                + "provider response omitted the symbol",
                        symbol
                ));
        return Map.copyOf(snapshots);
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

    private MarketDataProviderException malformedResponse(Throwable cause) {
        String message = "Market-data provider returned a malformed batch response";
        return cause == null
                ? new MarketDataProviderException(message)
                : new MarketDataProviderException(message, cause);
    }

    private String validateInstrument(MarketDataRequest instrument) {
        String symbol = instrument == null ? "" : normalizeSymbol(instrument.symbol());
        if (!supports(instrument)) {
            throw new MarketDataProviderException(
                    "Instrument is not supported by the configured US equity market-data feed: "
                            + (symbol.isBlank() ? "<missing>" : symbol)
            );
        }
        return symbol;
    }

    private void validateConfiguration() {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()
                || properties.getSecretKey() == null || properties.getSecretKey().isBlank()) {
            throw new MarketDataConfigurationException(
                    "Alpaca market-data credentials are not configured"
            );
        }
        if (!"iex".equalsIgnoreCase(properties.getFeed())) {
            throw new MarketDataConfigurationException(
                    "Alpaca market-data feed must be configured as iex"
            );
        }
    }

    private MarketSnapshot mapSnapshot(String symbol, JsonNode data) {
        if (data == null || !data.isObject()) {
            throw unavailableSnapshot(symbol);
        }
        QuoteValues quote = mapQuote(data.get("latestQuote"));
        TradeValues trade = mapTrade(data.get("latestTrade"));
        if (quote == null && trade == null) {
            throw unavailableSnapshot(symbol);
        }

        return new MarketSnapshot(
                quote == null ? null : quote.bidPrice(),
                quote == null ? null : quote.askPrice(),
                trade == null ? null : trade.lastPrice(),
                quote == null ? null : quote.observedAt(),
                trade == null ? null : trade.observedAt()
        );
    }

    private QuoteValues mapQuote(JsonNode quote) {
        if (quote == null || quote.isNull()) {
            return null;
        }
        BigDecimal bidPrice = decimalValue(quote, "bp");
        BigDecimal askPrice = decimalValue(quote, "ap");
        OffsetDateTime observedAt = timestampValue(quote, "t");
        if (!quote.isObject() || !isPositive(bidPrice) || !isPositive(askPrice)
                || observedAt == null) {
            throw new MarketDataProviderException(
                    "Market-data provider returned an invalid quote"
            );
        }
        return new QuoteValues(
                bidPrice,
                askPrice,
                normalizeForPostgres(observedAt)
        );
    }

    private TradeValues mapTrade(JsonNode trade) {
        if (trade == null || trade.isNull()) {
            return null;
        }
        BigDecimal price = decimalValue(trade, "p");
        OffsetDateTime observedAt = timestampValue(trade, "t");
        if (!trade.isObject() || !isPositive(price) || observedAt == null) {
            throw new MarketDataProviderException(
                    "Market-data provider returned an invalid latest trade"
            );
        }
        return new TradeValues(price, normalizeForPostgres(observedAt));
    }

    private BigDecimal decimalValue(JsonNode parent, String fieldName) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
        JsonNode value = parent.get(fieldName);
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private OffsetDateTime timestampValue(JsonNode parent, String fieldName) {
        if (parent == null || !parent.isObject()) {
            return null;
        }
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

    private MarketDataProviderException unavailableSnapshot(String symbol) {
        return new MarketDataProviderException(
                "No usable market data is available for instrument: " + symbol
        );
    }

    private boolean isPositive(BigDecimal price) {
        return price != null && price.signum() > 0;
    }

    private OffsetDateTime normalizeForPostgres(OffsetDateTime timestamp) {
        OffsetDateTime utc = timestamp.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withNano((utc.getNano() / 1_000) * 1_000);
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

    private boolean isAuthenticationFailure(HttpStatusCode status) {
        return status.value() == 401 || status.value() == 403;
    }

    private void throwAuthenticationFailure(
            org.springframework.http.HttpRequest request,
            org.springframework.http.client.ClientHttpResponse response
    ) {
        throw new MarketDataProviderException(
                "Market-data provider authentication or authorization failed"
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

    private record QuoteValues(
            BigDecimal bidPrice,
            BigDecimal askPrice,
            OffsetDateTime observedAt
    ) {
    }

    private record TradeValues(BigDecimal lastPrice, OffsetDateTime observedAt) {
    }
}
