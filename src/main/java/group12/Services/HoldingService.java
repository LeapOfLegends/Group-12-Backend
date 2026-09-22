package group12.Services;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import group12.Entities.HoldingEntity;
import group12.Repository.HoldingRepository;
import group12.Repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;


@Service
@RequiredArgsConstructor
public class HoldingService {

    private final HoldingRepository holdingRepository;
    private final ClientRepository clientRepository;

    public HoldingEntity getHoldingByHoldingId(Long holdingId) {
        return holdingRepository.getHoldingByHoldingId(holdingId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    String.format("Holding id: %s not found", holdingId)
                )
        );
    }

    public List<HoldingEntity> getHoldingsByClientId(Long clientId) {
        
        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    String.format("Client id: %s not found", clientId)
            );
        }

        List<HoldingEntity> holdings = holdingRepository.getHoldingsByClientId(clientId);

        if(holdings.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    String.format("No holdings found for client id: %s", clientId)
            );
        }
        
        return holdings;
    }

    public List<HoldingEntity> getHoldingsByInstrumentId(Long instrumentsId) {
        List<HoldingEntity> holdings = holdingRepository.getHoldingsByInstrumentId(instrumentsId);

        if(holdings.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    String.format("No holdings found for instrument id: %s", instrumentsId)
            );
        }
        // Check ID exists not implemented yet
        //
        // if(!instrumentRepository.exists(instrumentId)) {
        //     throw new ResponseStatusException(
        //             HttpStatus.NOT_FOUND,
        //             String.format("Instrument id: \"%s\" not found", instrumentId)
        //     );
        // }
        return holdings;
    }


}
