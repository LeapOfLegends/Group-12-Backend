package group12.Repository;

import group12.Entities.OrderEntity;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
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
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
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
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
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
            submitted_at,
            accepted_at,
            rejected_at,
            failed_at,
            filled_at,
            execution_price,
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
            accepted_at = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
          AND status = 'SUBMITTED'
        """)
    int acceptSubmittedOrder(@Param("orderId") Long orderId);


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
            filled_at = CURRENT_TIMESTAMP
        WHERE order_id = #{orderId}
          AND status = 'ACCEPTED'
        """)
    int fillAcceptedOrder(
            @Param("orderId") Long orderId,
            @Param("executionPrice") BigDecimal executionPrice
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
}
