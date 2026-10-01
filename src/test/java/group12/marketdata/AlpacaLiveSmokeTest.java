package group12.marketdata;

import group12.Entities.InstrumentEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "ALPACA_LIVE_TEST", matches = "(?i:true)")
class AlpacaLiveSmokeTest {

    @Test
    void retrievesAndMapsLiveAaplIexSnapshot() {
        AlpacaProperties properties = liveProperties();
        RestClient restClient = new AlpacaMarketDataConfiguration()
                .alpacaMarketDataRestClient(properties);
        AlpacaMarketDataClient client = new AlpacaMarketDataClient(restClient, properties);

        MarketSnapshot snapshot = client.getCurrentMarketSnapshots(List.of(aapl())).get("AAPL");

        assertNotNull(snapshot);
        assertTrue(snapshot.hasQuote() || snapshot.hasLastTrade());
        if (snapshot.hasQuote()) {
            assertPositive(snapshot.bidPrice());
            assertPositive(snapshot.askPrice());
            assertNotNull(snapshot.quoteAsOf());
        }
        if (snapshot.hasLastTrade()) {
            assertPositive(snapshot.lastPrice());
            assertNotNull(snapshot.lastTradeAsOf());
        }

        System.out.printf(
                "AAPL market values: bid=%s, ask=%s, quoteAsOf=%s, "
                        + "last=%s, lastTradeAsOf=%s%n",
                snapshot.bidPrice(),
                snapshot.askPrice(),
                snapshot.quoteAsOf(),
                snapshot.lastPrice(),
                snapshot.lastTradeAsOf()
        );
    }

    private AlpacaProperties liveProperties() {
        AlpacaProperties properties = new AlpacaProperties();
        properties.setApiKey(requiredEnvironmentVariable("ALPACA_API_KEY"));
        properties.setSecretKey(requiredEnvironmentVariable("ALPACA_SECRET_KEY"));
        properties.setSupportedUsEquitySymbols(Set.of("AAPL"));
        return properties;
    }

    private InstrumentEntity aapl() {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(1L);
        instrument.setSymbol("AAPL");
        instrument.setInstrumentName("Apple Inc.");
        instrument.setAssetClass("Equity");
        instrument.setCurrency("USD");
        instrument.setTradable(true);
        return instrument;
    }

    private void assertPositive(BigDecimal value) {
        assertNotNull(value);
        assertTrue(value.signum() > 0);
    }

    private String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for live Alpaca tests");
        }
        return value;
    }
}
