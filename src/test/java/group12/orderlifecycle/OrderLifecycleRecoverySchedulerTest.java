package group12.orderlifecycle;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Repository.OrderRepository;
import group12.Services.OrderAcceptanceService;
import group12.Services.OrderExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderLifecycleRecoverySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");

    @Test
    void schedulerCanBeDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(OrderLifecycleRecoveryScheduler.class)
                .withPropertyValues("order-lifecycle.recovery.enabled=false")
                .run(context -> assertFalse(
                        context.containsBean("orderLifecycleRecoveryScheduler")
                ));
    }

    @Test
    void schedulerCanBeEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(OrderLifecycleRecoveryScheduler.class)
                .withBean(OrderRepository.class, () -> mock(OrderRepository.class))
                .withBean(
                        OrderAcceptanceService.class,
                        () -> mock(OrderAcceptanceService.class)
                )
                .withBean(
                        OrderLifecycleProperties.class,
                        OrderLifecycleProperties::new
                )
                .withBean(
                        "orderLifecycleClock",
                        Clock.class,
                        () -> Clock.fixed(NOW, ZoneOffset.UTC)
                )
                .withPropertyValues("order-lifecycle.recovery.enabled=true")
                .run(context -> assertTrue(
                        context.containsBean("orderLifecycleRecoveryScheduler")
                ));
    }

    @Test
    void oldSubmittedOrdersAreReprocessedAndAcceptanceResultsAreLeftUnchanged() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                Duration.ofMinutes(2)
        );
        OffsetDateTime expectedCutoff = OffsetDateTime.parse("2026-10-02T14:58:00Z");
        OrderEntity accepted = order(11L, OrderStatus.ACCEPTED);
        OrderEntity rejected = order(12L, OrderStatus.REJECTED);
        when(repository.findSubmittedOrderIdsSubmittedBefore(expectedCutoff))
                .thenReturn(List.of(11L, 12L));
        when(acceptanceService.acceptSubmittedOrder(11L)).thenReturn(accepted);
        when(acceptanceService.acceptSubmittedOrder(12L)).thenReturn(rejected);

        scheduler.runRecoveryCycle();

        verify(repository).findSubmittedOrderIdsSubmittedBefore(expectedCutoff);
        verify(acceptanceService).acceptSubmittedOrder(11L);
        verify(acceptanceService).acceptSubmittedOrder(12L);
        verify(repository, never()).findAcceptedOrderIdsAcceptedBefore(any());
        assertEquals(OrderStatus.ACCEPTED, accepted.getStatus());
        assertEquals(OrderStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void submittedAgeDeterminesCutoffSoRecentOrdersAreNotSelected() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                Duration.ofMinutes(5)
        );
        OffsetDateTime expectedCutoff = OffsetDateTime.parse("2026-10-02T14:55:00Z");
        when(repository.findSubmittedOrderIdsSubmittedBefore(expectedCutoff))
                .thenReturn(List.of());

        scheduler.runRecoveryCycle();

        verify(repository).findSubmittedOrderIdsSubmittedBefore(expectedCutoff);
        verify(acceptanceService, never()).acceptSubmittedOrder(any());
    }

    @Test
    void oneOrderFailureDoesNotPreventAnotherOrderFromBeingRecovered() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                Duration.ofMinutes(1)
        );
        when(repository.findSubmittedOrderIdsSubmittedBefore(any()))
                .thenReturn(List.of(21L, 22L));
        when(acceptanceService.acceptSubmittedOrder(21L))
                .thenThrow(new RuntimeException("temporary database failure"));
        OrderEntity accepted = order(22L, OrderStatus.ACCEPTED);
        when(acceptanceService.acceptSubmittedOrder(22L)).thenReturn(accepted);

        scheduler.runRecoveryCycle();

        verify(acceptanceService).acceptSubmittedOrder(21L);
        verify(acceptanceService).acceptSubmittedOrder(22L);
        assertEquals(OrderStatus.ACCEPTED, accepted.getStatus());
    }

    @Test
    void overlappingRecoveryCycleIsSkipped() throws Exception {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of();
        }).when(repository).findSubmittedOrderIdsSubmittedBefore(any());
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                Duration.ofMinutes(1)
        );

        Thread firstCycle = Thread.startVirtualThread(scheduler::runRecoveryCycle);
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            scheduler.runRecoveryCycle();
            verify(repository, times(1)).findSubmittedOrderIdsSubmittedBefore(any());
        } finally {
            release.countDown();
            firstCycle.join();
        }
    }

    @Test
    void schedulerHasNoExecutionServiceDependency() {
        boolean hasExecutionDependency = Arrays.stream(
                        OrderLifecycleRecoveryScheduler.class.getDeclaredFields()
                )
                .map(Field::getType)
                .anyMatch(OrderExecutionService.class::equals);

        assertFalse(hasExecutionDependency);
    }

    private static OrderLifecycleRecoveryScheduler scheduler(
            OrderRepository repository,
            OrderAcceptanceService acceptanceService,
            Duration submittedAge
    ) {
        OrderLifecycleProperties properties = new OrderLifecycleProperties();
        properties.getRecovery().setSubmittedAge(submittedAge);
        return new OrderLifecycleRecoveryScheduler(
                repository,
                acceptanceService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static OrderEntity order(Long orderId, OrderStatus status) {
        OrderEntity order = new OrderEntity();
        order.setOrderId(orderId);
        order.setStatus(status);
        return order;
    }
}
