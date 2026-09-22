package group12.Controller;

import group12.dto.InstrumentDTO;
import group12.Services.InstrumentService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/instruments")
public class InstrumentController {

    private final InstrumentService instrumentService;

    public InstrumentController(InstrumentService instrumentService) {
        this.instrumentService = instrumentService;
    }

    @GetMapping
    public ResponseEntity<List<InstrumentDTO>> getAllInstruments() {

        return ResponseEntity.ok(
                instrumentService.getAllInstruments()
        );
    }

    @GetMapping("/{instrumentId}")
    public ResponseEntity<InstrumentDTO> getInstrument(
            @PathVariable Long instrumentId) {

        return ResponseEntity.ok(
                instrumentService.getInstrumentById(instrumentId)
        );
    }

    @GetMapping("/symbol/{symbol}")
    public ResponseEntity<InstrumentDTO> getInstrumentBySymbol(
            @PathVariable String symbol) {

        return ResponseEntity.ok(
                instrumentService.getInstrumentBySymbol(symbol)
        );
    }
}