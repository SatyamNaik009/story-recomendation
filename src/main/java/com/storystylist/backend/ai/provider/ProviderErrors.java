package com.storystylist.backend.ai.provider;
import com.storystylist.backend.ai.exception.*;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
final class ProviderErrors {
    private ProviderErrors() {}
    static AiException status(int status) {
        return new AiException(switch (status) {
            case 400, 401, 403, 404, 422 -> AiError.AI_CONFIGURATION_ERROR;
            case 408, 504 -> AiError.AI_PROVIDER_TIMEOUT;
            case 429 -> AiError.AI_RATE_LIMITED;
            default -> AiError.AI_PROVIDER_UNAVAILABLE;
        });
    }
    static AiException transport(Throwable failure) {
        for (int depth = 0; failure != null && depth < 12; depth++, failure = failure.getCause()) {
            if (failure instanceof InterruptedIOException || failure instanceof HttpTimeoutException
                    || failure instanceof TimeoutException) return new AiException(AiError.AI_PROVIDER_TIMEOUT);
        }
        return new AiException(AiError.AI_PROVIDER_UNAVAILABLE);
    }
}
