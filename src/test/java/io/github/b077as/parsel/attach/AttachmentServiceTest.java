package io.github.b077as.parsel.attach;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import io.github.b077as.parsel.account.AccountException;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.imap.ImapClient;
import io.github.b077as.parsel.send.SendService;
import io.github.b077as.parsel.store.Database;
import io.github.b077as.parsel.store.MailStore;
import io.github.b077as.parsel.sync.SyncService;
import io.github.b077as.jolt.credentials.CredentialStore;
import jakarta.activation.DataHandler;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentServiceTest {

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

    /** Stands in for the system dialogs: answers with what the test set up. */
    static final class StubChooser implements AttachmentService.FileChooser {
        Optional<Path> saveTo = Optional.empty();
        List<Path> openThese = List.of();
        String proposed;

        public Optional<Path> save(String suggestedName) {
            proposed = suggestedName;
            return saveTo;
        }

        public List<Path> open() { return openThese; }
    }

    GreenMailUser will;
    MailStore store;
    SyncService sync;
    StubChooser chooser;
    AttachmentService attachments;
    SendService send;
    long accountId;

    @BeforeEach
    void setUp() throws Exception {
        will = greenMail.setUser("will@localhost", "will", "secret");
        greenMail.setUser("elena@example.com", "elena", "secret");
        store = new MailStore(new Database(dir.resolve("mail.db")));
        MemoryStore secrets = new MemoryStore();
        secrets.put("will@localhost", "secret");
        CredentialBroker credentials = CredentialBroker.withStore(secrets);
        sync = new SyncService(store, credentials, status -> {}, id -> {});
        chooser = new StubChooser();
        attachments = new AttachmentService(store, credentials, ImapClient::connect, chooser);
        send = new SendService(store, credentials, ImapClient::connect, id -> {}, attachments);
        accountId = store.addAccount(new AccountSettings("Will Solari", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), Security.NONE));
    }

    @AfterEach
    void tearDown() {
        sync.close();
    }

    private static MimeBodyPart file(String name, String type, byte[] data) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, type)));
        part.setFileName(name);
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    /** A mail from Elena with a text body and two attachments, synced and opened. Returns its row id. */
    private String incomingWithTwoFiles(byte[] second) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("elena@example.com"));
        m.setRecipients(Message.RecipientType.TO, "will@localhost");
        m.setSubject("Files");
        MimeMultipart parts = new MimeMultipart("mixed");
        MimeBodyPart text = new MimeBodyPart();
        text.setText("Here you go");
        parts.addBodyPart(text);
        parts.addBodyPart(file("first.txt", "text/plain", "one".getBytes(StandardCharsets.UTF_8)));
        parts.addBodyPart(file("second.bin", "application/octet-stream", second));
        m.setContent(parts);
        m.saveChanges();
        will.deliver(m);
        sync.sync(accountId);
        String id = store.list(String.valueOf(accountId), "inbox").get(0).id();
        sync.loadBody(Long.parseLong(id));
        return id;
    }

    @Test
    void anAttachmentIsDownloadedToTheChosenFile() throws Exception {
        byte[] bytes = new byte[300_000];
        new java.util.Random(7).nextBytes(bytes);
        String id = incomingWithTwoFiles(bytes);
        Path target = dir.resolve("saved.bin");
        chooser.saveTo = Optional.of(target);

        AttachmentService.Saved saved = attachments.download(id, 1); // the second one: the body text does not count

        assertTrue(saved.saved());
        assertEquals("saved.bin", saved.name());
        assertEquals("second.bin", chooser.proposed);
        assertArrayEquals(bytes, Files.readAllBytes(target));
        assertFalse(Files.exists(dir.resolve("saved.bin.part")), "the temporary file is gone");
    }

    @Test
    void cancellingTheDialogDownloadsNothing() throws Exception {
        String id = incomingWithTwoFiles(new byte[10]);
        chooser.saveTo = Optional.empty();

        assertFalse(attachments.download(id, 0).saved());
    }

    @Test
    void anAttachmentThatIsNotThereIsRefused() throws Exception {
        String id = incomingWithTwoFiles(new byte[10]);

        assertThrows(IllegalArgumentException.class, () -> attachments.download(id, 2));
        assertThrows(IllegalArgumentException.class, () -> attachments.download(id, -1));
    }

    @Test
    void aProposedNameCannotLeaveTheFolderOrUseCharactersWindowsRefuses() {
        assertEquals(".._.._evil.exe", AttachmentService.safeName("../../evil.exe"));
        assertEquals("a_b_c", AttachmentService.safeName("a:b*c"));
        assertEquals("attachment", AttachmentService.safeName("..."));
        assertEquals("Résumé.pdf", AttachmentService.safeName("Résumé.pdf"));
    }

    @Test
    void filesPickedAreSentAsAttachmentsWithTheirNamesAndBytes() throws Exception {
        byte[] bytes = new byte[50_000];
        new java.util.Random(3).nextBytes(bytes);
        Path report = dir.resolve("Résumé 2026.pdf");
        Files.write(report, bytes);
        Path note = dir.resolve("note.txt");
        Files.writeString(note, "hello");
        chooser.openThese = List.of(report, note);

        List<AttachmentService.Picked> picked = attachments.pick();
        assertEquals(List.of("Résumé 2026.pdf", "note.txt"), picked.stream().map(AttachmentService.Picked::name).toList());
        assertEquals(50_000, picked.get(0).bytes());

        send.send(new SendService.Draft(String.valueOf(accountId), List.of("elena@example.com"), List.of(), List.of(),
                "With files", "See attached", null, picked.stream().map(AttachmentService.Picked::id).toList()));

        greenMail.waitForIncomingEmail(1);
        MimeMessage delivered = greenMail.getReceivedMessages()[0];
        Multipart parts = (Multipart) delivered.getContent();
        assertEquals(3, parts.getCount());
        assertEquals("See attached", ((String) parts.getBodyPart(0).getContent()).strip());
        assertEquals("Résumé 2026.pdf", parts.getBodyPart(1).getFileName());
        assertEquals("application/pdf", new jakarta.mail.internet.ContentType(parts.getBodyPart(1).getContentType()).getBaseType());
        try (var in = parts.getBodyPart(1).getInputStream()) {
            assertArrayEquals(bytes, in.readAllBytes());
        }
        assertEquals("note.txt", parts.getBodyPart(2).getFileName());
    }

    @Test
    void aMessageWithoutFilesStaysPlainText() throws Exception {
        send.send(new SendService.Draft(String.valueOf(accountId), List.of("elena@example.com"), List.of(), List.of(), "Plain", "just text", null));

        greenMail.waitForIncomingEmail(1);
        assertEquals("just text", ((String) greenMail.getReceivedMessages()[0].getContent()).strip());
    }

    @Test
    void aFileTooBigForMailIsRefusedWhenPicked() throws Exception {
        Path big = dir.resolve("big.iso");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(AttachmentService.MAX_TOTAL_BYTES + 1);
        }
        chooser.openThese = List.of(big);

        AccountException e = assertThrows(AccountException.class, () -> attachments.pick());
        assertTrue(e.getMessage().contains("big.iso"), e.getMessage());
    }

    @Test
    void anUnknownOrDeletedFileIsReportedNotSentAsNothing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> attachments.load(List.of("not-an-id")));

        Path gone = dir.resolve("gone.txt");
        Files.writeString(gone, "x");
        chooser.openThese = List.of(gone);
        String id = attachments.pick().get(0).id();
        Files.delete(gone);

        assertThrows(AccountException.class, () -> attachments.load(List.of(id)));
    }
}
