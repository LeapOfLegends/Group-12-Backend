package group12.marketdata.alpaca;

import group12.Entities.MarketCode;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaHistoricalDataClientTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-20T13:30:00Z");
    private static final OffsetDateTime END = OffsetDateTime.parse("2026-10-20T14:00:00Z");

    private MockRestServiceServer server;
    private AlpacaHistoricalDataClient client;

    @BeforeEach
    void setUp() {
        AlpacaProperties properties = new AlpacaProperties();
        properties.setApiKey("test-api-key");
        properties.setSecretKey("test-secret-key");
        properties.setFeed("iex");
        properties.setSupportedUsEquitySymbols(Set.of("AAPL"));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://data.alpaca.markets");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AlpacaHistoricalDataClient(builder.build(), properties);
    }

    @Test
    void requestsSingleSymbolRawIexBarsWithCredentialsAndMapsUtcValues() {
        expectBaseRequest()
                .andExpect(header("APCA-API-KEY-ID", "test-api-key"))
                .andExpect(header("APCA-API-SECRET-KEY", "test-secret-key"))
                .andRespond(withSuccess("""
                        {"bars":[
                          {"t":"2026-10-20T09:30:00-04:00","c":250.25},
                          {"t":"2026-10-20T13:35:00Z","c":250.80}
                        ],"next_page_token":null}
                        """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = request(HistoricalTimeframe.FIVE_MINUTES);

        assertThat(bars).extracting(HistoricalBar::barStart).containsExactly(
                OffsetDateTime.parse("2026-10-20T13:30:00Z"),
                OffsetDateTime.parse("2026-10-20T13:35:00Z")
        );
        assertThat(bars).extracting(HistoricalBar::closePrice)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("250.25"), new BigDecimal("250.80"));
        assertThat(bars).allMatch(HistoricalBar::complete);
        server.verify();
    }

    @Test
    void followsNextPageTokensAndDeduplicatesARepeatedBoundaryBar() {
        expectBaseRequest().andRespond(withSuccess("""
                {"bars":[{"t":"2026-10-20T13:30:00Z","c":250.25}],
                 "next_page_token":"page-two"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(queryParam("page_token", "page-two"))
                .andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-10-20T13:30:00Z","c":250.30},
                  {"t":"2026-10-20T13:35:00Z","c":250.80}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = request(HistoricalTimeframe.FIVE_MINUTES);

        assertThat(bars).hasSize(2);
        assertThat(bars.getFirst().closePrice()).isEqualByComparingTo("250.30");
        server.verify();
    }

    @Test
    void emptyResponseIsSuccessful() {
        expectBaseRequest().andRespond(withSuccess(
                "{\"bars\":[],\"next_page_token\":null}", MediaType.APPLICATION_JSON
        ));

        assertThat(request(HistoricalTimeframe.FIVE_MINUTES)).isEmpty();
    }

    @Test
    void invalidAndMissingPricesAreSkippedWithoutDiscardingValidBars() {
        expectBaseRequest().andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-10-20T13:30:00Z","c":0},
                  {"t":"2026-10-20T13:35:00Z"},
                  {"t":"2026-10-20T13:40:00Z","c":251.00}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        assertThat(request(HistoricalTimeframe.FIVE_MINUTES)).singleElement()
                .extracting(HistoricalBar::closePrice)
                .satisfies(price -> assertThat(price).isEqualByComparingTo("251.00"));
    }

    @Test
    void unfinishedIntradayCandleIsExcluded() {
        expectBaseRequest(OffsetDateTime.parse("2026-10-20T13:37:00Z"))
                .andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-10-20T13:30:00Z","c":250.25},
                  {"t":"2026-10-20T13:35:00Z","c":250.80}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        List<HistoricalBar> bars = client.getHistoricalBars(
                instrument(), HistoricalTimeframe.FIVE_MINUTES, START,
                OffsetDateTime.parse("2026-10-20T13:37:00Z")
        );

        assertThat(bars).extracting(HistoricalBar::barStart)
                .containsExactly(OffsetDateTime.parse("2026-10-20T13:30:00Z"));
    }

    @Test
    void unfinishedDailyCandleIsExcludedBeforeTheRegularClose() {
        OffsetDateTime start = OffsetDateTime.parse("2026-10-19T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-10-20T19:00:00Z");
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-10-19T04:00:00Z","c":249},
                  {"t":"2026-10-20T04:00:00Z","c":250}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getHistoricalBars(
                instrument(), HistoricalTimeframe.ONE_DAY, start, end
        )).extracting(HistoricalBar::closePrice).containsExactly(new BigDecimal("249"));
    }

    @Test
    void currentWeeklyAndMonthlyCandlesAreExcluded() {
        OffsetDateTime start = OffsetDateTime.parse("2026-08-01T00:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-10-22T18:00:00Z");
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-10-12T04:00:00Z","c":250},
                  {"t":"2026-10-19T04:00:00Z","c":251}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andRespond(withSuccess("""
                {"bars":[
                  {"t":"2026-09-01T04:00:00Z","c":240},
                  {"t":"2026-10-01T04:00:00Z","c":250}
                ],"next_page_token":null}
                """, MediaType.APPLICATION_JSON));

        assertThat(client.getHistoricalBars(
                instrument(), HistoricalTimeframe.ONE_WEEK, start, end
        )).extracting(HistoricalBar::closePrice).containsExactly(new BigDecimal("250"));
        assertThat(client.getHistoricalBars(
                instrument(), HistoricalTimeframe.ONE_MONTH, start, end
        )).extracting(HistoricalBar::closePrice).containsExactly(new BigDecimal("240"));
    }

    @Test
    void malformedHistoryEnvelopeIsAProviderFailure() {
        expectBaseRequest().andRespond(withSuccess(
                "{\"unexpected\":[]}", MediaType.APPLICATION_JSON
        ));

        assertThatThrownBy(() -> request(HistoricalTimeframe.FIVE_MINUTES))
                .isInstanceOf(MarketDataProviderException.class);
    }

    @Test
    void rateLimitRemainsDistinctFromGeneralProviderFailures() {
        expectBaseRequest().andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> request(HistoricalTimeframe.FIVE_MINUTES))
                .isInstanceOf(MarketDataRateLimitException.class);
    }

    @Test
    void authenticationAndServerFailuresAreProviderFailures() {
        expectBaseRequest().andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> request(HistoricalTimeframe.FIVE_MINUTES))
                .isInstanceOf(MarketDataProviderException.class);
    }

    @Test
    void timeoutAndServerFailureUseGeneralProviderFailure() {
        expectBaseRequest().andRespond(withException(
                new SocketTimeoutException("simulated timeout")
        ));
        assertThatThrownBy(() -> request(HistoricalTimeframe.FIVE_MINUTES))
                .isInstanceOf(MarketDataProviderException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void unsupportedInstrumentIsRejectedWithoutAnHttpRequest() {
        MarketDataRequest unsupported = new MarketDataRequest(
                "MSFT", "Equity", "USD", MarketCode.IEX
        );

        assertThat(client.supports(unsupported)).isFalse();
        assertThatThrownBy(() -> client.getHistoricalBars(
                unsupported, HistoricalTimeframe.ONE_DAY, START, END
        )).isInstanceOf(MarketDataProviderException.class);
        server.verify();
    }

    private org.springframework.test.web.client.ResponseActions expectBaseRequest() {
        return expectBaseRequest(END);
    }

    private org.springframework.test.web.client.ResponseActions expectBaseRequest(
            OffsetDateTime end
    ) {
        return server.expect(requestTo(containsString("/v2/stocks/AAPL/bars")))
                .andExpect(queryParam("timeframe", "5Min"))
                .andExpect(queryParam("start", START.toString()))
                .andExpect(queryParam("end", end.toString()))
                .andExpect(queryParam("feed", "iex"))
                .andExpect(queryParam("adjustment", "raw"))
                .andExpect(queryParam("limit", "10000"));
    }

    private List<HistoricalBar> request(HistoricalTimeframe timeframe) {
        return client.getHistoricalBars(instrument(), timeframe, START, END);
    }

    private MarketDataRequest instrument() {
        return new MarketDataRequest(" aapl ", "Equity", "USD", MarketCode.IEX);
    }
}
