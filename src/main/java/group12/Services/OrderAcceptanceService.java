package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.HoldingEntity;
import group12.Entities.InstrumentEntity;
import group12.Entities.OrderEntity;
import group12.Entities.OrderRejectionReason;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Repository.ClientRepository;
import group12.Repository.HoldingRepository;
import group12.Repository.InstrumentRepository;
import group12.Repository.OrderRepository;
import group12.exception.OrderLifecycleException;
import group12.exception.OrderNotFoundException;
import group12.orderlifecycle.QuoteFreshnessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class OrderAcceptanceService {

    private final OrderRepository orderRepository;
    private final ClientRepository clientRepository;
    private final HoldingRepository holdingRepository;
    private final InstrumentRepository instrumentRepository;
    private final QuoteFreshnessPolicy quoteFreshnessPolicy;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrderEntity acceptSubmittedOrder(Long orderId) {
        OrderEntity order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.SUBMITTED) {
            return order;
        }

        InstrumentEntity instrument = instrumentRepository.findById(order.getInstrumentId())
                .orElse(null);
        if (instrument == null) {
            return reject(orderId, OrderRejectionReason.INSTRUMENT_NOT_FOUND);
        }
        if (!instrument.isTradable()) {
            return reject(orderId, OrderRejectionReason.INSTRUMENT_NOT_TRADABLE);
        }
        if (instrument.getBidPrice() == null
                || instrument.getAskPrice() == null
                || instrument.getQuoteAsOf() == null) {
            return reject(orderId, OrderRejectionReason.MISSING_QUOTE);
        }
        if (!quoteFreshnessPolicy.isFresh(instrument.getQuoteAsOf())) {
            return reject(orderId, OrderRejectionReason.STALE_QUOTE);
        }

        if (order.getOrderType() == OrderType.BUY) {
            return acceptBuy(order, instrument.getAskPrice());
        }
        if (order.getOrderType() == OrderType.SELL) {
            return acceptSell(order);
        }

        throw new OrderLifecycleException(
                "Unsupported order type for order " + order.getOrderId()
        );
    }

    private OrderEntity acceptBuy(OrderEntity order, BigDecimal askPrice) {
        ClientEntity client = clientRepository.findByIdForUpdate(order.getClientId());
        if (client == null) {
            throw new OrderLifecycleException(
                    "Client " + order.getClientId() + " not found for order " + order.getOrderId()
            );
        }

        BigDecimal reservedCash = order.getQuantity().multiply(askPrice);
        BigDecimal acceptedReservations = orderRepository.sumActiveBuyReservedCash(
                order.getClientId()
        );
        BigDecimal availableCash = client.getAccountBalance().subtract(acceptedReservations);

        if (availableCash.compareTo(reservedCash) < 0) {
            return reject(order.getOrderId(), OrderRejectionReason.INSUFFICIENT_FUNDS);
        }

        requireSingleRow(
                orderRepository.acceptSubmittedBuyOrder(order.getOrderId(), reservedCash),
                order.getOrderId(),
                OrderStatus.ACCEPTED
        );
        return reload(order.getOrderId());
    }

    private OrderEntity acceptSell(OrderEntity order) {
        HoldingEntity holding = holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                order.getInstrumentId(),
                order.getClientId()
        );
        if (holding == null) {
            return reject(order.getOrderId(), OrderRejectionReason.INSUFFICIENT_HOLDINGS);
        }

        BigDecimal acceptedQuantity = orderRepository.sumActiveSellQuantity(
                order.getClientId(),
                order.getInstrumentId()
        );
        BigDecimal availableQuantity = holding.getQuantity().subtract(acceptedQuantity);

        if (availableQuantity.compareTo(order.getQuantity()) < 0) {
            return reject(order.getOrderId(), OrderRejectionReason.INSUFFICIENT_HOLDINGS);
        }

        requireSingleRow(
                orderRepository.acceptSubmittedSellOrder(order.getOrderId()),
                order.getOrderId(),
                OrderStatus.ACCEPTED
        );
        return reload(order.getOrderId());
    }

    private OrderEntity reject(Long orderId, OrderRejectionReason reason) {
        requireSingleRow(
                orderRepository.rejectSubmittedOrder(orderId, reason.name()),
                orderId,
                OrderStatus.REJECTED
        );
        return reload(orderId);
    }

    private void requireSingleRow(int rowsAffected, Long orderId, OrderStatus targetStatus) {
        if (rowsAffected != 1) {
            throw new OrderLifecycleException(
                    "Expected to transition order " + orderId + " to " + targetStatus
                            + " but updated " + rowsAffected + " rows"
            );
        }
    }

    private OrderEntity reload(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found"));
    }
}
