package io.github.b077as.parsel.imap;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.imap.ImapClient.RemoteFlags;
import io.github.b077as.parsel.store.MailStore.HeaderRow;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Talks to a real IMAP server (GreenMail, in memory) over a real socket. */
class ImapClientTest {

    @RegisterExtension
    GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP_IMAP);

    GreenMailUser user;
    AccountSettings settings;

    @BeforeEach
    void setUp() {
        user = greenMail.setUser("will@localhost", "will", "secret");
        settings = new AccountSettings("Will", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), Security.NONE);
    }

    private MimeMessage text(String from, String subject, String body) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress(from));
        m.setRecipients(MimeMessage.RecipientType.TO, "will@localhost");
        m.setSubject(subject);
        m.setText(body, "utf-8");
        m.saveChanges();
        return m;
    }

    @Test
    void foldersGetTheirRolesFromFlagsOrNames() throws Exception {
        var imap = greenMail.getManagers().getImapHostManager();
        imap.createMailbox(user, "Sent");
        imap.createMailbox(user, "Receipts");
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            Map<String, String> roles = new java.util.HashMap<>();
            for (var folder : client.folders()) {
                roles.put(folder.path(), String.valueOf(folder.role()));
            }
            assertEquals("inbox", roles.get("INBOX"));
            assertEquals("sent", roles.get("Sent"));
            assertEquals("null", roles.get("Receipts"));
        }
    }

    @Test
    void headersCarryTheSenderSubjectDateAndFlags() throws Exception {
        user.deliver(text("Elena Marchetti <elena@marchetti.example>", "Saturday dinner?", "I booked the place."));
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            List<HeaderRow> rows = client.fetchNewest("INBOX", 10);
            assertEquals(1, rows.size());
            HeaderRow row = rows.get(0);
            assertEquals("Elena Marchetti", row.fromName());
            assertEquals("elena@marchetti.example", row.fromAddress());
            assertEquals("Saturday dinner?", row.subject());
            assertEquals("will@localhost", row.to());
            assertTrue(row.unread());
            assertFalse(row.starred());
            assertFalse(row.hasAttachments());
            assertTrue(Math.abs(row.dateMillis() - System.currentTimeMillis()) < 60_000);
        }
    }

    @Test
    void aSenderWithoutADisplayNameShowsTheAddress() throws Exception {
        user.deliver(text("noreply@pinestreet.example", "Your hold", "ready"));
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            assertEquals("noreply@pinestreet.example", client.fetchNewest("INBOX", 5).get(0).fromName());
        }
    }

    @Test
    void fetchNewestLimitsTheCountAndFetchSinceOnlyReturnsWhatIsNew() throws Exception {
        for (int i = 1; i <= 5; i++) {
            user.deliver(text("a@example.com", "message " + i, "body " + i));
        }
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            List<HeaderRow> newest = client.fetchNewest("INBOX", 3);
            assertEquals(List.of("message 3", "message 4", "message 5"), newest.stream().map(HeaderRow::subject).toList());

            long lastUid = newest.get(2).uid();
            assertEquals(List.of(), client.fetchSince("INBOX", lastUid, 100), "nothing new: the server repeats the last message, which must be filtered out");

            user.deliver(text("a@example.com", "message 6", "body 6"));
            user.deliver(text("a@example.com", "message 7", "body 7"));
            assertEquals(List.of("message 6", "message 7"), client.fetchSince("INBOX", lastUid, 100).stream().map(HeaderRow::subject).toList());
            assertEquals(List.of("message 7"), client.fetchSince("INBOX", lastUid, 1).stream().map(HeaderRow::subject).toList(), "max keeps the newest");
        }
    }

    @Test
    void anAttachmentIsRecognisedFromTheHeadersAlone() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setText("see attached", "utf-8");
        mixed.addBodyPart(body);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[2000], "application/pdf")));
        file.setFileName("invoice.pdf");
        file.setDisposition("attachment");
        mixed.addBodyPart(file);
        MimeMessage m = text("a@example.com", "with attachment", "x");
        m.setContent(mixed);
        m.saveChanges();
        user.deliver(m);
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            assertTrue(client.fetchNewest("INBOX", 5).get(0).hasAttachments());
            long uid = client.fetchNewest("INBOX", 5).get(0).uid();
            BodyParser.Parsed parsed = client.fetchBody("INBOX", uid);
            assertEquals("invoice.pdf", parsed.attachments().get(0).name());
        }
    }

    @Test
    void flagsAreReadAndSeenCanBeSetAndDeletionsShowAsMissing() throws Exception {
        user.deliver(text("a@example.com", "one", "1"));
        user.deliver(text("a@example.com", "two", "2"));
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            List<HeaderRow> rows = client.fetchNewest("INBOX", 10);
            long first = rows.get(0).uid();
            long second = rows.get(1).uid();
            assertTrue(client.fetchFlags("INBOX", new long[]{first, second}).get(first).unread());

            client.setSeen("INBOX", first, true);
            Map<Long, RemoteFlags> flags = client.fetchFlags("INBOX", new long[]{first, second});
            assertFalse(flags.get(first).unread());
            assertTrue(flags.get(second).unread());

            // a UID that was never there (or was deleted) is simply absent
            assertFalse(client.fetchFlags("INBOX", new long[]{first, 9999}).containsKey(9999L));
            assertEquals(Map.of(), client.fetchFlags("INBOX", new long[0]));
        }
    }

    @Test
    void readingABodyDoesNotMarkTheMessageRead() throws Exception {
        user.deliver(text("a@example.com", "unread please", "body"));
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            long uid = client.fetchNewest("INBOX", 5).get(0).uid();
            client.fetchBody("INBOX", uid);
            assertTrue(client.fetchFlags("INBOX", new long[]{uid}).get(uid).unread());
        }
    }

    @Test
    void aBodyIsParsedAndSanitisedFromTheRealServer() throws Exception {
        MimeMessage m = text("a@example.com", "html mail", "x");
        m.setContent("<p>Hello <b>there</b></p><script>alert(1)</script><img src=\"https://tracker.example/p.gif\">", "text/html; charset=utf-8");
        m.saveChanges();
        user.deliver(m);
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            long uid = client.fetchNewest("INBOX", 5).get(0).uid();
            BodyParser.Parsed parsed = client.fetchBody("INBOX", uid);
            assertTrue(parsed.html().contains("<b>there</b>"));
            assertFalse(parsed.html().contains("script"));
            assertTrue(parsed.hasRemoteImages());
            assertEquals("Hello there", parsed.snippet());
        }
    }

    @Test
    void uidValidityIsStableForAFolder() throws Exception {
        user.deliver(text("a@example.com", "x", "x"));
        try (ImapClient client = ImapClient.connect(settings, "secret")) {
            long first = client.uidValidity("INBOX");
            assertEquals(first, client.uidValidity("INBOX"));
            assertNotEquals(0, first);
        }
    }

    @Test
    void aWrongPasswordIsAnAuthenticationFailure() {
        assertThrows(AuthenticationFailedException.class, () -> ImapClient.connect(settings, "wrong").close());
    }

    @Test
    void anUnreachableServerFailsWithAMessageNotAHang() {
        AccountSettings nobody = new AccountSettings("x", "x@localhost", "x", "127.0.0.1", 1, Security.NONE, "127.0.0.1", 1, Security.NONE);
        assertThrows(jakarta.mail.MessagingException.class, () -> ImapClient.connect(nobody, "pw").close());
    }
}
