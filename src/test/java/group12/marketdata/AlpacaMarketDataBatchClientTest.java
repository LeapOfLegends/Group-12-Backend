package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.marketdata.exception.UnsupportedInstrumentException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaMarketDataBatchClientTest {

    private static final String URL =
            "https://data.alpaca.markets/v2/stocks/snapshots"
                    + "?symbols=AAPL,MSFT&feed=iex";

    private MockRestServiceServer server;
    private AlpacaMarketDataClient client;

    @BeforeEach
    void setUp() {
        AlpacaProperties properties = new AlpacaProperties();
        properties.setApiKey("test-api-key");
        properties.setSecretKey("test-secret-key");
        properties.setFeed("iex");
        properties.setSupportedUsEquitySymbols(Set.of("AAPL", "MSFT"));

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://data.alpaca.markets");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AlpacaMarketDataClient(builder.build(), properties);
    }

    @Test
    void retrievesTwoSymbolsInOneAuthenticatedIexRequestAndMapsSnapshots() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("APCA-API-KEY-ID", "test-api-key"))
                .andExpect(header("APCA-API-SECRET-KEY", "test-secret-key"))
                .andRespond(withSuccess("""
                        {
                          "AAPL": {
                            "latestQuote": {
                              "bp": 201.123456789012345678,
                              "ap": 201.223456789012345679,
                              "t": "2026-09-29T15:00:00.123456789Z"
                            },
                            "latestTrade": {
                              "p": 201.173456789012345678,
                              "t": "2026-09-29T15:00:01.987654321Z"
                            }
                          },
                          "MSFT": {
                            "latestTrade": {
                              "p": 450.25,
                              "t": "2026-09-29T15:00:02Z"
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        Map<String, MarketSnapshot> result = client.getCurrentMarketSnapshots(
                List.of(instrument("MSFT"), instrument("AAPL"))
        );

        assertEquals(Set.of("AAPL", "MSFT"), result.keySet());
        MarketSnapshot aapl = result.get("AAPL");
        assertEquals(new BigDecimal("201.123456789012345678"), aapl.bidPrice());
        assertEquals(new BigDecimal("201.223456789012345679"), aapl.askPrice());
        assertEquals(new BigDecimal("201.173456789012345678"), aapl.lastPrice());
        assertEquals(
                OffsetDateTime.parse("2026-09-29T15:00:00.123456Z"),
                aapl.quoteAsOf()
        );
        assertEquals(
                OffsetDateTime.parse("2026-09-29T15:00:01.987654Z"),
                aapl.lastTradeAsOf()
        );
        assertFalse(result.get("MSFT").hasQuote());
        assertTrue(result.get("MSFT").hasLastTrade());
        server.verify();
    }

    @Test
    void unusableAndUnknownSymbolsDoNotDiscardValidRequestedSnapshots() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {
                  "AAPL": {
                    "latestQuote": {"bp": 201.10, "ap": 201.20,
                                    "t": "2026-09-29T15:00:00Z"}
                  },
                  "MSFT": {
                    "latestQuote": {"bp": 0, "ap": 450.20,
                                    "t": "2026-09-29T15:00:00Z"}
                  },
                  "UNKNOWN": {
                    "latestTrade": {"p": 10.00, "t": "2026-09-29T15:00:00Z"}
                  }
                }
                """, MediaType.APPLICATION_JSON));

        Map<String, MarketSnapshot> result = client.getCurrentMarketSnapshots(
                List.of(instrument("AAPL"), instrument("MSFT"))
        );

        assertEquals(Set.of("AAPL"), result.keySet());
        assertTrue(result.get("AAPL").hasQuote());
    }

    @Test
    void singleInstrumentRequestRejectsNonTradableInstrumentWithoutHttp() {
        InstrumentEntity instrument = instrument("AAPL");
        instrument.setTradable(false);

        assertThrows(
                UnsupportedInstrumentException.class,
                () -> client.getCurrentMarketSnapshot(instrument)
        );
        server.verify();
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
