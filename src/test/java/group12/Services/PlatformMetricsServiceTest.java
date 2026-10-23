package group12.Services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import group12.Repository.PlatformMetricsRepository;
import group12.Repository.TransactionsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

class PlatformMetricsServiceTest {

    private final PlatformMetricsRepository repository = mock(PlatformMetricsRepository.class);
    private final TransactionsRepository transactions = mock(TransactionsRepository.class);
    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    private final PlatformMetricsService service = new PlatformMetricsService(repository, transactions, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void rollingPeriodsUseUtcCalendarBoundaries() {
        for (var period : List.of("week", "month", "year")) {
            service.summarize(period);
        }
        verify(repository).summarize(Instant.parse("2026-10-16T12:00:00Z"), now);
        verify(repository).summarize(Instant.parse("2026-09-23T12:00:00Z"), now);
        verify(repository).summarize(Instant.parse("2025-10-23T12:00:00Z"), now);
        for (var start : List.of("2026-10-16T12:00:00Z", "2026-09-23T12:00:00Z", "2025-10-23T12:00:00Z")) {
            for (var type : List.of("DEPOSIT", "WITHDRAWAL")) {
                verify(transactions).getFundingTotal(type, Instant.parse(start).atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
            }
        }
    }

    @Test
    void fundingTotalsUseTransactionalRepositoryAndSelectedWindow() {
        when(transactions.getFundingTotal("DEPOSIT", null, now.atOffset(ZoneOffset.UTC))).thenReturn(new BigDecimal("1250.50"));
        when(transactions.getFundingTotal("WITHDRAWAL", null, now.atOffset(ZoneOffset.UTC))).thenReturn(new BigDecimal("250.25"));

        var result = service.summarize("all");

        assertEquals(new BigDecimal("1250.50"), result.totalDeposits());
        assertEquals(new BigDecimal("250.25"), result.totalWithdrawals());
    }

    @Test
    void fundingDatabaseFailureIsNotReportedAsZero() {
        when(transactions.getFundingTotal("DEPOSIT", null, now.atOffset(ZoneOffset.UTC)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.summarize("all")).getStatusCode().value());
    }

    @Test
    void allTimeSumsCountsButKeepsCurrencyValuesSeparate() {
        when(repository.summarize(null, now)).thenReturn(List.of(
                new PlatformMetricsRepository.CurrencyMetrics("USD", 5, 2, 1, 2, new BigDecimal("500.25")),
                new PlatformMetricsRepository.CurrencyMetrics("GBP", 3, 1, 1, 1, new BigDecimal("200.50"))));

        var result = service.summarize("all");

        assertNull(result.from());
        assertEquals(8, result.totalOrders());
        assertEquals(3, result.filledOrders());
        assertEquals(2, result.pendingOrders());
        assertEquals(3, result.failedRejectedOrders());
        assertEquals(2, result.tradingValues().size());
        assertEquals(new BigDecimal("500.25"), result.tradingValues().getFirst().value());
    }

    @Test
    void emptyWarehouseReturnsZeroCounts() {
        var result = service.summarize("all");
        assertEquals(0, result.totalOrders());
        assertEquals(List.of(), result.tradingValues());
    }

    @Test
    void invalidRangeIsRejectedBeforeQuerying() {
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.summarize("decade")).getStatusCode().value());
        verifyNoInteractions(repository, transactions);
    }

    @Test
    void warehouseFailureIsNotReportedAsZeroMetrics() {
        when(repository.summarize(null, now)).thenThrow(new DataAccessResourceFailureException("offline"));
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> service.summarize("all")).getStatusCode().value());
    }
}