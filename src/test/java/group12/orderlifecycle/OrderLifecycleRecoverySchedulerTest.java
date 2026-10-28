package group12.orderlifecycle;

import group12.Entities.OrderEntity;
import group12.Entities.OrderStatus;
import group12.Repository.OrderRepository;
import group12.Services.OrderAcceptanceService;
import group12.Services.OrderExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
                        OrderExecutionService.class,
                        () -> mock(OrderExecutionService.class)
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
        OrderExecutionService executionService = mock(OrderExecutionService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                executionService,
                Duration.ofMinutes(2),
                Duration.ofMinutes(1)
        );
        OffsetDateTime expectedCutoff = OffsetDateTime.parse("2026-10-02T14:58:00Z");
        OrderEntity accepted = order(11L, OrderStatus.ACCEPTED);
        OrderEntity rejected = order(12L, OrderStatus.REJECTED);
        when(repository.findSubmittedOrderIdsSubmittedBefore(expectedCutoff))
                .thenReturn(List.of(11L, 12L));
        when(repository.findAcceptedOrderIdsAcceptedBefore(any())).thenReturn(List.of());
        when(acceptanceService.acceptSubmittedOrder(11L)).thenReturn(accepted);
        when(acceptanceService.acceptSubmittedOrder(12L)).thenReturn(rejected);

        scheduler.runRecoveryCycle();

        verify(repository).findSubmittedOrderIdsSubmittedBefore(expectedCutoff);
        verify(acceptanceService).acceptSubmittedOrder(11L);
        verify(acceptanceService).acceptSubmittedOrder(12L);
        verify(executionService, never()).executeAcceptedOrder(any());
        assertEquals(OrderStatus.ACCEPTED, accepted.getStatus());
        assertEquals(OrderStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void submittedAgeDeterminesCutoffSoRecentOrdersAreNotSelected() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderExecutionService executionService = mock(OrderExecutionService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                executionService,
                Duration.ofMinutes(5),
                Duration.ofMinutes(1)
        );
        OffsetDateTime expectedCutoff = OffsetDateTime.parse("2026-10-02T14:55:00Z");
        when(repository.findSubmittedOrderIdsSubmittedBefore(expectedCutoff))
                .thenReturn(List.of());

        scheduler.runRecoveryCycle();

        verify(repository).findSubmittedOrderIdsSubmittedBefore(expectedCutoff);
        verify(acceptanceService, never()).acceptSubmittedOrder(any());
    }

    @Test
    void oldAcceptedOrdersAreReprocessedUsingAcceptedAgeCutoff() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderExecutionService executionService = mock(OrderExecutionService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                executionService,
                Duration.ofMinutes(1),
                Duration.ofMinutes(3)
        );
        OffsetDateTime expectedCutoff = OffsetDateTime.parse("2026-10-02T14:57:00Z");
        when(repository.findSubmittedOrderIdsSubmittedBefore(any())).thenReturn(List.of());
        when(repository.findAcceptedOrderIdsAcceptedBefore(expectedCutoff))
                .thenReturn(List.of(31L, 32L));

        scheduler.runRecoveryCycle();

        verify(repository).findAcceptedOrderIdsAcceptedBefore(expectedCutoff);
        verify(executionService).executeAcceptedOrder(31L);
        verify(executionService).executeAcceptedOrder(32L);
    }

    @Test
    void oneOrderFailureDoesNotPreventAnotherOrderFromBeingRecovered() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderExecutionService executionService = mock(OrderExecutionService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                executionService,
                Duration.ofMinutes(1),
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
    void oneAcceptedOrderFailureDoesNotPreventAnotherOrderFromBeingRecovered() {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderExecutionService executionService = mock(OrderExecutionService.class);
        OrderLifecycleRecoveryScheduler scheduler = scheduler(
                repository,
                acceptanceService,
                executionService,
                Duration.ofMinutes(1),
                Duration.ofMinutes(1)
        );
        when(repository.findSubmittedOrderIdsSubmittedBefore(any())).thenReturn(List.of());
        when(repository.findAcceptedOrderIdsAcceptedBefore(any()))
                .thenReturn(List.of(41L, 42L));
        when(executionService.executeAcceptedOrder(41L))
                .thenThrow(new RuntimeException("temporary database failure"));

        scheduler.runRecoveryCycle();

        verify(executionService).executeAcceptedOrder(41L);
        verify(executionService).executeAcceptedOrder(42L);
    }

    @Test
    void overlappingRecoveryCycleIsSkipped() throws Exception {
        OrderRepository repository = mock(OrderRepository.class);
        OrderAcceptanceService acceptanceService = mock(OrderAcceptanceService.class);
        OrderExecutionService executionService = mock(OrderExecutionService.class);
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
                executionService,
                Duration.ofMinutes(1),
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

    private static OrderLifecycleRecoveryScheduler scheduler(
            OrderRepository repository,
            OrderAcceptanceService acceptanceService,
            OrderExecutionService executionService,
            Duration submittedAge,
            Duration acceptedAge
    ) {
        OrderLifecycleProperties properties = new OrderLifecycleProperties();
        properties.getRecovery().setSubmittedAge(submittedAge);
        properties.getRecovery().setAcceptedAge(acceptedAge);
        return new OrderLifecycleRecoveryScheduler(
                repository,
                acceptanceService,
                executionService,
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
