package com.storystylist.backend.ai.domain;
import java.util.List;
public record GroupAnalysis(String commonTheme, List<String> complementaryPhotoIds, String observations) {}
