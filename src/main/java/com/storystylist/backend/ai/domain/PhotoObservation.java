package com.storystylist.backend.ai.domain;
import java.util.List;
public record PhotoObservation(String photoId, String description, String lighting, String composition,
                               List<String> visualStrengths, List<String> visualWeaknesses) {}
