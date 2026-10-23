package group12.Repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class TransactionsFundingIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

    private SqlSessionFactory sessions;
    private final OffsetDateTime from = OffsetDateTime.parse("2026-10-16T12:00:00Z");
    private final OffsetDateTime to = OffsetDateTime.parse("2026-10-23T12:00:00Z");

    @BeforeEach
    void setUp() {
        var datasource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var jdbc = new JdbcTemplate(datasource);
        jdbc.execute("DROP TABLE IF EXISTS transactions");
        jdbc.execute("CREATE TABLE transactions (transaction_type text, amount numeric(40,16), created_at timestamptz)");
        jdbc.execute("""
                INSERT INTO transactions VALUES
                    ('DEPOSIT', 100.25, '2026-10-16T12:00:00Z'),
                    ('DEPOSIT', 200.50, '2026-10-20T12:00:00Z'),
                    ('WITHDRAWAL', -75.25, '2026-10-20T12:00:00Z'),
                    ('WITHDRAWAL', -25, '2026-10-21T12:00:00Z'),
                    ('BUY', -999, '2026-10-20T12:00:00Z'),
                    ('SELL', 999, '2026-10-20T12:00:00Z'),
                    ('DEPOSIT', 10, '2026-10-15T12:00:00Z'),
                    ('DEPOSIT', 999, '2026-10-23T12:00:00Z'),
                    ('WITHDRAWAL', -999, '2026-10-23T12:00:00Z')
                """);
        var configuration = new Configuration(new Environment("funding-test", new JdbcTransactionFactory(), datasource));
        configuration.addMapper(TransactionsRepository.class);
        sessions = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void fundingTotalsExcludeTradesAndUseHalfOpenDateWindow() {
        try (var session = sessions.openSession()) {
            var repository = session.getMapper(TransactionsRepository.class);
            assertEquals(0, new BigDecimal("300.75").compareTo(repository.getFundingTotal("DEPOSIT", from, to)));
            assertEquals(0, new BigDecimal("100.25").compareTo(repository.getFundingTotal("WITHDRAWAL", from, to)));
        }
    }

    @Test
    void allTimeHasNoLowerBoundAndEmptyPeriodsReturnZero() {
        try (var session = sessions.openSession()) {
            var repository = session.getMapper(TransactionsRepository.class);
            assertEquals(0, new BigDecimal("310.75").compareTo(repository.getFundingTotal("DEPOSIT", null, to)));
            assertEquals(0, BigDecimal.ZERO.compareTo(repository.getFundingTotal("WITHDRAWAL", to.plusYears(1), to.plusYears(2))));
        }
    }
}