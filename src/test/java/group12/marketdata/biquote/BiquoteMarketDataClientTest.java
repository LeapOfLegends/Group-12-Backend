package group12.marketdata.biquote;

import group12.Entities.MarketCode;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.MarketSnapshot;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BiquoteMarketDataClientTest {

    private static final String EUR_USD_URL =
            "https://biquote.io/api/latest?symbols=EURUSD";
    private static final String TWO_SYMBOL_URL =
            "https://biquote.io/api/latest?symbols=EURUSD&symbols=USDJPY";

    private MockRestServiceServer server;
    private BiquoteMarketDataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://biquote.io");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BiquoteMarketDataClient(builder.build());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "EUR/USD", "USD/JPY", "GBP/USD", "AUD/USD", "USD/CAD", "USD/INR"
    })
    void supportsAllSeededFxPairs(String symbol) {
        assertTrue(client.supports(fxInstrument(" " + symbol.toLowerCase() + " ")));
    }

    @ParameterizedTest
    @MethodSource("unsupportedRequests")
    void supportsOnlySeededFxPairs(MarketDataRequest request) {
        assertFalse(client.supports(request));
    }

    @Test
    void mapsBiquoteQuoteToMarketSnapshotWithoutFabricatingLastTrade() {
        server.expect(once(), requestTo(EUR_USD_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "EURUSD": {
                            "symbol": "EURUSD",
                            "bid": 1.08542,
                            "ask": 1.08558,
                            "last": 1.08550,
                            "time": "2026-02-24T10:30:00.123456789+02:00",
                            "source": "MT5",
                            "type": "Forex",
                            "additionalField": "ignored"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        MarketSnapshot snapshot = client.getCurrentMarketSnapshots(
                List.of(fxInstrument("EUR/USD"))
        ).get("EUR/USD");

        assertEquals(new BigDecimal("1.08542"), snapshot.bidPrice());
        assertEquals(new BigDecimal("1.08558"), snapshot.askPrice());
        assertEquals(
                OffsetDateTime.parse("2026-02-24T08:30:00.123456Z"),
                snapshot.quoteAsOf()
        );
        assertNull(snapshot.lastPrice());
        assertNull(snapshot.lastTradeAsOf());
        server.verify();
    }

    @Test
    void batchesMultipleFxPairsIntoOneRequestWithRepeatedParameters() {
        server.expect(once(), requestTo(TWO_SYMBOL_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "EURUSD": {
                            "bid": 1.08542,
                            "ask": 1.08558,
                            "time": "2026-02-24T10:30:00Z"
                          },
                          "USDJPY": {
                            "bid": 149.10,
                            "ask": 149.12,
                            "time": "2026-02-24T10:30:01Z"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, MarketSnapshot> snapshots = client.getCurrentMarketSnapshots(
                List.of(fxInstrument("USD/JPY"), fxInstrument("EUR/USD"))
        );

        assertEquals(Set.of("EUR/USD", "USD/JPY"), snapshots.keySet());
        server.verify();
    }

    @Test
    void ignoresUnknownResponseSymbols() {
        server.expect(requestTo(EUR_USD_URL)).andRespond(withSuccess("""
                {
                  "EURUSD": {
                    "bid": 1.08542,
                    "ask": 1.08558,
                    "time": "2026-02-24T10:30:00Z"
                  },
                  "USDJPY": {
                    "bid": 149.10,
                    "ask": 149.12,
                    "time": "2026-02-24T10:30:00Z"
                  },
                  "EURCHF": {
                    "bid": 0.95,
                    "ask": 0.96,
                    "time": "2026-02-24T10:30:00Z"
                  }
                }
                """, MediaType.APPLICATION_JSON));

        Map<String, MarketSnapshot> snapshots = client.getCurrentMarketSnapshots(
                List.of(fxInstrument("EUR/USD"))
        );

        assertEquals(Set.of("EUR/USD"), snapshots.keySet());
    }

    @Test
    void skipsInvalidIndividualQuoteWithoutDiscardingValidQuote() {
        server.expect(requestTo(TWO_SYMBOL_URL)).andRespond(withSuccess("""
                {
                  "EURUSD": {
                    "bid": 1.08542,
                    "ask": 1.08558,
                    "time": "2026-02-24T10:30:00Z"
                  },
                  "USDJPY": {
                    "bid": 149.12,
                    "ask": 149.10,
                    "time": "2026-02-24T10:30:00Z"
                  }
                }
                """, MediaType.APPLICATION_JSON));

        Map<String, MarketSnapshot> snapshots = client.getCurrentMarketSnapshots(
                List.of(fxInstrument("EUR/USD"), fxInstrument("USD/JPY"))
        );

        assertEquals(Set.of("EUR/USD"), snapshots.keySet());
    }

    @Test
    void explicitlyStaleAndClosedQuotesAreSkipped() {
        server.expect(requestTo(TWO_SYMBOL_URL)).andRespond(withSuccess("""
                {
                  "EURUSD": {
                    "bid": 1.08542,
                    "ask": 1.08558,
                    "time": "2026-02-24T10:30:00Z",
                    "stale": true
                  },
                  "USDJPY": {
                    "bid": 149.10,
                    "ask": 149.12,
                    "time": "2026-02-24T10:30:00Z",
                    "marketState": "closed"
                  }
                }
                """, MediaType.APPLICATION_JSON));

        assertTrue(client.getCurrentMarketSnapshots(List.of(
                fxInstrument("EUR/USD"),
                fxInstrument("USD/JPY")
        )).isEmpty());
    }

    @Test
    void malformedTopLevelResponseIsAProviderFailure() {
        server.expect(requestTo(EUR_USD_URL))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThrows(MarketDataProviderException.class, this::requestEurUsd);
    }

    @Test
    void rateLimitFailureRemainsDistinct() {
        server.expect(requestTo(EUR_USD_URL))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(MarketDataRateLimitException.class, this::requestEurUsd);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500, 502, 503})
    void providerHttpFailuresUseGeneralProviderFailure(int status) {
        server.expect(requestTo(EUR_USD_URL))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThrows(MarketDataProviderException.class, this::requestEurUsd);
    }

    @Test
    void timeoutUsesGeneralProviderFailure() {
        server.expect(requestTo(EUR_USD_URL))
                .andRespond(withException(new SocketTimeoutException("simulated timeout")));

        assertThrows(MarketDataProviderException.class, this::requestEurUsd);
    }

    private void requestEurUsd() {
        client.getCurrentMarketSnapshots(List.of(fxInstrument("EUR/USD")));
    }

    private MarketDataRequest fxInstrument(String symbol) {
        return new MarketDataRequest(symbol, "FOREX", "USD", MarketCode.FX);
    }

    private static Stream<Arguments> unsupportedRequests() {
        return Stream.of(
                Arguments.of(new MarketDataRequest(
                        "EUR/CHF", "FOREX", "CHF", MarketCode.FX
                )),
                Arguments.of(new MarketDataRequest(
                        "EUR/USD", "EQUITY", "USD", MarketCode.FX
                )),
                Arguments.of(new MarketDataRequest(
                        "EUR/USD", "FOREX", "USD", MarketCode.IEX
                )),
                Arguments.of(new MarketDataRequest(
                        " ", "FOREX", "USD", MarketCode.FX
                )),
                Arguments.of((MarketDataRequest) null)
        );
    }
}
