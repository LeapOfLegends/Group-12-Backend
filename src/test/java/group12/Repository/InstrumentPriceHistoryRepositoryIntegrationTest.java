package group12.Repository;

import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalTimeframe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "market-data.refresh.enabled=false",
                "market-status.refresh.enabled=false"
        }
)
@Testcontainers(disabledWithoutDocker = true)
class InstrumentPriceHistoryRepositoryIntegrationTest {

    private static final OffsetDateTime WINDOW_START =
            OffsetDateTime.parse("2026-10-20T13:30:00Z");
    private static final OffsetDateTime WINDOW_END = WINDOW_START.plusMinutes(10);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18-alpine")
                    .withDatabaseName("instrument_history_test")
                    .withUsername("instrument_history_test")
                    .withPassword("instrument_history_test")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath("Database-Setup/createTables.sql"),
                            "/docker-entrypoint-initdb.d/001-create-tables.sql"
                    );

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private InstrumentPriceHistoryRepository historyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long marketId;
    private Long instrumentId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE instrument_price_history, instruments, markets "
                        + "RESTART IDENTITY CASCADE"
        );
        marketId = jdbcTemplate.queryForObject(
                "INSERT INTO markets (market_code, market_status) "
                        + "VALUES ('IEX', 'CLOSED') RETURNING market_id",
                Long.class
        );
        instrumentId = insertInstrument("AAPL");
    }

    @Test
    void schemaEnforcesCompositePrimaryKeyAndInstrumentForeignKey() {
        insertHistoryRow(instrumentId, "5Min", WINDOW_START, "250.25");

        assertInsertRejected(instrumentId, "5Min", WINDOW_START, "250.30");
        assertInsertRejected(Long.MAX_VALUE, "5Min", WINDOW_START.plusMinutes(5), "250.30");
        assertThat(historyRowCount()).isOne();
    }

    @ParameterizedTest(name = "timeframe={0}, close={1}")
    @CsvSource(value = {
            "1Hour|1", "5min|1", "''|1", "5Min|0", "5Min|-0.0000000000000001"
    }, delimiter = '|')
    void schemaRejectsInvalidTimeframesAndClosePrices(String timeframe, String closePrice) {
        assertInsertRejected(instrumentId, timeframe, WINDOW_START, closePrice);
    }

    @Test
    void findBarsFiltersByKeyAndHalfOpenWindowAndReturnsAscendingUtcBars() {
        Long otherInstrumentId = insertInstrument("MSFT");
        upsert(instrumentId, HistoricalTimeframe.FIVE_MINUTES,
                completedBar("2026-10-20T09:40:00-04:00", "250.40"),
                completedBar("2026-10-20T09:30:00-04:00", "250.20"),
                completedBar("2026-10-20T09:35:00-04:00", "250.30"));
        upsert(instrumentId, HistoricalTimeframe.THIRTY_MINUTES,
                completedBar(WINDOW_START, "999.00"));
        upsert(otherInstrumentId, HistoricalTimeframe.FIVE_MINUTES,
                completedBar(WINDOW_START, "888.00"));

        List<HistoricalBar> result = findFiveMinuteBars();

        assertThat(result)
                .extracting(HistoricalBar::barStart)
                .containsExactly(WINDOW_START, WINDOW_START.plusMinutes(5));
        assertThat(result).allMatch(HistoricalBar::complete);
    }

    @Test
    void batchUpsertIsIdempotentAndCorrectsAChangedClose() {
        HistoricalBar first = completedBar(WINDOW_START, "250.20");
        HistoricalBar second = completedBar(WINDOW_START.plusMinutes(5), "250.30");

        assertThat(upsert(instrumentId, HistoricalTimeframe.FIVE_MINUTES, first, second))
                .isEqualTo(2);
        assertThat(upsert(instrumentId, HistoricalTimeframe.FIVE_MINUTES, first, second))
                .isZero();
        assertThat(upsert(instrumentId, HistoricalTimeframe.FIVE_MINUTES,
                completedBar(WINDOW_START.plusMinutes(5), "251.00"))).isOne();

        List<HistoricalBar> persisted = findFiveMinuteBars();
        assertThat(persisted).hasSize(2);
        assertThat(persisted.get(1).closePrice()).isEqualByComparingTo("251.00");
    }

    @Test
    void upsertRejectsIncompleteBarsAndTreatsAnEmptyBatchAsANoOp() {
        HistoricalBar incomplete = new HistoricalBar(WINDOW_START, new BigDecimal("250.20"), false);

        assertThatThrownBy(() -> upsert(
                instrumentId, HistoricalTimeframe.FIVE_MINUTES, incomplete
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(upsert(instrumentId, HistoricalTimeframe.FIVE_MINUTES)).isZero();
        assertThat(historyRowCount()).isZero();
    }

    private Long insertInstrument(String symbol) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO instruments (
                    market_id, symbol, instrument_name, asset_class, currency, is_tradable
                )
                VALUES (?, ?, 'Test Instrument', 'Equity', 'USD', TRUE)
                RETURNING instrument_id
                """, Long.class, marketId, symbol);
    }

    private void insertHistoryRow(
            Long targetInstrumentId, String timeframe, OffsetDateTime barStart, String closePrice
    ) {
        jdbcTemplate.update(
                "INSERT INTO instrument_price_history "
                        + "(instrument_id, timeframe, bar_start, close_price) VALUES (?, ?, ?, ?)",
                targetInstrumentId, timeframe, barStart, new BigDecimal(closePrice)
        );
    }

    private void assertInsertRejected(
            Long targetInstrumentId, String timeframe, OffsetDateTime barStart, String closePrice
    ) {
        assertThatThrownBy(
                () -> insertHistoryRow(targetInstrumentId, timeframe, barStart, closePrice)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    private int upsert(
            Long targetInstrumentId, HistoricalTimeframe timeframe, HistoricalBar... bars
    ) {
        return historyRepository.upsertBars(targetInstrumentId, timeframe, List.of(bars));
    }

    private List<HistoricalBar> findFiveMinuteBars() {
        return historyRepository.findBars(
                instrumentId, HistoricalTimeframe.FIVE_MINUTES, WINDOW_START, WINDOW_END
        );
    }

    private int historyRowCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM instrument_price_history", Integer.class);
    }

    private static HistoricalBar completedBar(OffsetDateTime barStart, String closePrice) {
        return new HistoricalBar(barStart, new BigDecimal(closePrice), true);
    }

    private static HistoricalBar completedBar(String barStart, String closePrice) {
        return completedBar(OffsetDateTime.parse(barStart), closePrice);
    }
}
