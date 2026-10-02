package group12.orderlifecycle;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        QuoteFreshnessProperties.class,
        OrderExecutionProperties.class
})
public class OrderLifecycleConfiguration {

    @Bean
    @Qualifier("orderLifecycleClock")
    Clock orderLifecycleClock() {
        return Clock.systemUTC();
    }
}
