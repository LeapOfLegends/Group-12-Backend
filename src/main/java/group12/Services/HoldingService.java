package group12.Services;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import group12.exception.HoldingNotFoundException;
import group12.exception.ClientNotFoundException;
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
                .orElseThrow(() -> new HoldingNotFoundException("Holding not found"));
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

    public List<HoldingEntity> getHoldingsByInstrumentId(Long instrumentsId) {
        List<HoldingEntity> holdings = holdingRepository.getHoldingsByInstrumentId(instrumentsId);

        if(holdings.isEmpty()) {
            throw new HoldingNotFoundException("No holdings found for this instrument");
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
