package com.parsel.account;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.parsel.credentials.CredentialBroker;
import com.parsel.mail.Mail;
import com.parsel.store.Database;
import com.parsel.store.MailStore;
import io.github.b077as.jolt.credentials.CredentialStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    @TempDir
    Path dir;

    static final class MemoryStore implements CredentialStore {
        final Map<String, String> map = new HashMap<>();

        public Optional<String> get(String account) { return Optional.ofNullable(map.get(account)); }
        public void put(String account, String secret) { map.put(account, secret); }
        public boolean delete(String account) { return map.remove(account) != null; }
    }

    MemoryStore secrets = new MemoryStore();
    MailStore store;
    AccountService accounts;
    AccountSettings settings;

    @BeforeEach
    void setUp() {
        greenMail.setUser("will@localhost", "will", "secret");
        store = new MailStore(new Database(dir.resolve("mail.db")));
        accounts = new AccountService(store, CredentialBroker.withStore(secrets), new AutoConfig(u -> Optional.empty(), h -> false));
        settings = new AccountSettings("Will", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), Security.NONE);
    }

    @Test
    void aWorkingLoginPassesBothChecksAndSavesNothing() {
        AccountService.TestResult result = accounts.test(settings, "secret");
        assertTrue(result.imapOk(), result.imapError());
        assertTrue(result.smtpOk(), result.smtpError());
        assertTrue(result.folders() >= 1);
        assertTrue(store.accounts().isEmpty());
        assertTrue(secrets.map.isEmpty());
    }

    @Test
    void aWrongPasswordIsExplainedInPlainLanguage() {
        AccountService.TestResult result = accounts.test(settings, "wrong");
        assertFalse(result.imapOk());
        assertEquals("The server refused the login. Check the username and password.", result.imapError());
    }

    @Test
    void anUnreachableServerSaysWhichOneAndOnWhichPort() {
        AccountSettings dead = new AccountSettings("x", "will@localhost", "will", "127.0.0.1", 1, Security.NONE, "127.0.0.1", 1, Security.NONE);
        AccountService.TestResult result = accounts.test(dead, "secret");
        assertFalse(result.imapOk());
        assertTrue(result.imapError().contains("127.0.0.1:1"), result.imapError());
        assertFalse(result.smtpOk());
    }

    @Test
    void aWorkingImapAndABrokenSmtpAreReportedSeparately() {
        AccountSettings noSmtp = new AccountSettings("Will", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", 1, Security.NONE);
        AccountService.TestResult result = accounts.test(noSmtp, "secret");
        assertTrue(result.imapOk());
        assertFalse(result.smtpOk());
        assertTrue(result.smtpError().contains("127.0.0.1:1"), result.smtpError());
    }

    @Test
    void addingSavesThePasswordUnderTheAddressAndTheAccountInTheDatabase() {
        Mail.Account account = accounts.add(settings, "secret");
        assertEquals("will@localhost", account.email());
        assertEquals(Optional.of("secret"), secrets.get("will@localhost"));
        assertEquals(1, store.accounts().size());
        assertEquals(Security.NONE, store.accountRow(Long.parseLong(account.id())).orElseThrow().settings().imapSecurity());
    }

    @Test
    void aWrongPasswordAddsNothing() {
        AccountException e = assertThrows(AccountException.class, () -> accounts.add(settings, "wrong"));
        assertTrue(e.getMessage().contains("refused the login"));
        assertTrue(store.accounts().isEmpty());
        assertTrue(secrets.map.isEmpty());
    }

    @Test
    void theSameAccountCannotBeAddedTwiceAndTheFirstPasswordSurvives() {
        accounts.add(settings, "secret");
        AccountException e = assertThrows(AccountException.class, () -> accounts.add(settings, "secret"));
        assertTrue(e.getMessage().contains("already been added"), e.getMessage());
        assertEquals(1, store.accounts().size());
    }

    @Test
    void unencryptedServersAreRefusedUnlessTheyAreLocalhost() {
        AccountSettings remote = new AccountSettings("x", "a@example.com", "a", "imap.example.com", 143, Security.NONE, "smtp.example.com", 25, Security.SSL);
        AccountException e = assertThrows(AccountException.class, () -> accounts.add(remote, "pw"));
        assertTrue(e.getMessage().contains("SSL/TLS or STARTTLS"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> accounts.test(remote, "pw"));
    }

    @Test
    void aLockedVaultStopsTheAddBeforeAnythingIsWritten() {
        AccountService locked = new AccountService(store, CredentialBroker.vaultOnly(dir.resolve("vault")), new AutoConfig(u -> Optional.empty(), h -> false));
        assertThrows(CredentialBroker.VaultLockedException.class, () -> locked.add(settings, "secret"));
        assertTrue(store.accounts().isEmpty());
    }

    @Test
    void removingDeletesTheAccountItsMailAndItsPassword() {
        Mail.Account account = accounts.add(settings, "secret");
        accounts.remove(Long.parseLong(account.id()));
        assertTrue(store.accounts().isEmpty());
        assertTrue(secrets.map.isEmpty());
    }
}
