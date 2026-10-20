package group12.Services;

import group12.Repository.TransactionsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import group12.Entities.TransactionsEntity;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionsService {

	private final TransactionsRepository transactionsRepository;

	public List<TransactionsEntity> getTransactionsByClientId(Long clientId) {
		return transactionsRepository.getTransactionsByClientId(clientId);
	}

	public TransactionsEntity getTransactionById(Long transactionId) {
		return transactionsRepository.getTransactionById(transactionId);
	}

	public TransactionsEntity getTransactionByIdAndClientId(Long transactionId, Long clientId) {
		return transactionsRepository.getTransactionByIdAndClientId(transactionId, clientId);
	}

	@Transactional
	public void deposit(Long clientId, BigDecimal amount, BigDecimal balanceAfter) {
		validateAmount(amount);
		insertSuccessfully(
				transactionsRepository.deposit(clientId, amount, balanceAfter),
				"deposit"
		);
	}

	@Transactional
	public void withdraw(Long clientId, BigDecimal amount, BigDecimal balanceAfter) {
		validateAmount(amount);
		insertSuccessfully(
				transactionsRepository.withdraw(clientId, amount, balanceAfter),
				"withdrawal"
		);
	}

	@Transactional
	public void buy(Long clientId, Long orderId, BigDecimal amount, BigDecimal balanceAfter) {
		validateAmount(amount);
		insertSuccessfully(
				transactionsRepository.buy(clientId, orderId, amount, balanceAfter),
				"buy"
		);
	}

	@Transactional
	public void sell(Long clientId, Long orderId, BigDecimal amount, BigDecimal balanceAfter) {
		validateAmount(amount);
		insertSuccessfully(
				transactionsRepository.sell(clientId, orderId, amount, balanceAfter),
				"sell"
		);
	}

	private void validateAmount(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			log.error("Invalid transaction amount: {}", amount);
			throw new IllegalArgumentException("Amount must be greater than zero");
		}
	}

	private void insertSuccessfully(int rowsAffected, String transactionType) {
		if (rowsAffected != 1) {
			log.error(
					"Failed to record {} transaction; expected one inserted row but affected {} rows",
					transactionType,
					rowsAffected
			);
			throw new IllegalStateException(
					"Failed to record " + transactionType + " transaction"
			);
		}
	}
}
