package group12.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;

@Repository
public class PlatformMetricsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Autowired
    public PlatformMetricsRepository(
            @Value("${warehouse.datasource.url}") String url,
            @Value("${warehouse.datasource.username}") String username,
            @Value("${warehouse.datasource.password}") String password
    ) {
        this(new NamedParameterJdbcTemplate(new DriverManagerDataSource(url, username, password)));
    }

    PlatformMetricsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CurrencyMetrics> summarize(Instant from, Instant to) {
        var parameters = new MapSqlParameterSource()
                .addValue("from", from == null ? null : from.atOffset(ZoneOffset.UTC))
                .addValue("to", to.atOffset(ZoneOffset.UTC));
        return jdbc.query("""
                WITH latest_records AS (
                    SELECT DISTINCT ON (client_id, instrument_id, submitted_at) *
                    FROM dw.table_records
                    ORDER BY client_id, instrument_id, submitted_at, created_at DESC,
                        GREATEST(filled_at, failed_at, rejected_at, accepted_at, submitted_at) DESC,
                        CASE order_status WHEN 'FILLED' THEN 5 WHEN 'FAILED' THEN 4
                            WHEN 'REJECTED' THEN 3 WHEN 'ACCEPTED' THEN 2 ELSE 1 END DESC
                ), records AS (
                    SELECT *,
                        (CAST(:from AS timestamptz) IS NULL OR submitted_at >= :from)
                            AND submitted_at < :to AS submitted_in_range,
                        (CAST(:from AS timestamptz) IS NULL OR filled_at >= :from)
                            AND filled_at < :to AS filled_in_range
                    FROM latest_records
                )
                SELECT currency,
                    COUNT(*) FILTER (WHERE submitted_in_range) AS total_orders,
                    COUNT(*) FILTER (WHERE order_status = 'FILLED' AND filled_in_range) AS filled_orders,
                    COUNT(*) FILTER (WHERE submitted_in_range AND order_status IN ('SUBMITTED', 'ACCEPTED')) AS pending_orders,
                    COUNT(*) FILTER (WHERE submitted_in_range AND order_status IN ('FAILED', 'REJECTED')) AS failed_rejected_orders,
                    COALESCE(SUM(trade_value) FILTER (WHERE order_status = 'FILLED' AND filled_in_range), 0) AS trading_value
                FROM records
                WHERE submitted_in_range OR filled_in_range
                GROUP BY currency
                ORDER BY currency
                """, parameters, (result, rowNumber) -> new CurrencyMetrics(
                result.getString("currency"),
                result.getLong("total_orders"),
                result.getLong("filled_orders"),
                result.getLong("pending_orders"),
                result.getLong("failed_rejected_orders"),
                result.getBigDecimal("trading_value")
        ));
    }

    public record CurrencyMetrics(String currency, long totalOrders, long filledOrders,
                                  long pendingOrders, long failedRejectedOrders, BigDecimal tradingValue) {
    }
}