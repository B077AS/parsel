package io.github.b077as.parsel.account;

/** How a mail connection is protected. Plain text is only ever allowed to this machine itself (see {@link AccountSettings#validate()}). */
public enum Security {
    /** TLS from the first byte (IMAPS on 993, SMTPS on 465). */
    SSL,
    /** Starts plain and upgrades with STARTTLS; the upgrade is required, never optional (IMAP 143, SMTP 587). */
    STARTTLS,
    /** No encryption. Only accepted for localhost, which is what the tests and a local dev server use. */
    NONE
}
