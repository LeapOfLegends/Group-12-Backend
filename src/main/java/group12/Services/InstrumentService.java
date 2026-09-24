package group12.Services;

import group12.dto.InstrumentDTO;
import group12.Entities.InstrumentEntity;
import group12.Repository.InstrumentRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class InstrumentService {

    private final InstrumentRepository instrumentRepository;

    public InstrumentService(InstrumentRepository instrumentRepository) {
        this.instrumentRepository = instrumentRepository;
    }


    public List<InstrumentDTO> getAllInstruments() {

        return instrumentRepository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }


    public InstrumentDTO getInstrumentById(Long instrumentId) {

        InstrumentEntity instrument =
                instrumentRepository.findById(instrumentId)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Instrument not found"
                                )
                        );

        return toDto(instrument);
    }


    public InstrumentDTO getInstrumentBySymbol(String symbol) {

        String cleanedSymbol =
                symbol.trim().toUpperCase();

        InstrumentEntity instrument =
                instrumentRepository.findBySymbol(cleanedSymbol)
                        .orElseThrow(() -> new ResponseStatusException( HttpStatus.NOT_FOUND, "Instrument not found")
                        );

        return toDto(instrument);
    }


    private InstrumentDTO toDto(InstrumentEntity instrument) {

        return new InstrumentDTO(
                instrument.getSymbol(),
                instrument.getInstrumentName(),
                instrument.getAssetClass(),
                instrument.getCurrency(),
                instrument.isTradable(),
                instrument.getPrice()
        );
    }
}