package group12.dto;

import group12.Entities.OrderType;

public record CreateOrderRequest(

        // will remove once authentication is implemented
        Long clientId,

        Long instrumentId,
        OrderType orderType,
        Integer quantity
) {
}