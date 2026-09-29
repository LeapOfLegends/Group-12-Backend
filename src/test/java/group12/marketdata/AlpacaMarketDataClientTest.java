package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.marketdata.exception.MarketDataAuthenticationException;
import group12.marketdata.exception.MarketDataConfigurationException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import group12.marketdata.exception.MarketDataResponseException;
import group12.marketdata.exception.MarketDataTimeoutException;
import group12.marketdata.exception.MarketDataUnavailableException;
import group12.marketdata.exception.UnsupportedInstrumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaMarketDataClientTest {

    private static final String URL =
            "https://data.alpaca.markets/v2/stocks/AAPL/snapshot?feed=iex";

    private AlpacaProperties properties;
    private MockRestServiceServer server;
    private AlpacaMarketDataClient client;

    @BeforeEach
    void setUp() {
        properties = new AlpacaProperties();
        properties.setApiKey("test-api-key");
        properties.setSecretKey("test-secret-key");
        properties.setFeed("iex");
        properties.setSupportedUsEquitySymbols(Set.of("AAPL"));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://data.alpaca.markets");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AlpacaMarketDataClient(builder.build(), properties);
    }

    @Test
    void mapsSnapshotWithExactPricesObservationTimesFeedAndAuthenticationHeaders() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("APCA-API-KEY-ID", "test-api-key"))
                .andExpect(header("APCA-API-SECRET-KEY", "test-secret-key"))
                .andRespond(withSuccess("""
                        {
                          "symbol": "AAPL",
                          "latestQuote": {
                            "bp": 123.123456789012345678,
                            "ap": 123.223456789012345679,
                            "t": "2026-09-29T15:01:02.123456789Z"
                          },
                          "latestTrade": {
                            "p": 123.173456789012345678,
                            "t": "2026-09-29T10:01:03.987654321-05:00"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        MarketSnapshot result = client.getCurrentMarketSnapshot(instrument("AAPL"));

        assertEquals(new BigDecimal("123.123456789012345678"), result.bidPrice());
        assertEquals(new BigDecimal("123.223456789012345679"), result.askPrice());
        assertEquals(new BigDecimal("123.173456789012345678"), result.lastPrice());
        assertEquals(
                OffsetDateTime.parse("2026-09-29T15:01:02.123456Z"),
                result.quoteAsOf()
        );
        assertEquals(
                OffsetDateTime.parse("2026-09-29T15:01:03.987654Z"),
                result.lastTradeAsOf()
        );
        server.verify();
    }

    @Test
    void missingQuoteKeepsQuoteGroupAbsentWhenTradeIsValid() {
        respond("""
                {"symbol":"AAPL","latestTrade":{"p":42.25,"t":"2026-09-29T15:00:00Z"}}
                """);

        MarketSnapshot result = client.getCurrentMarketSnapshot(instrument("AAPL"));

        assertNull(result.bidPrice());
        assertNull(result.askPrice());
        assertNull(result.quoteAsOf());
        assertEquals(new BigDecimal("42.25"), result.lastPrice());
    }

    @Test
    void missingTradeKeepsTradeGroupAbsentWhenQuoteIsValid() {
        respond("""
                {"symbol":"AAPL","latestQuote":{"bp":42.20,"ap":42.30,"t":"2026-09-29T15:00:00Z"}}
                """);

        MarketSnapshot result = client.getCurrentMarketSnapshot(instrument("AAPL"));

        assertEquals(new BigDecimal("42.20"), result.bidPrice());
        assertEquals(new BigDecimal("42.30"), result.askPrice());
        assertNull(result.lastPrice());
        assertNull(result.lastTradeAsOf());
    }

    @Test
    void completelyUnavailableSnapshotIsReported() {
        respond("{" + "\"symbol\":\"AAPL\"" + "}");

        assertThrows(
                MarketDataUnavailableException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01"})
    void zeroAndNegativeQuotePricesAreRejected(String price) {
        respond("""
                {"symbol":"AAPL","latestQuote":{"bp":%s,"ap":42.30,"t":"2026-09-29T15:00:00Z"}}
                """.formatted(price));

        assertThrows(
                MarketDataResponseException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01"})
    void zeroAndNegativeTradePricesAreRejected(String price) {
        respond("""
                {"symbol":"AAPL","latestTrade":{"p":%s,"t":"2026-09-29T15:00:00Z"}}
                """.formatted(price));

        assertThrows(
                MarketDataResponseException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{not-json",
            "{\"symbol\":\"AAPL\",\"latestQuote\":{\"bp\":1,\"ap\":2}}",
            "{\"symbol\":\"AAPL\",\"latestTrade\":{\"p\":1}}",
            "{\"symbol\":\"MSFT\",\"latestTrade\":{\"p\":1,\"t\":\"2026-09-29T15:00:00Z\"}}"
    })
    void malformedOrInconsistentResponsesAreRejected(String body) {
        respond(body);

        assertThrows(
                MarketDataResponseException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationAndAuthorizationFailuresAreDistinct(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThrows(
                MarketDataAuthenticationException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @Test
    void rateLimitFailureIsDistinct() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(
                MarketDataRateLimitException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void providerServerFailuresAreDistinct(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThrows(
                MarketDataProviderException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @Test
    void timeoutFailureIsDistinct() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("simulated timeout")));

        assertThrows(
                MarketDataTimeoutException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
    }

    @Test
    void unsupportedInstrumentMakesNoHttpRequest() {
        assertThrows(
                UnsupportedInstrumentException.class,
                () -> client.getCurrentMarketSnapshot(instrument("BTCUSD"))
        );
        server.verify();
    }

    @Test
    void nonEquityOrNonUsdInstrumentIsRejectedEvenIfItsSymbolIsConfigured() {
        properties.setSupportedUsEquitySymbols(Set.of("AAPL", "GBPUSD"));
        InstrumentEntity instrument = instrument("GBPUSD");
        instrument.setAssetClass("FX");
        instrument.setCurrency("GBP");

        assertThrows(
                UnsupportedInstrumentException.class,
                () -> client.getCurrentMarketSnapshot(instrument)
        );
        server.verify();
    }

    @Test
    void missingCredentialsFailOnlyWhenMarketDataIsInvoked() {
        properties.setApiKey("");
        properties.setSecretKey("");

        assertThrows(
                MarketDataConfigurationException.class,
                () -> client.getCurrentMarketSnapshot(instrument("AAPL"))
        );
        server.verify();
    }

    private void respond(String body) {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private InstrumentEntity instrument(String symbol) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(1L);
        instrument.setSymbol(symbol);
        instrument.setInstrumentName(symbol);
        instrument.setAssetClass("Equity");
        instrument.setCurrency("USD");
        instrument.setTradable(true);
        return instrument;
    }
}
