package group12.Controller;

import org.springframework.web.bind.annotation.*;
import group12.Entities.HoldingEntity;
import group12.Services.HoldingService;
import group12.dto.HoldingUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;


@RestController 
@RequestMapping("api/client/{clientId}/holdings")
@RequiredArgsConstructor 
public class HoldingController {

    private final HoldingService holdingService;

    @GetMapping
    public List<HoldingEntity> getHoldingsByClientId(
        @PathVariable Long clientId
    ) {
        return holdingService.getHoldingsByClientId(clientId);
    }

    @GetMapping("/{holdingId}")
    public ResponseEntity<HoldingEntity> getHolding(
        @PathVariable Long holdingId,
        @PathVariable Long clientId
    ) {
        HoldingEntity holding = holdingService.getHoldingByHoldingIdAndClientId(holdingId, clientId);

        return ResponseEntity.ok(holding);
    }

    @GetMapping("/instrument/{instrumentId}")
    public ResponseEntity<Optional<HoldingEntity>> getInstrumentHoldings(
        @PathVariable Long instrumentId,
        @PathVariable Long clientId
    ) {
        Optional<HoldingEntity> holding = holdingService.getHoldingByInstrumentIdAndClientId(instrumentId, clientId);

        return ResponseEntity.ok(holding);
    }

    @PatchMapping("/instrument/{instrumentId}")
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

    @DeleteMapping("/{holdingId}")
    public ResponseEntity<Void> deleteHolding(
        @PathVariable Long holdingId,
        @PathVariable Long clientId
    ) {
        holdingService.deleteHolding(holdingId, clientId);
        return ResponseEntity.noContent().build();
    }
    
}
