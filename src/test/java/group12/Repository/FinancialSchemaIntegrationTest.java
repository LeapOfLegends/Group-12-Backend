package group12.Repository;

import group12.Entities.HoldingEntity;
import group12.Entities.InstrumentEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class FinancialSchemaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("financial_schema_test")
                    .withUsername("financial_schema_test")
                    .withPassword("financial_schema_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private InstrumentRepository instrumentRepository;

    @Autowired
    private HoldingRepository holdingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long clientId;
    private Long instrumentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE holdings, orders, clients, instruments RESTART IDENTITY CASCADE"
        );
        clientId = insertClient();
        instrumentId = insertInstrument("NULLS");
    }

    @Test
    void instrument_canInitiallyHaveNullMarketPricesAndTimestamps() {
        InstrumentEntity instrument = instrumentRepository.findById(instrumentId).orElseThrow();

        assertNull(instrument.getBidPrice());
        assertNull(instrument.getAskPrice());
        assertNull(instrument.getLastPrice());
        assertNull(instrument.getQuoteAsOf());
        assertNull(instrument.getLastTradeAsOf());
    }

    @Test
    void instrument_positivePricesAndTimestampsPersistAsCoherentSnapshots() {
        OffsetDateTime quoteAsOf = OffsetDateTime.parse("2026-09-28T15:01:02.123456Z");
        OffsetDateTime lastTradeAsOf =
                OffsetDateTime.parse("2026-09-28T15:01:03.654321Z");

        int quoteRows = instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("123.45678901"),
                new BigDecimal("123.55678901"),
                quoteAsOf
        );
        int tradeRows = instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("123.50678901"),
                lastTradeAsOf
        );

        InstrumentEntity instrument = instrumentRepository.findBySymbol("NULLS").orElseThrow();

        assertEquals(1, quoteRows);
        assertEquals(1, tradeRows);
        assertEquals(new BigDecimal("123.45678901"), instrument.getBidPrice());
        assertEquals(new BigDecimal("123.55678901"), instrument.getAskPrice());
        assertEquals(new BigDecimal("123.50678901"), instrument.getLastPrice());
        assertEquals(
                quoteAsOf.toInstant(),
                instrument.getQuoteAsOf().toInstant()
        );
        assertEquals(
                lastTradeAsOf.toInstant(),
                instrument.getLastTradeAsOf().toInstant()
        );
    }

    @Test
    void quoteUpdate_changesQuoteTogetherAndPreservesLastTradeSnapshot() {
        OffsetDateTime initialQuoteAsOf = OffsetDateTime.parse("2026-09-28T15:00:00Z");
        OffsetDateTime newerQuoteAsOf = OffsetDateTime.parse("2026-09-28T15:00:01Z");
        OffsetDateTime lastTradeAsOf = OffsetDateTime.parse("2026-09-28T14:59:59Z");
        instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("10.00000000"),
                new BigDecimal("10.10000000"),
                initialQuoteAsOf
        );
        instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("10.05000000"),
                lastTradeAsOf
        );

        int rows = instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("10.20000000"),
                new BigDecimal("10.30000000"),
                newerQuoteAsOf
        );

        InstrumentEntity instrument = instrumentRepository.findById(instrumentId).orElseThrow();
        assertEquals(1, rows);
        assertEquals(new BigDecimal("10.20000000"), instrument.getBidPrice());
        assertEquals(new BigDecimal("10.30000000"), instrument.getAskPrice());
        assertEquals(newerQuoteAsOf.toInstant(), instrument.getQuoteAsOf().toInstant());
        assertEquals(new BigDecimal("10.05000000"), instrument.getLastPrice());
        assertEquals(lastTradeAsOf.toInstant(), instrument.getLastTradeAsOf().toInstant());
    }

    @Test
    void lastTradeUpdate_changesTradeTogetherAndPreservesQuoteSnapshot() {
        OffsetDateTime quoteAsOf = OffsetDateTime.parse("2026-09-28T15:00:00Z");
        OffsetDateTime initialTradeAsOf = OffsetDateTime.parse("2026-09-28T15:00:01Z");
        OffsetDateTime newerTradeAsOf = OffsetDateTime.parse("2026-09-28T15:00:02Z");
        instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("10.00000000"),
                new BigDecimal("10.10000000"),
                quoteAsOf
        );
        instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("10.05000000"),
                initialTradeAsOf
        );

        int rows = instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("10.07500000"),
                newerTradeAsOf
        );

        InstrumentEntity instrument = instrumentRepository.findById(instrumentId).orElseThrow();
        assertEquals(1, rows);
        assertEquals(new BigDecimal("10.07500000"), instrument.getLastPrice());
        assertEquals(newerTradeAsOf.toInstant(), instrument.getLastTradeAsOf().toInstant());
        assertEquals(new BigDecimal("10.00000000"), instrument.getBidPrice());
        assertEquals(new BigDecimal("10.10000000"), instrument.getAskPrice());
        assertEquals(quoteAsOf.toInstant(), instrument.getQuoteAsOf().toInstant());
    }

    @Test
    void olderAndIdenticalMarketObservationsDoNotOverwriteNewerSnapshots() {
        OffsetDateTime persistedAt = OffsetDateTime.parse("2026-09-28T15:00:02Z");
        OffsetDateTime olderAt = OffsetDateTime.parse("2026-09-28T15:00:01Z");
        instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("20.00000000"),
                new BigDecimal("20.10000000"),
                persistedAt
        );
        instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("20.05000000"),
                persistedAt
        );

        assertEquals(0, instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("1.00000000"),
                new BigDecimal("1.10000000"),
                olderAt
        ));
        assertEquals(0, instrumentRepository.updateQuoteSnapshot(
                instrumentId,
                new BigDecimal("2.00000000"),
                new BigDecimal("2.10000000"),
                persistedAt
        ));
        assertEquals(0, instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("1.05000000"),
                olderAt
        ));
        assertEquals(0, instrumentRepository.updateLastTradeSnapshot(
                instrumentId,
                new BigDecimal("2.05000000"),
                persistedAt
        ));

        InstrumentEntity instrument = instrumentRepository.findById(instrumentId).orElseThrow();
        assertEquals(new BigDecimal("20.00000000"), instrument.getBidPrice());
        assertEquals(new BigDecimal("20.10000000"), instrument.getAskPrice());
        assertEquals(new BigDecimal("20.05000000"), instrument.getLastPrice());
    }

    @Test
    void marketSnapshotUpdates_forUnknownInstrumentAffectZeroRows() {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-28T15:00:00Z");

        assertEquals(0, instrumentRepository.updateQuoteSnapshot(
                999L,
                new BigDecimal("1.00000000"),
                new BigDecimal("1.10000000"),
                observedAt
        ));
        assertEquals(0, instrumentRepository.updateLastTradeSnapshot(
                999L,
                new BigDecimal("1.05000000"),
                observedAt
        ));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidMarketPrices")
    void instrument_zeroAndNegativePricesAreRejected(String column, BigDecimal value) {
        OffsetDateTime observedAt = OffsetDateTime.parse("2026-09-28T15:00:00Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> {
                    switch (column) {
                        case "bid_price" -> instrumentRepository.updateQuoteSnapshot(
                                instrumentId, value, BigDecimal.ONE, observedAt
                        );
                        case "ask_price" -> instrumentRepository.updateQuoteSnapshot(
                                instrumentId, BigDecimal.ONE, value, observedAt
                        );
                        case "last_price" -> instrumentRepository.updateLastTradeSnapshot(
                                instrumentId, value, observedAt
                        );
                        default -> throw new IllegalArgumentException(
                                "Unexpected price column: " + column
                        );
                    }
                }
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00000000", "-0.00000001"})
    void holding_zeroAndNegativeQuantitiesAreRejected(String quantity) {
        HoldingEntity holding = holding(new BigDecimal(quantity));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> holdingRepository.insert(holding)
        );
    }

    @Test
    void holding_fractionalQuantityPersistsWithoutTruncation() {
        HoldingEntity holding = holding(new BigDecimal("0.12345678"));

        int rows = holdingRepository.insert(holding);

        List<HoldingEntity> persisted = holdingRepository.getHoldingsByClientId(clientId);
        assertEquals(1, rows);
        assertNotNull(holding.getHoldingId());
        assertEquals(1, persisted.size());
        assertEquals(new BigDecimal("0.12345678"), persisted.getFirst().getQuantity());
        assertEquals(new BigDecimal("987.6543210987654321"), persisted.getFirst().getAverageCost());
    }

    @Test
    void holding_updateRetainsFractionalPrecision() {
        HoldingEntity holding = holding(new BigDecimal("1.00000000"));
        holdingRepository.insert(holding);

        int rows = holdingRepository.updateHolding(
                instrumentId,
                clientId,
                new BigDecimal("2.87654321"),
                new BigDecimal("123.4567890123456789")
        );

        HoldingEntity persisted = holdingRepository
                .getHoldingByInstrumentIdAndClientId(instrumentId, clientId)
                .orElseThrow();
        assertEquals(1, rows);
        assertEquals(new BigDecimal("2.87654321"), persisted.getQuantity());
        assertEquals(new BigDecimal("123.4567890123456789"), persisted.getAverageCost());
    }

    @Test
    @Transactional
    void holding_rowLockingAndIdQueriesMapTheRequestedHolding() {
        HoldingEntity holding = holding(new BigDecimal("3.12500000"));
        holdingRepository.insert(holding);

        HoldingEntity locked = holdingRepository
                .getHoldingByInstrumentIdAndClientIdForUpdate(instrumentId, clientId);
        HoldingEntity byId = holdingRepository
                .getHoldingByHoldingIdAndClientId(holding.getHoldingId(), clientId);

        assertNotNull(locked);
        assertNotNull(byId);
        assertEquals(holding.getHoldingId(), locked.getHoldingId());
        assertEquals(new BigDecimal("3.12500000"), locked.getQuantity());
        assertEquals(holding.getHoldingId(), byId.getHoldingId());
    }

    @Test
    void holding_deleteReturnsAffectedRowCountAndRemovesPosition() {
        HoldingEntity holding = holding(new BigDecimal("1.25000000"));
        holdingRepository.insert(holding);

        int rows = holdingRepository.deleteHoldingByHoldingIdAndClientId(
                holding.getHoldingId(),
                clientId
        );

        assertEquals(1, rows);
        assertEquals(
                0,
                holdingRepository.getHoldingsByClientId(clientId).size()
        );
    }

    @Test
    void holding_duplicateClientAndInstrumentIsRejected() {
        holdingRepository.insert(holding(new BigDecimal("1.25000000")));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> holdingRepository.insert(holding(new BigDecimal("2.50000000")))
        );
    }

    @Test
    void holding_negativeAverageCostIsRejected() {
        HoldingEntity holding = holding(new BigDecimal("1.00000000"));
        holding.setAverageCost(new BigDecimal("-0.0000000000000001"));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> holdingRepository.insert(holding)
        );
    }

    @Test
    void incompleteQuoteOrTradeSnapshotsAreRejected() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE instruments SET bid_price = 1.00000000 WHERE instrument_id = ?",
                        instrumentId
                )
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE instruments SET last_trade_as_of = CURRENT_TIMESTAMP "
                                + "WHERE instrument_id = ?",
                        instrumentId
                )
        );
    }

    private static Stream<Arguments> invalidMarketPrices() {
        return Stream.of("bid_price", "ask_price", "last_price")
                .flatMap(column -> Stream.of(
                        Arguments.of(column, new BigDecimal("0.00000000")),
                        Arguments.of(column, new BigDecimal("-0.00000001"))
                ));
    }

    private Long insertClient() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance
                )
                VALUES (
                    'Test', 'Client', 'financial@example.com', 'hash',
                    '000-00-0000', '555-000-0000', 1000.0000000000000000
                )
                RETURNING client_id
                """, Long.class);
    }

    private Long insertInstrument(String symbol) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    symbol, instrument_name, asset_class, currency, is_tradable
                )
                VALUES (?, 'Test Instrument', 'Equity', 'USD', TRUE)
                RETURNING instrument_id
                """, Long.class, symbol);
    }

    private HoldingEntity holding(BigDecimal quantity) {
        HoldingEntity holding = new HoldingEntity();
        holding.setClientId(clientId);
        holding.setInstrumentId(instrumentId);
        holding.setQuantity(quantity);
        holding.setAverageCost(new BigDecimal("987.6543210987654321"));
        return holding;
    }
}
