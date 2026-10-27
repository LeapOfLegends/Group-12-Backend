package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.HoldingEntity;
import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Entities.TransactionsEntity;
import group12.Repository.ClientRepository;
import group12.Repository.HoldingRepository;
import group12.Repository.OrderRepository;
import group12.Repository.TransactionsRepository;
import group12.dto.PortfolioFinancialEvent;
import group12.dto.PortfolioFinancialHistory;
import group12.dto.PortfolioFinancialState;
import group12.exception.ClientNotFoundException;
import group12.exception.FinancialHistoryReconstructionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PortfolioFinancialHistoryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ClientRepository clientRepository;
    private final TransactionsRepository transactionsRepository;
    private final OrderRepository orderRepository;
    private final HoldingRepository holdingRepository;

    public PortfolioFinancialHistoryService(
            ClientRepository clientRepository,
            TransactionsRepository transactionsRepository,
            OrderRepository orderRepository,
            HoldingRepository holdingRepository
    ) {
        this.clientRepository = clientRepository;
        this.transactionsRepository = transactionsRepository;
        this.orderRepository = orderRepository;
        this.holdingRepository = holdingRepository;
    }

    /**
     * Replays the complete financial ledger once and captures states at the supplied,
     * nondecreasing timestamps. Events at a requested timestamp are included in that state.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PortfolioFinancialHistory reconstruct(
            Long clientId,
            List<OffsetDateTime> timestamps
    ) {
        Objects.requireNonNull(clientId, "clientId is required");
        validateTimestamps(timestamps);

        ClientEntity client = clientRepository.findById(clientId);
        if (client == null) {
            throw new ClientNotFoundException("Client not found with id: " + clientId);
        }
        if (client.getCreatedAt() == null) {
            throw failure(clientId, "account creation time is missing");
        }

        List<TransactionsEntity> transactions =
                transactionsRepository.getFinancialHistoryByClientId(clientId);
        List<OrderEntity> filledOrders = orderRepository.findFilledByClientId(clientId);
        List<HoldingEntity> currentHoldings =
                holdingRepository.getHoldingsByClientId(clientId);

        Map<Long, OrderEntity> ordersById = indexFilledOrders(clientId, filledOrders);
        Set<Long> appliedOrderIds = new HashSet<>();
        Map<Long, BigDecimal> quantities = new HashMap<>();
        List<PortfolioFinancialEvent> events = new ArrayList<>(transactions.size());
        List<PortfolioFinancialState> states = new ArrayList<>(timestamps.size());

        BigDecimal cash = ZERO;
        int timestampIndex = 0;
        TransactionsEntity previous = null;

        for (TransactionsEntity transaction : transactions) {
            validateTransactionOrder(clientId, previous, transaction);

            while (timestampIndex < timestamps.size()
                    && timestamps.get(timestampIndex).isBefore(transaction.getCreatedAt())) {
                states.add(snapshot(timestamps.get(timestampIndex), cash, quantities));
                timestampIndex++;
            }

            cash = applyTransaction(
                    clientId,
                    transaction,
                    cash,
                    quantities,
                    ordersById,
                    appliedOrderIds,
                    events
            );
            previous = transaction;
        }

        while (timestampIndex < timestamps.size()) {
            states.add(snapshot(timestamps.get(timestampIndex), cash, quantities));
            timestampIndex++;
        }

        if (appliedOrderIds.size() != ordersById.size()) {
            Long missingOrderId = ordersById.keySet().stream()
                    .filter(orderId -> !appliedOrderIds.contains(orderId))
                    .sorted()
                    .findFirst()
                    .orElseThrow();
            throw failure(
                    clientId,
                    "filled order " + missingOrderId + " has no trade transaction"
            );
        }

        reconcileCash(clientId, cash, client.getAccountBalance());
        reconcileHoldings(clientId, quantities, currentHoldings);

        return new PortfolioFinancialHistory(clientId, events, states);
    }

    private void validateTimestamps(List<OffsetDateTime> timestamps) {
        Objects.requireNonNull(timestamps, "timestamps are required");
        OffsetDateTime previous = null;
        for (OffsetDateTime timestamp : timestamps) {
            Objects.requireNonNull(timestamp, "timestamps cannot contain null");
            if (previous != null && timestamp.isBefore(previous)) {
                throw new IllegalArgumentException("timestamps must be in nondecreasing order");
            }
            previous = timestamp;
        }
    }

    private Map<Long, OrderEntity> indexFilledOrders(
            Long clientId,
            List<OrderEntity> filledOrders
    ) {
        Map<Long, OrderEntity> result = new LinkedHashMap<>();
        for (OrderEntity order : filledOrders) {
            if (order == null || order.getOrderId() == null) {
                throw failure(clientId, "a filled order is missing its identifier");
            }
            if (!clientId.equals(order.getClientId())) {
                throw failure(
                        clientId,
                        "filled order " + order.getOrderId() + " belongs to another client"
                );
            }
            if (order.getStatus() != OrderStatus.FILLED) {
                throw failure(clientId, "order " + order.getOrderId() + " is not filled");
            }
            if (order.getFilledAt() == null) {
                throw failure(clientId, "filled order " + order.getOrderId() + " has no fill time");
            }
            if (result.put(order.getOrderId(), order) != null) {
                throw failure(clientId, "filled order " + order.getOrderId() + " is duplicated");
            }
        }
        return result;
    }

    private void validateTransactionOrder(
            Long clientId,
            TransactionsEntity previous,
            TransactionsEntity current
    ) {
        if (current == null || current.getTransactionId() == null) {
            throw failure(clientId, "a transaction is missing its identifier");
        }
        if (!clientId.equals(current.getClientId())) {
            throw failure(
                    clientId,
                    "transaction " + current.getTransactionId() + " belongs to another client"
            );
        }
        if (current.getCreatedAt() == null) {
            throw failure(
                    clientId,
                    "transaction " + current.getTransactionId() + " has no timestamp"
            );
        }
        if (previous == null) {
            return;
        }
        int timestampComparison = current.getCreatedAt().compareTo(previous.getCreatedAt());
        if (timestampComparison < 0
                || (timestampComparison == 0
                && current.getTransactionId() <= previous.getTransactionId())) {
            throw failure(clientId, "transactions are not deterministically ordered");
        }
    }

    private BigDecimal applyTransaction(
            Long clientId,
            TransactionsEntity transaction,
            BigDecimal currentCash,
            Map<Long, BigDecimal> quantities,
            Map<Long, OrderEntity> ordersById,
            Set<Long> appliedOrderIds,
            List<PortfolioFinancialEvent> events
    ) {
        Long transactionId = transaction.getTransactionId();
        String type = transaction.getTransactionType();
        BigDecimal amount = transaction.getAmount();
        BigDecimal balanceAfter = transaction.getBalanceAfter();
        if (type == null || amount == null || balanceAfter == null) {
            throw failure(clientId, "transaction " + transactionId + " is incomplete");
        }

        Long orderId = transaction.getOrderId();
        Long instrumentId = null;
        BigDecimal quantityDelta = null;

        switch (type) {
            case "DEPOSIT" -> validateFundingTransaction(
                    clientId, transactionId, orderId, amount, true
            );
            case "WITHDRAWAL" -> validateFundingTransaction(
                    clientId, transactionId, orderId, amount, false
            );
            case "BUY", "SELL" -> {
                OrderEntity order = validateTradeTransaction(
                        clientId,
                        transaction,
                        ordersById,
                        appliedOrderIds
                );
                instrumentId = order.getInstrumentId();
                quantityDelta = type.equals("BUY")
                        ? order.getQuantity()
                        : order.getQuantity().negate();
                BigDecimal resultingQuantity = quantities.getOrDefault(instrumentId, ZERO)
                        .add(quantityDelta);
                if (resultingQuantity.signum() < 0) {
                    throw failure(
                            clientId,
                            "order " + orderId + " would make instrument "
                                    + instrumentId + " quantity negative"
                    );
                }
                if (resultingQuantity.signum() == 0) {
                    quantities.remove(instrumentId);
                } else {
                    quantities.put(instrumentId, resultingQuantity);
                }
            }
            default -> throw failure(
                    clientId,
                    "transaction " + transactionId + " has unsupported type " + type
            );
        }

        BigDecimal resultingCash = currentCash.add(amount);
        if (resultingCash.signum() < 0) {
            throw failure(
                    clientId,
                    "transaction " + transactionId + " would make cash negative"
            );
        }
        if (resultingCash.compareTo(balanceAfter) != 0) {
            throw failure(
                    clientId,
                    "transaction " + transactionId + " balance_after does not match replayed cash"
            );
        }

        events.add(new PortfolioFinancialEvent(
                transactionId,
                transaction.getCreatedAt(),
                type,
                amount,
                balanceAfter,
                orderId,
                instrumentId,
                quantityDelta
        ));
        return resultingCash;
    }

    private void validateFundingTransaction(
            Long clientId,
            Long transactionId,
            Long orderId,
            BigDecimal amount,
            boolean deposit
    ) {
        if (orderId != null) {
            throw failure(
                    clientId,
                    "funding transaction " + transactionId + " unexpectedly references an order"
            );
        }
        int expectedSign = deposit ? 1 : -1;
        if (amount.signum() != expectedSign) {
            throw failure(clientId, "transaction " + transactionId + " has an invalid amount sign");
        }
    }

    private OrderEntity validateTradeTransaction(
            Long clientId,
            TransactionsEntity transaction,
            Map<Long, OrderEntity> ordersById,
            Set<Long> appliedOrderIds
    ) {
        Long transactionId = transaction.getTransactionId();
        Long orderId = transaction.getOrderId();
        if (orderId == null) {
            throw failure(clientId, "trade transaction " + transactionId + " has no order");
        }
        OrderEntity order = ordersById.get(orderId);
        if (order == null) {
            throw failure(
                    clientId,
                    "trade transaction " + transactionId + " has no matching filled order"
            );
        }
        if (!appliedOrderIds.add(orderId)) {
            throw failure(clientId, "filled order " + orderId + " is applied more than once");
        }
        if (order.getOrderType() == null
                || !order.getOrderType().name().equals(transaction.getTransactionType())) {
            throw failure(clientId, "transaction " + transactionId + " direction mismatches its order");
        }
        if (order.getInstrumentId() == null
                || order.getQuantity() == null
                || order.getQuantity().signum() <= 0
                || order.getExecutionPrice() == null
                || order.getExecutionPrice().signum() <= 0
                || order.getTradeValue() == null
                || order.getTradeValue().signum() <= 0) {
            throw failure(clientId, "filled order " + orderId + " has incomplete execution data");
        }
        BigDecimal calculatedTradeValue = order.getQuantity().multiply(order.getExecutionPrice());
        if (calculatedTradeValue.compareTo(order.getTradeValue()) != 0) {
            throw failure(clientId, "filled order " + orderId + " has an inconsistent trade value");
        }
        BigDecimal expectedAmount = order.getOrderType() == OrderType.BUY
                ? order.getTradeValue().negate()
                : order.getTradeValue();
        if (transaction.getAmount().compareTo(expectedAmount) != 0) {
            throw failure(clientId, "transaction " + transactionId + " amount mismatches its order");
        }
        return order;
    }

    private void reconcileCash(
            Long clientId,
            BigDecimal reconstructedCash,
            BigDecimal currentCash
    ) {
        if (currentCash == null || reconstructedCash.compareTo(currentCash) != 0) {
            throw failure(clientId, "reconstructed cash does not match the current account balance");
        }
    }

    private void reconcileHoldings(
            Long clientId,
            Map<Long, BigDecimal> reconstructed,
            List<HoldingEntity> currentHoldings
    ) {
        Map<Long, BigDecimal> current = new HashMap<>();
        for (HoldingEntity holding : currentHoldings) {
            if (holding == null
                    || !clientId.equals(holding.getClientId())
                    || holding.getInstrumentId() == null
                    || holding.getQuantity() == null
                    || holding.getQuantity().signum() <= 0) {
                throw failure(clientId, "current holdings contain an invalid row");
            }
            if (current.put(holding.getInstrumentId(), holding.getQuantity()) != null) {
                throw failure(
                        clientId,
                        "current holdings contain duplicate instrument " + holding.getInstrumentId()
                );
            }
        }
        if (reconstructed.size() != current.size()) {
            throw failure(clientId, "reconstructed quantities do not match current holdings");
        }
        for (Map.Entry<Long, BigDecimal> entry : reconstructed.entrySet()) {
            BigDecimal currentQuantity = current.get(entry.getKey());
            if (currentQuantity == null || entry.getValue().compareTo(currentQuantity) != 0) {
                throw failure(
                        clientId,
                        "reconstructed quantity for instrument " + entry.getKey()
                                + " does not match current holdings"
                );
            }
        }
    }

    private PortfolioFinancialState snapshot(
            OffsetDateTime timestamp,
            BigDecimal cash,
            Map<Long, BigDecimal> quantities
    ) {
        return new PortfolioFinancialState(timestamp, cash, quantities);
    }

    private FinancialHistoryReconstructionException failure(Long clientId, String detail) {
        return new FinancialHistoryReconstructionException(clientId, detail);
    }
}
