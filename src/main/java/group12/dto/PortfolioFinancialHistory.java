package group12.dto;

import java.util.List;
import java.util.Objects;

public record PortfolioFinancialHistory(
        Long clientId,
        List<PortfolioFinancialEvent> events,
        List<PortfolioFinancialState> states
) {
    public PortfolioFinancialHistory {
        Objects.requireNonNull(clientId, "clientId is required");
        events = List.copyOf(Objects.requireNonNull(events, "events are required"));
        states = List.copyOf(Objects.requireNonNull(states, "states are required"));
    }
}
