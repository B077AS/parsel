package com.parsel.account;

/** Something the user can act on, with a message written for them (not a stack trace): a wrong password, an unreachable server. */
public final class AccountException extends RuntimeException {

    public AccountException(String message) {
        super(message);
    }

    public AccountException(String message, Throwable cause) {
        super(message, cause);
    }
}
