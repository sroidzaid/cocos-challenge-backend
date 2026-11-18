package com.cocos.challenge.shared.domain;

/**
 * The request is well-formed but cannot be processed because it violates a business rule
 * (e.g. no market price available to execute a MARKET order).
 * <p>
 * Note: insufficient funds/holdings are <b>not</b> modelled with this exception; per the challenge
 * those orders are persisted with status {@code REJECTED}.
 */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String code, String message) {
        super(code, message);
    }
}
