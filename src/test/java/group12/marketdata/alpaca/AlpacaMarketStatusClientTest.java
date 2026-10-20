package group12.marketdata.alpaca;

import group12.marketdata.exception.MarketDataConfigurationException;
import group12.marketdata.exception.MarketDataProviderException;
import group12.marketdata.exception.MarketDataRateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AlpacaMarketStatusClientTest {

    private static final String URL = "https://api.alpaca.markets/v2/clock";

    private AlpacaProperties properties;
    private MockRestServiceServer server;
    private AlpacaMarketStatusClient client;

    @BeforeEach
    void setUp() {
        properties = new AlpacaProperties();
        properties.setApiKey("test-api-key");
        properties.setSecretKey("test-secret-key");

        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.alpaca.markets");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AlpacaMarketStatusClient(builder.build(), properties);
    }

    @Test
    void parsesOpenClockState() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"is_open\":true}", MediaType.APPLICATION_JSON));

        assertTrue(client.isIexOpen());
    }

    @Test
    void parsesClosedClockState() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"is_open\":false}", MediaType.APPLICATION_JSON));

        assertFalse(client.isIexOpen());
    }

    @Test
    void malformedResponseIsProviderFailure() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(MarketDataProviderException.class, () -> client.isIexOpen());
    }

    @Test
    void rateLimitFailureRemainsDistinct() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThrows(MarketDataRateLimitException.class, () -> client.isIexOpen());
    }

    @Test
    void timeoutIsProviderFailure() {
        server.expect(requestTo(URL))
                .andRespond(withException(new SocketTimeoutException("simulated timeout")));

        assertThrows(MarketDataProviderException.class, () -> client.isIexOpen());
    }

    @Test
    void missingCredentialsFailBeforeHttpCall() {
        properties.setApiKey("");
        properties.setSecretKey("");

        assertThrows(MarketDataConfigurationException.class, () -> client.isIexOpen());
        server.verify();
    }
}
