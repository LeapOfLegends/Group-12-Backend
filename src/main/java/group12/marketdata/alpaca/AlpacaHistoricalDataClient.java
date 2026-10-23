package group12.marketdata.alpaca;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group12.Entities.MarketCode;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalMarketDataProvider;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.MarketDataRequest;
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
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class AlpacaHistoricalDataClient implements HistoricalMarketDataProvider {

    private static final String API_KEY_HEADER = "APCA-API-KEY-ID";
    private static final String SECRET_KEY_HEADER = "APCA-API-SECRET-KEY";
    private static final int MAX_PAGES = 100;
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AlpacaHistoricalDataClient.class);

    private final RestClient restClient;
    private final AlpacaProperties properties;

    public AlpacaHistoricalDataClient(
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
                && instrument.marketCode() == MarketCode.IEX
                && properties.getSupportedUsEquitySymbols().contains(symbol);
    }

    @Override
    public List<HistoricalBar> getHistoricalBars(
            MarketDataRequest instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        String symbol = validateRequest(instrument, timeframe, startInclusive, endExclusive);
        validateConfiguration();

        Map<OffsetDateTime, HistoricalBar> bars = new LinkedHashMap<>();
        Set<String> seenTokens = new java.util.HashSet<>();
        String pageToken = null;

        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode response = fetchPage(
                    symbol, timeframe, startInclusive, endExclusive, pageToken
            );
            JsonNode barsNode = response.get("bars");
            if (barsNode == null || !barsNode.isArray()) {
                throw malformedResponse(null);
            }
            for (JsonNode barNode : barsNode) {
                HistoricalBar bar = mapCompletedBar(
                        barNode, timeframe, startInclusive, endExclusive
                );
                if (bar != null) {
                    bars.put(bar.barStart(), bar);
                }
            }

            JsonNode tokenNode = response.get("next_page_token");
            if (tokenNode == null || tokenNode.isNull()
                    || tokenNode.isTextual() && tokenNode.textValue().isBlank()) {
                return bars.values().stream()
                        .sorted(java.util.Comparator.comparing(HistoricalBar::barStart))
                        .toList();
            }
            if (!tokenNode.isTextual()) {
                throw malformedResponse(null);
            }
            pageToken = tokenNode.textValue();
            if (!seenTokens.add(pageToken)) {
                throw new MarketDataProviderException(
                        "Market-data provider returned a repeated history page token"
                );
            }
        }
        throw new MarketDataProviderException(
                "Market-data provider history pagination exceeded the safety limit"
        );
    }

    private JsonNode fetchPage(
            String symbol,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive,
            String pageToken
    ) {
        String responseBody = executeRequest(() -> restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder
                            .path("/v2/stocks/{symbol}/bars")
                            .queryParam("timeframe", timeframe.getDatabaseValue())
                            .queryParam("start", utc(startInclusive))
                            .queryParam("end", utc(endExclusive))
                            .queryParam("feed", "iex")
                            .queryParam("adjustment", "raw")
                            .queryParam("limit", 10_000);
                    if (pageToken != null) {
                        builder.queryParam("page_token", pageToken);
                    }
                    return builder.build(symbol);
                })
                .header(API_KEY_HEADER, properties.getApiKey())
                .header(SECRET_KEY_HEADER, properties.getSecretKey())
                .retrieve()
                .onStatus(this::isAuthenticationFailure, this::throwAuthenticationFailure)
                .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                .body(String.class));
        return parseResponse(responseBody);
    }

    private HistoricalBar mapCompletedBar(
            JsonNode node,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        if (node == null || !node.isObject()) {
            LOGGER.warn("Skipping malformed Alpaca historical bar");
            return null;
        }
        OffsetDateTime timestamp = timestampValue(node.get("t"));
        BigDecimal close = decimalValue(node.get("c"));
        if (timestamp == null || close == null || close.signum() <= 0) {
            LOGGER.warn("Skipping Alpaca historical bar with invalid timestamp or close price");
            return null;
        }
        OffsetDateTime normalized = normalizeForPostgres(timestamp);
        if (normalized.isBefore(startInclusive) || !normalized.isBefore(endExclusive)
                || !isComplete(normalized, timeframe, endExclusive)) {
            return null;
        }
        return new HistoricalBar(normalized, close, true);
    }

    private boolean isComplete(
            OffsetDateTime barStart,
            HistoricalTimeframe timeframe,
            OffsetDateTime referenceTime
    ) {
        return switch (timeframe) {
            case FIVE_MINUTES -> !barStart.plusMinutes(5).isAfter(referenceTime);
            case THIRTY_MINUTES -> !barStart.plusMinutes(30).isAfter(referenceTime);
            case ONE_DAY -> {
                ZonedDateTime bar = barStart.atZoneSameInstant(NEW_YORK);
                ZonedDateTime reference = referenceTime.atZoneSameInstant(NEW_YORK);
                yield bar.toLocalDate().isBefore(reference.toLocalDate())
                        || bar.toLocalDate().equals(reference.toLocalDate())
                        && !reference.toLocalTime().isBefore(REGULAR_CLOSE);
            }
            case ONE_WEEK -> {
                var barWeek = barStart.atZoneSameInstant(NEW_YORK).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                var referenceWeek = referenceTime.atZoneSameInstant(NEW_YORK).toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield barWeek.isBefore(referenceWeek);
            }
            case ONE_MONTH -> {
                YearMonth barMonth = YearMonth.from(barStart.atZoneSameInstant(NEW_YORK));
                YearMonth referenceMonth = YearMonth.from(
                        referenceTime.atZoneSameInstant(NEW_YORK)
                );
                yield barMonth.isBefore(referenceMonth);
            }
        };
    }

    private String validateRequest(
            MarketDataRequest instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        if (!supports(instrument)) {
            throw new MarketDataProviderException(
                    "Instrument is not supported by Alpaca historical market data"
            );
        }
        if (timeframe == null || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("A valid historical timeframe and window are required");
        }
        return normalizeSymbol(instrument.symbol());
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

    private BigDecimal decimalValue(JsonNode value) {
        return value != null && value.isNumber() ? value.decimalValue() : null;
    }

    private OffsetDateTime timestampValue(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.textValue());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String utc(OffsetDateTime timestamp) {
        return DateTimeFormatter.ISO_INSTANT.format(timestamp.toInstant());
    }

    private OffsetDateTime normalizeForPostgres(OffsetDateTime timestamp) {
        OffsetDateTime utc = timestamp.withOffsetSameInstant(ZoneOffset.UTC);
        return utc.withNano((utc.getNano() / 1_000) * 1_000);
    }

    private MarketDataProviderException malformedResponse(Throwable cause) {
        String message = "Market-data provider returned a malformed history response";
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
            String message = causedByTimeout(exception)
                    ? "Market-data provider request timed out"
                    : "Market-data provider could not be reached";
            throw new MarketDataProviderException(message, exception);
        } catch (RestClientException exception) {
            throw new MarketDataProviderException(
                    "Market-data provider returned a malformed response", exception
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
}
