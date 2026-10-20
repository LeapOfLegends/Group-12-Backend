package group12.Repository;

import org.apache.ibatis.annotations.*;
import group12.Entities.TransactionsEntity;
import java.math.BigDecimal;
import java.util.List;


@Mapper
public interface TransactionsRepository {

    @Select("""
        SELECT
            transaction_id,
            client_id,
            order_id,
            transaction_type,
            amount,
            balance_after,
            created_at
        FROM transactions
        WHERE client_id = #{clientId}
        ORDER BY created_at DESC, transaction_id DESC
        """)
    List<TransactionsEntity> getTransactionsByClientId(@Param("clientId") Long clientId);

    @Select("""
        SELECT
            transaction_id,
            client_id,
            order_id,
            transaction_type,
            amount,
            balance_after,
            created_at
        FROM transactions
        WHERE transaction_id = #{transactionId}
        """)
    TransactionsEntity getTransactionById(@Param("transactionId") Long transactionId);

    @Select("""
        SELECT
            transaction_id,
            client_id,
            order_id,
            transaction_type,
            amount,
            balance_after,
            created_at
        FROM transactions
        WHERE transaction_id = #{transactionId}
          AND client_id = #{clientId}
        """)
    TransactionsEntity getTransactionByIdAndClientId(
            @Param("transactionId") Long transactionId,
            @Param("clientId") Long clientId
    );

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