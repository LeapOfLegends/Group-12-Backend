package group12.Controller;

import group12.Services.TransactionsService;
import group12.dto.CreateTransactionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionsController {

    private final TransactionsService transactionsService;

    @PostMapping("/deposit")
    public ResponseEntity<Void> deposit(@Valid @RequestBody CreateTransactionRequest request) {
        transactionsService.deposit(
            request.clientId(),
            request.amount(),
            request.balanceAfter()
        );
        return createdResponse("deposit");
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Void> withdraw( @Valid @RequestBody CreateTransactionRequest request ) {
        transactionsService.withdraw(
            request.clientId(),
            request.amount(),
            request.balanceAfter()
        );
        return createdResponse("withdraw");
    }

    @PostMapping("/buy")
    public ResponseEntity<Void> buy( @Valid @RequestBody CreateTransactionRequest request ) {
        requireOrderId(request);
        transactionsService.buy(
            request.clientId(),
            request.orderId(),
            request.amount(),
            request.balanceAfter()
        );
        return createdResponse("buy");
    }

    @PostMapping("/sell")
    public ResponseEntity<Void> sell( @Valid @RequestBody CreateTransactionRequest request ) {
        requireOrderId(request);
        transactionsService.sell(
            request.clientId(),
            request.orderId(),
            request.amount(),
            request.balanceAfter()
        );
        return createdResponse("sell");
    }

    private void requireOrderId(CreateTransactionRequest request) {
        if (request.orderId() == null) {
            throw new IllegalArgumentException("Order ID is required for buy and sell transactions");
        }
    }

    private ResponseEntity<Void> createdResponse(String transactionType) {
        return ResponseEntity
            .created(URI.create("/api/transactions/" + transactionType))
            .build();
    }
}
