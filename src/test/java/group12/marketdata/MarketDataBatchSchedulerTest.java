package group12.marketdata;

import group12.marketdata.exception.MarketDataRateLimitException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MarketDataBatchSchedulerTest {

    @Test
    void schedulerIsDisabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(MarketDataBatchScheduler.class)
                .withPropertyValues("market-data.refresh.enabled=false")
                .run(context -> assertFalse(
                        context.containsBean("marketDataBatchScheduler")
                ));
    }

    @Test
    void schedulerCanBeEnabledExplicitly() {
        new ApplicationContextRunner()
                .withUserConfiguration(MarketDataBatchScheduler.class)
                .withBean(
                        MarketDataBatchRefreshService.class,
                        () -> mock(MarketDataBatchRefreshService.class)
                )
                .withPropertyValues("market-data.refresh.enabled=true")
                .run(context -> assertTrue(
                        context.containsBean("marketDataBatchScheduler")
                ));
    }

    @Test
    void oneScheduledInvocationRunsOneBatchRefresh() {
        MarketDataBatchRefreshService service = mock(MarketDataBatchRefreshService.class);
        MarketDataBatchScheduler scheduler = scheduler(service);

        scheduler.runRefreshCycle();

        verify(service).refreshEligibleInstruments();
    }

    @Test
    void rateLimitStartsCooldownAndSkipsImmediateCycles() {
        MarketDataBatchRefreshService service = mock(MarketDataBatchRefreshService.class);
        doThrow(new MarketDataRateLimitException())
                .when(service).refreshEligibleInstruments();
        MarketDataBatchScheduler scheduler = scheduler(service);

        scheduler.runRefreshCycle();
        scheduler.runRefreshCycle();

        verify(service, times(1)).refreshEligibleInstruments();
    }

    @Test
    void overlappingInvocationIsSkipped() throws Exception {
        MarketDataBatchRefreshService service = mock(MarketDataBatchRefreshService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return 0;
        }).when(service).refreshEligibleInstruments();
        MarketDataBatchScheduler scheduler = scheduler(service);

        Thread firstCycle = Thread.startVirtualThread(scheduler::runRefreshCycle);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.runRefreshCycle();
            verify(service, times(1)).refreshEligibleInstruments();
        } finally {
            release.countDown();
            firstCycle.join();
        }
    }

    private MarketDataBatchScheduler scheduler(MarketDataBatchRefreshService service) {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-29T15:00:00Z"),
                ZoneOffset.UTC
        );
        return new MarketDataBatchScheduler(service, clock);
    }
}
