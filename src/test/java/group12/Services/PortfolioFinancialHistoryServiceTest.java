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
import group12.dto.PortfolioFinancialHistory;
import group12.dto.PortfolioFinancialState;
import group12.exception.FinancialHistoryReconstructionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioFinancialHistoryServiceTest {

    private static final Long CLIENT_ID = 1L;
    private static final OffsetDateTime BASE =
            OffsetDateTime.parse("2026-01-02T10:00:00Z");

    @Mock
    private ClientRepository clientRepository;
    @Mock
    private TransactionsRepository transactionsRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private HoldingRepository holdingRepository;

    private PortfolioFinancialHistoryService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioFinancialHistoryService(
                clientRepository,
                transactionsRepository,
                orderRepository,
                holdingRepository
        );
    }

    @Test
    void newAccountReconstructsZeroCashAndNoPositions() {
        stubHistory("0", List.of(), List.of(), List.of());

        PortfolioFinancialHistory history = service.reconstruct(
                CLIENT_ID,
                List.of(BASE, BASE.plusDays(1))
        );

        assertTrue(history.events().isEmpty());
        assertEquals(2, history.states().size());
        history.states().forEach(state -> {
            assertMoney("0", state.cashBalance());
            assertTrue(state.instrumentQuantities().isEmpty());
        });
    }

    @Test
    void replaysFundingBuysPartialSellFullLiquidationAndMultipleInstruments() {
        OffsetDateTime depositAt = BASE.plusMinutes(1);
        OffsetDateTime firstBuyAt = BASE.plusMinutes(2);
        OffsetDateTime secondBuyAt = BASE.plusMinutes(3);
        OffsetDateTime otherBuyAt = BASE.plusMinutes(4);
        OffsetDateTime partialSellAt = BASE.plusMinutes(5);
        OffsetDateTime liquidationAt = BASE.plusMinutes(6);

        List<TransactionsEntity> transactions = List.of(
                transaction(1, null, "DEPOSIT", "10000", "10000", depositAt),
                transaction(2, 11L, "BUY", "-1000", "9000", firstBuyAt),
                transaction(3, 12L, "BUY", "-550", "8450", secondBuyAt),
                transaction(4, 13L, "BUY", "-400", "8050", otherBuyAt),
                transaction(5, 14L, "SELL", "480", "8530", partialSellAt),
                transaction(6, 15L, "SELL", "1430", "9960", liquidationAt)
        );
        List<OrderEntity> orders = List.of(
                filledOrder(11L, 101L, OrderType.BUY, "10", "100", firstBuyAt),
                filledOrder(12L, 101L, OrderType.BUY, "5", "110", secondBuyAt),
                filledOrder(13L, 202L, OrderType.BUY, "2", "200", otherBuyAt),
                filledOrder(14L, 101L, OrderType.SELL, "4", "120", partialSellAt),
                filledOrder(15L, 101L, OrderType.SELL, "11", "130", liquidationAt)
        );
        stubHistory("9960", transactions, orders, List.of(holding(202L, "2")));

        PortfolioFinancialHistory history = service.reconstruct(
                CLIENT_ID,
                List.of(
                        depositAt.minusNanos(1),
                        depositAt,
                        firstBuyAt,
                        secondBuyAt,
                        otherBuyAt,
                        partialSellAt,
                        liquidationAt
                )
        );

        assertEquals(6, history.events().size());
        assertState(history.states().get(0), "0");
        assertState(history.states().get(1), "10000");
        assertState(history.states().get(2), "9000", 101L, "10");
        assertState(history.states().get(3), "8450", 101L, "15");
        assertState(history.states().get(4), "8050", 101L, "15", 202L, "2");
        assertState(history.states().get(5), "8530", 101L, "11", 202L, "2");
        assertState(history.states().get(6), "9960", 202L, "2");
        assertMoney("10", history.events().get(1).quantityDelta());
        assertMoney("-11", history.events().get(5).quantityDelta());
    }

    @Test
    void sameTimestampUsesTransactionIdTieBreakerAndIncludesWholeTimestamp() {
        OffsetDateTime timestamp = BASE.plusMinutes(1);
        stubHistory(
                "75",
                List.of(
                        transaction(1, null, "DEPOSIT", "100", "100", timestamp),
                        transaction(2, null, "WITHDRAWAL", "-25", "75", timestamp)
                ),
                List.of(),
                List.of()
        );

        PortfolioFinancialHistory history = service.reconstruct(
                CLIENT_ID,
                List.of(timestamp.minusNanos(1), timestamp)
        );

        assertMoney("0", history.states().get(0).cashBalance());
        assertMoney("75", history.states().get(1).cashBalance());
        assertEquals(List.of(1L, 2L), history.events().stream()
                .map(event -> event.transactionId())
                .toList());
    }

    @Test
    void unfilledOrdersDoNotAppearInFinancialReplay() {
        stubHistory(
                "100",
                List.of(transaction(1, null, "DEPOSIT", "100", "100", BASE)),
                List.of(),
                List.of()
        );

        PortfolioFinancialHistory history = service.reconstruct(
                CLIENT_ID,
                List.of(BASE.plusDays(1))
        );

        assertEquals(1, history.events().size());
        assertTrue(history.states().getFirst().instrumentQuantities().isEmpty());
    }

    @Test
    void rejectsMissingTradeTransaction() {
        OrderEntity order = filledOrder(
                11L, 101L, OrderType.BUY, "1", "10", BASE.plusMinutes(1)
        );
        stubHistory("0", List.of(), List.of(order), List.of());

        assertFailureContains("has no trade transaction");
    }

    @Test
    void rejectsMismatchedTradeAmount() {
        OffsetDateTime tradeAt = BASE.plusMinutes(2);
        List<TransactionsEntity> transactions = List.of(
                transaction(1, null, "DEPOSIT", "100", "100", BASE.plusMinutes(1)),
                transaction(2, 11L, "BUY", "-9", "91", tradeAt)
        );
        stubHistory(
                "91",
                transactions,
                List.of(filledOrder(11L, 101L, OrderType.BUY, "1", "10", tradeAt)),
                List.of(holding(101L, "1"))
        );

        assertFailureContains("amount mismatches its order");
    }

    @Test
    void rejectsUnexplainedOpeningBalanceAndBalanceAfterMismatch() {
        stubHistory("100", List.of(), List.of(), List.of());
        assertFailureContains("current account balance");

        stubHistory(
                "100",
                List.of(transaction(1, null, "DEPOSIT", "100", "99", BASE)),
                List.of(),
                List.of()
        );
        assertFailureContains("balance_after");
    }

    @Test
    void rejectsNegativeCashOrQuantity() {
        stubHistory(
                "0",
                List.of(transaction(1, null, "WITHDRAWAL", "-1", "0", BASE)),
                List.of(),
                List.of()
        );
        assertFailureContains("cash negative");

        OrderEntity sell = filledOrder(11L, 101L, OrderType.SELL, "1", "10", BASE);
        stubHistory(
                "10",
                List.of(transaction(1, 11L, "SELL", "10", "10", BASE)),
                List.of(sell),
                List.of()
        );
        assertFailureContains("quantity negative");
    }

    @Test
    void rejectsCurrentHoldingMismatch() {
        OffsetDateTime buyAt = BASE.plusMinutes(1);
        stubHistory(
                "90",
                List.of(
                        transaction(1, null, "DEPOSIT", "100", "100", BASE),
                        transaction(2, 11L, "BUY", "-10", "90", buyAt)
                ),
                List.of(filledOrder(11L, 101L, OrderType.BUY, "1", "10", buyAt)),
                List.of(holding(101L, "2"))
        );

        assertFailureContains("does not match current holdings");
    }

    @Test
    void rejectsCrossClientRowsAndOutOfOrderRequests() {
        TransactionsEntity foreign = transaction(
                1, null, "DEPOSIT", "10", "10", BASE
        );
        foreign.setClientId(2L);
        stubHistory("10", List.of(foreign), List.of(), List.of());
        assertFailureContains("belongs to another client");

        assertThrows(
                IllegalArgumentException.class,
                () -> service.reconstruct(CLIENT_ID, List.of(BASE.plusSeconds(1), BASE))
        );
    }

    @Test
    void rejectsRequestsAndFinancialEventsBeforeUtcAccountCreation() {
        ClientEntity client = client("10");
        client.setCreatedAt(BASE.toLocalDateTime());
        when(clientRepository.findById(CLIENT_ID)).thenReturn(client);

        assertFailureAt(List.of(BASE.minusNanos(1)), "before account creation");

        stubHistory(
                "10",
                List.of(transaction(
                        1, null, "DEPOSIT", "10", "10", BASE.minusNanos(1)
                )),
                List.of(),
                List.of()
        );
        ClientEntity sameClient = client("10");
        sameClient.setCreatedAt(BASE.toLocalDateTime());
        when(clientRepository.findById(CLIENT_ID)).thenReturn(sameClient);
        assertFailureAt(List.of(BASE.plusDays(1)), "predates account creation");
    }

    @Test
    void acceptsPostgresPrecisionDifferenceButRejectsDifferentTradeEventTime() {
        OffsetDateTime transactionAt = BASE.plusMinutes(1);
        OrderEntity withinTolerance = filledOrder(
                11L, 101L, OrderType.BUY, "1", "10",
                transactionAt.plusNanos(999_999)
        );
        stubHistory(
                "0",
                List.of(
                        transaction(1, null, "DEPOSIT", "10", "10", BASE),
                        transaction(2, 11L, "BUY", "-10", "0", transactionAt)
                ),
                List.of(withinTolerance),
                List.of(holding(101L, "1"))
        );
        service.reconstruct(CLIENT_ID, List.of(BASE.plusDays(1)));

        OrderEntity differentEvent = filledOrder(
                11L, 101L, OrderType.BUY, "1", "10", transactionAt.plusSeconds(1)
        );
        stubHistory(
                "0",
                List.of(
                        transaction(1, null, "DEPOSIT", "10", "10", BASE),
                        transaction(2, 11L, "BUY", "-10", "0", transactionAt)
                ),
                List.of(differentEvent),
                List.of(holding(101L, "1"))
        );
        assertFailureContains("timestamp mismatches");
    }

    private void stubHistory(
            String currentBalance,
            List<TransactionsEntity> transactions,
            List<OrderEntity> filledOrders,
            List<HoldingEntity> holdings
    ) {
        when(clientRepository.findById(CLIENT_ID)).thenReturn(client(currentBalance));
        when(transactionsRepository.getFinancialHistoryByClientId(CLIENT_ID))
                .thenReturn(transactions);
        when(orderRepository.findFilledByClientId(CLIENT_ID)).thenReturn(filledOrders);
        when(holdingRepository.getHoldingsByClientId(CLIENT_ID)).thenReturn(holdings);
    }

    private void assertFailureContains(String expectedMessage) {
        assertFailureAt(List.of(BASE.plusDays(1)), expectedMessage);
    }

    private void assertFailureAt(
            List<OffsetDateTime> timestamps,
            String expectedMessage
    ) {
        FinancialHistoryReconstructionException exception = assertThrows(
                FinancialHistoryReconstructionException.class,
                () -> service.reconstruct(CLIENT_ID, timestamps)
        );
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
    }

    private ClientEntity client(String balance) {
        ClientEntity client = new ClientEntity();
        client.setClientId(CLIENT_ID);
        client.setAccountBalance(new BigDecimal(balance));
        client.setCreatedAt(LocalDateTime.ofInstant(BASE.minusDays(1).toInstant(), ZoneOffset.UTC));
        return client;
    }

    private TransactionsEntity transaction(
            long transactionId,
            Long orderId,
            String type,
            String amount,
            String balanceAfter,
            OffsetDateTime createdAt
    ) {
        return new TransactionsEntity(
                transactionId,
                CLIENT_ID,
                orderId,
                type,
                new BigDecimal(amount),
                new BigDecimal(balanceAfter),
                createdAt
        );
    }

    private OrderEntity filledOrder(
            Long orderId,
            Long instrumentId,
            OrderType type,
            String quantity,
            String price,
            OffsetDateTime filledAt
    ) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setClientId(CLIENT_ID);
        order.setInstrumentId(instrumentId);
        order.setOrderType(type);
        order.setQuantity(new BigDecimal(quantity));
        order.setStatus(OrderStatus.FILLED);
        order.setFilledAt(filledAt);
        order.setExecutionPrice(new BigDecimal(price));
        order.setTradeValue(new BigDecimal(quantity).multiply(new BigDecimal(price)));
        return order;
    }

    private HoldingEntity holding(Long instrumentId, String quantity) {
        HoldingEntity holding = new HoldingEntity();
        holding.setClientId(CLIENT_ID);
        holding.setInstrumentId(instrumentId);
        holding.setQuantity(new BigDecimal(quantity));
        return holding;
    }

    private void assertState(
            PortfolioFinancialState state,
            String cash,
            Object... instrumentQuantityPairs
    ) {
        assertMoney(cash, state.cashBalance());
        assertEquals(instrumentQuantityPairs.length / 2, state.instrumentQuantities().size());
        for (int index = 0; index < instrumentQuantityPairs.length; index += 2) {
            Long instrumentId = (Long) instrumentQuantityPairs[index];
            String quantity = (String) instrumentQuantityPairs[index + 1];
            assertMoney(quantity, state.instrumentQuantities().get(instrumentId));
        }
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
