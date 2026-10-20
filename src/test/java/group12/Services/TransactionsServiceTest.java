package group12.Services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import group12.Repository.TransactionsRepository;
import group12.Entities.TransactionsEntity;

@ExtendWith(MockitoExtension.class)
class TransactionsServiceTest {

    @Mock
    private TransactionsRepository transactionsRepository;

    private TransactionsService transactionsService;

    @BeforeEach
    void setUp() {
        transactionsService = new TransactionsService(transactionsRepository);
    }

    @Test
    void getTransactionsByClientId_returnsRepositoryResults() {
        List<TransactionsEntity> transactions = List.of(new TransactionsEntity());
        when(transactionsRepository.getTransactionsByClientId(1L)).thenReturn(transactions);

        List<TransactionsEntity> result = transactionsService.getTransactionsByClientId(1L);

        org.junit.jupiter.api.Assertions.assertSame(transactions, result);
        verify(transactionsRepository).getTransactionsByClientId(1L);
    }

    @Test
    void getTransactionById_returnsRepositoryResult() {
        TransactionsEntity transaction = new TransactionsEntity();
        when(transactionsRepository.getTransactionById(10L)).thenReturn(transaction);

        TransactionsEntity result = transactionsService.getTransactionById(10L);

        org.junit.jupiter.api.Assertions.assertSame(transaction, result);
        verify(transactionsRepository).getTransactionById(10L);
    }

    @Test
    void deposit_recordsTransaction() {
        when(transactionsRepository.deposit(1L, amount("25.00"), amount("125.00")))
                .thenReturn(1);

        transactionsService.deposit(1L, amount("25.00"), amount("125.00"));

        verify(transactionsRepository).deposit(1L, amount("25.00"), amount("125.00"));
    }

    @Test
    void withdraw_recordsTransaction() {
        when(transactionsRepository.withdraw(1L, amount("25.00"), amount("75.00")))
                .thenReturn(1);

        transactionsService.withdraw(1L, amount("25.00"), amount("75.00"));

        verify(transactionsRepository).withdraw(1L, amount("25.00"), amount("75.00"));
    }

    @Test
    void buy_recordsTransactionWithOrderId() {
        when(transactionsRepository.buy(1L, 10L, amount("50.00"), amount("50.00")))
                .thenReturn(1);

        transactionsService.buy(1L, 10L, amount("50.00"), amount("50.00"));

        verify(transactionsRepository).buy(1L, 10L, amount("50.00"), amount("50.00"));
    }

    @Test
    void sell_recordsTransactionWithOrderId() {
        when(transactionsRepository.sell(1L, 10L, amount("50.00"), amount("150.00")))
                .thenReturn(1);

        transactionsService.sell(1L, 10L, amount("50.00"), amount("150.00"));

        verify(transactionsRepository).sell(1L, 10L, amount("50.00"), amount("150.00"));
    }

    @Test
    void transactionWithInvalidAmount_throwsWithoutRepositoryCall() {
        assertThrows(
                IllegalArgumentException.class,
                () -> transactionsService.deposit(1L, BigDecimal.ZERO, amount("100.00"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> transactionsService.withdraw(1L, amount("-1.00"), amount("100.00"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> transactionsService.buy(1L, 10L, null, amount("100.00"))
        );

        verify(transactionsRepository, never()).deposit(1L, BigDecimal.ZERO, amount("100.00"));
        verify(transactionsRepository, never()).withdraw(1L, amount("-1.00"), amount("100.00"));
        verify(transactionsRepository, never()).buy(1L, 10L, null, amount("100.00"));
    }

    @Test
    void transactionInsertThatDoesNotAffectOneRow_throws() {
        when(transactionsRepository.deposit(1L, amount("25.00"), amount("125.00")))
                .thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> transactionsService.deposit(1L, amount("25.00"), amount("125.00"))
        );
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
