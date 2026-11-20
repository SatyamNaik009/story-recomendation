package com.storystylist.backend.render.validation;
/** Safe internal rule identifier; no image data or provider details retained. No public rendering API yet. */
public final class InvalidRenderSpecificationException extends IllegalArgumentException {
    public InvalidRenderSpecificationException(String rule) { super(rule); }
}
