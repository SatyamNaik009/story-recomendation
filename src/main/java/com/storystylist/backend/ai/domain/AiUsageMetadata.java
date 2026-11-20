package com.storystylist.backend.ai.domain;
/** Null means the provider did not report this count. Counts are never estimated. */
public record AiUsageMetadata(Long inputTokens, Long outputTokens, Long totalTokens) {}
