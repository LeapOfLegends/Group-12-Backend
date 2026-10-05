package group12.orderlifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderLifecyclePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OrderLifecycleConfiguration.class);

    @Test
    void defaultsMatchExistingLifecycleConfiguration() {
        OrderLifecycleProperties properties = new OrderLifecycleProperties();

        assertEquals(Duration.ofSeconds(30), properties.getQuote().getMaxAge());
        assertEquals(Duration.ofMinutes(2), properties.getExecution().getMaxWait());
        assertEquals(Duration.ofSeconds(30), properties.getRecovery().getInterval());
        assertEquals(Duration.ofMinutes(1), properties.getRecovery().getSubmittedAge());
    }

    @Test
    void existingPropertyHierarchyBindsToNestedSections() {
        contextRunner
                .withPropertyValues(
                        "order-lifecycle.quote.max-age=45s",
                        "order-lifecycle.execution.max-wait=3m",
                        "order-lifecycle.recovery.interval=40s",
                        "order-lifecycle.recovery.submitted-age=2m"
                )
                .run(context -> {
                    OrderLifecycleProperties properties = context.getBean(
                            OrderLifecycleProperties.class
                    );

                    assertEquals(Duration.ofSeconds(45), properties.getQuote().getMaxAge());
                    assertEquals(Duration.ofMinutes(3), properties.getExecution().getMaxWait());
                    assertEquals(
                            Duration.ofSeconds(40),
                            properties.getRecovery().getInterval()
                    );
                    assertEquals(
                            Duration.ofMinutes(2),
                            properties.getRecovery().getSubmittedAge()
                    );
                });
    }

    @Test
    void durationPropertiesRejectNullZeroAndNegativeValues() {
        for (Duration invalid : Arrays.asList(
                null,
                Duration.ZERO,
                Duration.ofSeconds(-1)
        )) {
            OrderLifecycleProperties properties = new OrderLifecycleProperties();

            assertThrows(
                    IllegalArgumentException.class,
                    () -> properties.getQuote().setMaxAge(invalid)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> properties.getExecution().setMaxWait(invalid)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> properties.getRecovery().setInterval(invalid)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> properties.getRecovery().setSubmittedAge(invalid)
            );
        }
    }
}
