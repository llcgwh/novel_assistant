package com.novelwriting.service;

import com.novelwriting.entity.Outline;
import com.novelwriting.repository.OutlineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class OutlineService {

    @Autowired
    private OutlineRepository outlineRepository;

    public List<Outline> getAllOutlines() {
        return outlineRepository.findAll();
    }

    public Optional<Outline> getOutlineById(Long id) {
        return outlineRepository.findById(id);
    }

    public Outline createOutline(Outline outline) {
        return outlineRepository.save(outline);
    }

    public Outline updateOutline(Long id, Outline outlineDetails) {
        Outline outline = outlineRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Outline not found"));

        outline.setTitle(outlineDetails.getTitle());
        outline.setContent(outlineDetails.getContent());
        outline.setChapterNumber(outlineDetails.getChapterNumber());
        outline.setPlotOrder(outlineDetails.getPlotOrder());
        outline.setStatus(outlineDetails.getStatus());

        return outlineRepository.save(outline);
    }

    public void deleteOutline(Long id) {
        outlineRepository.deleteById(id);
    }
}
