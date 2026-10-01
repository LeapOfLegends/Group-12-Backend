package group12.marketdata;

import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "ALPACA_LIVE_TEST", matches = "(?i:true)")
class MarketDataLiveRefreshTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("market_data_live_test")
                    .withUsername("market_data_live_test")
                    .withPassword("market_data_live_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("alpaca.api-key", () -> environmentVariable("ALPACA_API_KEY"));
        registry.add("alpaca.secret-key", () -> environmentVariable("ALPACA_SECRET_KEY"));
        registry.add("alpaca.feed", () -> "iex");
        registry.add("alpaca.supported-us-equity-symbols", () -> "AAPL");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private MarketDataBatchRefreshService batchRefreshService;

    @Autowired
    private MarketSnapshotPersistenceService persistenceService;

    private Long instrumentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE holdings, orders, clients, instruments RESTART IDENTITY CASCADE"
        );
        instrumentId = jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    symbol, instrument_name, asset_class, currency, is_tradable
                )
                VALUES ('AAPL', 'Apple Inc.', 'Equity', 'USD', TRUE)
                RETURNING instrument_id
                """, Long.class);
    }

    @Test
    void refreshesAaplThroughRealProviderAndPersistsAvailableGroups() {
        InstrumentEntity initial = instrumentRepository.findById(instrumentId).orElseThrow();
        assertNull(initial.getBidPrice());
        assertNull(initial.getAskPrice());
        assertNull(initial.getLastPrice());
        assertNull(initial.getQuoteAsOf());
        assertNull(initial.getLastTradeAsOf());

        assertEquals(1, batchRefreshService.refreshEligibleInstruments());

        InstrumentEntity persisted = instrumentRepository.findById(instrumentId).orElseThrow();
        assertLiveGroupsWerePersisted(persisted);
        verifyAbsentGroupsPreserveExistingValues();
    }

    private void assertLiveGroupsWerePersisted(InstrumentEntity persisted) {
        boolean hasQuote = persisted.getBidPrice() != null
                && persisted.getAskPrice() != null
                && persisted.getQuoteAsOf() != null;
        boolean hasTrade = persisted.getLastPrice() != null
                && persisted.getLastTradeAsOf() != null;
        assertTrue(hasQuote || hasTrade);

        if (hasQuote) {
            assertPositiveDatabasePrice(persisted.getBidPrice());
            assertPositiveDatabasePrice(persisted.getAskPrice());
            assertNotNull(persisted.getQuoteAsOf());
        } else {
            assertNull(persisted.getBidPrice());
            assertNull(persisted.getAskPrice());
            assertNull(persisted.getQuoteAsOf());
        }

        if (hasTrade) {
            assertPositiveDatabasePrice(persisted.getLastPrice());
            assertNotNull(persisted.getLastTradeAsOf());
        } else {
            assertNull(persisted.getLastPrice());
            assertNull(persisted.getLastTradeAsOf());
        }
    }

    private void verifyAbsentGroupsPreserveExistingValues() {
        OffsetDateTime baselineAt = OffsetDateTime.parse("2099-01-01T00:00:00Z");
        BigDecimal baselineBid = new BigDecimal("100.00000000");
        BigDecimal baselineAsk = new BigDecimal("100.10000000");
        BigDecimal baselineLast = new BigDecimal("100.05000000");
        instrumentRepository.updateQuoteSnapshot(
                instrumentId, baselineBid, baselineAsk, baselineAt
        );
        instrumentRepository.updateLastTradeSnapshot(
                instrumentId, baselineLast, baselineAt
        );

        OffsetDateTime quoteAt = baselineAt.plusSeconds(1);
        persistenceService.persist(instrumentId, new MarketSnapshot(
                new BigDecimal("101.00000000"),
                new BigDecimal("101.10000000"),
                null,
                quoteAt,
                null
        ));

        InstrumentEntity afterQuoteOnly =
                instrumentRepository.findById(instrumentId).orElseThrow();
        assertEquals(baselineLast, afterQuoteOnly.getLastPrice());
        assertEquals(baselineAt.toInstant(), afterQuoteOnly.getLastTradeAsOf().toInstant());

        BigDecimal quoteBidBeforeTradeOnly = afterQuoteOnly.getBidPrice();
        BigDecimal quoteAskBeforeTradeOnly = afterQuoteOnly.getAskPrice();
        OffsetDateTime quoteTimeBeforeTradeOnly = afterQuoteOnly.getQuoteAsOf();
        OffsetDateTime tradeAt = quoteAt.plusSeconds(1);
        persistenceService.persist(instrumentId, new MarketSnapshot(
                null,
                null,
                new BigDecimal("101.05000000"),
                null,
                tradeAt
        ));

        InstrumentEntity afterTradeOnly =
                instrumentRepository.findById(instrumentId).orElseThrow();
        assertEquals(quoteBidBeforeTradeOnly, afterTradeOnly.getBidPrice());
        assertEquals(quoteAskBeforeTradeOnly, afterTradeOnly.getAskPrice());
        assertEquals(
                quoteTimeBeforeTradeOnly.toInstant(),
                afterTradeOnly.getQuoteAsOf().toInstant()
        );
    }

    private void assertPositiveDatabasePrice(BigDecimal databasePrice) {
        assertNotNull(databasePrice);
        assertTrue(databasePrice.signum() > 0);
    }

    private static String environmentVariable(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
