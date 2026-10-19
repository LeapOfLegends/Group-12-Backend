package group12.Repository;

import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;


@Mapper
public interface TransactionsRepository {

    @Insert("""
        INSERT INTO transactions (
            client_id,
            amount,
            balance_after,
            transaction_type
        )
        VALUES
            (#{clientId}, #{amount}, #{balanceAfter}, 'DEPOSIT')
            """)
    int deposit(@Param("clientId") Long clientId, @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    @Insert("""
        INSERT INTO transactions (
            client_id,
            amount,
            balance_after,
            transaction_type
        )
        VALUES
            (#{clientId}, -#{amount}, #{balanceAfter}, 'WITHDRAWAL')
        """)
    int withdraw(
        @Param("clientId") Long clientId, @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

    @Insert("""
        INSERT INTO transactions (
            client_id,
            order_id,
            amount,
            balance_after,
            transaction_type
        )
        VALUES
            (#{clientId}, #{orderId}, -#{amount}, #{balanceAfter}, 'BUY')
        """)
    int buy(@Param("clientId") Long clientId, @Param("orderId") Long orderId, @Param("amount") BigDecimal amount,  @Param("balanceAfter") BigDecimal balanceAfter);

    @Insert("""
        INSERT INTO transactions (
            client_id,
            order_id,
            amount,
            balance_after,
            transaction_type
        )
        VALUES
            (#{clientId}, #{orderId}, #{amount}, #{balanceAfter}, 'SELL')
        """)
    int sell(@Param("clientId") Long clientId, @Param("orderId") Long orderId, @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter);

}