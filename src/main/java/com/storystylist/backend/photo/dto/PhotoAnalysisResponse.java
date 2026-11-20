package com.storystylist.backend.photo.dto;
import com.storystylist.backend.ai.domain.*;
import java.util.List;
public record PhotoAnalysisResponse(String status, AiProvider provider, String model, int photoCount,
                                    String overallMood, List<PhotoObservation> photos, GroupAnalysis groupAnalysis,
                                    long latencyMillis, AiUsageMetadata usage, String promptVersion) {}
