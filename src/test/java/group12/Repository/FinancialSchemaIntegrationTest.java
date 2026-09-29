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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        jdbcTemplate.update("""
                UPDATE instruments
                SET bid_price = 123.45678901,
                    ask_price = 123.55678901,
                    last_price = 123.50678901,
                    quote_as_of = '2026-09-28T15:01:02.123456Z'::timestamptz,
                    last_trade_as_of = '2026-09-28T15:01:03.654321Z'::timestamptz
                WHERE instrument_id = ?
                """, instrumentId);

        InstrumentEntity instrument = instrumentRepository.findBySymbol("NULLS").orElseThrow();

        assertEquals(new BigDecimal("123.45678901"), instrument.getBidPrice());
        assertEquals(new BigDecimal("123.55678901"), instrument.getAskPrice());
        assertEquals(new BigDecimal("123.50678901"), instrument.getLastPrice());
        assertEquals(
                Instant.parse("2026-09-28T15:01:02.123456Z"),
                instrument.getQuoteAsOf().toInstant()
        );
        assertEquals(
                Instant.parse("2026-09-28T15:01:03.654321Z"),
                instrument.getLastTradeAsOf().toInstant()
        );
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidMarketPrices")
    void instrument_zeroAndNegativePricesAreRejected(String column, BigDecimal value) {
        String sql = switch (column) {
            case "bid_price" -> """
                    UPDATE instruments
                    SET bid_price = ?, ask_price = 1.00000000, quote_as_of = CURRENT_TIMESTAMP
                    WHERE instrument_id = ?
                    """;
            case "ask_price" -> """
                    UPDATE instruments
                    SET bid_price = 1.00000000, ask_price = ?, quote_as_of = CURRENT_TIMESTAMP
                    WHERE instrument_id = ?
                    """;
            case "last_price" -> """
                    UPDATE instruments
                    SET last_price = ?, last_trade_as_of = CURRENT_TIMESTAMP
                    WHERE instrument_id = ?
                    """;
            default -> throw new IllegalArgumentException("Unexpected price column: " + column);
        };

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, value, instrumentId)
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

        holdingRepository.insert(holding);

        List<HoldingEntity> persisted = holdingRepository.getHoldingsByClientId(clientId);
        assertEquals(1, persisted.size());
        assertEquals(new BigDecimal("0.12345678"), persisted.getFirst().getQuantity());
        assertEquals(new BigDecimal("987.6543210987654321"), persisted.getFirst().getAverageCost());
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
