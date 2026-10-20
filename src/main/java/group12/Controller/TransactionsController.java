package group12.Controller;

import group12.Services.TransactionsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import group12.Entities.TransactionsEntity;

@RestController
@RequestMapping("/api/clients/me/transactions")
@RequiredArgsConstructor
public class TransactionsController {

    private final TransactionsService transactionsService;

        @GetMapping
        @PreAuthorize("hasRole('CLIENT')")
        public ResponseEntity<List<TransactionsEntity>> getCurrentClientTransactions( @AuthenticationPrincipal Jwt jwt ) {
            return ResponseEntity.ok(
                transactionsService.getTransactionsByClientId(currentClientId(jwt))
            );
        }

        @GetMapping("/{transactionId}")
        @PreAuthorize("hasRole('CLIENT')")
        public ResponseEntity<TransactionsEntity> getCurrentClientTransaction( @PathVariable Long transactionId, @AuthenticationPrincipal Jwt jwt ) {
            return ResponseEntity.ok(
                transactionsService.getTransactionByIdAndClientId(
                    transactionId,
                    currentClientId(jwt)
                )
            );
        }

    private Long currentClientId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
