package group12.Services;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.exception.RetryableOrderExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EmbeddedKafka(
        partitions = 1,
        brokerProperties = {
            "listeners=PLAINTEXT://localhost:9093",
            "port=9093"
        }
)
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
class OrderExecutionIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("order_execution_test")
                    .withUsername("order_execution_test")
                    .withPassword("order_execution_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("market-data.refresh.enabled", () -> "false");
        registry.add("order-lifecycle.quote.max-age", () -> "30s");
        registry.add("order-lifecycle.execution.max-wait", () -> "2m");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderExecutionService executionService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_balance_update ON clients");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_balance_update()");
        jdbcTemplate.execute(
                "TRUNCATE TABLE orders, holdings, clients, instruments RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void buyNewHoldingCommitsCashHoldingAndPermanentFillFromAsk() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.BUY, "2", "24", "CURRENT_TIMESTAMP"
        );
        OffsetDateTime quoteAsOf = quoteAsOf(instrumentId);

        OrderEntity result = executionService.executeAcceptedOrder(orderId);

        assertEquals(OrderStatus.FILLED, result.getStatus());
        assertMoney("10.00000000", result.getExecutionPrice());
        assertEquals(quoteAsOf.toInstant(), result.getExecutionQuoteAsOf().toInstant());
        assertMoney("20.0000000000000000", result.getTradeValue());
        assertNotNull(result.getFilledAt());
        assertMoney("80.0000000000000000", balance(clientId));
        assertMoney("2.00000000", holdingQuantity(clientId, instrumentId));
        assertMoney("10.0000000000000000", holdingAverageCost(clientId, instrumentId));
    }

    @Test
    void buyExistingHoldingUsesDeterministicWeightedAverageAndAllowsEqualReservation() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        insertHolding(clientId, instrumentId, "3", "8");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.BUY, "2", "20", "CURRENT_TIMESTAMP"
        );

        executionService.executeAcceptedOrder(orderId);

        assertMoney("5.00000000", holdingQuantity(clientId, instrumentId));
        assertMoney("8.8000000000000000", holdingAverageCost(clientId, instrumentId));
        assertMoney("80.0000000000000000", balance(clientId));
        assertEquals("FILLED", status(orderId));
    }

    @Test
    void partialSellUsesBidKeepsAverageCostAndCreditsCash() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        insertHolding(clientId, instrumentId, "5", "7.25");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.SELL, "2", null, "CURRENT_TIMESTAMP"
        );

        OrderEntity result = executionService.executeAcceptedOrder(orderId);

        assertMoney("9.00000000", result.getExecutionPrice());
        assertMoney("3.00000000", holdingQuantity(clientId, instrumentId));
        assertMoney("7.2500000000000000", holdingAverageCost(clientId, instrumentId));
        assertMoney("118.0000000000000000", balance(clientId));
    }

    @Test
    void fullSellDeletesHoldingAndCommitsCreditWithFill() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        insertHolding(clientId, instrumentId, "5", "7.25");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.SELL, "5", null, "CURRENT_TIMESTAMP"
        );

        executionService.executeAcceptedOrder(orderId);

        assertEquals(0, holdingCount(clientId, instrumentId));
        assertMoney("145.0000000000000000", balance(clientId));
        assertEquals("FILLED", status(orderId));
    }

    @Test
    void reservationBreachFailsWithoutFinancialChangesAndCannotUseExtraCash() {
        Long clientId = insertClient("1000");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.BUY, "2", "19.99", "CURRENT_TIMESTAMP"
        );

        executionService.executeAcceptedOrder(orderId);

        assertEquals("FAILED", status(orderId));
        assertEquals("PRICE_EXCEEDS_RESERVED_CASH", failureReason(orderId));
        assertMoney("1000.0000000000000000", balance(clientId));
        assertEquals(0, holdingCount(clientId, instrumentId));
    }

    @Test
    void sellMissingAndInsufficientHoldingFailWithoutChangingCash() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        Long missingId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.SELL, "1", null, "CURRENT_TIMESTAMP"
        );

        executionService.executeAcceptedOrder(missingId);
        assertEquals("HOLDING_NOT_FOUND", failureReason(missingId));

        insertHolding(clientId, instrumentId, "2", "7.25");
        Long insufficientId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.SELL, "3", null, "CURRENT_TIMESTAMP"
        );
        executionService.executeAcceptedOrder(insufficientId);

        assertEquals("INSUFFICIENT_HOLDINGS", failureReason(insufficientId));
        assertMoney("100.0000000000000000", balance(clientId));
        assertMoney("2.00000000", holdingQuantity(clientId, instrumentId));
    }

    @Test
    void staleQuoteRetriesBeforeWaitAndFailsAfterWaitWhileNonTradableFailsImmediately() {
        Long clientId = insertClient("100");
        Long staleInstrumentId = insertInstrument(
                true, "9", "10", "99", "CURRENT_TIMESTAMP - INTERVAL '31 seconds'"
        );
        Long retryId = insertAcceptedOrder(
                clientId,
                staleInstrumentId,
                OrderType.BUY,
                "1",
                "10",
                "CURRENT_TIMESTAMP - INTERVAL '1 minute'"
        );

        assertThrows(
                RetryableOrderExecutionException.class,
                () -> executionService.executeAcceptedOrder(retryId)
        );
        assertEquals("ACCEPTED", status(retryId));

        Long timeoutId = insertAcceptedOrder(
                clientId,
                staleInstrumentId,
                OrderType.BUY,
                "1",
                "10",
                "CURRENT_TIMESTAMP - INTERVAL '3 minutes'"
        );
        executionService.executeAcceptedOrder(timeoutId);
        assertEquals("FAILED", status(timeoutId));
        assertEquals("MARKET_QUOTE_TIMEOUT", failureReason(timeoutId));

        Long nonTradableId = insertInstrument(
                false, "9", "10", "99", "CURRENT_TIMESTAMP"
        );
        Long nonTradableOrderId = insertAcceptedOrder(
                clientId,
                nonTradableId,
                OrderType.BUY,
                "1",
                "10",
                "CURRENT_TIMESTAMP"
        );
        executionService.executeAcceptedOrder(nonTradableOrderId);
        assertEquals("INSTRUMENT_NOT_TRADABLE", failureReason(nonTradableOrderId));
        assertMoney("100.0000000000000000", balance(clientId));
    }

    @Test
    void failureAfterHoldingWriteRollsBackHoldingCashAndOrderTogether() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.BUY, "2", "20", "CURRENT_TIMESTAMP"
        );
        jdbcTemplate.execute("""
                CREATE FUNCTION reject_balance_update() RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'simulated balance write failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_balance_update
                BEFORE UPDATE OF account_balance ON clients
                FOR EACH ROW EXECUTE FUNCTION reject_balance_update()
                """);

        assertThrows(RuntimeException.class, () -> executionService.executeAcceptedOrder(orderId));

        assertEquals("ACCEPTED", status(orderId));
        assertMoney("100.0000000000000000", balance(clientId));
        assertEquals(0, holdingCount(clientId, instrumentId));
    }

    @Test
    void duplicateConcurrentExecutionCannotDoubleFill() throws Exception {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "9", "10", "99", "CURRENT_TIMESTAMP");
        Long orderId = insertAcceptedOrder(
                clientId, instrumentId, OrderType.BUY, "2", "20", "CURRENT_TIMESTAMP"
        );
        CountDownLatch start = new CountDownLatch(1);

        List<OrderEntity> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<OrderEntity> first = executor.submit(() -> {
                start.await();
                return executionService.executeAcceptedOrder(orderId);
            });
            Future<OrderEntity> second = executor.submit(() -> {
                start.await();
                return executionService.executeAcceptedOrder(orderId);
            });
            start.countDown();
            results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
        }

        assertTrue(results.stream().allMatch(order -> order.getStatus() == OrderStatus.FILLED));
        assertMoney("80.0000000000000000", balance(clientId));
        assertMoney("2.00000000", holdingQuantity(clientId, instrumentId));
        assertEquals("FILLED", status(orderId));
    }

    private Long insertClient(String balance) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance
                )
                VALUES (
                    'Test', 'Client', 'client@test',
                    'hash', '000-00-0000', '555-000-0000', ?
                )
                RETURNING client_id
                """, Long.class, new BigDecimal(balance));
    }

    private Long insertInstrument(
            boolean tradable,
            String bid,
            String ask,
            String last,
            String quoteAsOfExpression
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    symbol, instrument_name, asset_class, currency, is_tradable,
                    bid_price, ask_price, last_price, quote_as_of, last_trade_as_of
                )
                VALUES (
                    'TEST',
                    'Test Instrument', 'Equity', 'USD', ?, ?, ?, ?,
                    %s, CURRENT_TIMESTAMP
                )
                RETURNING instrument_id
                """.formatted(quoteAsOfExpression),
                Long.class,
                tradable,
                new BigDecimal(bid),
                new BigDecimal(ask),
                new BigDecimal(last)
        );
    }

    private Long insertAcceptedOrder(
            Long clientId,
            Long instrumentId,
            OrderType orderType,
            String quantity,
            String reservedCash,
            String acceptedAtExpression
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    reserved_cash, accepted_at
                )
                VALUES (?, ?, ?, ?, 'ACCEPTED', ?, %s)
                RETURNING order_id
                """.formatted(acceptedAtExpression),
                Long.class,
                clientId,
                instrumentId,
                orderType.name(),
                new BigDecimal(quantity),
                reservedCash == null ? null : new BigDecimal(reservedCash)
        );
    }

    private void insertHolding(
            Long clientId,
            Long instrumentId,
            String quantity,
            String averageCost
    ) {
        jdbcTemplate.update("""
                INSERT INTO holdings (client_id, instrument_id, quantity, average_cost)
                VALUES (?, ?, ?, ?)
                """,
                clientId,
                instrumentId,
                new BigDecimal(quantity),
                new BigDecimal(averageCost)
        );
    }

    private BigDecimal balance(Long clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT account_balance FROM clients WHERE client_id = ?",
                BigDecimal.class,
                clientId
        );
    }

    private int holdingCount(Long clientId, Long instrumentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holdings WHERE client_id = ? AND instrument_id = ?",
                Integer.class,
                clientId,
                instrumentId
        );
    }

    private BigDecimal holdingQuantity(Long clientId, Long instrumentId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM holdings WHERE client_id = ? AND instrument_id = ?",
                BigDecimal.class,
                clientId,
                instrumentId
        );
    }

    private BigDecimal holdingAverageCost(Long clientId, Long instrumentId) {
        return jdbcTemplate.queryForObject(
                "SELECT average_cost FROM holdings WHERE client_id = ? AND instrument_id = ?",
                BigDecimal.class,
                clientId,
                instrumentId
        );
    }

    private OffsetDateTime quoteAsOf(Long instrumentId) {
        return jdbcTemplate.queryForObject(
                "SELECT quote_as_of FROM instruments WHERE instrument_id = ?",
                OffsetDateTime.class,
                instrumentId
        );
    }

    private String status(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_id = ?",
                String.class,
                orderId
        );
    }

    private String failureReason(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM orders WHERE order_id = ?",
                String.class,
                orderId
        );
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
