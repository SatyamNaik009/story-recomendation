package com.storystylist.backend.ai.domain;
public record VisionAiResponse(VisionAnalysisResult analysis, String model, AiUsageMetadata usage) {}
