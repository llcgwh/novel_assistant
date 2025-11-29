package com.novelwriting.service;

import com.novelwriting.entity.Foreshadow;
import com.novelwriting.repository.ForeshadowRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class ForeshadowService {

    @Autowired
    private ForeshadowRepository foreshadowRepository;

    public List<Foreshadow> getAllForeshadows() {
        return foreshadowRepository.findAll();
    }

    public Optional<Foreshadow> getForeshadowById(Long id) {
        return foreshadowRepository.findById(id);
    }

    public Foreshadow createForeshadow(Foreshadow foreshadow) {
        return foreshadowRepository.save(foreshadow);
    }

    public Foreshadow updateForeshadow(Long id, Foreshadow foreshadowDetails) {
        Foreshadow foreshadow = foreshadowRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Foreshadow not found"));

        foreshadow.setTitle(foreshadowDetails.getTitle());
        foreshadow.setContent(foreshadowDetails.getContent());
        foreshadow.setLaidAt(foreshadowDetails.getLaidAt());
        foreshadow.setRevealedAt(foreshadowDetails.getRevealedAt());
        foreshadow.setStatus(foreshadowDetails.getStatus());

        return foreshadowRepository.save(foreshadow);
    }

    public void deleteForeshadow(Long id) {
        foreshadowRepository.deleteById(id);
    }
}
