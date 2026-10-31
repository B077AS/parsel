package com.parsel.send;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.parsel.account.AccountException;
import com.parsel.account.AccountSettings;
import com.parsel.account.Security;
import com.parsel.credentials.CredentialBroker;
import com.parsel.imap.ImapClient;
import com.parsel.mail.Mail;
import com.parsel.store.Database;
import com.parsel.store.MailStore;
import com.parsel.sync.SyncService;
import io.github.b077as.jolt.credentials.CredentialStore;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SendServiceTest {

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

    GreenMailUser will;
    MailStore store;
    SyncService sync;
    SendService send;
    long accountId;
    final List<Long> sentFrom = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        will = greenMail.setUser("will@localhost", "will", "secret");
        greenMail.setUser("elena@example.com", "elena", "secret");
        greenMail.setUser("tomas@example.com", "tomas", "secret");
        greenMail.getManagers().getImapHostManager().createMailbox(will, "Sent");

        store = new MailStore(new Database(dir.resolve("mail.db")));
        MemoryStore secrets = new MemoryStore();
        secrets.put("will@localhost", "secret");
        CredentialBroker credentials = CredentialBroker.withStore(secrets);
        sync = new SyncService(store, credentials, status -> {}, id -> {});
        send = new SendService(store, credentials, ImapClient::connect, sentFrom::add);
        accountId = store.addAccount(new AccountSettings("Will Solari", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), Security.NONE));
    }

    @AfterEach
    void tearDown() {
        sync.close();
    }

    private SendService.Draft draft(List<String> to, List<String> cc, List<String> bcc, String subject, String body, String replyTo) {
        return new SendService.Draft(String.valueOf(accountId), to, cc, bcc, subject, body, replyTo);
    }

    private SendService.Result sendDraft(SendService.Draft d) {
        return send.send(d);
    }

    /** An incoming mail from Elena to Will (and Tomás on Cc), synced and opened so its body is cached. Returns its row id. */
    private String incoming() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("Elena <elena@example.com>"));
        m.setRecipients(Message.RecipientType.TO, "will@localhost, tomas@example.com");
        m.setRecipients(Message.RecipientType.CC, "Priya <priya@example.com>");
        m.setSubject("Saturday dinner?");
        m.setContent("<p>I booked the place near the station.</p><p>Can you let me know?</p>", "text/html; charset=utf-8");
        m.saveChanges();
        will.deliver(m);
        sync.sync(accountId);
        String id = store.list(String.valueOf(accountId), "inbox").get(0).id();
        sync.loadBody(Long.parseLong(id));
        return id;
    }

    @Test
    void aNewMessageIsDeliveredAndBccStaysOutOfTheHeaders() throws Exception {
        sync.sync(accountId); // learn the Sent folder
        SendService.Result result = sendDraft(draft(List.of("elena@example.com"), List.of(), List.of("tomas@example.com"),
                "Hello", "Héllo Elena, ünïcode ✓", null));

        assertNull(result.warning());
        greenMail.waitForIncomingEmail(3);
        // Everything GreenMail holds: the To recipient, the Bcc recipient and the copy filed in Sent over IMAP.
        List<MimeMessage> all = List.of(greenMail.getReceivedMessages());
        assertEquals(3, all.size());
        List<MimeMessage> delivered = new ArrayList<>();
        for (MimeMessage m : all) {
            if (m.getHeader("Bcc") == null) {
                delivered.add(m);
            }
        }
        assertEquals(2, delivered.size(), "only the copy in Sent keeps the Bcc line; the two delivered messages do not show it");
        MimeMessage first = delivered.get(0);
        assertEquals("Hello", first.getSubject());
        assertEquals("Héllo Elena, ünïcode ✓", ((String) first.getContent()).strip());
        assertEquals("Will Solari <will@localhost>", first.getFrom()[0].toString());
        assertTrue(first.getMessageID().endsWith("@localhost>"), "the id uses the sender's domain: " + first.getMessageID());
        assertEquals(List.of(accountId), sentFrom);
    }

    @Test
    void aCopyIsFiledInSentWithItsBcc() throws Exception {
        sync.sync(accountId);
        sendDraft(draft(List.of("elena@example.com"), List.of(), List.of("tomas@example.com"), "Copy me", "body", null));

        sync.sync(accountId);
        List<Mail.Summary> sent = store.list(String.valueOf(accountId), "sent");
        assertEquals(1, sent.size());
        assertEquals("Copy me", sent.get(0).subject());
        assertFalse(sent.get(0).unread(), "your own sent mail is not unread");
    }

    @Test
    void aRichMessageGoesOutAsPlainTextAndCleanedHtml() throws Exception {
        sync.sync(accountId);
        SendService.Draft rich = new SendService.Draft(String.valueOf(accountId), List.of("elena@example.com"), List.of(), List.of(), "Menu", "", null,
                List.of(), "<p>Hi <strong>Elena</strong></p><ul><li><p>soup</p></li><li><p>bread</p></li></ul><script>alert(1)</script>"
                + "<p><a href=\"https://example.com/menu\">menu</a></p>");
        assertNull(sendDraft(rich).warning());

        greenMail.waitForIncomingEmail(2);
        MimeMessage got = greenMail.getReceivedMessages()[0];
        assertTrue(got.isMimeType("multipart/alternative"), got.getContentType());
        jakarta.mail.Multipart parts = (jakarta.mail.Multipart) got.getContent();
        assertEquals(2, parts.getCount());
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"), "plain text first");
        assertEquals("Hi Elena\n\n- soup\n- bread\n\nmenu (https://example.com/menu)",
                ((String) parts.getBodyPart(0).getContent()).strip().replace("\r\n", "\n"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/html"), "html last");
        String html = (String) parts.getBodyPart(1).getContent();
        assertTrue(html.contains("<strong>Elena</strong>") && html.contains("<li>"), html);
        assertFalse(html.contains("script") || html.contains("alert"), html);
    }

    @Test
    void aReplyStartsAsHtmlWithAnEmptyLineAndTheOriginalQuoted() throws Exception {
        String id = incoming();
        SendService.Prefill reply = send.prefill(id, SendService.Mode.REPLY, false);
        assertTrue(reply.html().startsWith("<p></p><p>On "), reply.html());
        assertTrue(reply.html().contains("<blockquote><p>I booked the place near the station.</p><p>Can you let me know?</p></blockquote>"), reply.html());
        assertTrue(reply.html().contains("elena@example.com"), reply.html());

        SendService.Prefill forward = send.prefill(id, SendService.Mode.FORWARD, false);
        assertTrue(forward.html().contains("Forwarded message") && forward.html().contains("Subject: Saturday dinner?"), forward.html());
        assertFalse(forward.html().contains("<blockquote>"), "a forward is not quoted: " + forward.html());
    }

    @Test
    void withoutASentFolderTheMailGoesOutAndTheUserIsTold() throws Exception {
        SendService.Result result = sendDraft(draft(List.of("elena@example.com"), List.of(), List.of(), "No folder yet", "body", null));

        assertNotNull(result.warning());
        assertTrue(result.warning().contains("no Sent folder"));
        greenMail.waitForIncomingEmail(1);
    }

    @Test
    void aReplyAnswersTheSenderQuotesTheOriginalAndKeepsTheThread() throws Exception {
        String id = incoming();
        SendService.Prefill reply = send.prefill(id, SendService.Mode.REPLY);

        assertEquals(List.of("elena@example.com"), reply.to());
        assertEquals(List.of(), reply.cc());
        assertEquals("Re: Saturday dinner?", reply.subject());
        assertTrue(reply.body().contains("Elena <elena@example.com> wrote:"), reply.body());
        assertTrue(reply.body().contains("> I booked the place near the station."), reply.body());
        assertEquals(id, reply.replyTo());

        sendDraft(draft(reply.to(), reply.cc(), List.of(), reply.subject(), "Yes, 8pm." + reply.body(), reply.replyTo()));
        greenMail.waitForIncomingEmail(2); // the original and the reply
        MimeMessage sent = List.of(greenMail.getReceivedMessages()).stream()
                .filter(m -> { try { return m.getSubject().startsWith("Re:"); } catch (Exception e) { return false; } })
                .findFirst().orElseThrow();
        MimeMessage original = List.of(greenMail.getReceivedMessages()).stream()
                .filter(m -> { try { return !m.getSubject().startsWith("Re:"); } catch (Exception e) { return false; } })
                .findFirst().orElseThrow();
        assertEquals(original.getMessageID(), sent.getHeader("In-Reply-To", null));
        assertTrue(sent.getHeader("References", null).contains(original.getMessageID()));
    }

    @Test
    void replyAllAddsTheOtherRecipientsAndLeavesOutTheUser() throws Exception {
        SendService.Prefill reply = send.prefill(incoming(), SendService.Mode.REPLY_ALL);

        assertEquals(List.of("elena@example.com", "tomas@example.com"), reply.to());
        assertEquals(List.of("priya@example.com"), reply.cc());
    }

    @Test
    void aForwardHasNoRecipientsAndStartsAFreshThread() throws Exception {
        SendService.Prefill forward = send.prefill(incoming(), SendService.Mode.FORWARD);

        assertEquals(List.of(), forward.to());
        assertEquals("Fwd: Saturday dinner?", forward.subject());
        assertNull(forward.replyTo());
        assertTrue(forward.body().contains("Forwarded message"));
        assertTrue(forward.body().contains("I booked the place near the station."));
    }

    @Test
    void aSubjectThatAlreadyHasThePrefixIsNotPrefixedTwice() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("elena@example.com"));
        m.setRecipients(Message.RecipientType.TO, "will@localhost");
        m.setSubject("RE: Saturday dinner?");
        m.setText("hi");
        m.saveChanges();
        will.deliver(m);
        sync.sync(accountId);
        String id = store.list(String.valueOf(accountId), "inbox").get(0).id();
        sync.loadBody(Long.parseLong(id));

        assertEquals("RE: Saturday dinner?", send.prefill(id, SendService.Mode.REPLY).subject());
    }

    @Test
    void badInputIsRefusedBeforeAnythingIsSent() {
        var noOne = assertThrows(IllegalArgumentException.class,
                () -> sendDraft(draft(List.of(), List.of(), List.of(), "x", "y", null)));
        assertEquals("Add at least one recipient", noOne.getMessage());

        var bad = assertThrows(IllegalArgumentException.class,
                () -> sendDraft(draft(List.of("elena@example.com", "not an address"), List.of(), List.of(), "x", "y", null)));
        assertTrue(bad.getMessage().contains("not an address"));

        assertEquals(List.of(), List.of(greenMail.getReceivedMessages()));
    }

    @Test
    void aLineBreakInTheSubjectCannotAddHeaders() throws Exception {
        sendDraft(draft(List.of("elena@example.com"), List.of(), List.of(), "Hi\r\nBcc: attacker@example.com", "body", null));

        greenMail.waitForIncomingEmail(1);
        MimeMessage got = greenMail.getReceivedMessages()[0];
        assertEquals("Hi Bcc: attacker@example.com", got.getSubject());
        assertNull(got.getHeader("Bcc"));
        assertEquals(1, greenMail.getReceivedMessages().length);
    }

    @Test
    void aRefusedLoginIsReportedAsAnAccountProblem() {
        MemoryStore wrong = new MemoryStore();
        wrong.put("will@localhost", "not-the-password");
        SendService other = new SendService(store, CredentialBroker.withStore(wrong), ImapClient::connect, id -> {});

        var error = assertThrows(AccountException.class,
                () -> other.send(new SendService.Draft(String.valueOf(accountId), List.of("elena@example.com"), List.of(), List.of(), "x", "y", null)));
        assertFalse(error.getMessage().isBlank());
        assertEquals(0, greenMail.getReceivedMessages().length);
    }
}
