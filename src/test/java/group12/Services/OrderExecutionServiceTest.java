package group12.Services;

import group12.Entities.ClientEntity;
import group12.Entities.HoldingEntity;
import group12.Entities.InstrumentEntity;
import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Entities.OrderType;
import group12.Repository.ClientRepository;
import group12.Repository.HoldingRepository;
import group12.Repository.InstrumentRepository;
import group12.Repository.OrderRepository;
import group12.exception.OrderLifecycleException;
import group12.exception.RetryableOrderExecutionException;
import group12.kafka.OrderProducer;
import group12.orderlifecycle.OrderLifecycleProperties;
import group12.orderlifecycle.QuoteFreshnessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T10:00:00Z");
    private static final OffsetDateTime FRESH_QUOTE =
            OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC);

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private InstrumentRepository instrumentRepository;
    @Mock
    private OrderProducer orderProducer;

    private OrderExecutionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrderLifecycleProperties lifecycleProperties = new OrderLifecycleProperties();
        lifecycleProperties.getQuote().setMaxAge(Duration.ofSeconds(30));
        lifecycleProperties.getExecution().setMaxWait(Duration.ofMinutes(2));
        service = new OrderExecutionService(
                orderRepository,
                clientRepository,
                holdingRepository,
                instrumentRepository,
                new QuoteFreshnessPolicy(lifecycleProperties, clock),
                lifecycleProperties,
                clock,
                orderProducer
        );
    }

    @Test
    void buyWithoutHolding_usesAskCreatesHoldingDebitsCashAndFillsLast() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "24");
        ClientEntity client = client("100");
        InstrumentEntity instrument = instrument(true, "9", "10", "99", FRESH_QUOTE);
        stubLockedState(order, client, null, instrument);
        when(holdingRepository.insert(any(HoldingEntity.class))).thenReturn(1);
        when(clientRepository.updateAccountBalance(1L, new BigDecimal("80"))).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(7L, new BigDecimal("10"), FRESH_QUOTE))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        OrderEntity result = service.executeAcceptedOrder(7L);

        assertEquals(OrderStatus.FILLED, result.getStatus());
        ArgumentCaptor<HoldingEntity> holdingCaptor =
                ArgumentCaptor.forClass(HoldingEntity.class);
        verify(holdingRepository).insert(holdingCaptor.capture());
        HoldingEntity inserted = holdingCaptor.getValue();
        assertEquals(new BigDecimal("2"), inserted.getQuantity());
        assertEquals(new BigDecimal("10"), inserted.getAverageCost());
        verify(orderRepository, never()).fillAcceptedOrder(7L, new BigDecimal("99"), FRESH_QUOTE);

        InOrder writes = inOrder(holdingRepository, clientRepository, orderRepository);
        writes.verify(holdingRepository).insert(any(HoldingEntity.class));
        writes.verify(clientRepository).updateAccountBalance(1L, new BigDecimal("80"));
        writes.verify(orderRepository).fillAcceptedOrder(7L, new BigDecimal("10"), FRESH_QUOTE);
    }

    @Test
    void buyWithHolding_updatesQuantityAndDeterministicWeightedAverageCost() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "24");
        ClientEntity client = client("100");
        HoldingEntity holding = holding("3", "8.0000000000000000");
        InstrumentEntity instrument = instrument(true, "9", "10", "99", FRESH_QUOTE);
        stubLockedState(order, client, holding, instrument);
        when(holdingRepository.updateHolding(
                2L,
                1L,
                new BigDecimal("5"),
                new BigDecimal("8.8000000000000000")
        )).thenReturn(1);
        when(clientRepository.updateAccountBalance(1L, new BigDecimal("80"))).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(7L, new BigDecimal("10"), FRESH_QUOTE))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        service.executeAcceptedOrder(7L);

        verify(holdingRepository).updateHolding(
                2L,
                1L,
                new BigDecimal("5"),
                new BigDecimal("8.8000000000000000")
        );
    }

    @Test
    void buyAtReservationFillsAndBuyBelowReservationFills() {
        executeSuccessfulBuy("20", "10");
        executeSuccessfulBuy("25", "10");
    }

    @Test
    void buyAboveReservationFailsWithoutFinancialChanges() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "19.99");
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "PRICE_EXCEEDS_RESERVED_CASH"))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        OrderEntity result = service.executeAcceptedOrder(7L);

        assertEquals(OrderStatus.FAILED, result.getStatus());
        verifyNoInteractionsBeyondLock(holdingRepository);
        verify(clientRepository, never()).updateAccountBalance(any(), any());
        verify(orderRepository, never()).fillAcceptedOrder(any(), any(), any());
    }

    @Test
    void buyWithInsufficientActualCashFailsWithoutNegativeBalanceOrHoldingMutation() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        stubLockedState(
                order,
                client("19.99"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "INSUFFICIENT_ACTUAL_CASH"))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(holdingRepository, never()).insert(any());
        verify(holdingRepository, never()).updateHolding(any(), any(), any(), any());
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void sellPartial_usesBidKeepsAverageCostAndCreditsCash() {
        OrderEntity order = acceptedOrder(OrderType.SELL, "2", null);
        HoldingEntity holding = holding("5", "7.2500000000000000");
        stubLockedState(
                order,
                client("100"),
                holding,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(holdingRepository.updateHolding(
                2L,
                1L,
                new BigDecimal("3"),
                new BigDecimal("7.2500000000000000")
        )).thenReturn(1);
        when(clientRepository.updateAccountBalance(1L, new BigDecimal("118"))).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(7L, new BigDecimal("9"), FRESH_QUOTE))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        service.executeAcceptedOrder(7L);

        verify(holdingRepository).updateHolding(
                2L,
                1L,
                new BigDecimal("3"),
                new BigDecimal("7.2500000000000000")
        );
        verify(orderRepository, never()).fillAcceptedOrder(7L, new BigDecimal("99"), FRESH_QUOTE);
    }

    @Test
    void sellFull_deletesHoldingThenCreditsCashAndFills() {
        OrderEntity order = acceptedOrder(OrderType.SELL, "5", null);
        HoldingEntity holding = holding("5", "7.2500000000000000");
        stubLockedState(
                order,
                client("100"),
                holding,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(holdingRepository.deleteHoldingByHoldingIdAndClientId(3L, 1L)).thenReturn(1);
        when(clientRepository.updateAccountBalance(1L, new BigDecimal("145"))).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(7L, new BigDecimal("9"), FRESH_QUOTE))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        service.executeAcceptedOrder(7L);

        InOrder writes = inOrder(holdingRepository, clientRepository, orderRepository);
        writes.verify(holdingRepository).deleteHoldingByHoldingIdAndClientId(3L, 1L);
        writes.verify(clientRepository).updateAccountBalance(1L, new BigDecimal("145"));
        writes.verify(orderRepository).fillAcceptedOrder(7L, new BigDecimal("9"), FRESH_QUOTE);
    }

    @Test
    void sellMissingOrInsufficientHoldingFailsWithoutCashMutation() {
        OrderEntity missingOrder = acceptedOrder(OrderType.SELL, "2", null);
        stubLockedState(
                missingOrder,
                client("100"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "HOLDING_NOT_FOUND")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(missingOrder)));

        service.executeAcceptedOrder(7L);

        verify(clientRepository, never()).updateAccountBalance(any(), any());

        org.mockito.Mockito.reset(
                orderRepository,
                clientRepository,
                holdingRepository,
                instrumentRepository
        );
        OrderEntity insufficientOrder = acceptedOrder(OrderType.SELL, "6", null);
        stubLockedState(
                insufficientOrder,
                client("100"),
                holding("5", "7.25"),
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "INSUFFICIENT_HOLDINGS")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(insufficientOrder)));

        service.executeAcceptedOrder(7L);

        verify(clientRepository, never()).updateAccountBalance(any(), any());
        verify(holdingRepository, never()).updateHolding(any(), any(), any(), any());
        verify(holdingRepository, never()).deleteHoldingByHoldingIdAndClientId(any(), any());
    }

    @Test
    void staleQuoteBeforeMaxWaitThrowsRetryableAndLeavesOrderUntouched() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        order.setAcceptedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(
                        true,
                        "9",
                        "10",
                        "99",
                        OffsetDateTime.ofInstant(NOW.minusSeconds(31), ZoneOffset.UTC)
                )
        );

        assertThrows(
                RetryableOrderExecutionException.class,
                () -> service.executeAcceptedOrder(7L)
        );

        verify(orderRepository, never()).failAcceptedOrder(any(), any());
        verify(orderRepository, never()).fillAcceptedOrder(any(), any(), any());
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void missingQuoteAfterMaxWaitFailsWithMarketQuoteTimeout() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        order.setAcceptedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(120), ZoneOffset.UTC));
        stubLockedState(order, client("100"), null, instrument(true, "9", null, "99", null));
        when(orderRepository.failAcceptedOrder(7L, "MARKET_QUOTE_TIMEOUT")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(orderRepository).failAcceptedOrder(7L, "MARKET_QUOTE_TIMEOUT");
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void staleQuoteAfterMaxWaitFailsWithMarketQuoteTimeout() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        order.setAcceptedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(121), ZoneOffset.UTC));
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(
                        true,
                        "9",
                        "10",
                        "99",
                        OffsetDateTime.ofInstant(NOW.minusSeconds(31), ZoneOffset.UTC)
                )
        );
        when(orderRepository.failAcceptedOrder(7L, "MARKET_QUOTE_TIMEOUT")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(orderRepository).failAcceptedOrder(7L, "MARKET_QUOTE_TIMEOUT");
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void missingInstrumentFailsWithoutFinancialChanges() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));
        when(clientRepository.findByIdForUpdate(1L)).thenReturn(client("100"));
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(2L, 1L))
                .thenReturn(null);
        when(instrumentRepository.findById(2L)).thenReturn(Optional.empty());
        when(orderRepository.failAcceptedOrder(7L, "INSTRUMENT_NOT_FOUND")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(orderRepository).failAcceptedOrder(7L, "INSTRUMENT_NOT_FOUND");
        verify(clientRepository, never()).updateAccountBalance(any(), any());
        verifyNoInteractionsBeyondLock(holdingRepository);
    }

    @Test
    void invalidBuyReservationFailsWithoutFinancialChanges() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", null);
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "INVALID_RESERVED_CASH")).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(orderRepository).failAcceptedOrder(7L, "INVALID_RESERVED_CASH");
        verify(clientRepository, never()).updateAccountBalance(any(), any());
        verifyNoInteractionsBeyondLock(holdingRepository);
    }

    @Test
    void nonTradableInstrumentFailsWithoutFinancialChanges() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(false, "9", "10", "99", FRESH_QUOTE)
        );
        when(orderRepository.failAcceptedOrder(7L, "INSTRUMENT_NOT_TRADABLE"))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(failed(order)));

        service.executeAcceptedOrder(7L);

        verify(orderRepository).failAcceptedOrder(7L, "INSTRUMENT_NOT_TRADABLE");
        verifyNoInteractionsBeyondLock(holdingRepository);
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void duplicateExecutionOfFilledOrderIsNoOp() {
        OrderEntity filled = acceptedOrder(OrderType.BUY, "2", "20");
        filled.setStatus(OrderStatus.FILLED);
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(filled));

        OrderEntity result = service.executeAcceptedOrder(7L);

        assertEquals(OrderStatus.FILLED, result.getStatus());
        verifyNoInteractions(clientRepository, holdingRepository, instrumentRepository);
        verify(orderRepository, never()).fillAcceptedOrder(any(), any(), any());
        verify(orderRepository, never()).failAcceptedOrder(any(), any());
    }

    @Test
    void duplicateExecutionOfFailedOrderIsNoOp() {
        OrderEntity failed = acceptedOrder(OrderType.BUY, "2", "20");
        failed.setStatus(OrderStatus.FAILED);
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(failed));

        OrderEntity result = service.executeAcceptedOrder(7L);

        assertEquals(OrderStatus.FAILED, result.getStatus());
        verifyNoInteractions(clientRepository, holdingRepository, instrumentRepository);
        verify(orderRepository, never()).fillAcceptedOrder(any(), any(), any());
        verify(orderRepository, never()).failAcceptedOrder(any(), any());
    }

    @Test
    void locksOrderThenClientThenHoldingBeforeReadingInstrument() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(holdingRepository.insert(any())).thenReturn(1);
        when(clientRepository.updateAccountBalance(any(), any())).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(any(), any(), any())).thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        service.executeAcceptedOrder(7L);

        InOrder locks = inOrder(
                orderRepository,
                clientRepository,
                holdingRepository,
                instrumentRepository
        );
        locks.verify(orderRepository).findByIdForUpdate(7L);
        locks.verify(clientRepository).findByIdForUpdate(1L);
        locks.verify(holdingRepository)
                .getHoldingByInstrumentIdAndClientIdForUpdate(2L, 1L);
        locks.verify(instrumentRepository).findById(2L);
    }

    @Test
    void unexpectedWriteCountThrowsInsteadOfMarkingFailed() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(true, "9", "10", "99", FRESH_QUOTE)
        );
        when(holdingRepository.insert(any())).thenReturn(0);

        assertThrows(OrderLifecycleException.class, () -> service.executeAcceptedOrder(7L));

        verify(orderRepository, never()).failAcceptedOrder(any(), any());
        verify(clientRepository, never()).updateAccountBalance(any(), any());
    }

    @Test
    void databaseFailurePropagatesWithoutFailureTransition() {
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", "20");
        when(orderRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(order));
        when(clientRepository.findByIdForUpdate(1L)).thenReturn(client("100"));
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(2L, 1L))
                .thenThrow(new IllegalStateException("simulated lock timeout"));

        assertThrows(IllegalStateException.class, () -> service.executeAcceptedOrder(7L));

        verify(orderRepository, never()).failAcceptedOrder(any(), any());
        verify(orderRepository, never()).fillAcceptedOrder(any(), any(), any());
        verifyNoInteractions(instrumentRepository);
    }

    private void executeSuccessfulBuy(String reservation, String ask) {
        org.mockito.Mockito.reset(
                orderRepository,
                clientRepository,
                holdingRepository,
                instrumentRepository
        );
        OrderEntity order = acceptedOrder(OrderType.BUY, "2", reservation);
        stubLockedState(
                order,
                client("100"),
                null,
                instrument(true, "9", ask, "99", FRESH_QUOTE)
        );
        when(holdingRepository.insert(any())).thenReturn(1);
        BigDecimal tradeValue = new BigDecimal("2").multiply(new BigDecimal(ask));
        when(clientRepository.updateAccountBalance(
                eq(1L),
                eq(new BigDecimal("100").subtract(tradeValue))
        )).thenReturn(1);
        when(orderRepository.fillAcceptedOrder(7L, new BigDecimal(ask), FRESH_QUOTE))
                .thenReturn(1);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(filled(order)));

        assertEquals(OrderStatus.FILLED, service.executeAcceptedOrder(7L).getStatus());
    }

    private void stubLockedState(
            OrderEntity order,
            ClientEntity client,
            HoldingEntity holding,
            InstrumentEntity instrument
    ) {
        when(orderRepository.findByIdForUpdate(order.getOrderId()))
                .thenReturn(Optional.of(order));
        when(clientRepository.findByIdForUpdate(order.getClientId())).thenReturn(client);
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                order.getInstrumentId(),
                order.getClientId()
        )).thenReturn(holding);
        when(instrumentRepository.findById(order.getInstrumentId()))
                .thenReturn(Optional.of(instrument));
    }

    private static OrderEntity acceptedOrder(OrderType type, String quantity, String reservation) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(7L);
        order.setClientId(1L);
        order.setInstrumentId(2L);
        order.setOrderType(type);
        order.setQuantity(new BigDecimal(quantity));
        order.setReservedCash(reservation == null ? null : new BigDecimal(reservation));
        order.setStatus(OrderStatus.ACCEPTED);
        order.setAcceptedAt(OffsetDateTime.ofInstant(NOW.minusSeconds(10), ZoneOffset.UTC));
        return order;
    }

    private static ClientEntity client(String balance) {
        ClientEntity client = new ClientEntity();
        client.setClientId(1L);
        client.setAccountBalance(new BigDecimal(balance));
        return client;
    }

    private static HoldingEntity holding(String quantity, String averageCost) {
        HoldingEntity holding = new HoldingEntity();
        holding.setHoldingId(3L);
        holding.setClientId(1L);
        holding.setInstrumentId(2L);
        holding.setQuantity(new BigDecimal(quantity));
        holding.setAverageCost(new BigDecimal(averageCost));
        return holding;
    }

    private static InstrumentEntity instrument(
            boolean tradable,
            String bid,
            String ask,
            String last,
            OffsetDateTime quoteAsOf
    ) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(2L);
        instrument.setTradable(tradable);
        instrument.setBidPrice(bid == null ? null : new BigDecimal(bid));
        instrument.setAskPrice(ask == null ? null : new BigDecimal(ask));
        instrument.setLastPrice(last == null ? null : new BigDecimal(last));
        instrument.setQuoteAsOf(quoteAsOf);
        return instrument;
    }

    private static OrderEntity filled(OrderEntity order) {
        return withStatus(order, OrderStatus.FILLED);
    }

    private static OrderEntity failed(OrderEntity order) {
        return withStatus(order, OrderStatus.FAILED);
    }

    private static OrderEntity withStatus(OrderEntity source, OrderStatus status) {
        OrderEntity result = new OrderEntity();
        result.setOrderId(source.getOrderId());
        result.setClientId(source.getClientId());
        result.setInstrumentId(source.getInstrumentId());
        result.setOrderType(source.getOrderType());
        result.setQuantity(source.getQuantity());
        result.setReservedCash(source.getReservedCash());
        result.setAcceptedAt(source.getAcceptedAt());
        result.setStatus(status);
        return result;
    }

    private void verifyNoInteractionsBeyondLock(HoldingRepository repository) {
        verify(repository, never()).insert(any());
        verify(repository, never()).updateHolding(any(), any(), any(), any());
        verify(repository, never()).deleteHoldingByHoldingIdAndClientId(any(), any());
    }
}
