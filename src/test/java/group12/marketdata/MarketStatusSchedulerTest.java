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

class MarketStatusSchedulerTest {

    @Test
    void schedulerIsDisabledByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(MarketStatusScheduler.class)
                .withPropertyValues("market-status.refresh.enabled=false")
                .run(context -> assertFalse(
                        context.containsBean("marketStatusScheduler")
                ));
    }

    @Test
    void schedulerCanBeEnabledExplicitly() {
        new ApplicationContextRunner()
                .withUserConfiguration(MarketStatusScheduler.class)
                .withBean(
                        MarketStatusRefreshService.class,
                        () -> mock(MarketStatusRefreshService.class)
                )
                .withPropertyValues("market-status.refresh.enabled=true")
                .run(context -> assertTrue(
                        context.containsBean("marketStatusScheduler")
                ));
    }

    @Test
    void oneScheduledInvocationRunsOneStatusRefresh() {
        MarketStatusRefreshService service = mock(MarketStatusRefreshService.class);
        MarketStatusScheduler scheduler = scheduler(service);

        scheduler.runRefreshCycle();

        verify(service).refreshFromAlpacaClock();
    }

    @Test
    void rateLimitStartsCooldownAndSkipsImmediateCycles() {
        MarketStatusRefreshService service = mock(MarketStatusRefreshService.class);
        doThrow(new MarketDataRateLimitException())
                .when(service).refreshFromAlpacaClock();
        MarketStatusScheduler scheduler = scheduler(service);

        scheduler.runRefreshCycle();
        scheduler.runRefreshCycle();

        verify(service, times(1)).refreshFromAlpacaClock();
    }

    @Test
    void overlappingInvocationIsSkipped() throws Exception {
        MarketStatusRefreshService service = mock(MarketStatusRefreshService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(service).refreshFromAlpacaClock();
        MarketStatusScheduler scheduler = scheduler(service);

        Thread firstCycle = Thread.startVirtualThread(scheduler::runRefreshCycle);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.runRefreshCycle();
            verify(service, times(1)).refreshFromAlpacaClock();
        } finally {
            release.countDown();
            firstCycle.join();
        }
    }

    private MarketStatusScheduler scheduler(MarketStatusRefreshService service) {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-12T09:30:00Z"),
                ZoneOffset.UTC
        );
        return new MarketStatusScheduler(service, clock);
    }
}
