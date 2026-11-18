package com.cocos.challenge.shared.domain;

/**
 * The request conflicts with the current state of a resource
 * (e.g. cancelling an order that is no longer {@code NEW}).
 */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }
}
