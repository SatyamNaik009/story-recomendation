package com.storystylist.backend.ai.domain;
public record StructuredAiResponse(String json, String model, AiUsageMetadata usage) {}
