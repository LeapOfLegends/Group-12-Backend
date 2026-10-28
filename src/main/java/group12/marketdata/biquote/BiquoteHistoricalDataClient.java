package group12.marketdata.biquote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group12.Entities.MarketCode;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalMarketDataProvider;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import group12.marketdata.exception.PartialHistoricalDataException;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Component
public class BiquoteHistoricalDataClient implements HistoricalMarketDataProvider {

    private static final int REQUEST_LIMIT = 1_000;
    private static final int MAX_CHUNKS = 100;
    private static final ObjectMapper RESPONSE_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final Logger LOGGER =
            LoggerFactory.getLogger(BiquoteHistoricalDataClient.class);

    private final RestClient restClient;

    public BiquoteHistoricalDataClient(
            @Qualifier("biquoteMarketDataRestClient") RestClient restClient
    ) {
        this.restClient = restClient;
    }

    @Override
    public boolean supports(MarketDataRequest request) {
        if (request == null) {
            return false;
        }
        String symbol = BiquoteSymbols.normalize(request.symbol());
        return !symbol.isBlank()
                && "FOREX".equalsIgnoreCase(request.assetClass())
                && request.marketCode() == MarketCode.FX
                && BiquoteSymbols.supports(symbol);
    }

    @Override
    public List<HistoricalBar> getHistoricalBars(
            MarketDataRequest instrument,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        String symbol = validateRequest(instrument, timeframe, startInclusive, endExclusive);
        if (timeframe == HistoricalTimeframe.ONE_WEEK
                || timeframe == HistoricalTimeframe.ONE_MONTH) {
            try {
                return aggregate(fetchDailySource(
                                symbol, timeframe, startInclusive, endExclusive
                        ), timeframe, startInclusive, endExclusive);
            } catch (PartialHistoricalDataException exception) {
                List<HistoricalBar> aggregates = aggregate(
                        exception.getBars(), timeframe, startInclusive, endExclusive
                );
                throw new PartialHistoricalDataException(
                        exception.getMessage(), exception, aggregates
                );
            }
        }
        return fetchNative(symbol, timeframe, startInclusive, endExclusive);
    }

    private List<HistoricalBar> fetchDailySource(
            String symbol,
            HistoricalTimeframe aggregateTimeframe,
            OffsetDateTime requestedStart,
            OffsetDateTime endExclusive
    ) {
        OffsetDateTime utcStart = requestedStart.withOffsetSameInstant(ZoneOffset.UTC);
        var sourceDate = aggregateTimeframe == HistoricalTimeframe.ONE_WEEK
                ? utcStart.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : utcStart.toLocalDate().withDayOfMonth(1);
        OffsetDateTime sourceStart = sourceDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        return fetchNative(symbol, HistoricalTimeframe.ONE_DAY, sourceStart, endExclusive);
    }

    private List<HistoricalBar> fetchNative(
            String symbol,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        Duration interval = intervalDuration(timeframe);
        Duration chunkSpan = interval.multipliedBy(REQUEST_LIMIT - 1L);
        Map<OffsetDateTime, HistoricalBar> bars = new LinkedHashMap<>();
        OffsetDateTime cursor = startInclusive;

        for (int chunk = 0; cursor.isBefore(endExclusive); chunk++) {
            if (chunk >= MAX_CHUNKS) {
                throw partialFailure(
                        "Biquote history chunking exceeded the safety limit", null, bars
                );
            }
            OffsetDateTime chunkEnd = earlier(cursor.plus(chunkSpan), endExclusive);
            try {
                for (HistoricalBar bar : fetchChunk(
                        symbol, timeframe, cursor, chunkEnd, endExclusive
                )) {
                    bars.put(bar.barStart(), bar);
                }
            } catch (MarketDataException exception) {
                if (!bars.isEmpty()) {
                    throw partialFailure(
                            "Biquote returned only part of the requested history",
                            exception,
                            bars
                    );
                }
                throw exception;
            }
            if (chunkEnd.equals(endExclusive)) {
                break;
            }
            cursor = chunkEnd;
        }
        return sorted(bars);
    }

    private List<HistoricalBar> fetchChunk(
            String symbol,
            HistoricalTimeframe timeframe,
            OffsetDateTime chunkStart,
            OffsetDateTime chunkEnd,
            OffsetDateTime requestedEnd
    ) {
        String interval = providerInterval(timeframe);
        String responseBody = executeRequest(() -> restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/{symbol}/ohlc")
                        .queryParam("interval", interval)
                        .queryParam("from", utc(chunkStart))
                        .queryParam("to", utc(chunkEnd))
                        .queryParam("limit", REQUEST_LIMIT)
                        .build(symbol))
                .retrieve()
                .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                .body(String.class));
        JsonNode response = parseResponse(responseBody);
        validateEnvelope(response, symbol, interval);

        List<HistoricalBar> bars = new ArrayList<>();
        for (JsonNode node : response.get("bars")) {
            HistoricalBar bar = mapCompletedBar(
                    node, timeframe, chunkStart, chunkEnd, requestedEnd
            );
            if (bar != null) {
                bars.add(bar);
            }
        }
        return bars;
    }

    private HistoricalBar mapCompletedBar(
            JsonNode node,
            HistoricalTimeframe timeframe,
            OffsetDateTime chunkStart,
            OffsetDateTime chunkEnd,
            OffsetDateTime requestedEnd
    ) {
        if (node == null || !node.isObject()) {
            LOGGER.warn("Skipping malformed Biquote historical bar");
            return null;
        }
        JsonNode openNode = node.get("isOpen");
        if (openNode == null || !openNode.isBoolean() || openNode.booleanValue()) {
            return null;
        }
        OffsetDateTime timestamp = timestampValue(node.get("openTime"));
        BigDecimal close = decimalValue(node.get("close"));
        if (timestamp == null || close == null || close.signum() <= 0) {
            LOGGER.warn("Skipping Biquote historical bar with invalid timestamp or close price");
            return null;
        }
        OffsetDateTime normalized = normalizeForPostgres(timestamp);
        if (normalized.isBefore(chunkStart) || normalized.isAfter(chunkEnd)
                || !barEnd(normalized, timeframe).isBefore(requestedEnd)
                && !barEnd(normalized, timeframe).isEqual(requestedEnd)) {
            return null;
        }
        return new HistoricalBar(normalized, close, true);
    }

    private List<HistoricalBar> aggregate(
            List<HistoricalBar> dailyBars,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        Map<OffsetDateTime, HistoricalBar> finalDailyByPeriod = new LinkedHashMap<>();
        dailyBars.stream()
                .filter(HistoricalBar::complete)
                .sorted(Comparator.comparing(HistoricalBar::barStart))
                .forEach(bar -> finalDailyByPeriod.put(
                        periodStart(bar.barStart(), timeframe), bar
                ));

        return finalDailyByPeriod.entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(startInclusive))
                .filter(entry -> periodEnd(entry.getKey(), timeframe)
                        .compareTo(endExclusive) <= 0)
                .map(entry -> new HistoricalBar(
                        entry.getKey(), entry.getValue().closePrice(), true
                ))
                .sorted(Comparator.comparing(HistoricalBar::barStart))
                .toList();
    }

    private OffsetDateTime periodStart(
            OffsetDateTime timestamp,
            HistoricalTimeframe timeframe
    ) {
        var date = timestamp.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (timeframe == HistoricalTimeframe.ONE_WEEK) {
            date = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        } else {
            date = date.withDayOfMonth(1);
        }
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private OffsetDateTime periodEnd(
            OffsetDateTime periodStart,
            HistoricalTimeframe timeframe
    ) {
        return timeframe == HistoricalTimeframe.ONE_WEEK
                ? periodStart.plusWeeks(1)
                : periodStart.plusMonths(1);
    }

    private OffsetDateTime barEnd(
            OffsetDateTime barStart,
            HistoricalTimeframe timeframe
    ) {
        return switch (timeframe) {
            case FIVE_MINUTES -> barStart.plusMinutes(5);
            case THIRTY_MINUTES -> barStart.plusMinutes(30);
            case ONE_DAY -> barStart.plusDays(1);
            default -> throw new IllegalArgumentException("Biquote native interval is required");
        };
    }

    private Duration intervalDuration(HistoricalTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTES -> Duration.ofMinutes(5);
            case THIRTY_MINUTES -> Duration.ofMinutes(30);
            case ONE_DAY -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Biquote native interval is required");
        };
    }

    private String providerInterval(HistoricalTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTES -> "5m";
            case THIRTY_MINUTES -> "30m";
            case ONE_DAY -> "1d";
            default -> throw new IllegalArgumentException("Biquote native interval is required");
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
                    "Instrument is not supported by Biquote historical market data"
            );
        }
        if (timeframe == null || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("A valid historical timeframe and window are required");
        }
        return BiquoteSymbols.toProviderSymbol(instrument.symbol());
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

    private void validateEnvelope(JsonNode response, String symbol, String interval) {
        JsonNode bars = response.get("bars");
        if (bars == null || !bars.isArray()) {
            throw malformedResponse(null);
        }
        JsonNode responseSymbol = response.get("symbol");
        if (responseSymbol != null && (!responseSymbol.isTextual()
                || !symbol.equals(BiquoteSymbols.normalize(responseSymbol.textValue())))) {
            throw malformedResponse(null);
        }
        JsonNode responseInterval = response.get("interval");
        if (responseInterval != null && (!responseInterval.isTextual()
                || !interval.equals(responseInterval.textValue().trim().toLowerCase(Locale.ROOT)))) {
            throw malformedResponse(null);
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

    private List<HistoricalBar> sorted(Map<OffsetDateTime, HistoricalBar> bars) {
        return bars.values().stream()
                .sorted(Comparator.comparing(HistoricalBar::barStart))
                .toList();
    }

    private PartialHistoricalDataException partialFailure(
            String message,
            Throwable cause,
            Map<OffsetDateTime, HistoricalBar> bars
    ) {
        return new PartialHistoricalDataException(message, cause, sorted(bars));
    }

    private OffsetDateTime earlier(OffsetDateTime first, OffsetDateTime second) {
        return first.isBefore(second) ? first : second;
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
}
