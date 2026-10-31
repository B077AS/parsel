package com.parsel.account;

import java.util.Locale;

/**
 * Everything needed to talk to one mailbox, except the password (that lives in the credential store).
 *
 * @param name     the display name for the account
 * @param email    the address; also the key the password is filed under
 * @param username what the servers want as the login: often the address, sometimes only the part before the @
 */
public record AccountSettings(String name, String email, String username,
                              String imapHost, int imapPort, Security imapSecurity,
                              String smtpHost, int smtpPort, Security smtpSecurity) {

    /** @throws IllegalArgumentException with a message fit to show the user */
    public AccountSettings validate() {
        requireText(email, "The email address is required");
        if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") && !email.matches("[^@\\s]+@localhost")) {
            throw new IllegalArgumentException("That does not look like an email address");
        }
        requireText(username, "The username is required");
        requireText(imapHost, "The incoming (IMAP) server is required");
        requireText(smtpHost, "The outgoing (SMTP) server is required");
        requirePort(imapPort, "incoming");
        requirePort(smtpPort, "outgoing");
        requireSafe(imapHost, imapSecurity, "incoming");
        requireSafe(smtpHost, smtpSecurity, "outgoing");
        return this;
    }

    /** The name the user gave, or the part of the address before the @ (the page never shows the whole address as a name). */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requirePort(int port, String which) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("The " + which + " port must be between 1 and 65535");
        }
    }

    private static void requireSafe(String host, Security security, String which) {
        if (security == null) {
            throw new IllegalArgumentException("Choose the security of the " + which + " server");
        }
        if (security == Security.NONE && !isLoopback(host)) {
            throw new IllegalArgumentException("The " + which + " server must use SSL/TLS or STARTTLS: unencrypted mail is only accepted for localhost");
        }
    }

    static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1");
    }
}
