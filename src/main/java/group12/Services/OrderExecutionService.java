package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.HoldingEntity;
import group12.Entities.InstrumentEntity;
import group12.Entities.OrderEntity;
import group12.Entities.OrderFailureReason;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Repository.ClientRepository;
import group12.Repository.HoldingRepository;
import group12.Repository.InstrumentRepository;
import group12.Repository.OrderRepository;
import group12.dto.OrderFilledEvent;
import group12.exception.OrderLifecycleException;
import group12.exception.OrderNotFoundException;
import group12.exception.RetryableOrderExecutionException;
import group12.kafka.OrderProducer;
import group12.orderlifecycle.OrderLifecycleProperties;
import group12.orderlifecycle.QuoteFreshnessPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Service
public class OrderExecutionService {

    private static final int HOLDING_AVERAGE_COST_SCALE = 16;
    private static final RoundingMode HOLDING_AVERAGE_COST_ROUNDING = RoundingMode.HALF_UP;

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final HoldingRepository holdingRepository;
    private final InstrumentRepository instrumentRepository;
    private final QuoteFreshnessPolicy quoteFreshnessPolicy;
    private final OrderLifecycleProperties lifecycleProperties;
    private final Clock clock;
    private final OrderProducer orderProducer;

    public OrderExecutionService(
            OrderRepository orderRepository,
            ClientRepository clientRepository,
            HoldingRepository holdingRepository,
            InstrumentRepository instrumentRepository,
            QuoteFreshnessPolicy quoteFreshnessPolicy,
            OrderLifecycleProperties lifecycleProperties,
            @Qualifier("orderLifecycleClock") Clock clock,
            OrderProducer orderProducer
    ) {
        this.orderRepository = orderRepository;
        this.clientRepository = clientRepository;
        this.holdingRepository = holdingRepository;
        this.instrumentRepository = instrumentRepository;
        this.quoteFreshnessPolicy = quoteFreshnessPolicy;
        this.lifecycleProperties = lifecycleProperties;
        this.clock = clock;
        this.orderProducer = orderProducer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderEntity executeAcceptedOrder(Long orderId) {
        OrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.ACCEPTED) {
            return order;
        }

        ClientEntity client = clientRepository.findByIdForUpdate(order.getClientId());
        if (client == null) {
            throw invariantViolation(order, "client " + order.getClientId() + " does not exist");
        }

        HoldingEntity holding = holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                order.getInstrumentId(),
                order.getClientId()
        );

        InstrumentEntity instrument = instrumentRepository.findById(order.getInstrumentId())
                .orElse(null);
        if (instrument == null) {
            fail(order, OrderFailureReason.INSTRUMENT_NOT_FOUND);
            return reload(order.getOrderId());
        }
        if (!instrument.isTradable()) {
            fail(order, OrderFailureReason.INSTRUMENT_NOT_TRADABLE);
            return reload(order.getOrderId());
        }

        BigDecimal executionPrice = order.getOrderType() == OrderType.BUY
                ? instrument.getAskPrice()
                : instrument.getBidPrice();
        OffsetDateTime quoteAsOf = instrument.getQuoteAsOf();
        if (executionPrice == null
                || quoteAsOf == null
                || !quoteFreshnessPolicy.isFresh(quoteAsOf)) {
            return handleUnavailableQuote(order);
        }
        if (executionPrice.signum() <= 0) {
            throw invariantViolation(order, "persisted execution quote is not positive");
        }

        if (order.getOrderType() == OrderType.BUY) {
            executeBuy(order, client, holding, executionPrice, quoteAsOf);
        } else if (order.getOrderType() == OrderType.SELL) {
            executeSell(order, client, holding, executionPrice, quoteAsOf);
        } else {
            throw invariantViolation(order, "order type is unsupported");
        }

        OrderEntity filledOrder = reload(order.getOrderId());
        
        // Publish OrderFilledEvent to Kafka after successful execution
        if (filledOrder.getStatus() == OrderStatus.FILLED) {
            OrderFilledEvent event = new OrderFilledEvent(
                filledOrder.getOrderId(),
                filledOrder.getClientId(),
                filledOrder.getQuantity().doubleValue(),
                filledOrder.getExecutionPrice(),
                LocalDateTime.now()
            );
            orderProducer.publishOrderFilled(event);
        }
        
        return filledOrder;
    }

    private void executeBuy(
            OrderEntity order,
            ClientEntity client,
            HoldingEntity holding,
            BigDecimal executionPrice,
            OffsetDateTime quoteAsOf
    ) {
        BigDecimal reservedCash = order.getReservedCash();
        if (reservedCash == null || reservedCash.signum() <= 0) {
            fail(order, OrderFailureReason.INVALID_RESERVED_CASH);
            return;
        }

        BigDecimal tradeValue = order.getQuantity().multiply(executionPrice);
        if (tradeValue.compareTo(reservedCash) > 0) {
            fail(order, OrderFailureReason.PRICE_EXCEEDS_RESERVED_CASH);
            return;
        }

        BigDecimal balance = client.getAccountBalance();
        if (balance == null) {
            throw invariantViolation(order, "client account balance is null");
        }
        if (balance.compareTo(tradeValue) < 0) {
            fail(order, OrderFailureReason.INSUFFICIENT_ACTUAL_CASH);
            return;
        }

        if (holding == null) {
            HoldingEntity newHolding = new HoldingEntity();
            newHolding.setClientId(order.getClientId());
            newHolding.setInstrumentId(order.getInstrumentId());
            newHolding.setQuantity(order.getQuantity());
            newHolding.setAverageCost(executionPrice);
            requireSingleRow(
                    holdingRepository.insert(newHolding),
                    "insert holding for order " + order.getOrderId()
            );
        } else {
            BigDecimal newQuantity = holding.getQuantity().add(order.getQuantity());
            BigDecimal totalCost = holding.getQuantity().multiply(holding.getAverageCost())
                    .add(order.getQuantity().multiply(executionPrice));
            BigDecimal newAverageCost = totalCost.divide(
                    newQuantity,
                    HOLDING_AVERAGE_COST_SCALE,
                    HOLDING_AVERAGE_COST_ROUNDING
            );
            requireSingleRow(
                    holdingRepository.updateHolding(
                            order.getInstrumentId(),
                            order.getClientId(),
                            newQuantity,
                            newAverageCost
                    ),
                    "update holding for order " + order.getOrderId()
            );
        }

        requireSingleRow(
                clientRepository.updateAccountBalance(
                        order.getClientId(),
                        balance.subtract(tradeValue)
                ),
                "debit client for order " + order.getOrderId()
        );
        fillAsFinalWrite(order, executionPrice, quoteAsOf);
    }

    private void executeSell(
            OrderEntity order,
            ClientEntity client,
            HoldingEntity holding,
            BigDecimal executionPrice,
            OffsetDateTime quoteAsOf
    ) {
        if (holding == null) {
            fail(order, OrderFailureReason.HOLDING_NOT_FOUND);
            return;
        }
        if (holding.getQuantity().compareTo(order.getQuantity()) < 0) {
            fail(order, OrderFailureReason.INSUFFICIENT_HOLDINGS);
            return;
        }

        int quantityComparison = holding.getQuantity().compareTo(order.getQuantity());
        if (quantityComparison == 0) {
            requireSingleRow(
                    holdingRepository.deleteHoldingByHoldingIdAndClientId(
                            holding.getHoldingId(),
                            order.getClientId()
                    ),
                    "delete holding for order " + order.getOrderId()
            );
        } else {
            requireSingleRow(
                    holdingRepository.updateHolding(
                            order.getInstrumentId(),
                            order.getClientId(),
                            holding.getQuantity().subtract(order.getQuantity()),
                            holding.getAverageCost()
                    ),
                    "update holding for order " + order.getOrderId()
            );
        }

        BigDecimal balance = client.getAccountBalance();
        if (balance == null) {
            throw invariantViolation(order, "client account balance is null");
        }
        BigDecimal tradeValue = order.getQuantity().multiply(executionPrice);
        requireSingleRow(
                clientRepository.updateAccountBalance(
                        order.getClientId(),
                        balance.add(tradeValue)
                ),
                "credit client for order " + order.getOrderId()
        );
        fillAsFinalWrite(order, executionPrice, quoteAsOf);
    }

    private OrderEntity handleUnavailableQuote(OrderEntity order) {
        OffsetDateTime acceptedAt = order.getAcceptedAt();
        if (acceptedAt == null) {
            throw invariantViolation(order, "accepted_at is null");
        }

        boolean waitElapsed = !clock.instant().isBefore(
                acceptedAt.toInstant().plus(
                        lifecycleProperties.getExecution().getMaxWait()
                )
        );
        if (waitElapsed) {
            fail(order, OrderFailureReason.MARKET_QUOTE_TIMEOUT);
            return reload(order.getOrderId());
        }

        throw new RetryableOrderExecutionException(
                "Order " + order.getOrderId() + " is waiting for a current market quote"
        );
    }

    private void fillAsFinalWrite(
            OrderEntity order,
            BigDecimal executionPrice,
            OffsetDateTime quoteAsOf
    ) {
        requireSingleRow(
                orderRepository.fillAcceptedOrder(
                        order.getOrderId(),
                        executionPrice,
                        quoteAsOf
                ),
                "fill order " + order.getOrderId()
        );
    }

    private void fail(OrderEntity order, OrderFailureReason reason) {
        requireSingleRow(
                orderRepository.failAcceptedOrder(order.getOrderId(), reason.name()),
                "fail order " + order.getOrderId()
        );
    }

    private void requireSingleRow(int rowsAffected, String operation) {
        if (rowsAffected != 1) {
            throw new OrderLifecycleException(
                    "Expected one row to " + operation + " but affected " + rowsAffected
            );
        }
    }

    private OrderEntity reload(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }

    private OrderLifecycleException invariantViolation(OrderEntity order, String detail) {
        return new OrderLifecycleException(
                "Cannot execute order " + order.getOrderId() + ": " + detail
        );
    }
}
