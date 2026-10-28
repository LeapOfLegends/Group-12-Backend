package group12.Services;

import group12.Entities.InstrumentEntity;
import group12.Entities.MarketCode;
import group12.Repository.InstrumentPriceHistoryRepository;
import group12.Repository.InstrumentRepository;
import group12.dto.InstrumentHistoryDTO;
import group12.dto.InstrumentHistoryPointDTO;
import group12.marketdata.HistoricalBar;
import group12.marketdata.HistoricalMarketDataProvider;
import group12.marketdata.HistoricalRange;
import group12.marketdata.HistoricalTimeframe;
import group12.marketdata.MarketDataRequest;
import group12.marketdata.exception.MarketDataException;
import group12.marketdata.exception.PartialHistoricalDataException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InstrumentHistoryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstrumentHistoryService.class);
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(16, 0);
    private static final Duration FAILURE_COOLDOWN = Duration.ofSeconds(30);
    private static final int[] INTRADAY_LOOKBACK_DAYS = {14, 28, 56};

    private final InstrumentRepository instrumentRepository;
    private final InstrumentPriceHistoryRepository historyRepository;
    private final List<HistoricalMarketDataProvider> providers;
    private final Clock clock;
    private final Map<CacheKey, Coverage> successfulCoverage = new ConcurrentHashMap<>();
    private final Map<CacheKey, Instant> failedUntil = new ConcurrentHashMap<>();

    public InstrumentHistoryService(
            InstrumentRepository instrumentRepository,
            InstrumentPriceHistoryRepository historyRepository,
            List<HistoricalMarketDataProvider> providers,
            @Qualifier("instrumentHistoryClock") Clock clock
    ) {
        this.instrumentRepository = instrumentRepository;
        this.historyRepository = historyRepository;
        this.providers = List.copyOf(providers);
        this.clock = clock;
    }

    public InstrumentHistoryDTO getHistory(String symbol, String requestedRange) {
        HistoricalRange range = HistoricalRange.fromApiValue(requestedRange);
        String normalizedSymbol = symbol == null
                ? ""
                : symbol.trim().toUpperCase(Locale.ROOT);
        InstrumentEntity instrument = instrumentRepository.findBySymbol(normalizedSymbol)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Instrument not found"
                ));
        return getHistory(instrument, range);
    }

    public InstrumentHistoryDTO getHistory(Long instrumentId, String requestedRange) {
        HistoricalRange range = HistoricalRange.fromApiValue(requestedRange);
        InstrumentEntity instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Instrument not found"
                ));
        return getHistory(instrument, range);
    }

    /**
     * Internal bulk-oriented history access for portfolio valuation.  Provider routing,
     * caching, completed-bar persistence, partial-result handling, and request chunking
     * remain centralized here.  The caller is responsible for applying candle completion
     * times because a persisted bar_start is not itself an as-of price timestamp.
     */
    public PriceHistory loadCompletedPriceHistory(
            Long instrumentId,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        if (instrumentId == null || timeframe == null || startInclusive == null
                || endExclusive == null || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("A valid instrument history window is required");
        }
        InstrumentEntity instrument = instrumentRepository.findById(instrumentId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "A portfolio instrument no longer exists"
                ));
        MarketDataRequest request = new MarketDataRequest(
                instrument.getSymbol(), instrument.getAssetClass(), instrument.getCurrency(),
                instrument.getMarketCode()
        );
        HistoricalMarketDataProvider provider = selectProvider(request);
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        OffsetDateTime eligibleEnd = eligibleEnd(request, now, timeframe);
        OffsetDateTime requestedEnd = earlier(endExclusive, eligibleEnd);
        OffsetDateTime requestedStart = startInclusive.minus(priceCarryLookback(timeframe));
        if (!requestedStart.isBefore(requestedEnd)) {
            return new PriceHistory(instrument, timeframe, List.of(), false);
        }
        LoadResult result = loadWindow(
                instrumentId, request, provider, timeframe, requestedStart, requestedEnd
        );
        return new PriceHistory(instrument, timeframe, result.bars(), result.partial());
    }

    private InstrumentHistoryDTO getHistory(
            InstrumentEntity instrument,
            HistoricalRange range
    ) {
        Long instrumentId = instrument.getInstrumentId();
        MarketDataRequest request = new MarketDataRequest(
                instrument.getSymbol(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                instrument.getMarketCode()
        );
        HistoricalMarketDataProvider provider = selectProvider(request);
        OffsetDateTime referenceTime = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        HistoricalTimeframe timeframe = range.getTimeframe();
        OffsetDateTime endExclusive = eligibleEnd(request, referenceTime, timeframe);
        boolean equity = isEquity(request);

        LoadResult loadResult;
        if (range.isIntradayEquityRange() && equity) {
            loadResult = loadIntradayHistory(
                    instrumentId, request, provider, range, referenceTime, endExclusive
            );
        } else {
            OffsetDateTime startInclusive = range.isIntradayEquityRange()
                    ? rollingRangeStart(referenceTime, range)
                    : calendarRangeStart(referenceTime, range);
            loadResult = loadWindow(
                    instrumentId, request, provider, timeframe, startInclusive, endExclusive
            );
        }

        List<HistoricalBar> selected = range.isIntradayEquityRange() && equity
                ? selectLatestSessions(loadResult.bars(), range.representedSessionCount())
                : loadResult.bars().stream()
                        .filter(bar -> !bar.barStart().isBefore(
                                range.isIntradayEquityRange()
                                        ? rollingRangeStart(referenceTime, range)
                                        : calendarRangeStart(referenceTime, range)
                        ))
                        .sorted(Comparator.comparing(HistoricalBar::barStart))
                        .toList();

        if (loadResult.partial() && selected.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Historical market data is temporarily unavailable"
            );
        }
        return toDto(instrument, range, selected, loadResult.partial());
    }

    private LoadResult loadIntradayHistory(
            Long instrumentId,
            MarketDataRequest request,
            HistoricalMarketDataProvider provider,
            HistoricalRange range,
            OffsetDateTime referenceTime,
            OffsetDateTime endExclusive
    ) {
        LoadResult result = new LoadResult(List.of(), false);
        for (int lookbackDays : INTRADAY_LOOKBACK_DAYS) {
            OffsetDateTime startInclusive = referenceTime.minusDays(lookbackDays);
            result = loadWindow(
                    instrumentId,
                    request,
                    provider,
                    range.getTimeframe(),
                    startInclusive,
                    endExclusive
            );
            if (result.partial()
                    || representedSessionCount(result.bars()) >= range.representedSessionCount()) {
                break;
            }
        }
        return result;
    }

    private LoadResult loadWindow(
            Long instrumentId,
            MarketDataRequest request,
            HistoricalMarketDataProvider provider,
            HistoricalTimeframe timeframe,
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive
    ) {
        if (!startInclusive.isBefore(endExclusive)) {
            return new LoadResult(List.of(), false);
        }
        CacheKey key = new CacheKey(instrumentId, timeframe);
        List<HistoricalBar> cached = historyRepository.findBars(
                instrumentId, timeframe, startInclusive, endExclusive
        );
        List<FetchWindow> missing = missingWindows(
                key, cached, request, timeframe, startInclusive, endExclusive
        );
        boolean partial = false;

        for (FetchWindow window : missing) {
            if (!window.startInclusive().isBefore(window.endExclusive())) {
                continue;
            }
            if (cooldownActive(key)) {
                partial = true;
                break;
            }
            try {
                List<HistoricalBar> fetched = provider.getHistoricalBars(
                        request, timeframe, window.startInclusive(), window.endExclusive()
                );
                List<HistoricalBar> completed = completedInWindow(fetched, window);
                historyRepository.upsertBars(instrumentId, timeframe, completed);
                mergeCoverage(key, window);
                failedUntil.remove(key);
            } catch (PartialHistoricalDataException exception) {
                historyRepository.upsertBars(
                        instrumentId,
                        timeframe,
                        completedInWindow(exception.getBars(), window)
                );
                LOGGER.warn(
                        "Historical market-data retrieval was partial for instrument {} and {}: {}",
                        instrumentId,
                        timeframe.getDatabaseValue(),
                        exception.getMessage()
                );
                failedUntil.put(key, clock.instant().plus(FAILURE_COOLDOWN));
                partial = true;
                break;
            } catch (MarketDataException exception) {
                LOGGER.warn(
                        "Historical market-data retrieval failed for instrument {} and {}: {}",
                        instrumentId,
                        timeframe.getDatabaseValue(),
                        exception.getMessage()
                );
                failedUntil.put(key, clock.instant().plus(FAILURE_COOLDOWN));
                partial = true;
                break;
            }
        }

        List<HistoricalBar> result = historyRepository.findBars(
                instrumentId, timeframe, startInclusive, endExclusive
        ).stream().sorted(Comparator.comparing(HistoricalBar::barStart)).toList();
        return new LoadResult(result, partial);
    }

    private List<FetchWindow> missingWindows(
            CacheKey key,
            List<HistoricalBar> cached,
            MarketDataRequest request,
            HistoricalTimeframe timeframe,
            OffsetDateTime requestedStart,
            OffsetDateTime requestedEnd
    ) {
        Coverage coverage = successfulCoverage.get(key);
        if (coverage != null) {
            List<FetchWindow> missing = new ArrayList<>(2);
            if (coverage.startInclusive().isAfter(requestedStart)) {
                missing.add(new FetchWindow(
                        requestedStart, earlier(coverage.startInclusive(), requestedEnd)
                ));
            }
            if (coverage.endExclusive().isBefore(requestedEnd)
                    && !isClosedFxWeekend(request, requestedEnd)) {
                missing.add(new FetchWindow(
                        later(coverage.endExclusive(), requestedStart), requestedEnd
                ));
            }
            return missing;
        }
        if (cached.isEmpty()) {
            return List.of(new FetchWindow(requestedStart, requestedEnd));
        }

        List<FetchWindow> missing = new ArrayList<>(2);
        HistoricalBar first = cached.stream()
                .min(Comparator.comparing(HistoricalBar::barStart)).orElseThrow();
        HistoricalBar last = cached.stream()
                .max(Comparator.comparing(HistoricalBar::barStart)).orElseThrow();
        if (first.barStart().isAfter(requestedStart.plus(backfillTolerance(timeframe)))) {
            missing.add(new FetchWindow(requestedStart, first.barStart()));
        }
        if (!isSufficientlyCurrent(last, request, timeframe, requestedEnd)) {
            OffsetDateTime tailStart = nextBarStart(last.barStart(), request, timeframe);
            if (tailStart.isBefore(requestedEnd)) {
                missing.add(new FetchWindow(tailStart, requestedEnd));
            }
        }
        return missing;
    }

    private boolean cooldownActive(CacheKey key) {
        Instant until = failedUntil.get(key);
        if (until == null) {
            return false;
        }
        if (!clock.instant().isBefore(until)) {
            failedUntil.remove(key, until);
            return false;
        }
        return true;
    }

    private void mergeCoverage(CacheKey key, FetchWindow window) {
        successfulCoverage.merge(
                key,
                new Coverage(window.startInclusive(), window.endExclusive()),
                (current, added) -> new Coverage(
                        earlier(current.startInclusive(), added.startInclusive()),
                        later(current.endExclusive(), added.endExclusive())
                )
        );
    }

    private HistoricalMarketDataProvider selectProvider(MarketDataRequest request) {
        List<HistoricalMarketDataProvider> supported = providers.stream()
                .filter(provider -> provider.supports(request))
                .toList();
        if (supported.isEmpty()) {
            // The instrument exists, but its attributes cannot be routed to a history adapter.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Historical prices are not supported for this instrument"
            );
        }
        if (supported.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Historical market-data provider routing is ambiguous"
            );
        }
        return supported.getFirst();
    }

    private OffsetDateTime calendarRangeStart(
            OffsetDateTime referenceTime,
            HistoricalRange range
    ) {
        return switch (range) {
            case ONE_MONTH -> referenceTime.minusMonths(1);
            case ONE_YEAR -> referenceTime.minusYears(1);
            case FIVE_YEARS -> referenceTime.minusYears(5);
            default -> throw new IllegalArgumentException("Range is not calendar-based");
        };
    }

    private OffsetDateTime rollingRangeStart(
            OffsetDateTime referenceTime,
            HistoricalRange range
    ) {
        return switch (range) {
            case ONE_DAY -> referenceTime.minusHours(24);
            case FIVE_DAYS -> referenceTime.minusHours(120);
            default -> throw new IllegalArgumentException("Range is not rolling intraday");
        };
    }

    private OffsetDateTime eligibleEnd(
            MarketDataRequest request,
            OffsetDateTime referenceTime,
            HistoricalTimeframe timeframe
    ) {
        if (!isEquity(request)) {
            return utcEligibleEnd(referenceTime, timeframe);
        }
        ZonedDateTime eastern = referenceTime.atZoneSameInstant(NEW_YORK);
        return switch (timeframe) {
            case FIVE_MINUTES -> truncate(referenceTime, Duration.ofMinutes(5));
            case THIRTY_MINUTES -> truncate(referenceTime, Duration.ofMinutes(30));
            case ONE_DAY -> {
                LocalDate exclusiveDate = eastern.toLocalTime().isBefore(REGULAR_CLOSE)
                        ? eastern.toLocalDate()
                        : eastern.toLocalDate().plusDays(1);
                yield exclusiveDate.atStartOfDay(NEW_YORK).toOffsetDateTime()
                        .withOffsetSameInstant(ZoneOffset.UTC);
            }
            case ONE_WEEK -> eastern.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay(NEW_YORK).toOffsetDateTime()
                    .withOffsetSameInstant(ZoneOffset.UTC);
            case ONE_MONTH -> YearMonth.from(eastern).atDay(1)
                    .atStartOfDay(NEW_YORK).toOffsetDateTime()
                    .withOffsetSameInstant(ZoneOffset.UTC);
        };
    }

    private OffsetDateTime utcEligibleEnd(
            OffsetDateTime referenceTime,
            HistoricalTimeframe timeframe
    ) {
        OffsetDateTime utc = referenceTime.withOffsetSameInstant(ZoneOffset.UTC);
        return switch (timeframe) {
            case FIVE_MINUTES -> truncate(utc, Duration.ofMinutes(5));
            case THIRTY_MINUTES -> truncate(utc, Duration.ofMinutes(30));
            case ONE_DAY -> utc.toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
            case ONE_WEEK -> utc.toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
            case ONE_MONTH -> YearMonth.from(utc).atDay(1)
                    .atStartOfDay().atOffset(ZoneOffset.UTC);
        };
    }

    private OffsetDateTime truncate(OffsetDateTime timestamp, Duration duration) {
        long intervalSeconds = duration.toSeconds();
        long epochSeconds = timestamp.toEpochSecond();
        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(epochSeconds - Math.floorMod(epochSeconds, intervalSeconds)),
                ZoneOffset.UTC
        );
    }

    private boolean isSufficientlyCurrent(
            HistoricalBar last,
            MarketDataRequest request,
            HistoricalTimeframe timeframe,
            OffsetDateTime requestedEnd
    ) {
        if (!isEquity(request)) {
            return !nextBarStart(last.barStart(), request, timeframe).isBefore(requestedEnd);
        }
        ZonedDateTime lastEastern = last.barStart().atZoneSameInstant(NEW_YORK);
        ZonedDateTime referenceEastern = requestedEnd.atZoneSameInstant(NEW_YORK);
        return switch (timeframe) {
            case FIVE_MINUTES, THIRTY_MINUTES -> intradayIsCurrent(
                    last, timeframe, referenceEastern, requestedEnd
            );
            case ONE_DAY -> !lastEastern.toLocalDate().isBefore(
                    previousWeekday(referenceEastern.toLocalDate().minusDays(1))
            );
            case ONE_WEEK -> {
                LocalDate expectedWeek = referenceEastern.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .minusWeeks(1);
                LocalDate lastWeek = lastEastern.toLocalDate()
                        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield !lastWeek.isBefore(expectedWeek);
            }
            case ONE_MONTH -> !YearMonth.from(lastEastern)
                    .isBefore(YearMonth.from(referenceEastern).minusMonths(1));
        };
    }

    private boolean intradayIsCurrent(
            HistoricalBar last,
            HistoricalTimeframe timeframe,
            ZonedDateTime referenceEastern,
            OffsetDateTime requestedEnd
    ) {
        Duration duration = timeframe == HistoricalTimeframe.FIVE_MINUTES
                ? Duration.ofMinutes(5) : Duration.ofMinutes(30);
        LocalDate referenceDate = referenceEastern.toLocalDate();
        LocalTime firstCompletedAt = REGULAR_OPEN.plus(duration);
        if (referenceEastern.getDayOfWeek() == DayOfWeek.SATURDAY
                || referenceEastern.getDayOfWeek() == DayOfWeek.SUNDAY
                || referenceEastern.toLocalTime().isBefore(firstCompletedAt)) {
            LocalDate expectedDate = previousWeekday(referenceDate.minusDays(1));
            return !last.barStart().atZoneSameInstant(NEW_YORK).toLocalDate()
                    .isBefore(expectedDate);
        }
        OffsetDateTime marketClose = referenceDate.atTime(REGULAR_CLOSE)
                .atZone(NEW_YORK).toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
        OffsetDateTime requiredThrough = earlier(requestedEnd, marketClose);
        return !last.barStart().plus(duration).isBefore(requiredThrough);
    }

    private LocalDate previousWeekday(LocalDate date) {
        LocalDate result = date;
        while (result.getDayOfWeek() == DayOfWeek.SATURDAY
                || result.getDayOfWeek() == DayOfWeek.SUNDAY) {
            result = result.minusDays(1);
        }
        return result;
    }

    private OffsetDateTime nextBarStart(
            OffsetDateTime barStart,
            MarketDataRequest request,
            HistoricalTimeframe timeframe
    ) {
        if (!isEquity(request)) {
            OffsetDateTime utc = barStart.withOffsetSameInstant(ZoneOffset.UTC);
            return switch (timeframe) {
                case FIVE_MINUTES -> utc.plusMinutes(5);
                case THIRTY_MINUTES -> utc.plusMinutes(30);
                case ONE_DAY -> utc.plusDays(1);
                case ONE_WEEK -> utc.plusWeeks(1);
                case ONE_MONTH -> utc.plusMonths(1);
            };
        }
        return switch (timeframe) {
            case FIVE_MINUTES -> barStart.plusMinutes(5);
            case THIRTY_MINUTES -> barStart.plusMinutes(30);
            case ONE_DAY -> calendarPlus(barStart, 1, CalendarUnit.DAY);
            case ONE_WEEK -> calendarPlus(barStart, 1, CalendarUnit.WEEK);
            case ONE_MONTH -> calendarPlus(barStart, 1, CalendarUnit.MONTH);
        };
    }

    private OffsetDateTime calendarPlus(
            OffsetDateTime timestamp,
            int amount,
            CalendarUnit unit
    ) {
        ZonedDateTime eastern = timestamp.atZoneSameInstant(NEW_YORK);
        ZonedDateTime result = switch (unit) {
            case DAY -> eastern.plusDays(amount);
            case WEEK -> eastern.plusWeeks(amount);
            case MONTH -> eastern.plusMonths(amount);
        };
        return result.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC);
    }

    private Duration backfillTolerance(HistoricalTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTES, THIRTY_MINUTES, ONE_DAY -> Duration.ofDays(4);
            case ONE_WEEK -> Duration.ofDays(8);
            case ONE_MONTH -> Duration.ofDays(35);
        };
    }

    private Duration priceCarryLookback(HistoricalTimeframe timeframe) {
        return switch (timeframe) {
            case FIVE_MINUTES, THIRTY_MINUTES -> Duration.ofDays(5);
            case ONE_DAY -> Duration.ofDays(8);
            case ONE_WEEK -> Duration.ofDays(16);
            case ONE_MONTH -> Duration.ofDays(45);
        };
    }

    private boolean isEquity(MarketDataRequest request) {
        return request != null
                && "Equity".equalsIgnoreCase(request.assetClass())
                && request.marketCode() == MarketCode.IEX;
    }

    private boolean isClosedFxWeekend(
            MarketDataRequest request,
            OffsetDateTime requestedEnd
    ) {
        if (request == null || request.marketCode() != MarketCode.FX) {
            return false;
        }
        DayOfWeek day = requestedEnd.withOffsetSameInstant(ZoneOffset.UTC).getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    private List<HistoricalBar> completedInWindow(
            List<HistoricalBar> bars,
            FetchWindow window
    ) {
        return bars.stream()
                .filter(HistoricalBar::complete)
                .filter(bar -> !bar.barStart().isBefore(window.startInclusive()))
                .filter(bar -> bar.barStart().isBefore(window.endExclusive()))
                .sorted(Comparator.comparing(HistoricalBar::barStart))
                .toList();
    }

    private int representedSessionCount(List<HistoricalBar> bars) {
        return (int) regularSessionBars(bars).stream()
                .map(bar -> bar.barStart().atZoneSameInstant(NEW_YORK).toLocalDate())
                .distinct()
                .count();
    }

    private List<HistoricalBar> selectLatestSessions(List<HistoricalBar> bars, int count) {
        List<HistoricalBar> regularBars = regularSessionBars(bars);
        List<LocalDate> dates = regularBars.stream()
                .map(bar -> bar.barStart().atZoneSameInstant(NEW_YORK).toLocalDate())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(count)
                .toList();
        LinkedHashSet<LocalDate> selectedDates = new LinkedHashSet<>(dates);
        return regularBars.stream()
                .filter(bar -> selectedDates.contains(
                        bar.barStart().atZoneSameInstant(NEW_YORK).toLocalDate()
                ))
                .sorted(Comparator.comparing(HistoricalBar::barStart))
                .toList();
    }

    private List<HistoricalBar> regularSessionBars(List<HistoricalBar> bars) {
        return bars.stream().filter(bar -> {
            LocalTime time = bar.barStart().atZoneSameInstant(NEW_YORK).toLocalTime();
            return !time.isBefore(REGULAR_OPEN) && time.isBefore(REGULAR_CLOSE);
        }).toList();
    }

    private InstrumentHistoryDTO toDto(
            InstrumentEntity instrument,
            HistoricalRange range,
            List<HistoricalBar> bars,
            boolean partial
    ) {
        List<InstrumentHistoryPointDTO> points = bars.stream()
                .map(bar -> new InstrumentHistoryPointDTO(
                        bar.barStart(), bar.closePrice()
                ))
                .toList();
        OffsetDateTime availableFrom = points.isEmpty() ? null : points.getFirst().timestamp();
        OffsetDateTime availableTo = points.isEmpty() ? null : points.getLast().timestamp();
        return new InstrumentHistoryDTO(
                instrument.getInstrumentId(),
                instrument.getSymbol(),
                range.getApiValue(),
                range.getTimeframe().getDatabaseValue(),
                instrument.getCurrency(),
                availableFrom,
                availableTo,
                partial,
                points
        );
    }

    private OffsetDateTime earlier(OffsetDateTime first, OffsetDateTime second) {
        return first.isBefore(second) ? first : second;
    }

    private OffsetDateTime later(OffsetDateTime first, OffsetDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private record CacheKey(Long instrumentId, HistoricalTimeframe timeframe) {
    }

    private record Coverage(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
    }

    private record FetchWindow(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
    }

    private record LoadResult(List<HistoricalBar> bars, boolean partial) {
    }

    public record PriceHistory(
            InstrumentEntity instrument,
            HistoricalTimeframe timeframe,
            List<HistoricalBar> bars,
            boolean partial
    ) {
        public PriceHistory {
            bars = List.copyOf(bars);
        }
    }

    private enum CalendarUnit {
        DAY, WEEK, MONTH
    }
}
