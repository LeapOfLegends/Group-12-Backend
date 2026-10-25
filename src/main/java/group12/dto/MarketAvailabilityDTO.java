package group12.dto;

import group12.Entities.MarketCode;
import group12.Entities.OrderRejectionReason;

import java.time.OffsetDateTime;

/** Server-authoritative availability; UNAVAILABLE is distinct from a known closure. */
public record MarketAvailabilityDTO(
        MarketCode marketCode,
        String status,
        boolean tradingAllowed,
        String message,
        OffsetDateTime statusAsOf,
        OffsetDateTime validUntil,
        OrderRejectionReason rejectionReason
) {}