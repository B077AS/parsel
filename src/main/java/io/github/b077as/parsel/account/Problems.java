package io.github.b077as.parsel.account;

import jakarta.mail.AuthenticationFailedException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Turns the exceptions a mail library throws into one sentence the user can act on. */
public final class Problems {

    private Problems() {}

    /** @param host the server that was being contacted, for the message; @param port likewise */
    public static String explain(Throwable error, String host, int port) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof AuthenticationFailedException) {
                return "The server refused the login. Check the username and password.";
            }
            if (t instanceof UnknownHostException) {
                return "Could not find the server " + host + ". Check the name for typos and that you are online.";
            }
            if (t instanceof SSLException) {
                return "The secure connection to " + host + " failed (" + shorten(t.getMessage()) + "). The server's certificate may not be trusted, or the security setting does not match the port.";
            }
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return "Could not connect to " + host + ":" + port + ". Check the port and that the server is reachable.";
            }
            if (t instanceof SocketTimeoutException) {
                return "The server " + host + ":" + port + " did not answer in time.";
            }
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : shorten(message);
    }

    private static String shorten(String message) {
        if (message == null) {
            return "no details";
        }
        String oneLine = message.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
