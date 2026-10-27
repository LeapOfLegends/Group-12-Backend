package group12.marketdata.biquote;

import group12.Entities.MarketCode;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import group12.marketdata.exception.PartialHistoricalDataException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BiquoteHistoricalDataClientTest {

    private MockRestServiceServer server;
    private BiquoteHistoricalDataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://biquote.io");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BiquoteHistoricalDataClient(builder.build());
    }

    @ParameterizedTest
    @CsvSource({
            "EUR/USD,EURUSD", "USD/JPY,USDJPY", "GBP/USD,GBPUSD",
            "AUD/USD,AUDUSD", "USD/CAD,USDCAD", "USD/INR,USDINR"
    })
    void mapsEverySupportedFxSymbol(String internal, String provider) {
        server.expect(requestTo(containsString("/api/" + provider + "/ohlc")))
                .andRespond(emptyResponse(provider, "1d"));

        assertThat(client.supports(instrument(internal))).isTrue();
        assertThat(client.getHistoricalBars(
                instrument(internal), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2026-01-03T00:00:00Z")
        )).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"FIVE_MINUTES,5m", "THIRTY_MINUTES,30m", "ONE_DAY,1d"})
    void sendsNativeIntervalAndBoundedWindowParameters(
            HistoricalTimeframe timeframe,
            String interval
    ) {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andExpect(queryParam("interval", interval))
                .andExpect(queryParam("from", "2026-01-01T00:00:00Z"))
                .andExpect(queryParam("to", "2026-01-02T00:00:00Z"))
                .andExpect(queryParam("limit", "1000"))
                .andRespond(emptyResponse("EURUSD", interval));

        client.getHistoricalBars(
                instrument("EUR/USD"), timeframe,
                time("2026-01-01T00:00:00Z"), time("2026-01-02T00:00:00Z")
        );
        server.verify();
    }

    @Test
    void parsesCompletedBarsSkipsOpenAndInvalidRecordsAndPreservesGaps() {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withSuccess("""
                        {"symbol":"EURUSD","interval":"1d","bars":[
                          {"openTime":"2026-01-02T00:00:00Z","close":1.10,"isOpen":false},
                          {"openTime":"2026-01-05T00:00:00Z","close":1.12,"isOpen":false},
                          {"openTime":"2026-01-06T00:00:00Z","close":1.13,"isOpen":true},
                          {"openTime":"bad","close":1.14,"isOpen":false},
                          {"openTime":"2026-01-07T00:00:00Z","close":0,"isOpen":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2026-01-08T00:00:00Z")
        );

        assertThat(bars).extracting(HistoricalBar::barStart).containsExactly(
                time("2026-01-02T00:00:00Z"), time("2026-01-05T00:00:00Z")
        );
    }

    @Test
    void aggregatesCompletedIsoWeekUsingFinalDailyClose() {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andExpect(queryParam("interval", "1d"))
                .andRespond(withSuccess("""
                        {"symbol":"EURUSD","interval":"1d","bars":[
                          {"openTime":"2026-01-05T00:00:00Z","close":1.10,"isOpen":false},
                          {"openTime":"2026-01-08T00:00:00Z","close":1.11,"isOpen":false},
                          {"openTime":"2026-01-09T00:00:00Z","close":1.12,"isOpen":false},
                          {"openTime":"2026-01-12T00:00:00Z","close":1.13,"isOpen":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_WEEK,
                time("2026-01-05T00:00:00Z"), time("2026-01-14T00:00:00Z")
        );

        assertThat(bars).singleElement().satisfies(bar -> {
            assertThat(bar.barStart()).isEqualTo(time("2026-01-05T00:00:00Z"));
            assertThat(bar.closePrice()).isEqualByComparingTo("1.12");
        });
    }

    @Test
    void aggregatesCompletedMonthsUsingFinalCloseAndExcludesCurrentMonth() {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withSuccess("""
                        {"symbol":"EURUSD","interval":"1d","bars":[
                          {"openTime":"2026-01-30T00:00:00Z","close":1.10,"isOpen":false},
                          {"openTime":"2026-02-27T00:00:00Z","close":1.20,"isOpen":false},
                          {"openTime":"2026-03-13T00:00:00Z","close":1.30,"isOpen":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_MONTH,
                time("2026-01-01T00:00:00Z"), time("2026-03-15T00:00:00Z")
        );

        assertThat(bars).hasSize(2);
        assertThat(bars.get(0).closePrice()).isEqualByComparingTo("1.10");
        assertThat(bars.get(1).closePrice()).isEqualByComparingTo("1.20");
    }

    @Test
    void chunksLargeNativeWindowsAtTheProviderLimit() {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andExpect(queryParam("from", "2026-01-01T00:00:00Z"))
                .andExpect(queryParam("to", "2028-09-26T00:00:00Z"))
                .andRespond(emptyResponse("EURUSD", "1d"));
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andExpect(queryParam("from", "2028-09-26T00:00:00Z"))
                .andExpect(queryParam("to", "2029-01-01T00:00:00Z"))
                .andRespond(emptyResponse("EURUSD", "1d"));

        client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2029-01-01T00:00:00Z")
        );
        server.verify();
    }

    @Test
    void laterChunkFailureReturnsValidatedEarlierBarsAsKnownPartialHistory() {
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withSuccess("""
                        {"symbol":"EURUSD","interval":"1d","bars":[
                          {"openTime":"2026-01-02T00:00:00Z","close":1.10,"isOpen":false}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2029-01-01T00:00:00Z")
        )).isInstanceOfSatisfying(PartialHistoricalDataException.class, exception ->
                assertThat(exception.getBars()).singleElement().satisfies(bar ->
                        assertThat(bar.closePrice()).isEqualByComparingTo("1.10")
                )
        );
    }

    @Test
    void rejectsUnsupportedSymbolsMalformedResponsesRateLimitsAndTimeouts() {
        assertThat(client.supports(instrument("EUR/CHF"))).isFalse();
        assertThatThrownBy(() -> client.getHistoricalBars(
                instrument("EUR/CHF"), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2026-01-02T00:00:00Z")
        )).isInstanceOf(MarketDataProviderException.class);

        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(containsString("/api/EURUSD/ohlc")))
                .andRespond(withException(new SocketTimeoutException("simulated")));

        assertThatThrownBy(() -> dailyRequest()).isInstanceOf(MarketDataProviderException.class);
        assertThatThrownBy(() -> dailyRequest()).isInstanceOf(MarketDataRateLimitException.class);
        assertThatThrownBy(() -> dailyRequest())
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("timed out");
    }

    private void dailyRequest() {
        client.getHistoricalBars(
                instrument("EUR/USD"), HistoricalTimeframe.ONE_DAY,
                time("2026-01-01T00:00:00Z"), time("2026-01-02T00:00:00Z")
        );
    }

    private org.springframework.test.web.client.ResponseCreator emptyResponse(
            String symbol,
            String interval
    ) {
        return withSuccess(
                "{\"symbol\":\"" + symbol + "\",\"interval\":\"" + interval
                        + "\",\"bars\":[]}",
                MediaType.APPLICATION_JSON
        );
    }

    private MarketDataRequest instrument(String symbol) {
        return new MarketDataRequest(symbol, "FOREX", "USD", MarketCode.FX);
    }

    private OffsetDateTime time(String value) {
        return OffsetDateTime.parse(value);
    }
}
