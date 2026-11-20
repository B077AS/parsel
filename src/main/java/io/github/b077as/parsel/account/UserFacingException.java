package io.github.b077as.parsel.account;

/**
 * A failure whose message was written for the user: a wrong password, an unreachable server, a folder name that is taken. It is the
 * only kind of exception whose text reaches the page (as the RPC error {@code problem}, see {@code ParselRpc}); anything else the
 * page is told about only as a generic error, because its message may name files, SQL or classes.
 */
public final class UserFacingException extends RuntimeException {

    public UserFacingException(String message) {
        super(message);
    }

    public UserFacingException(String message, Throwable cause) {
        super(message, cause);
    }
}
