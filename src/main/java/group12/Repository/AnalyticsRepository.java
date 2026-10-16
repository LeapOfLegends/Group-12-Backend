package group12.Repository;

import group12.dto.ClientCountDTO;
import group12.dto.MostTradedInstrumentDTO;
import group12.dto.OrderStatusDTO;
import group12.dto.PortfolioValueDTO;
import group12.dto.TopInstrumentPriceDTO;
import group12.dto.TradeVolumeDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnalyticsRepository {

    @Select("""
            SELECT
                CAST(EXTRACT(YEAR FROM created_at) AS INTEGER) AS year,
                COUNT(DISTINCT client_id) AS total_new_clients
            FROM dw.table_records
            GROUP BY EXTRACT(YEAR FROM created_at)
            ORDER BY year
            """)
    List<ClientCountDTO> findClientCounts();

    @Select("""
            SELECT
                holdings_updated AS date,
                account_balance,
                SUM(holding_quantity * instrument_last_price) AS holdings_value,
                account_balance
                    + COALESCE(SUM(holding_quantity * instrument_last_price), 0)
                    AS total_portfolio_value
            FROM dw.table_records
            WHERE client_id = #{clientId}
              AND holdings_updated IS NOT NULL
            GROUP BY holdings_updated, account_balance
            ORDER BY holdings_updated
            """)
    List<PortfolioValueDTO> findClientPortfolio(@Param("clientId") Long clientId);

    @Select("""
            SELECT
                instrument_id,
                symbol,
                COUNT(*) AS traded_orders
            FROM dw.table_records
            GROUP BY instrument_id, symbol
            ORDER BY traded_orders DESC, symbol
            LIMIT 10
            """)
    List<MostTradedInstrumentDTO> findMostTradedInstruments();

    @Select("""
            SELECT
                instrument_id,
                symbol,
                MAX(instrument_last_price) AS price
            FROM dw.table_records
            WHERE asset_class = 'Equity'
            GROUP BY instrument_id, symbol
            ORDER BY price DESC, symbol
            LIMIT 10
            """)
    List<TopInstrumentPriceDTO> findTopInstrumentsByPrice();

    @Select("""
            SELECT
                order_status AS status,
                COUNT(*) AS total_orders
            FROM dw.table_records
            GROUP BY order_status
            ORDER BY total_orders DESC, status
            """)
    List<OrderStatusDTO> findOrderStatus();

    @Select("""
            SELECT
                CAST(EXTRACT(YEAR FROM filled_at) AS INTEGER) AS year,
                COUNT(*) AS total_trades,
                SUM(order_quantity) AS shares_traded,
                SUM(trade_value) AS total_value
            FROM dw.table_records
            WHERE order_status = 'FILLED'
              AND filled_at IS NOT NULL
            GROUP BY EXTRACT(YEAR FROM filled_at)
            ORDER BY year
            """)
    List<TradeVolumeDTO> findTradeVolume();
}
