package com.storystylist.backend.ai.validation;
import com.storystylist.backend.ai.domain.*;
import com.storystylist.backend.ai.exception.*;
import com.storystylist.backend.photo.dto.ProcessedPhoto;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class VisionResultValidator {
    public VisionAnalysisResult validate(VisionAnalysisResult result, List<ProcessedPhoto> inputs) {
        if (result == null) fail();
        text(result.overallMood(), 1000);
        if (result.photos() == null || result.photos().size() != inputs.size() || result.photos().size() > 10) fail();
        Set<String> expected = new HashSet<>();
        inputs.forEach(photo -> expected.add(photo.metadata().photoId()));
        Map<String, PhotoObservation> observations = new HashMap<>();
        for (var photo : result.photos()) {
            if (photo == null || !expected.contains(photo.photoId()) || observations.containsKey(photo.photoId())) fail();
            text(photo.description(), 2000); text(photo.lighting(), 1000); text(photo.composition(), 1000);
            strings(photo.visualStrengths(), 10); strings(photo.visualWeaknesses(), 10);
            observations.put(photo.photoId(), new PhotoObservation(photo.photoId(), photo.description(), photo.lighting(),
                    photo.composition(), List.copyOf(photo.visualStrengths()), List.copyOf(photo.visualWeaknesses())));
        }
        var group = result.groupAnalysis();
        if (group == null) fail();
        text(group.commonTheme(), 1000); text(group.observations(), 4000);
        strings(group.complementaryPhotoIds(), 10);
        Set<String> references = new HashSet<>();
        for (String id : group.complementaryPhotoIds()) if (!expected.contains(id) || !references.add(id)) fail();
        return new VisionAnalysisResult(result.overallMood(),
                inputs.stream().map(photo -> observations.get(photo.metadata().photoId())).toList(),
                new GroupAnalysis(group.commonTheme(), List.copyOf(group.complementaryPhotoIds()), group.observations()));
    }
    private void strings(List<String> values, int max) {
        if (values == null || values.size() > max) fail();
        values.forEach(value -> text(value, 1000));
    }
    private void text(String value, int max) { if (value == null || value.isBlank() || value.length() > max) fail(); }
    private void fail() { throw new AiException(AiError.AI_INVALID_RESPONSE); }
}
