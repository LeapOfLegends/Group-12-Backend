package group12.Services;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;

import group12.Repository.PlatformMetricsRepository;
import group12.Repository.TransactionsRepository;
import group12.dto.PlatformMetricsDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlatformMetricsService {

    private final PlatformMetricsRepository repository;
    private final TransactionsRepository transactions;
    private final Clock clock;

    @Autowired
    public PlatformMetricsService(PlatformMetricsRepository repository, TransactionsRepository transactions) {
        this(repository, transactions, Clock.systemUTC());
    }

    PlatformMetricsService(PlatformMetricsRepository repository, TransactionsRepository transactions, Clock clock) {
        this.repository = repository;
        this.transactions = transactions;
        this.clock = clock;
    }

    public PlatformMetricsDTO summarize(String range) {
        String normalizedRange = range.toLowerCase(Locale.ROOT);
        Instant to = clock.instant();
        var end = to.atZone(ZoneOffset.UTC);
        Instant from = switch (normalizedRange) {
            case "week" -> end.minusWeeks(1).toInstant();
            case "month" -> end.minusMonths(1).toInstant();
            case "year" -> end.minusYears(1).toInstant();
            case "all" -> null;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Range must be week, month, year or all.");
        };
        try {
            var rows = repository.summarize(from, to);
            return new PlatformMetricsDTO(normalizedRange, from, to,
                    rows.stream().mapToLong(PlatformMetricsRepository.CurrencyMetrics::totalOrders).sum(),
                    rows.stream().mapToLong(PlatformMetricsRepository.CurrencyMetrics::filledOrders).sum(),
                    rows.stream().mapToLong(PlatformMetricsRepository.CurrencyMetrics::pendingOrders).sum(),
                    rows.stream().mapToLong(PlatformMetricsRepository.CurrencyMetrics::failedRejectedOrders).sum(),
                    transactions.getFundingTotal("DEPOSIT", from == null ? null : from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC)),
                    transactions.getFundingTotal("WITHDRAWAL", from == null ? null : from.atOffset(ZoneOffset.UTC), to.atOffset(ZoneOffset.UTC)),
                    rows.stream().map(row -> new PlatformMetricsDTO.TradingValue(row.currency(), row.tradingValue())).toList());
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Platform metrics could not be loaded.", exception);
        }
    }
}