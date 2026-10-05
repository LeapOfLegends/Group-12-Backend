package group12.orderlifecycle;

import group12.Repository.OrderRepository;
import group12.Services.OrderAcceptanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        prefix = "order-lifecycle.recovery",
        name = "enabled",
        havingValue = "true"
)
public class OrderLifecycleRecoveryScheduler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OrderLifecycleRecoveryScheduler.class);

    private final OrderRepository orderRepository;
    private final OrderAcceptanceService orderAcceptanceService;
    private final OrderLifecycleProperties lifecycleProperties;
    private final Clock clock;
    private final AtomicBoolean cycleRunning = new AtomicBoolean();

    public OrderLifecycleRecoveryScheduler(
            OrderRepository orderRepository,
            OrderAcceptanceService orderAcceptanceService,
            OrderLifecycleProperties lifecycleProperties,
            @Qualifier("orderLifecycleClock") Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.orderAcceptanceService = orderAcceptanceService;
        this.lifecycleProperties = lifecycleProperties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${order-lifecycle.recovery.interval:30s}")
    public void runRecoveryCycle() {
        if (!cycleRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            OffsetDateTime submittedBefore = OffsetDateTime.ofInstant(
                    clock.instant().minus(
                            lifecycleProperties.getRecovery().getSubmittedAge()
                    ),
                    ZoneOffset.UTC
            );
            List<Long> orderIds = orderRepository
                    .findSubmittedOrderIdsSubmittedBefore(submittedBefore);

            for (Long orderId : orderIds) {
                recoverSubmittedOrder(orderId);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Scheduled submitted-order recovery failed before all orders were considered",
                    exception
            );
        } finally {
            cycleRunning.set(false);
        }
    }

    private void recoverSubmittedOrder(Long orderId) {
        try {
            orderAcceptanceService.acceptSubmittedOrder(orderId);
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to recover submitted order {}", orderId, exception);
        }
    }
}
