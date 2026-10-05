package group12.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaOrderConfiguration {
    
    public static final String ORDER_CREATED_TOPIC = "trading.orders.created";
    public static final String ORDER_SUBMITTED_TOPIC = "trading.orders.submitted";
    public static final String ORDER_REJECTED_TOPIC = "trading.orders.rejected";
    public static final String ORDER_FAILED_TOPIC = "trading.orders.failed";
    public static final String ORDER_FILLED_TOPIC = "trading.orders.filled";
    
    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(ORDER_CREATED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
    
    @Bean
    public NewTopic orderSubmittedTopic() {
        return TopicBuilder.name(ORDER_SUBMITTED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
    
    @Bean
    public NewTopic orderRejectedTopic() {
        return TopicBuilder.name(ORDER_REJECTED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
    
    @Bean
    public NewTopic orderFailedTopic() {
        return TopicBuilder.name(ORDER_FAILED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
    
    @Bean
    public NewTopic orderFilledTopic() {
        return TopicBuilder.name(ORDER_FILLED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
