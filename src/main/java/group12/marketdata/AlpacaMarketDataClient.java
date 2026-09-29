package group12.marketdata;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import group12.Entities.InstrumentEntity;
import group12.marketdata.exception.MarketDataAuthenticationException;
import group12.marketdata.exception.MarketDataConfigurationException;
import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import group12.marketdata.exception.MarketDataResponseException;
import group12.marketdata.exception.MarketDataTimeoutException;
import group12.marketdata.exception.MarketDataUnavailableException;
import group12.marketdata.exception.UnsupportedInstrumentException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
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
import java.util.Locale;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
    public MarketSnapshot getCurrentMarketSnapshot(InstrumentEntity instrument) {
        String symbol = validateInstrument(instrument);
        validateConfiguration();

        AlpacaSnapshotResponse response = executeRequest(() -> restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/stocks/{symbol}/snapshot")
                            .queryParam("feed", properties.getFeed())
                            .build(symbol))
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header(SECRET_KEY_HEADER, properties.getSecretKey())
                    .retrieve()
                    .onStatus(this::isAuthenticationFailure, this::throwAuthenticationFailure)
                    .onStatus(status -> status.value() == 429, this::throwRateLimitFailure)
                    .onStatus(HttpStatusCode::is5xxServerError, this::throwServerFailure)
                    .onStatus(HttpStatusCode::is4xxClientError, this::throwClientFailure)
                    .body(AlpacaSnapshotResponse.class));

        return mapResponse(symbol, response);
    }

    @Override
    public Map<String, MarketSnapshot> getCurrentMarketSnapshots(
            Collection<InstrumentEntity> instruments
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
        Map<String, AlpacaSnapshotData> response = executeRequest(() -> restClient.get()
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
                .body(new ParameterizedTypeReference<>() {
                }));

        if (response == null) {
            throw new MarketDataResponseException(
                    "Market-data provider returned a malformed batch response"
            );
        }

        Map<String, MarketSnapshot> snapshots = new LinkedHashMap<>();
        response.forEach((responseSymbol, data) -> {
            String symbol = normalizeSymbol(responseSymbol);
            if (!uniqueSymbols.contains(symbol)) {
                return;
            }
            try {
                snapshots.put(symbol, mapSnapshot(symbol, data));
            } catch (MarketDataResponseException | MarketDataUnavailableException exception) {
                // A bad snapshot for one symbol must not discard valid snapshots for others.
            }
        });
        return Map.copyOf(snapshots);
    }

    private String validateInstrument(InstrumentEntity instrument) {
        String symbol = instrument == null || instrument.getSymbol() == null
                ? ""
                : instrument.getSymbol().trim().toUpperCase(Locale.ROOT);
        boolean supportedType = instrument != null
                && instrument.isTradable()
                && "Equity".equalsIgnoreCase(instrument.getAssetClass())
                && "USD".equalsIgnoreCase(instrument.getCurrency());

        if (symbol.isBlank()
                || !supportedType
                || !properties.getSupportedUsEquitySymbols().contains(symbol)) {
            throw new UnsupportedInstrumentException(symbol.isBlank() ? "<missing>" : symbol);
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

    private MarketSnapshot mapResponse(String requestedSymbol, AlpacaSnapshotResponse response) {
        if (response == null || response.symbol() == null
                || !requestedSymbol.equalsIgnoreCase(response.symbol().trim())) {
            throw new MarketDataResponseException(
                    "Market-data provider response did not match the requested instrument"
            );
        }

        return mapSnapshot(
                requestedSymbol,
                new AlpacaSnapshotData(response.latestQuote(), response.latestTrade())
        );
    }

    private MarketSnapshot mapSnapshot(String symbol, AlpacaSnapshotData data) {
        if (data == null) {
            throw new MarketDataUnavailableException(symbol);
        }
        QuoteValues quote = mapQuote(data.latestQuote());
        TradeValues trade = mapTrade(data.latestTrade());
        if (quote == null && trade == null) {
            throw new MarketDataUnavailableException(symbol);
        }

        return new MarketSnapshot(
                quote == null ? null : quote.bidPrice(),
                quote == null ? null : quote.askPrice(),
                trade == null ? null : trade.lastPrice(),
                quote == null ? null : quote.observedAt(),
                trade == null ? null : trade.observedAt()
        );
    }

    private QuoteValues mapQuote(AlpacaQuote quote) {
        if (quote == null) {
            return null;
        }
        if (!isPositive(quote.bidPrice()) || !isPositive(quote.askPrice())
                || quote.timestamp() == null) {
            throw new MarketDataResponseException(
                    "Market-data provider returned an invalid quote"
            );
        }
        return new QuoteValues(
                quote.bidPrice(),
                quote.askPrice(),
                normalizeForPostgres(quote.timestamp())
        );
    }

    private TradeValues mapTrade(AlpacaTrade trade) {
        if (trade == null) {
            return null;
        }
        if (!isPositive(trade.price()) || trade.timestamp() == null) {
            throw new MarketDataResponseException(
                    "Market-data provider returned an invalid latest trade"
            );
        }
        return new TradeValues(trade.price(), normalizeForPostgres(trade.timestamp()));
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
                throw new MarketDataTimeoutException(exception);
            }
            throw new MarketDataProviderException(
                    "Market-data provider could not be reached",
                    exception
            );
        } catch (RestClientException exception) {
            throw new MarketDataResponseException(
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
        throw new MarketDataAuthenticationException();
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlpacaSnapshotResponse(
            String symbol,
            @JsonProperty("latestQuote") AlpacaQuote latestQuote,
            @JsonProperty("latestTrade") AlpacaTrade latestTrade
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlpacaSnapshotData(
            @JsonProperty("latestQuote") AlpacaQuote latestQuote,
            @JsonProperty("latestTrade") AlpacaTrade latestTrade
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlpacaQuote(
            @JsonProperty("bp") BigDecimal bidPrice,
            @JsonProperty("ap") BigDecimal askPrice,
            @JsonProperty("t") OffsetDateTime timestamp
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlpacaTrade(
            @JsonProperty("p") BigDecimal price,
            @JsonProperty("t") OffsetDateTime timestamp
    ) {
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
