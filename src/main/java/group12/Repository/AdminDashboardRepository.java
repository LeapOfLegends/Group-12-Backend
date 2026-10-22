package group12.Repository;

import group12.dto.CashPositionDTO;
import group12.dto.ClientActivityDTO;
import group12.dto.MarketDataHealthDTO;
import group12.dto.OrderLookupDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface AdminDashboardRepository {

    @Select("""
        SELECT c.client_id, c.first_name, c.last_name, c.email, c.account_balance,
               COUNT(o.order_id) AS total_orders, MAX(o.submitted_at) AS last_order_at
        FROM clients c
        LEFT JOIN orders o ON o.client_id = c.client_id
         WHERE (CAST(#{search} AS TEXT) IS NULL OR CAST(#{search} AS TEXT) = ''
             OR CAST(c.client_id AS TEXT) = CAST(#{search} AS TEXT)
             OR c.email ILIKE CONCAT('%', CAST(#{search} AS TEXT), '%'))
        GROUP BY c.client_id, c.first_name, c.last_name, c.email, c.account_balance
        ORDER BY c.client_id
        LIMIT 100
        """)
    List<ClientActivityDTO> findClientActivity(@Param("search") String search);

    @Select("""
        SELECT
            o.order_id, o.client_id, c.email AS client_email,
            o.instrument_id, i.symbol, o.order_type, o.quantity, o.status,
            o.reserved_cash, o.submitted_at, o.accepted_at, o.filled_at,
            o.execution_price, o.trade_value, o.rejection_reason, o.failure_reason
        FROM orders o
        JOIN clients c ON c.client_id = o.client_id
        JOIN instruments i ON i.instrument_id = o.instrument_id
        WHERE o.order_id = #{orderId}
        """)
    Optional<OrderLookupDTO> findOrder(@Param("orderId") Long orderId);

    @Select("""
        SELECT
            COUNT(*) AS all,
            COUNT(*) FILTER (
                WHERE is_tradable AND quote_as_of >= CURRENT_TIMESTAMP - INTERVAL '30 seconds'
            ) AS fresh,
            COUNT(*) FILTER (
                WHERE is_tradable AND quote_as_of < CURRENT_TIMESTAMP - INTERVAL '30 seconds'
            ) AS stale,
            COUNT(*) FILTER (WHERE is_tradable AND quote_as_of IS NULL) AS no_quote,
            COUNT(*) FILTER (WHERE NOT is_tradable) AS not_tradable
        FROM instruments
        """)
    MarketDataHealthDTO findMarketDataHealth();

    @Select("""
        SELECT client_id, first_name, last_name, email, account_balance, created_at
        FROM clients
         WHERE (CAST(#{search} AS TEXT) IS NULL OR CAST(#{search} AS TEXT) = ''
             OR first_name ILIKE CONCAT('%', CAST(#{search} AS TEXT), '%')
             OR last_name ILIKE CONCAT('%', CAST(#{search} AS TEXT), '%')
             OR email ILIKE CONCAT('%', CAST(#{search} AS TEXT), '%'))
        ORDER BY
             CASE WHEN CAST(#{sort} AS TEXT) = 'highest_balance' THEN account_balance END DESC,
             CASE WHEN CAST(#{sort} AS TEXT) = 'name' THEN last_name END,
             CASE WHEN CAST(#{sort} AS TEXT) = 'name' THEN first_name END,
             CASE WHEN CAST(#{sort} AS TEXT) = 'newest' THEN created_at END DESC,
            client_id
        LIMIT 100
        """)
    List<CashPositionDTO> findCashPositions(
            @Param("search") String search,
            @Param("sort") String sort
    );
}
