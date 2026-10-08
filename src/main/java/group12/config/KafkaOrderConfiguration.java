package group12.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaOrderConfiguration {
    
    public static final String ORDER_ACCEPTED_TOPIC = "trading.orders.accepted";
    public static final String ORDER_FILLED_TOPIC = "trading.orders.filled";
    
    @Bean
    public NewTopic orderAcceptedTopic() {
        return TopicBuilder.name(ORDER_ACCEPTED_TOPIC)
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
