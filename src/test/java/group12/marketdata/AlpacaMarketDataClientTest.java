package group12.marketdata;

import group12.marketdata.exception.MarketDataConfigurationException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaMarketDataClientTest {

    private static final String URL =
            "https://data.alpaca.markets/v2/stocks/snapshots?symbols=AAPL&feed=iex";

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
    void malformedBatchResponseIsAProviderFailure() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThrows(MarketDataProviderException.class, this::requestAapl);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationAndAuthorizationFailuresAreProviderFailures(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThrows(MarketDataProviderException.class, this::requestAapl);
    }

    @Test
    void rateLimitFailureRemainsDistinct() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(MarketDataRateLimitException.class, this::requestAapl);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 502, 503})
    void providerHttpFailuresUseGeneralProviderFailure(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThrows(MarketDataProviderException.class, this::requestAapl);
    }

    @Test
    void timeoutUsesGeneralProviderFailure() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("simulated timeout")));

        assertThrows(MarketDataProviderException.class, this::requestAapl);
    }

    @Test
    void unsupportedInstrumentMakesNoHttpRequest() {
        assertThrows(
                MarketDataProviderException.class,
                () -> client.getCurrentMarketSnapshots(List.of(instrument("BTCUSD")))
        );
        server.verify();
    }

    @Test
    void nonEquityOrNonUsdInstrumentIsRejectedEvenIfItsSymbolIsConfigured() {
        properties.setSupportedUsEquitySymbols(Set.of("AAPL", "GBPUSD"));
        MarketDataRequest instrument = new MarketDataRequest("GBPUSD", "FX", "GBP");

        assertThrows(
                MarketDataProviderException.class,
                () -> client.getCurrentMarketSnapshots(List.of(instrument))
        );
        server.verify();
    }

    @Test
    void supportIsDeterminedFromProviderNeutralAttributesAndAlpacaAllowlist() {
        assertTrue(client.supports(instrument(" aapl ")));
        assertFalse(client.supports(instrument("MSFT")));
        assertFalse(client.supports(new MarketDataRequest("AAPL", "Crypto", "USD")));
        assertFalse(client.supports(new MarketDataRequest("AAPL", "Equity", "GBP")));
        assertFalse(client.supports(null));
    }

    @Test
    void providerEnforcesItsFiftySymbolBatchLimitWithoutHttpRequest() {
        Set<String> symbols = IntStream.rangeClosed(1, 51)
                .mapToObj(number -> "SYM" + number)
                .collect(java.util.stream.Collectors.toSet());
        properties.setSupportedUsEquitySymbols(symbols);
        List<MarketDataRequest> requests = symbols.stream()
                .map(this::instrument)
                .toList();

        assertThrows(
                MarketDataConfigurationException.class,
                () -> client.getCurrentMarketSnapshots(requests)
        );
        server.verify();
    }

    @Test
    void missingCredentialsFailOnlyWhenBatchMarketDataIsInvoked() {
        properties.setApiKey("");
        properties.setSecretKey("");

        assertThrows(MarketDataConfigurationException.class, this::requestAapl);
        server.verify();
    }

    private void requestAapl() {
        client.getCurrentMarketSnapshots(List.of(instrument("AAPL")));
    }

    private MarketDataRequest instrument(String symbol) {
        return new MarketDataRequest(symbol, "Equity", "USD");
    }
}
