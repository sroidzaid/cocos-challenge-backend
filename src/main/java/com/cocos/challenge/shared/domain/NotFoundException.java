package com.cocos.challenge.shared.domain;

/** A referenced resource (user, instrument, order...) does not exist or is not visible to the caller. */
public class NotFoundException extends DomainException {

    public NotFoundException(String code, String message) {
        super(code, message);
    }
}
