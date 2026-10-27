package group12.Services;

import group12.dto.PortfolioFinancialHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PortfolioFinancialHistoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("portfolio_history_test")
                    .withUsername("portfolio_history_test")
                    .withPassword("portfolio_history_test")
                    .withInitScript("order-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private PortfolioFinancialHistoryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long clientId;
    private Long otherClientId;
    private Long instrumentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE transactions, holdings, orders, clients, instruments, markets "
                        + "RESTART IDENTITY CASCADE"
        );
        clientId = insertClient("history@test", "000-00-0001", "90");
        otherClientId = insertClient("other@test", "000-00-0002", "55");
        instrumentId = jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    symbol, instrument_name, asset_class, currency, is_tradable
                ) VALUES ('HIST', 'History Instrument', 'Equity', 'USD', TRUE)
                RETURNING instrument_id
                """, Long.class);
    }

    @Test
    void reconstructsFromClientScopedChronologicalQueriesAndIgnoresUnfilledOrders() {
        OffsetDateTime depositAt = OffsetDateTime.parse("2026-01-02T10:00:00Z");
        OffsetDateTime buyAt = depositAt.plusMinutes(1);

        jdbcTemplate.update("""
                INSERT INTO transactions (
                    client_id, transaction_type, amount, balance_after, created_at
                ) VALUES (?, 'DEPOSIT', 100, 100, ?)
                """, clientId, depositAt);
        Long buyOrderId = jdbcTemplate.queryForObject("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    filled_at, execution_price
                ) VALUES (?, ?, 'BUY', 1, 'FILLED', ?, 10)
                RETURNING order_id
                """, Long.class, clientId, instrumentId, buyAt);
        jdbcTemplate.update("""
                INSERT INTO transactions (
                    client_id, order_id, transaction_type, amount, balance_after, created_at
                ) VALUES (?, ?, 'BUY', -10, 90, ?)
                """, clientId, buyOrderId, buyAt);
        jdbcTemplate.update("""
                INSERT INTO holdings (client_id, instrument_id, quantity, average_cost)
                VALUES (?, ?, 1, 10)
                """, clientId, instrumentId);

        jdbcTemplate.update("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status, rejected_at
                ) VALUES (?, ?, 'BUY', 99, 'REJECTED', ?)
                """, clientId, instrumentId, buyAt.plusSeconds(1));
        jdbcTemplate.update("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status
                ) VALUES (?, ?, 'BUY', 99, 'SUBMITTED')
                """, clientId, instrumentId);
        jdbcTemplate.update("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status,
                    reserved_cash, accepted_at
                ) VALUES (?, ?, 'BUY', 99, 'ACCEPTED', 990, ?)
                """, clientId, instrumentId, buyAt.plusSeconds(2));
        jdbcTemplate.update("""
                INSERT INTO orders (
                    client_id, instrument_id, order_type, quantity, status, failed_at
                ) VALUES (?, ?, 'BUY', 99, 'FAILED', ?)
                """, clientId, instrumentId, buyAt.plusSeconds(3));
        jdbcTemplate.update("""
                INSERT INTO transactions (
                    client_id, transaction_type, amount, balance_after, created_at
                ) VALUES (?, 'DEPOSIT', 55, 55, ?)
                """, otherClientId, depositAt);

        PortfolioFinancialHistory history = service.reconstruct(
                clientId,
                List.of(depositAt.minusNanos(1), depositAt, buyAt)
        );

        assertEquals(2, history.events().size());
        assertEquals(clientId, history.clientId());
        assertMoney("0", history.states().get(0).cashBalance());
        assertMoney("100", history.states().get(1).cashBalance());
        assertMoney("90", history.states().get(2).cashBalance());
        assertMoney("1", history.states().get(2).instrumentQuantities().get(instrumentId));
        assertTrue(history.states().get(0).instrumentQuantities().isEmpty());
    }

    private Long insertClient(String email, String ssn, String balance) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO clients (
                    first_name, last_name, email, password_hash, ssn,
                    phone_number, account_balance, created_at
                ) VALUES (
                    'Test', 'Client', ?, 'hash', ?, '555-000-0000', ?,
                    TIMESTAMP '2026-01-01 00:00:00'
                )
                RETURNING client_id
                """, Long.class, email, ssn, new BigDecimal(balance));
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
