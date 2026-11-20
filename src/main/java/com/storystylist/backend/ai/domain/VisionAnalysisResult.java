package com.storystylist.backend.ai.domain;
import java.util.List;
public record VisionAnalysisResult(String overallMood, List<PhotoObservation> photos, GroupAnalysis groupAnalysis) {}
