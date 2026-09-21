package group12.Repository;

import group12.Entities.OrderEntity;
import org.apache.ibatis.annotations.*;
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
}
