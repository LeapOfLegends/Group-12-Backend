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
import java.util.concurrent.TimeoutException;

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

        AlpacaSnapshotResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v2/stocks/{symbol}/snapshot")
                            .queryParam("feed", properties.getFeed())
                            .build(symbol))
                    .header(API_KEY_HEADER, properties.getApiKey())
                    .header(SECRET_KEY_HEADER, properties.getSecretKey())
                    .retrieve()
                    .onStatus(
                            status -> status.value() == 401 || status.value() == 403,
                            (request, providerResponse) -> {
                                throw new MarketDataAuthenticationException();
                            }
                    )
                    .onStatus(
                            status -> status.value() == 429,
                            (request, providerResponse) -> {
                                throw new MarketDataRateLimitException();
                            }
                    )
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            (request, providerResponse) -> {
                                throw new MarketDataProviderException(
                                        "Market-data provider is unavailable"
                                );
                            }
                    )
                    .onStatus(
                            HttpStatusCode::is4xxClientError,
                            (request, providerResponse) -> {
                                throw new MarketDataProviderException(
                                        "Market-data provider rejected the request"
                                );
                            }
                    )
                    .body(AlpacaSnapshotResponse.class);
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

        return mapResponse(symbol, response);
    }

    private String validateInstrument(InstrumentEntity instrument) {
        String symbol = instrument == null || instrument.getSymbol() == null
                ? ""
                : instrument.getSymbol().trim().toUpperCase(Locale.ROOT);
        boolean supportedType = instrument != null
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

        QuoteValues quote = mapQuote(response.latestQuote());
        TradeValues trade = mapTrade(response.latestTrade());
        if (quote == null && trade == null) {
            throw new MarketDataUnavailableException(requestedSymbol);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AlpacaSnapshotResponse(
            String symbol,
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
