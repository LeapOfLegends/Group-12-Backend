package group12.Repository;

import group12.dto.PlatformOverviewDTO;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WarehouseRepository {

    private final JdbcTemplate jdbcTemplate;

    public WarehouseRepository(@Qualifier("warehouseJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PlatformOverviewDTO findPlatformOverview() {
        return jdbcTemplate.queryForObject("""
            WITH clients AS (
                SELECT client_id, MAX(account_balance) AS account_balance
                FROM dw.table_records
                GROUP BY client_id
            ), instruments AS (
                SELECT instrument_id, BOOL_OR(is_tradable) AS is_tradable
                FROM dw.table_records
                GROUP BY instrument_id
            )
            SELECT
                (SELECT COUNT(*) FROM clients) AS registered_clients,
                (SELECT COUNT(*) FROM clients WHERE account_balance > 0) AS accounts_with_cash,
                COALESCE((SELECT SUM(account_balance) FROM clients), 0) AS total_client_cash,
                (SELECT COUNT(*) FROM instruments WHERE is_tradable) AS tradable_instruments
            """, (resultSet, rowNum) -> new PlatformOverviewDTO(
                resultSet.getLong("registered_clients"),
                resultSet.getLong("accounts_with_cash"),
                resultSet.getBigDecimal("total_client_cash").setScale(2),
                resultSet.getLong("tradable_instruments")
        ));
    }
}
