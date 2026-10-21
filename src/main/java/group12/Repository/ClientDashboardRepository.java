package group12.Repository;

import group12.dto.AssetAllocationDTO;
import group12.dto.ClientDashboardSummaryDTO;
import group12.dto.ClientHoldingDTO;
import group12.dto.ClientOrderDTO;
import group12.dto.PortfolioValueDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ClientDashboardRepository {

    @Select("""
            SELECT
                c.account_balance AS account_cash_balance,
                c.account_balance + COALESCE((
                    SELECT SUM(h.quantity * COALESCE(i.last_price, 0))
                    FROM holdings h
                    JOIN instruments i ON i.instrument_id = h.instrument_id
                    WHERE h.client_id = c.client_id
                ), 0) AS total_portfolio_value,
                COALESCE((
                    SELECT SUM(h.quantity * (COALESCE(i.last_price, 0) - h.average_cost))
                    FROM holdings h
                    JOIN instruments i ON i.instrument_id = h.instrument_id
                    WHERE h.client_id = c.client_id
                ), 0) AS total_return,
                CASE
                    WHEN COALESCE((
                        SELECT SUM(h.quantity * h.average_cost)
                        FROM holdings h
                        WHERE h.client_id = c.client_id
                    ), 0) = 0 THEN 0
                    ELSE COALESCE((
                        SELECT SUM(h.quantity * (COALESCE(i.last_price, 0) - h.average_cost))
                        FROM holdings h
                        JOIN instruments i ON i.instrument_id = h.instrument_id
                        WHERE h.client_id = c.client_id
                    ), 0) * 100
                    / (SELECT SUM(h.quantity * h.average_cost)
                       FROM holdings h
                       WHERE h.client_id = c.client_id)
                END AS total_return_percent,
                c.account_balance - COALESCE((
                    SELECT SUM(o.reserved_cash)
                    FROM orders o
                    WHERE o.client_id = c.client_id
                      AND o.order_type = 'BUY'
                      AND o.status IN ('SUBMITTED', 'ACCEPTED')
                ), 0) AS buying_power
            FROM clients c
            WHERE c.client_id = #{clientId}
            """)
    ClientDashboardSummaryDTO findSummary(@Param("clientId") Long clientId);

    @Select("""
            SELECT
                hh.updated_at AS date,
                c.account_balance,
                SUM(hh.quantity * COALESCE(i.last_price, 0)) AS holdings_value,
                c.account_balance + SUM(hh.quantity * COALESCE(i.last_price, 0))
                    AS total_portfolio_value
            FROM holding_history hh
            JOIN clients c ON c.client_id = hh.client_id
            JOIN instruments i ON i.instrument_id = hh.instrument_id
            WHERE hh.client_id = #{clientId}
            GROUP BY hh.updated_at, c.account_balance
            ORDER BY hh.updated_at
            """)
    List<PortfolioValueDTO> findPortfolioPerformance(@Param("clientId") Long clientId);

    @Select("""
            WITH allocation_values AS (
                SELECT
                    i.asset_class,
                    SUM(h.quantity * COALESCE(i.last_price, 0)) AS market_value
                FROM holdings h
                JOIN instruments i ON i.instrument_id = h.instrument_id
                WHERE h.client_id = #{clientId}
                GROUP BY i.asset_class
            )
            SELECT
                asset_class,
                market_value,
                CASE
                    WHEN SUM(market_value) OVER () = 0 THEN 0
                    ELSE market_value * 100 / SUM(market_value) OVER ()
                END AS allocation_percent
            FROM allocation_values
            ORDER BY market_value DESC, asset_class
            """)
    List<AssetAllocationDTO> findAssetAllocation(@Param("clientId") Long clientId);

    @Select("""
            SELECT
                i.instrument_id,
                i.symbol,
                i.instrument_name,
                i.asset_class,
                h.quantity,
                h.average_cost,
                i.last_price AS current_price,
                h.quantity * COALESCE(i.last_price, 0) AS market_value,
                h.quantity * (COALESCE(i.last_price, 0) - h.average_cost)
                    AS unrealized_return,
                CASE
                    WHEN h.quantity * h.average_cost = 0 THEN 0
                    ELSE (h.quantity * (COALESCE(i.last_price, 0) - h.average_cost)) * 100
                         / (h.quantity * h.average_cost)
                END AS unrealized_return_percent
            FROM holdings h
            JOIN instruments i ON i.instrument_id = h.instrument_id
            WHERE h.client_id = #{clientId}
            ORDER BY market_value DESC, i.symbol
            """)
    List<ClientHoldingDTO> findCurrentHoldings(@Param("clientId") Long clientId);

    @Select("""
            SELECT
                o.order_id,
                o.instrument_id,
                i.symbol,
                o.order_type,
                o.quantity,
                o.status,
                o.reserved_cash,
                o.submitted_at,
                o.filled_at,
                o.execution_price,
                o.trade_value
            FROM orders o
            JOIN instruments i ON i.instrument_id = o.instrument_id
            WHERE o.client_id = #{clientId}
            ORDER BY o.submitted_at DESC, o.order_id DESC
            LIMIT 10
            """)
    List<ClientOrderDTO> findRecentOrders(@Param("clientId") Long clientId);
}
