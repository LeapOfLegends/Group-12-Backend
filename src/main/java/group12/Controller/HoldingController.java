package group12.Controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import group12.dto.HoldingUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;


@RestController 
@RequestMapping("/api/clients")
@RequiredArgsConstructor 
public class HoldingController {

    private final HoldingService holdingService;

    /*
     * =========================================================================
     * CLIENT SELF-SERVICE ENDPOINTS
     *
     * These routes operate on the authenticated client's holdings. The client
     * ID is read from the verified JWT subject rather than supplied in the URL.
     * =========================================================================
     */
    @GetMapping("/me/holdings")
    @PreAuthorize("hasRole('CLIENT')")
    public List<HoldingEntity> getCurrentClientHoldings(@AuthenticationPrincipal Jwt jwt) {
        return holdingService.getHoldingsByClientId(currentClientId(jwt));
    }

    @GetMapping("/me/holdings/{holdingId}")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<HoldingEntity> getCurrentClientHolding(
        @PathVariable Long holdingId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        HoldingEntity holding = holdingService.getHoldingByHoldingIdAndClientId(holdingId, currentClientId(jwt));
        return ResponseEntity.ok(holding);
    }

    @GetMapping("/me/holdings/instrument/{instrumentId}")
    @PreAuthorize("hasRole('CLIENT')")
    public ResponseEntity<Optional<HoldingEntity>> getCurrentClientInstrumentHolding(
        @PathVariable Long instrumentId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        Optional<HoldingEntity> holding = holdingService.getHoldingByInstrumentIdAndClientId(instrumentId, currentClientId(jwt));
        return ResponseEntity.ok(holding);
    }

    /*
     * =========================================================================
     * ADMIN ENDPOINTS
     *
     * Admin routes include a client ID so an authorized admin can select the
     * account. Finance, Operations, and Super Admin can read holdings; only
     * Operations and Super Admin can update or delete them.
     * =========================================================================
     */
    @GetMapping("/{clientId}/holdings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public List<HoldingEntity> getHoldingsByClientId(
        @PathVariable Long clientId
    ) {
        return holdingService.getHoldingsByClientId(clientId);
    }

    @GetMapping("/{clientId}/holdings/{holdingId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<HoldingEntity> getHolding(
        @PathVariable Long holdingId,
        @PathVariable Long clientId
    ) {
        HoldingEntity holding = holdingService.getHoldingByHoldingIdAndClientId(holdingId, clientId);

        return ResponseEntity.ok(holding);
    }

    @GetMapping("/{clientId}/holdings/instrument/{instrumentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'FINANCE', 'OPERATIONS')")
    public ResponseEntity<Optional<HoldingEntity>> getInstrumentHoldings(
        @PathVariable Long instrumentId,
        @PathVariable Long clientId
    ) {
        Optional<HoldingEntity> holding = holdingService.getHoldingByInstrumentIdAndClientId(instrumentId, clientId);

        return ResponseEntity.ok(holding);
    }

    @PatchMapping("/{clientId}/holdings/instrument/{instrumentId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<HoldingEntity> updateHolding(
        @PathVariable Long instrumentId,
        @PathVariable Long clientId,
        @Valid @RequestBody HoldingUpdateRequest request
    ) {
        HoldingEntity updatedHolding = holdingService.updateHolding(
            instrumentId,
            clientId,
            request.quantity(),
            request.averageCost()
        );

        return ResponseEntity.ok(updatedHolding);
    }

    @DeleteMapping("/{clientId}/holdings/{holdingId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'OPERATIONS')")
    public ResponseEntity<Void> deleteHolding(
        @PathVariable Long holdingId,
        @PathVariable Long clientId
    ) {
        holdingService.deleteHolding(holdingId, clientId);
        return ResponseEntity.noContent().build();
    }

    /*
     * =========================================================================
     * SHARED PRIVATE HELPERS
     * =========================================================================
     */

    /** Returns the client ID from the already-verified JWT subject. */
    private Long currentClientId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
    
}
