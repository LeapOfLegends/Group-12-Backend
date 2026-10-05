package group12.Repository;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Services.OrderFailureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class OrderRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("orders_test")
                    .withUsername("orders_test")
                    .withPassword("orders_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderFailureService orderFailureService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long firstClientId;
    private Long secondClientId;
    private Long instrumentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE orders, clients, instruments RESTART IDENTITY CASCADE"
        );
        firstClientId = insertClient("first.client@example.com");
        secondClientId = insertClient("second.client@example.com");
        instrumentId = insertInstrument("ACME");
    }

    @Test
    void findById_whenOrderExists_mapsAllImportantFields() {
        // Arrange
        Long orderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    reserved_cash, submitted_at, accepted_at, filled_at,
                    execution_price, execution_quote_as_of
                )
                VALUES (?, ?, 'BUY', 4.12500000, 'FILLED', 509.2592546662500000,
                        ?::timestamptz, ?::timestamptz, ?::timestamptz,
                        123.45678901, ?::timestamptz)
                RETURNING order_id
                """, Long.class,
                firstClientId,
                instrumentId,
                "2026-01-02T10:15:30Z",
                "2026-01-02T10:15:31Z",
                "2026-01-02T10:15:35Z",
                "2026-01-02T10:15:34.123456Z"
        );

        // Act
        Optional<OrderEntity> result = orderRepository.findById(orderId);

        // Assert
        assertTrue(result.isPresent());
        OrderEntity order = result.orElseThrow();
        assertEquals(orderId, order.getOrderId());
        assertEquals(firstClientId, order.getClientId());
        assertEquals(instrumentId, order.getInstrumentId());
        assertEquals(OrderType.BUY, order.getOrderType());
        assertEquals(new BigDecimal("4.12500000"), order.getQuantity());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(Instant.parse("2026-01-02T10:15:30Z"), order.getSubmittedAt().toInstant());
        assertEquals(new BigDecimal("509.2592546662500000"), order.getReservedCash());
        assertEquals(new BigDecimal("123.45678901"), order.getExecutionPrice());
        assertEquals(
                Instant.parse("2026-01-02T10:15:34.123456Z"),
                order.getExecutionQuoteAsOf().toInstant()
        );
        assertEquals(new BigDecimal("509.2592546662500000"), order.getTradeValue());
        assertNotNull(order.getAcceptedAt());
        assertNotNull(order.getFilledAt());
    }

    @Test
    @Transactional
    void findByIdForUpdate_whenOrderExists_mapsOrder() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 3);

        Optional<OrderEntity> result = orderRepository.findByIdForUpdate(orderId);

        assertTrue(result.isPresent());
        OrderEntity order = result.orElseThrow();
        assertEquals(orderId, order.getOrderId());
        assertEquals(firstClientId, order.getClientId());
        assertEquals(instrumentId, order.getInstrumentId());
        assertEquals(OrderType.BUY, order.getOrderType());
        assertEquals(new BigDecimal("3.00000000"), order.getQuantity());
        assertEquals(OrderStatus.SUBMITTED, order.getStatus());
    }

    @Test
    void insert_withValidOrder_usesGeneratedIdAndPostgreSqlDefaults() {
        // Arrange
        OrderEntity newOrder = newOrder(
                firstClientId, instrumentId, new BigDecimal("8.12345678")
        );

        // Act
        int rowsAffected = orderRepository.insert(newOrder);
        OrderEntity persistedOrder = orderRepository.findById(newOrder.getOrderId()).orElseThrow();
        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE order_id = ?",
                Integer.class,
                newOrder.getOrderId()
        );

        // Assert
        assertEquals(1, rowsAffected);
        assertNotNull(newOrder.getOrderId());
        assertTrue(newOrder.getOrderId() > 0);
        assertEquals(1, rowCount);
        assertEquals(firstClientId, persistedOrder.getClientId());
        assertEquals(instrumentId, persistedOrder.getInstrumentId());
        assertEquals(OrderType.BUY, persistedOrder.getOrderType());
        assertEquals(new BigDecimal("8.12345678"), persistedOrder.getQuantity());
        assertEquals(OrderStatus.SUBMITTED, persistedOrder.getStatus());
        assertNotNull(persistedOrder.getSubmittedAt());
        assertNull(persistedOrder.getExecutionPrice());
        assertNull(persistedOrder.getFilledAt());
        assertNull(persistedOrder.getTradeValue());
    }

    @Test
    void findByClientId_returnsOnlyRequestedClientOrdersInNewestFirstOrder() {
        // Arrange
        Long olderOrderId = insertOrder(firstClientId, "2026-01-01T10:00:00Z");
        Long newerOrderId = insertOrder(firstClientId, "2026-01-03T10:00:00Z");
        insertOrder(secondClientId, "2026-01-04T10:00:00Z");

        // Act
        List<OrderEntity> orders = orderRepository.findByClientId(firstClientId);

        // Assert
        assertEquals(2, orders.size());
        assertEquals(newerOrderId, orders.get(0).getOrderId());
        assertEquals(olderOrderId, orders.get(1).getOrderId());
        assertTrue(orders.stream().allMatch(order -> firstClientId.equals(order.getClientId())));
    }

    @Test
    void findByClientId_whenValidClientHasNoOrders_returnsEmptyList() {
        // Arrange uses the seeded second client, which has no orders.

        // Act
        List<OrderEntity> orders = orderRepository.findByClientId(secondClientId);

        // Assert
        assertTrue(orders.isEmpty());
    }

    @ParameterizedTest(name = "quantity {0} violates the database constraint")
    @ValueSource(ints = {0, -1})
    void insert_withNonPositiveQuantity_isRejectedByPostgreSql(int quantity) {
        // Arrange
        OrderEntity invalidOrder = newOrder(firstClientId, instrumentId, quantity);

        // Act and Assert
        assertThrows(
                DataIntegrityViolationException.class,
                () -> orderRepository.insert(invalidOrder)
        );
    }

    @Test
    void insert_withNonexistentClient_isRejectedByPostgreSql() {
        // Arrange
        OrderEntity invalidOrder = newOrder(999L, instrumentId, 1);

        // Act and Assert
        assertThrows(
                DataIntegrityViolationException.class,
                () -> orderRepository.insert(invalidOrder)
        );
    }

    @Test
    void insert_withNonexistentInstrument_isRejectedByPostgreSql() {
        // Arrange
        OrderEntity invalidOrder = newOrder(firstClientId, 999L, 1);

        // Act and Assert
        assertThrows(
                DataIntegrityViolationException.class,
                () -> orderRepository.insert(invalidOrder)
        );
    }

    @Test
    void acceptSubmittedBuyOrder_whenSubmitted_storesReservedCash() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.acceptSubmittedBuyOrder(
                orderId,
                new BigDecimal("246.9135780200000000")
        );

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertNotNull(order.getAcceptedAt());
        assertEquals(new BigDecimal("246.9135780200000000"), order.getReservedCash());
    }

    @Test
    void acceptSubmittedBuyOrder_whenAlreadyAccepted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);
        assertEquals(
                1,
                orderRepository.acceptSubmittedBuyOrder(
                        orderId,
                        new BigDecimal("20.0000000000000000")
                )
        );

        int rowsAffected = orderRepository.acceptSubmittedBuyOrder(
                orderId,
                new BigDecimal("30.0000000000000000")
        );

        assertEquals(0, rowsAffected);
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertEquals(new BigDecimal("20.0000000000000000"), order.getReservedCash());
    }

    @Test
    void acceptSubmittedSellOrder_whenSubmitted_leavesReservedCashNull() {
        Long orderId = insertOrderWithStatus(OrderType.SELL, OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.acceptSubmittedSellOrder(orderId);

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertNotNull(order.getAcceptedAt());
        assertNull(order.getReservedCash());
    }

    @Test
    void acceptancePrimitives_requireTheirMatchingOrderType() {
        Long buyOrderId = insertOrderWithStatus(OrderType.BUY, OrderStatus.SUBMITTED, 2);
        Long sellOrderId = insertOrderWithStatus(OrderType.SELL, OrderStatus.SUBMITTED, 2);

        assertEquals(0, orderRepository.acceptSubmittedSellOrder(buyOrderId));
        assertEquals(
                0,
                orderRepository.acceptSubmittedBuyOrder(
                        sellOrderId,
                        new BigDecimal("20.0000000000000000")
                )
        );
        assertEquals(
                OrderStatus.SUBMITTED,
                orderRepository.findById(buyOrderId).orElseThrow().getStatus()
        );
        assertEquals(
                OrderStatus.SUBMITTED,
                orderRepository.findById(sellOrderId).orElseThrow().getStatus()
        );
    }

    @Test
    void rejectSubmittedOrder_whenSubmitted_persistsReasonAndTimestamp() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.rejectSubmittedOrder(
                orderId,
                "INSUFFICIENT_FUNDS"
        );

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.REJECTED, order.getStatus());
        assertNotNull(order.getRejectedAt());
        assertEquals("INSUFFICIENT_FUNDS", order.getRejectionReason());
    }

    @Test
    void rejectSubmittedOrder_whenAccepted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.ACCEPTED, 2);

        int rowsAffected = orderRepository.rejectSubmittedOrder(orderId, "TOO_LATE");

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(0, rowsAffected);
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertNull(order.getRejectedAt());
        assertNull(order.getRejectionReason());
    }

    @Test
    void fillAcceptedOrder_whenAccepted_persistsPriceTimestampAndGeneratedTradeValue() {
        Long orderId = insertOrderWithStatus(
                OrderStatus.ACCEPTED, new BigDecimal("4.12500000")
        );
        OffsetDateTime executionQuoteAsOf =
                OffsetDateTime.parse("2026-01-02T10:15:34.123456Z");

        int rowsAffected = orderRepository.fillAcceptedOrder(
                orderId,
                new BigDecimal("12.34567890"),
                executionQuoteAsOf
        );

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("12.34567890"), order.getExecutionPrice());
        assertEquals(executionQuoteAsOf.toInstant(), order.getExecutionQuoteAsOf().toInstant());
        assertEquals(new BigDecimal("50.9259254625000000"), order.getTradeValue());
        assertNotNull(order.getFilledAt());
    }

    @Test
    void fillAcceptedOrder_whenSubmitted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.fillAcceptedOrder(
                orderId,
                new BigDecimal("10.0000"),
                OffsetDateTime.parse("2026-01-02T10:15:34Z")
        );

        assertEquals(0, rowsAffected);
        assertEquals(
                OrderStatus.SUBMITTED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
        );
    }

    @Test
    void failAcceptedOrder_whenAccepted_persistsReasonAndTimestamp() {
        Long orderId = insertOrderWithStatus(OrderStatus.ACCEPTED, 2);

        int rowsAffected = orderRepository.failAcceptedOrder(orderId, "EXECUTION_ERROR");

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertNotNull(order.getFailedAt());
        assertEquals("EXECUTION_ERROR", order.getFailureReason());
    }

    @Test
    void failAcceptedOrder_whenSubmitted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.failAcceptedOrder(orderId, "EXECUTION_ERROR");

        assertEquals(0, rowsAffected);
        assertEquals(
                OrderStatus.SUBMITTED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
        );
    }

    @Test
    void sumActiveBuyReservedCash_includesOnlyAcceptedBuysForClient() {
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.BUY, OrderStatus.ACCEPTED,
                new BigDecimal("1.00000000"), new BigDecimal("10.5000000000000000")
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.BUY, OrderStatus.ACCEPTED,
                new BigDecimal("2.00000000"), new BigDecimal("20.2500000000000000")
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.BUY, OrderStatus.FILLED,
                new BigDecimal("3.00000000"), new BigDecimal("30.0000000000000000")
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.BUY, OrderStatus.SUBMITTED,
                new BigDecimal("4.00000000"), null
        );
        insertReservationOrder(
                secondClientId, instrumentId, OrderType.BUY, OrderStatus.ACCEPTED,
                new BigDecimal("5.00000000"), new BigDecimal("50.0000000000000000")
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.SELL, OrderStatus.ACCEPTED,
                new BigDecimal("6.00000000"), null
        );

        BigDecimal reservedCash = orderRepository.sumActiveBuyReservedCash(firstClientId);

        assertEquals(new BigDecimal("30.7500000000000000"), reservedCash);
        assertEquals(
                0,
                orderRepository.sumActiveBuyReservedCash(999L).compareTo(BigDecimal.ZERO)
        );
    }

    @Test
    void sumActiveSellQuantity_includesOnlyAcceptedSellsForClientAndInstrument() {
        Long secondInstrumentId = insertInstrument("OTHER");
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.SELL, OrderStatus.ACCEPTED,
                new BigDecimal("1.25000000"), null
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.SELL, OrderStatus.ACCEPTED,
                new BigDecimal("2.50000000"), null
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.SELL, OrderStatus.FILLED,
                new BigDecimal("3.00000000"), null
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.SELL, OrderStatus.SUBMITTED,
                new BigDecimal("4.00000000"), null
        );
        insertReservationOrder(
                firstClientId, instrumentId, OrderType.BUY, OrderStatus.ACCEPTED,
                new BigDecimal("5.00000000"), new BigDecimal("50.0000000000000000")
        );
        insertReservationOrder(
                firstClientId, secondInstrumentId, OrderType.SELL, OrderStatus.ACCEPTED,
                new BigDecimal("6.00000000"), null
        );
        insertReservationOrder(
                secondClientId, instrumentId, OrderType.SELL, OrderStatus.ACCEPTED,
                new BigDecimal("7.00000000"), null
        );

        BigDecimal reservedQuantity = orderRepository.sumActiveSellQuantity(
                firstClientId,
                instrumentId
        );

        assertEquals(new BigDecimal("3.75000000"), reservedQuantity);
        assertEquals(
                0,
                orderRepository.sumActiveSellQuantity(999L, instrumentId)
                        .compareTo(BigDecimal.ZERO)
        );
    }

    @Test
    void findSubmittedOrderIdsSubmittedBefore_selectsOnlyOlderSubmittedOrders() {
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        Long oldestOrderId = insertRecoveryOrder(
                OrderStatus.SUBMITTED,
                "2026-01-01T08:00:00Z",
                null
        );
        Long olderOrderId = insertRecoveryOrder(
                OrderStatus.SUBMITTED,
                "2026-01-01T09:00:00Z",
                null
        );
        insertRecoveryOrder(OrderStatus.SUBMITTED, cutoff.toString(), null);
        insertRecoveryOrder(OrderStatus.SUBMITTED, "2026-01-03T08:00:00Z", null);
        insertRecoveryOrder(OrderStatus.REJECTED, "2026-01-01T07:00:00Z", null);
        insertRecoveryOrder(
                OrderStatus.ACCEPTED,
                "2026-01-01T06:00:00Z",
                "2026-01-01T06:01:00Z"
        );

        List<Long> orderIds =
                orderRepository.findSubmittedOrderIdsSubmittedBefore(cutoff);

        assertEquals(List.of(oldestOrderId, olderOrderId), orderIds);
    }

    @Test
    void findAcceptedOrderIdsAcceptedBefore_selectsOnlyOlderAcceptedOrders() {
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        Long oldestOrderId = insertRecoveryOrder(
                OrderStatus.ACCEPTED,
                "2026-01-01T07:00:00Z",
                "2026-01-01T08:00:00Z"
        );
        Long olderOrderId = insertRecoveryOrder(
                OrderStatus.ACCEPTED,
                "2026-01-01T08:00:00Z",
                "2026-01-01T09:00:00Z"
        );
        insertRecoveryOrder(OrderStatus.ACCEPTED, "2026-01-01T09:00:00Z", cutoff.toString());
        insertRecoveryOrder(
                OrderStatus.ACCEPTED,
                "2026-01-01T10:00:00Z",
                "2026-01-03T08:00:00Z"
        );
        insertRecoveryOrder(
                OrderStatus.FAILED,
                "2026-01-01T05:00:00Z",
                "2026-01-01T06:00:00Z"
        );
        insertRecoveryOrder(OrderStatus.SUBMITTED, "2026-01-01T04:00:00Z", null);

        List<Long> orderIds = orderRepository.findAcceptedOrderIdsAcceptedBefore(cutoff);

        assertEquals(List.of(oldestOrderId, olderOrderId), orderIds);
    }

    @Test
    void rejectedOrder_isTerminal() {
        Long orderId = insertOrderWithStatus(OrderStatus.REJECTED, 2);

        assertNoLifecycleTransitionSucceeds(orderId);
        assertEquals(
                OrderStatus.REJECTED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
        );
    }

    @Test
    void filledOrder_isTerminal() {
        Long orderId = insertOrderWithStatus(OrderStatus.FILLED, 2);

        assertNoLifecycleTransitionSucceeds(orderId);
        assertEquals(
                OrderStatus.FILLED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
        );
    }

    @Test
    void failedOrder_isTerminal() {
        Long orderId = insertOrderWithStatus(OrderStatus.FAILED, 2);

        assertNoLifecycleTransitionSucceeds(orderId);
        assertEquals(
                OrderStatus.FAILED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
        );
    }

    @Test
    void markFailed_requiresNewCommitSurvivesOuterTransactionRollback() {
        Long orderId = insertOrderWithStatus(OrderStatus.ACCEPTED, 2);
        BigDecimal originalBalance = jdbcTemplate.queryForObject(
                "SELECT account_balance FROM clients WHERE client_id = ?",
                BigDecimal.class,
                firstClientId
        );
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update(
                    "UPDATE clients SET account_balance = 500.0000 WHERE client_id = ?",
                    firstClientId
            );
            orderFailureService.markFailed(orderId, "EXECUTION_ERROR");
            status.setRollbackOnly();
        });

        BigDecimal persistedBalance = jdbcTemplate.queryForObject(
                "SELECT account_balance FROM clients WHERE client_id = ?",
                BigDecimal.class,
                firstClientId
        );
        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(originalBalance, persistedBalance);
        assertEquals(OrderStatus.FAILED, order.getStatus());
        assertEquals("EXECUTION_ERROR", order.getFailureReason());
    }

    private Long insertClient(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance
                )
                VALUES ('Test', 'Client', ?, 'hash', '000-00-0000', '555-000-0000', 1000.0000)
                RETURNING client_id
                """, Long.class, email);
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

    private Long insertOrder(Long clientId, String submittedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, submitted_at
                )
                VALUES (?, ?, 'BUY', 1, ?::timestamptz)
                RETURNING order_id
                """, Long.class, clientId, instrumentId, submittedAt);
    }

    private Long insertOrderWithStatus(OrderStatus status, int quantity) {
        return insertOrderWithStatus(status, BigDecimal.valueOf(quantity));
    }

    private Long insertOrderWithStatus(OrderStatus status, BigDecimal quantity) {
        return insertOrderWithStatus(OrderType.BUY, status, quantity);
    }

    private Long insertOrderWithStatus(OrderType orderType, OrderStatus status, int quantity) {
        return insertOrderWithStatus(orderType, status, BigDecimal.valueOf(quantity));
    }

    private Long insertOrderWithStatus(
            OrderType orderType,
            OrderStatus status,
            BigDecimal quantity
    ) {
        boolean wasAccepted = status == OrderStatus.ACCEPTED
                || status == OrderStatus.FILLED
                || status == OrderStatus.FAILED;
        BigDecimal reservedCash = orderType == OrderType.BUY && wasAccepted
                ? quantity.multiply(new BigDecimal("10.00000000"))
                : null;
        OffsetDateTime acceptedAt = wasAccepted
                ? OffsetDateTime.parse("2026-01-01T10:00:00Z")
                : null;
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    reserved_cash, accepted_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING order_id
                """, Long.class,
                firstClientId,
                instrumentId,
                orderType.name(),
                quantity,
                status.name(),
                reservedCash,
                acceptedAt
        );
    }

    private Long insertReservationOrder(
            Long clientId,
            Long selectedInstrumentId,
            OrderType orderType,
            OrderStatus status,
            BigDecimal quantity,
            BigDecimal reservedCash
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    reserved_cash, accepted_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?,
                    CASE WHEN ? = 'ACCEPTED' THEN CURRENT_TIMESTAMP ELSE NULL END
                )
                RETURNING order_id
                """, Long.class,
                clientId,
                selectedInstrumentId,
                orderType.name(),
                quantity,
                status.name(),
                reservedCash,
                status.name()
        );
    }

    private Long insertRecoveryOrder(
            OrderStatus status,
            String submittedAt,
            String acceptedAt
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    submitted_at, accepted_at
                )
                VALUES (?, ?, 'SELL', 1.00000000, ?, ?::timestamptz, ?::timestamptz)
                RETURNING order_id
                """, Long.class,
                firstClientId,
                instrumentId,
                status.name(),
                submittedAt,
                acceptedAt
        );
    }

    private void assertNoLifecycleTransitionSucceeds(Long orderId) {
        assertEquals(
                0,
                orderRepository.acceptSubmittedBuyOrder(
                        orderId,
                        new BigDecimal("10.0000000000000000")
                )
        );
        assertEquals(0, orderRepository.acceptSubmittedSellOrder(orderId));
        assertEquals(0, orderRepository.rejectSubmittedOrder(orderId, "REJECTED"));
        assertEquals(
                0,
                orderRepository.fillAcceptedOrder(
                        orderId,
                        new BigDecimal("10.0000"),
                        OffsetDateTime.parse("2026-01-02T10:15:34Z")
                )
        );
        assertEquals(0, orderRepository.failAcceptedOrder(orderId, "FAILED"));
    }

    private static OrderEntity newOrder(Long clientId, Long instrumentId, int quantity) {
        return newOrder(clientId, instrumentId, BigDecimal.valueOf(quantity));
    }

    private static OrderEntity newOrder(
            Long clientId,
            Long instrumentId,
            BigDecimal quantity
    ) {
        OrderEntity order = new OrderEntity();
        order.setClientId(clientId);
        order.setInstrumentId(instrumentId);
        order.setOrderType(OrderType.BUY);
        order.setQuantity(quantity);
        return order;
    }
}
