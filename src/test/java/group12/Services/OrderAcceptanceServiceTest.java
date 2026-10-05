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
import group12.exception.OrderNotFoundException;
import group12.orderlifecycle.QuoteFreshnessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAcceptanceServiceTest {

    private static final long ORDER_ID = 42L;
    private static final long CLIENT_ID = 10L;
    private static final long INSTRUMENT_ID = 20L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private InstrumentRepository instrumentRepository;
    @Mock
    private QuoteFreshnessPolicy quoteFreshnessPolicy;

    private OrderAcceptanceService service;

    @BeforeEach
    void setUp() {
        service = new OrderAcceptanceService(
                orderRepository,
                clientRepository,
                holdingRepository,
                instrumentRepository,
                quoteFreshnessPolicy
        );
    }

    @Test
    void buyWithSufficientAvailableCash_isAcceptedAtPersistedAsk() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "3.00000000");
        InstrumentEntity instrument = freshInstrument("2.50000000");
        ClientEntity client = client("10.0000000000000000");
        OrderEntity accepted = orderWithStatus(OrderStatus.ACCEPTED);
        accepted.setReservedCash(new BigDecimal("7.5000000000000000"));
        stubCommon(submitted, instrument);
        when(clientRepository.findByIdForUpdate(CLIENT_ID)).thenReturn(client);
        when(orderRepository.sumActiveBuyReservedCash(CLIENT_ID)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.acceptSubmittedBuyOrder(
                ORDER_ID, new BigDecimal("7.5000000000000000")
        )).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(accepted));

        OrderEntity result = service.acceptSubmittedOrder(ORDER_ID);

        assertSame(accepted, result);
        verify(orderRepository).acceptSubmittedBuyOrder(
                ORDER_ID, new BigDecimal("7.5000000000000000")
        );
        verify(clientRepository, never()).updateAccountBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    void buyWithInsufficientCash_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "5");
        stubCommon(submitted, freshInstrument("3"));
        when(clientRepository.findByIdForUpdate(CLIENT_ID)).thenReturn(client("14"));
        when(orderRepository.sumActiveBuyReservedCash(CLIENT_ID)).thenReturn(BigDecimal.ZERO);
        OrderEntity rejected = stubRejection("INSUFFICIENT_FUNDS");

        OrderEntity result = service.acceptSubmittedOrder(ORDER_ID);

        assertSame(rejected, result);
        verify(orderRepository, never()).acceptSubmittedBuyOrder(anyLong(), any(BigDecimal.class));
        verify(clientRepository, never()).updateAccountBalance(anyLong(), any(BigDecimal.class));
    }

    @Test
    void acceptedBuyReservation_reducesAvailableCash() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "3");
        stubCommon(submitted, freshInstrument("3"));
        when(clientRepository.findByIdForUpdate(CLIENT_ID)).thenReturn(client("10"));
        when(orderRepository.sumActiveBuyReservedCash(CLIENT_ID))
                .thenReturn(new BigDecimal("2"));
        stubRejection("INSUFFICIENT_FUNDS");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "INSUFFICIENT_FUNDS");
    }

    @Test
    void sellWithSufficientUnreservedHolding_isAcceptedWithoutChangingHolding() {
        OrderEntity submitted = submittedOrder(OrderType.SELL, "4");
        stubCommon(submitted, freshInstrument("3"));
        HoldingEntity holding = holding("10");
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                INSTRUMENT_ID, CLIENT_ID
        )).thenReturn(holding);
        when(orderRepository.sumActiveSellQuantity(CLIENT_ID, INSTRUMENT_ID))
                .thenReturn(new BigDecimal("5"));
        when(orderRepository.acceptSubmittedSellOrder(ORDER_ID)).thenReturn(1);
        OrderEntity accepted = orderWithStatus(OrderStatus.ACCEPTED);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(accepted));

        OrderEntity result = service.acceptSubmittedOrder(ORDER_ID);

        assertSame(accepted, result);
        verify(orderRepository).acceptSubmittedSellOrder(ORDER_ID);
        verify(holdingRepository, never()).updateHolding(
                anyLong(), anyLong(), any(BigDecimal.class), any(BigDecimal.class)
        );
    }

    @Test
    void acceptedSellReservationsPreventOverReservation() {
        OrderEntity submitted = submittedOrder(OrderType.SELL, "6");
        stubCommon(submitted, freshInstrument("3"));
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                INSTRUMENT_ID, CLIENT_ID
        )).thenReturn(holding("10"));
        when(orderRepository.sumActiveSellQuantity(CLIENT_ID, INSTRUMENT_ID))
                .thenReturn(new BigDecimal("5"));
        stubRejection("INSUFFICIENT_HOLDINGS");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "INSUFFICIENT_HOLDINGS");
        verify(orderRepository, never()).acceptSubmittedSellOrder(ORDER_ID);
    }

    @Test
    void sellWithMissingHolding_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.SELL, "1");
        stubCommon(submitted, freshInstrument("3"));
        when(holdingRepository.getHoldingByInstrumentIdAndClientIdForUpdate(
                INSTRUMENT_ID, CLIENT_ID
        )).thenReturn(null);
        stubRejection("INSUFFICIENT_HOLDINGS");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "INSUFFICIENT_HOLDINGS");
    }

    @Test
    void nonTradableInstrument_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "1");
        InstrumentEntity instrument = freshInstrument("3");
        instrument.setTradable(false);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(submitted));
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        stubRejection("INSTRUMENT_NOT_TRADABLE");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "INSTRUMENT_NOT_TRADABLE");
        verify(clientRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void missingInstrument_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "1");
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(submitted));
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.empty());
        stubRejection("INSTRUMENT_NOT_FOUND");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "INSTRUMENT_NOT_FOUND");
    }

    @Test
    void missingQuote_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "1");
        InstrumentEntity instrument = freshInstrument("3");
        instrument.setBidPrice(null);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(submitted));
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        stubRejection("MISSING_QUOTE");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "MISSING_QUOTE");
        verify(quoteFreshnessPolicy, never()).isFresh(any());
    }

    @Test
    void staleQuote_isRejected() {
        OrderEntity submitted = submittedOrder(OrderType.BUY, "1");
        InstrumentEntity instrument = freshInstrument("3");
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(submitted));
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(quoteFreshnessPolicy.isFresh(instrument.getQuoteAsOf())).thenReturn(false);
        stubRejection("STALE_QUOTE");

        service.acceptSubmittedOrder(ORDER_ID);

        verify(orderRepository).rejectSubmittedOrder(ORDER_ID, "STALE_QUOTE");
    }

    @Test
    void nonSubmittedOrder_isReturnedWithoutAnotherTransition() {
        OrderEntity accepted = orderWithStatus(OrderStatus.ACCEPTED);
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(accepted));

        OrderEntity result = service.acceptSubmittedOrder(ORDER_ID);

        assertSame(accepted, result);
        verify(instrumentRepository, never()).findById(anyLong());
        verify(orderRepository, never()).rejectSubmittedOrder(anyLong(), any());
        verify(orderRepository, never()).acceptSubmittedBuyOrder(anyLong(), any());
        verify(orderRepository, never()).acceptSubmittedSellOrder(anyLong());
    }

    @Test
    void missingOrder_throwsNotFound() {
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.empty());

        assertThrows(
                OrderNotFoundException.class,
                () -> service.acceptSubmittedOrder(ORDER_ID)
        );
    }

    private void stubCommon(OrderEntity order, InstrumentEntity instrument) {
        when(orderRepository.findByIdForUpdate(ORDER_ID)).thenReturn(Optional.of(order));
        when(instrumentRepository.findById(INSTRUMENT_ID)).thenReturn(Optional.of(instrument));
        when(quoteFreshnessPolicy.isFresh(instrument.getQuoteAsOf())).thenReturn(true);
    }

    private OrderEntity stubRejection(String reason) {
        OrderEntity rejected = orderWithStatus(OrderStatus.REJECTED);
        rejected.setRejectionReason(reason);
        when(orderRepository.rejectSubmittedOrder(ORDER_ID, reason)).thenReturn(1);
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(rejected));
        return rejected;
    }

    private static OrderEntity submittedOrder(OrderType type, String quantity) {
        OrderEntity order = orderWithStatus(OrderStatus.SUBMITTED);
        order.setOrderType(type);
        order.setQuantity(new BigDecimal(quantity));
        return order;
    }

    private static OrderEntity orderWithStatus(OrderStatus status) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(ORDER_ID);
        order.setClientId(CLIENT_ID);
        order.setInstrumentId(INSTRUMENT_ID);
        order.setStatus(status);
        return order;
    }

    private static InstrumentEntity freshInstrument(String askPrice) {
        InstrumentEntity instrument = new InstrumentEntity();
        instrument.setInstrumentId(INSTRUMENT_ID);
        instrument.setTradable(true);
        instrument.setBidPrice(new BigDecimal("2"));
        instrument.setAskPrice(new BigDecimal(askPrice));
        instrument.setQuoteAsOf(OffsetDateTime.parse("2026-10-02T15:00:00Z"));
        return instrument;
    }

    private static ClientEntity client(String accountBalance) {
        ClientEntity client = new ClientEntity();
        client.setClientId(CLIENT_ID);
        client.setAccountBalance(new BigDecimal(accountBalance));
        return client;
    }

    private static HoldingEntity holding(String quantity) {
        HoldingEntity holding = new HoldingEntity();
        holding.setClientId(CLIENT_ID);
        holding.setInstrumentId(INSTRUMENT_ID);
        holding.setQuantity(new BigDecimal(quantity));
        return holding;
    }
}
