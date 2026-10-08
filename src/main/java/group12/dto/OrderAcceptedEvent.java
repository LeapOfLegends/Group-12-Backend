package group12.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderAcceptedEvent {
    private Long orderId;
    private Long clientId;
    private LocalDateTime acceptedAt;
}
