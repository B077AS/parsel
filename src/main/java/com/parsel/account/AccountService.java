package com.parsel.account;

import com.parsel.credentials.CredentialBroker;
import com.parsel.imap.ImapClient;
import com.parsel.mail.Mail;
import com.parsel.send.MailSender;
import com.parsel.store.MailStore;
import jakarta.mail.MessagingException;
import jakarta.mail.Transport;

import java.util.Locale;
import java.util.Optional;

/** Adding, checking and removing mail accounts. The password goes to the credential store and nowhere else. */
public final class AccountService {

    /** The outcome of trying to log in. {@code error} fields are sentences for the user, null when that part worked. */
    public record TestResult(boolean imapOk, String imapError, boolean smtpOk, String smtpError, int folders) {}

    private final MailStore store;
    private final CredentialBroker credentials;
    private final AutoConfig autoConfig;

    public AccountService(MailStore store, CredentialBroker credentials, AutoConfig autoConfig) {
        this.store = store;
        this.credentials = credentials;
        this.autoConfig = autoConfig;
    }

    public AutoConfig.Detected detect(String email) {
        return autoConfig.detect(email);
    }

    /** Logs in to the incoming and outgoing servers without saving anything. */
    public TestResult test(AccountSettings settings, String password) {
        settings.validate();
        boolean imapOk = false;
        String imapError = null;
        int folders = 0;
        try (ImapClient client = ImapClient.connect(settings, password)) {
            imapOk = true;
            folders = client.folders().size();
        } catch (Exception e) {
            imapError = Problems.explain(e, settings.imapHost(), settings.imapPort());
        }
        boolean smtpOk = false;
        String smtpError = null;
        try {
            checkSmtp(settings, password);
            smtpOk = true;
        } catch (Exception e) {
            smtpError = Problems.explain(e, settings.smtpHost(), settings.smtpPort());
        }
        return new TestResult(imapOk, imapError, smtpOk, smtpError, folders);
    }

    /**
     * Checks the login, then saves the password and the account. Needs a working incoming server; a failing outgoing
     * server does not stop it (reading mail still works), the caller sees that from {@link #test}.
     *
     * @throws AccountException                         if the login failed, the account exists, or the settings are not valid
     * @throws CredentialBroker.VaultLockedException    if the password vault has to be unlocked first
     */
    public Mail.Account add(AccountSettings settings, String password) {
        try {
            settings.validate();
        } catch (IllegalArgumentException e) {
            throw new AccountException(e.getMessage(), e);
        }
        if (password == null || password.isEmpty()) {
            throw new AccountException("The password is required");
        }
        try {
            ImapClient.connect(settings, password).close(); // logged in: the settings and the password are right
        } catch (Exception e) {
            throw new AccountException(Problems.explain(e, settings.imapHost(), settings.imapPort()), e);
        }
        String key = key(settings.email());
        credentials.store(); // fails early with VaultLockedException, before anything is written
        long id;
        try {
            credentials.put(key, password);
            id = store.addAccount(settings);
        } catch (IllegalArgumentException e) {
            throw new AccountException(e.getMessage(), e);
        } catch (RuntimeException e) {
            safelyDelete(key);
            throw e;
        }
        return new Mail.Account(String.valueOf(id), settings.displayName(), settings.email());
    }

    /** Removes the account, its cached mail and its saved password. */
    public void remove(long accountId) {
        Optional<String> email = store.removeAccount(accountId);
        email.ifPresent(this::safelyDelete);
    }

    /** The key a password is filed under: the address, lower case, so "Will@Example.com" and "will@example.com" are one account. */
    public static String key(String email) {
        return email.toLowerCase(Locale.ROOT);
    }

    private void safelyDelete(String key) {
        try {
            credentials.delete(key);
        } catch (RuntimeException e) {
            // A locked vault cannot delete; the leftover entry is harmless and is replaced if the account is added again.
        }
    }

    private static void checkSmtp(AccountSettings s, String password) throws MessagingException {
        try (Transport ignored = MailSender.connect(s, password)) {
            // logged in: the server, the security setting and the password are right
        }
    }
}
