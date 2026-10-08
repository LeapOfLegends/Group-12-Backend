package group12.Services;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.dto.CreateOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@EmbeddedKafka(partitions = 1)
class OrderLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("order_lifecycle_test")
                    .withUsername("order_lifecycle_test")
                    .withPassword("order_lifecycle_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("market-data.refresh.enabled", () -> "false");
        registry.add("order-lifecycle.quote.max-age", () -> "30s");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderSubmissionService submissionService;

    @Autowired
    private OrderAcceptanceService acceptanceService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE orders, holdings, clients, instruments RESTART IDENTITY CASCADE"
        );
    }

    @Test
    void submissionCommitsDurableSubmittedOrder_independentlyOfCallerTransaction() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);

        Long orderId = outerTransaction.execute(status -> {
            OrderEntity submitted = submissionService.submit(
                    request(clientId, instrumentId, OrderType.BUY, "1")
            );
            status.setRollbackOnly();
            return submitted.getOrderId();
        });

        assertNotNull(orderId);
        assertEquals("SUBMITTED", orderStatus(orderId));
    }

//     @Test
//     void normalSubmissionAcceptanceAndExecution_useDifferentDatabaseTransactions() {
//         installTransactionAudit();
//         Long clientId = insertClient("100");
//         Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");

//         OrderEntity result = orderService.submitOrder(
//                 request(clientId, instrumentId, OrderType.BUY, "2")
//         );

//         List<Long> transactionIds = jdbcTemplate.queryForList(
//                 "SELECT tx_id FROM order_tx_audit WHERE order_id = ? ORDER BY audit_id",
//                 Long.class,
//                 result.getOrderId()
//         );
//         assertEquals(OrderStatus.FILLED, result.getStatus());
//         assertEquals(3, transactionIds.size());
//         assertEquals(3, transactionIds.stream().distinct().count());
//         assertEquals(new BigDecimal("80.0000000000000000"), accountBalance(clientId));
//         assertEquals(
//                 new BigDecimal("2.00000000"),
//                 holdingQuantity(clientId, instrumentId)
//         );
//     }

    @Test
    void buyAcceptance_reservesPersistedAskWithoutChangingBalance() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "12.34567890", "CURRENT_TIMESTAMP");
        Long orderId = insertOrder(clientId, instrumentId, OrderType.BUY, "2.50000000");

        OrderEntity result = acceptanceService.acceptSubmittedOrder(orderId);

        assertEquals(OrderStatus.ACCEPTED, result.getStatus());
        assertEquals(new BigDecimal("30.8641972500000000"), result.getReservedCash());
        assertEquals(new BigDecimal("100.0000000000000000"), accountBalance(clientId));
    }

    @Test
    void acceptedBuyReservation_reducesCashAvailableToNextOrder() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        Long firstOrderId = insertOrder(clientId, instrumentId, OrderType.BUY, "6");
        Long secondOrderId = insertOrder(clientId, instrumentId, OrderType.BUY, "5");
        acceptanceService.acceptSubmittedOrder(firstOrderId);

        OrderEntity second = acceptanceService.acceptSubmittedOrder(secondOrderId);

        assertEquals(OrderStatus.REJECTED, second.getStatus());
        assertEquals("INSUFFICIENT_FUNDS", second.getRejectionReason());
        assertEquals(new BigDecimal("100.0000000000000000"), accountBalance(clientId));
    }

    @Test
    void concurrentBuyAcceptances_cannotOverReserveOneClientBalance() throws Exception {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        Long firstOrderId = insertOrder(clientId, instrumentId, OrderType.BUY, "6");
        Long secondOrderId = insertOrder(clientId, instrumentId, OrderType.BUY, "6");

        List<OrderEntity> results = acceptConcurrently(firstOrderId, secondOrderId);

        assertEquals(1, results.stream()
                .filter(order -> order.getStatus() == OrderStatus.ACCEPTED)
                .count());
        assertEquals(1, results.stream()
                .filter(order -> order.getStatus() == OrderStatus.REJECTED)
                .count());
        assertEquals(new BigDecimal("100.0000000000000000"), accountBalance(clientId));
    }

    @Test
    void sellAcceptance_usesUnreservedHoldingWithoutChangingQuantity() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        insertHolding(clientId, instrumentId, "10");
        Long firstOrderId = insertOrder(clientId, instrumentId, OrderType.SELL, "4");
        Long secondOrderId = insertOrder(clientId, instrumentId, OrderType.SELL, "7");

        OrderEntity first = acceptanceService.acceptSubmittedOrder(firstOrderId);
        OrderEntity second = acceptanceService.acceptSubmittedOrder(secondOrderId);

        assertEquals(OrderStatus.ACCEPTED, first.getStatus());
        assertEquals(OrderStatus.REJECTED, second.getStatus());
        assertEquals("INSUFFICIENT_HOLDINGS", second.getRejectionReason());
        assertEquals(new BigDecimal("10.00000000"), holdingQuantity(clientId, instrumentId));
    }

    @Test
    void concurrentSellAcceptances_cannotOverReserveOneHolding() throws Exception {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        insertHolding(clientId, instrumentId, "10");
        Long firstOrderId = insertOrder(clientId, instrumentId, OrderType.SELL, "6");
        Long secondOrderId = insertOrder(clientId, instrumentId, OrderType.SELL, "6");

        List<OrderEntity> results = acceptConcurrently(firstOrderId, secondOrderId);

        assertEquals(1, results.stream()
                .filter(order -> order.getStatus() == OrderStatus.ACCEPTED)
                .count());
        assertEquals(1, results.stream()
                .filter(order -> order.getStatus() == OrderStatus.REJECTED)
                .count());
        assertEquals(new BigDecimal("10.00000000"), holdingQuantity(clientId, instrumentId));
    }

    @Test
    void missingAndInsufficientHoldings_areRejected() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        Long missingHoldingOrder = insertOrder(
                clientId, instrumentId, OrderType.SELL, "1"
        );

        OrderEntity missingResult = acceptanceService.acceptSubmittedOrder(missingHoldingOrder);
        insertHolding(clientId, instrumentId, "2");
        Long insufficientOrder = insertOrder(
                clientId, instrumentId, OrderType.SELL, "3"
        );
        OrderEntity insufficientResult = acceptanceService.acceptSubmittedOrder(insufficientOrder);

        assertEquals("INSUFFICIENT_HOLDINGS", missingResult.getRejectionReason());
        assertEquals("INSUFFICIENT_HOLDINGS", insufficientResult.getRejectionReason());
    }

    @Test
    void nonTradableMissingAndStaleQuotes_areRejected() {
        Long clientId = insertClient("100");
        Long nonTradableId = insertInstrument(false, "10", "CURRENT_TIMESTAMP");
        Long missingQuoteId = insertInstrument(true, null, null);
        Long staleQuoteId = insertInstrument(
                true, "10", "CURRENT_TIMESTAMP - INTERVAL '31 seconds'"
        );

        OrderEntity nonTradable = acceptanceService.acceptSubmittedOrder(
                insertOrder(clientId, nonTradableId, OrderType.BUY, "1")
        );
        OrderEntity missing = acceptanceService.acceptSubmittedOrder(
                insertOrder(clientId, missingQuoteId, OrderType.BUY, "1")
        );
        OrderEntity stale = acceptanceService.acceptSubmittedOrder(
                insertOrder(clientId, staleQuoteId, OrderType.BUY, "1")
        );

        assertEquals("INSTRUMENT_NOT_TRADABLE", nonTradable.getRejectionReason());
        assertEquals("MISSING_QUOTE", missing.getRejectionReason());
        assertEquals("STALE_QUOTE", stale.getRejectionReason());
    }

    @Test
    void repeatedAcceptanceOfAcceptedOrder_doesNotCreateIllegalTransition() {
        Long clientId = insertClient("100");
        Long instrumentId = insertInstrument(true, "10", "CURRENT_TIMESTAMP");
        Long orderId = insertOrder(clientId, instrumentId, OrderType.BUY, "1");
        OrderEntity first = acceptanceService.acceptSubmittedOrder(orderId);

        OrderEntity repeated = acceptanceService.acceptSubmittedOrder(orderId);

        assertEquals(OrderStatus.ACCEPTED, first.getStatus());
        assertEquals(OrderStatus.ACCEPTED, repeated.getStatus());
        assertEquals(first.getReservedCash(), repeated.getReservedCash());
        assertNull(repeated.getRejectionReason());
    }

    private List<OrderEntity> acceptConcurrently(Long firstOrderId, Long secondOrderId)
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<OrderEntity> first = executor.submit(() -> {
                start.await();
                return acceptanceService.acceptSubmittedOrder(firstOrderId);
            });
            Future<OrderEntity> second = executor.submit(() -> {
                start.await();
                return acceptanceService.acceptSubmittedOrder(secondOrderId);
            });
            start.countDown();
            return List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
        }
    }

    private void installTransactionAudit() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS order_tx_audit");
        jdbcTemplate.execute("""
                CREATE TABLE order_tx_audit (
                    audit_id BIGSERIAL PRIMARY KEY,
                    event_type TEXT NOT NULL,
                    order_id BIGINT NOT NULL,
                    tx_id BIGINT NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION audit_order_transaction() RETURNS TRIGGER AS $$
                BEGIN
                    INSERT INTO order_tx_audit (event_type, order_id, tx_id)
                    VALUES (TG_OP, NEW.order_id, txid_current());
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS order_transaction_audit ON orders");
        jdbcTemplate.execute("""
                CREATE TRIGGER order_transaction_audit
                AFTER INSERT OR UPDATE ON orders
                FOR EACH ROW EXECUTE FUNCTION audit_order_transaction()
                """);
    }

    private Long insertClient(String balance) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance
                )
                VALUES ('Test', 'Client', 'client-' || nextval('clients_client_id_seq') || '@example.com',
                        'hash', '000-00-0000', '555-000-0000', ?)
                RETURNING client_id
                """, Long.class, new BigDecimal(balance));
    }

    private Long insertInstrument(boolean tradable, String askPrice, String quoteExpression) {
        if (askPrice == null) {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO instruments (
                        symbol, instrument_name, asset_class, currency, is_tradable
                    )
                    VALUES ('NOQUOTE-' || nextval('instruments_instrument_id_seq'),
                            'Test Instrument', 'Equity', 'USD', ?)
                    RETURNING instrument_id
                    """, Long.class, tradable);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    symbol, instrument_name, asset_class, currency, is_tradable,
                    bid_price, ask_price, quote_as_of
                )
                VALUES ('QUOTED-' || nextval('instruments_instrument_id_seq'),
                        'Test Instrument', 'Equity', 'USD', ?, 1.00000000, ?,
                        %s)
                RETURNING instrument_id
                """.formatted(quoteExpression), Long.class, tradable, new BigDecimal(askPrice));
    }

    private Long insertOrder(
            Long clientId,
            Long instrumentId,
            OrderType type,
            String quantity
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (client_id, instrument_id, order_type, quantity)
                VALUES (?, ?, ?, ?)
                RETURNING order_id
                """, Long.class, clientId, instrumentId, type.name(), new BigDecimal(quantity));
    }

    private void insertHolding(Long clientId, Long instrumentId, String quantity) {
        jdbcTemplate.update("""
                INSERT INTO holdings (client_id, instrument_id, quantity, average_cost)
                VALUES (?, ?, ?, 1)
                """, clientId, instrumentId, new BigDecimal(quantity));
    }

    private CreateOrderRequest request(
            Long clientId,
            Long instrumentId,
            OrderType type,
            String quantity
    ) {
        return new CreateOrderRequest(
                clientId, instrumentId, type, new BigDecimal(quantity)
        );
    }

    private String orderStatus(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE order_id = ?",
                String.class,
                orderId
        );
    }

    private BigDecimal accountBalance(Long clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT account_balance FROM clients WHERE client_id = ?",
                BigDecimal.class,
                clientId
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
}
