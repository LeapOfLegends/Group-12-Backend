package group12.Repository;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
                VALUES (?, ?, 'BUY', 4, 'FILLED', ?::timestamptz, ?::timestamptz,
                        ?::timestamptz, 123.4500)
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
        assertEquals(4, order.getQuantity());
        assertEquals(OrderStatus.FILLED, order.getStatus());
        assertEquals(Instant.parse("2026-01-02T10:15:30Z"), order.getSubmittedAt().toInstant());
        assertEquals(new BigDecimal("123.4500"), order.getExecutionPrice());
        assertEquals(new BigDecimal("493.8000"), order.getTradeValue());
        assertNotNull(order.getAcceptedAt());
        assertNotNull(order.getFilledAt());
    }

    @Test
    void insert_withValidOrder_usesGeneratedIdAndPostgreSqlDefaults() {
        // Arrange
        OrderEntity newOrder = newOrder(firstClientId, instrumentId, 8);

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
        assertEquals(8, persistedOrder.getQuantity());
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
                    symbol, instrument_name, asset_class, currency, is_tradable, price
                )
                VALUES (?, 'Test Instrument', 'Equity', 'USD', TRUE, 10.0000)
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

    private static OrderEntity newOrder(Long clientId, Long instrumentId, int quantity) {
        OrderEntity order = new OrderEntity();
        order.setClientId(clientId);
        order.setInstrumentId(instrumentId);
        order.setOrderType(OrderType.BUY);
        order.setQuantity(quantity);
        return order;
    }
}
