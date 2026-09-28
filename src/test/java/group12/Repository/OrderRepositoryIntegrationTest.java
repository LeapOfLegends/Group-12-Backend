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
                    submitted_at, accepted_at, filled_at, execution_price
                )
                VALUES (?, ?, 'BUY', 4.12500000, 'FILLED', ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, 123.45678901)
                RETURNING order_id
                """, Long.class,
                firstClientId,
                instrumentId,
                "2026-01-02T10:15:30Z",
                "2026-01-02T10:15:31Z",
                "2026-01-02T10:15:35Z"
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
        assertEquals(new BigDecimal("123.45678901"), order.getExecutionPrice());
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
    void acceptSubmittedOrder_whenSubmitted_transitionsToAccepted() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.acceptSubmittedOrder(orderId);

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.ACCEPTED, order.getStatus());
        assertNotNull(order.getAcceptedAt());
    }

    @Test
    void acceptSubmittedOrder_whenAlreadyAccepted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);
        assertEquals(1, orderRepository.acceptSubmittedOrder(orderId));

        int rowsAffected = orderRepository.acceptSubmittedOrder(orderId);

        assertEquals(0, rowsAffected);
        assertEquals(
                OrderStatus.ACCEPTED,
                orderRepository.findById(orderId).orElseThrow().getStatus()
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
    void fillAcceptedOrder_whenAccepted_persistsPriceTimestampAndGeneratedTradeValue() {
        Long orderId = insertOrderWithStatus(
                OrderStatus.ACCEPTED, new BigDecimal("4.12500000")
        );

        int rowsAffected = orderRepository.fillAcceptedOrder(
                orderId,
                new BigDecimal("12.34567890")
        );

        OrderEntity order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(1, rowsAffected);
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(new BigDecimal("12.34567890"), order.getExecutionPrice());
        assertEquals(new BigDecimal("50.9259254625000000"), order.getTradeValue());
        assertNotNull(order.getFilledAt());
    }

    @Test
    void fillAcceptedOrder_whenSubmitted_returnsZero() {
        Long orderId = insertOrderWithStatus(OrderStatus.SUBMITTED, 2);

        int rowsAffected = orderRepository.fillAcceptedOrder(
                orderId,
                new BigDecimal("10.0000")
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
        return jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status
                )
                VALUES (?, ?, 'BUY', ?, ?)
                RETURNING order_id
                """, Long.class, firstClientId, instrumentId, quantity, status.name());
    }

    private void assertNoLifecycleTransitionSucceeds(Long orderId) {
        assertEquals(0, orderRepository.acceptSubmittedOrder(orderId));
        assertEquals(0, orderRepository.rejectSubmittedOrder(orderId, "REJECTED"));
        assertEquals(
                0,
                orderRepository.fillAcceptedOrder(orderId, new BigDecimal("10.0000"))
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
