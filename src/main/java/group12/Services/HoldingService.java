package group12.Services;

import org.springframework.stereotype.Service;
import group12.exception.HoldingNotFoundException;
import group12.exception.ClientNotFoundException;
import group12.Entities.HoldingEntity;
import group12.Repository.HoldingRepository;
import group12.Repository.ClientRepository;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Optional;


@Service
@RequiredArgsConstructor
public class HoldingService {

    private final HoldingRepository holdingRepository;
    private final ClientRepository clientRepository;

    public HoldingEntity getHoldingByHoldingIdAndClientId(Long holdingId, Long clientId) {

        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        HoldingEntity holding = holdingRepository.getHoldingByHoldingIdAndClientId(holdingId, clientId);

        if(holding == null){
            throw new HoldingNotFoundException("Holding not found");
        }
        return holding;
    }

    public List<HoldingEntity> getHoldingsByClientId(Long clientId) {
        
        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        List<HoldingEntity> holdings = holdingRepository.getHoldingsByClientId(clientId);

        if(holdings.isEmpty()) {
            throw new HoldingNotFoundException("No holdings found for this client");
        }
        
        return holdings;
    }

    public Optional<HoldingEntity> getHoldingByInstrumentIdAndClientId(Long instrumentId,  Long clientId) {

        Optional<HoldingEntity> holdings = holdingRepository.getHoldingByInstrumentIdAndClientId(instrumentId, clientId);

        //check if client exists
        if(clientRepository.findById(clientId) == null) {
            throw new ClientNotFoundException("Client not found");
        }

        if(holdings.isEmpty()) {
            throw new HoldingNotFoundException("Client does not hold this instrument");
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
