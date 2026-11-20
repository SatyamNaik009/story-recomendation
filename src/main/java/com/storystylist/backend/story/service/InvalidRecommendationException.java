package com.storystylist.backend.story.service;
import com.storystylist.backend.ai.exception.*;
/** Rule identifiers are internal constants, never model-supplied values. */
final class InvalidRecommendationException extends AiException {
    private final String rule;
    InvalidRecommendationException(String rule) { super(AiError.AI_INVALID_RESPONSE); this.rule=rule; }
    String rule() { return rule; }
}
