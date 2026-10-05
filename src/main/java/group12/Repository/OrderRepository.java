package group12.Repository;

import group12.Entities.OrderEntity;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface OrderRepository {

    @Select("""
        SELECT
            order_id,
            client_id,
            instrument_id,
            order_type,
            quantity,
            status,
            reserved_cash,
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
            execution_quote_as_of,
            trade_value,
            rejection_reason,
            failure_reason
        FROM orders
        WHERE order_id = #{orderId}
        """)
    Optional<OrderEntity> findById(@Param("orderId") Long orderId);


    @Select("""
        SELECT
            order_id,
            client_id,
            instrument_id,
            order_type,
            quantity,
            status,
            reserved_cash,
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
            execution_quote_as_of,
            trade_value,
            rejection_reason,
            failure_reason
        FROM orders
        WHERE order_id = #{orderId}
        FOR UPDATE
        """)
    Optional<OrderEntity> findByIdForUpdate(@Param("orderId") Long orderId);


    @Select("""
        SELECT
            order_id,
            client_id,
            instrument_id,
            order_type,
            quantity,
            status,
            reserved_cash,
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
            execution_quote_as_of,
            trade_value,
            rejection_reason,
            failure_reason
        FROM orders
        WHERE client_id = #{clientId}
        ORDER BY submitted_at DESC
        """)
    List<OrderEntity> findByClientId(@Param("clientId") Long clientId);


    @Insert("""
        INSERT INTO orders (
            client_id,
            instrument_id,
            order_type,
            quantity
        )
        VALUES (
            #{clientId},
            #{instrumentId},
            #{orderType},
            #{quantity}
        )
        """)
    @Options(
            useGeneratedKeys = true,
            keyProperty = "orderId",
            keyColumn = "order_id"
    )
    int insert(OrderEntity order);


    @Update("""
        UPDATE orders
        SET status = 'ACCEPTED',
            accepted_at = CURRENT_TIMESTAMP,
            reserved_cash = #{reservedCash}
        WHERE order_id = #{orderId}
          AND status = 'SUBMITTED'
          AND order_type = 'BUY'
          AND #{reservedCash} > 0
        """)
    int acceptSubmittedBuyOrder(
            @Param("orderId") Long orderId,
            @Param("reservedCash") BigDecimal reservedCash
    );


    @Update("""
        UPDATE orders
        SET status = 'ACCEPTED',
            accepted_at = CURRENT_TIMESTAMP,
            reserved_cash = NULL
        WHERE order_id = #{orderId}
          AND status = 'SUBMITTED'
          AND order_type = 'SELL'
        """)
    int acceptSubmittedSellOrder(@Param("orderId") Long orderId);


    @Update("""
        UPDATE orders
        SET status = 'REJECTED',
            rejected_at = CURRENT_TIMESTAMP,
            rejection_reason = #{rejectionReason}
        WHERE order_id = #{orderId}
          AND status = 'SUBMITTED'
        """)
    int rejectSubmittedOrder(
            @Param("orderId") Long orderId,
            @Param("rejectionReason") String rejectionReason
    );


    @Update("""
        UPDATE orders
        SET status = 'FILLED',
            execution_price = #{executionPrice},
            execution_quote_as_of = #{executionQuoteAsOf},
            filled_at = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
          AND status = 'ACCEPTED'
          AND #{executionPrice} > 0
          AND #{executionQuoteAsOf} IS NOT NULL
        """)
    int fillAcceptedOrder(
            @Param("orderId") Long orderId,
            @Param("executionPrice") BigDecimal executionPrice,
            @Param("executionQuoteAsOf") OffsetDateTime executionQuoteAsOf
    );


    @Update("""
        UPDATE orders
        SET status = 'FAILED',
            failed_at = CURRENT_TIMESTAMP,
            failure_reason = #{failureReason}
        WHERE order_id = #{orderId}
          AND status = 'ACCEPTED'
        """)
    int failAcceptedOrder(
            @Param("orderId") Long orderId,
            @Param("failureReason") String failureReason
    );


    @Select("""
        SELECT COALESCE(SUM(reserved_cash), 0)
        FROM orders
        WHERE client_id = #{clientId}
          AND order_type = 'BUY'
          AND status = 'ACCEPTED'
        """)
    BigDecimal sumActiveBuyReservedCash(@Param("clientId") Long clientId);


    @Select("""
        SELECT COALESCE(SUM(quantity), 0)
        FROM orders
        WHERE client_id = #{clientId}
          AND instrument_id = #{instrumentId}
          AND order_type = 'SELL'
          AND status = 'ACCEPTED'
        """)
    BigDecimal sumActiveSellQuantity(
            @Param("clientId") Long clientId,
            @Param("instrumentId") Long instrumentId
    );


    @Select("""
        SELECT order_id
        FROM orders
        WHERE status = 'SUBMITTED'
          AND submitted_at < #{submittedBefore}
        ORDER BY submitted_at, order_id
        """)
    List<Long> findSubmittedOrderIdsSubmittedBefore(
            @Param("submittedBefore") OffsetDateTime submittedBefore
    );


    @Select("""
        SELECT order_id
        FROM orders
        WHERE status = 'ACCEPTED'
          AND accepted_at < #{acceptedBefore}
        ORDER BY accepted_at, order_id
        """)
    List<Long> findAcceptedOrderIdsAcceptedBefore(
            @Param("acceptedBefore") OffsetDateTime acceptedBefore
    );
}
