package com.cocos.challenge.shared.domain;

import java.util.Objects;

/**
 * Base type for every expected business error.
 * <p>
 * Carries a stable machine-readable {@code code} (part of the API contract, e.g. {@code ORDER_NOT_CANCELLABLE})
 * in addition to a human-readable message. Subclasses express the error category, which the web layer maps
 * to an HTTP status.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getCode() {
        return code;
    }
}
