package com.parsel.sync;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.parsel.account.AccountSettings;
import com.parsel.account.Security;
import com.parsel.credentials.CredentialBroker;
import com.parsel.mail.Mail;
import com.parsel.store.Database;
import com.parsel.store.MailStore;
import io.github.b077as.jolt.credentials.CredentialStore;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
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
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncServiceTest {

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

    GreenMailUser user;
    MemoryStore secrets = new MemoryStore();
    MailStore store;
    SyncService sync;
    long accountId;
    final List<SyncService.Status> statuses = Collections.synchronizedList(new ArrayList<>());
    final List<String> changes = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        user = greenMail.setUser("will@localhost", "will", "secret");
        store = new MailStore(new Database(dir.resolve("mail.db")));
        secrets.put("will@localhost", "secret");
        sync = new SyncService(store, CredentialBroker.withStore(secrets), statuses::add, changes::add);
        accountId = store.addAccount(new AccountSettings("Will", "will@localhost", "will", "127.0.0.1", ServerSetupTest.IMAP.getPort(), Security.NONE,
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), Security.NONE));
    }

    @AfterEach
    void tearDown() {
        sync.close();
    }

    private MimeMessage mail(String subject, String body) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom(new InternetAddress("Elena <elena@example.com>"));
        m.setRecipients(Message.RecipientType.TO, "will@localhost");
        m.setSubject(subject);
        m.setText(body, "utf-8");
        m.saveChanges();
        return m;
    }

    private List<String> inboxSubjects() {
        return store.list(String.valueOf(accountId), "inbox").stream().map(Mail.Summary::subject).toList();
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 60 && !condition.getAsBoolean(); i++) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true within 6 s");
    }

    /** Runs {@code action} on the INBOX as a second mail client would. */
    private void asAnotherClient(java.util.function.Consumer<Folder> action) throws Exception {
        Properties props = new Properties();
        Store imap = Session.getInstance(props).getStore("imap");
        imap.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), "will", "secret");
        try {
            Folder inbox = imap.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            action.accept(inbox);
            inbox.close(true);
        } finally {
            imap.close();
        }
    }

    @Test
    void theFirstSyncFillsFoldersHeadersAndPreviews() throws Exception {
        user.deliver(mail("Saturday dinner?", "I booked the place near the station."));
        user.deliver(mail("Invoice", "Your invoice is attached."));
        sync.sync(accountId);

        assertEquals(List.of("Invoice", "Saturday dinner?"), inboxSubjects().stream().sorted().toList());
        List<Mail.Summary> rows = store.list(String.valueOf(accountId), "inbox");
        assertTrue(rows.stream().allMatch(Mail.Summary::unread));
        assertTrue(rows.stream().anyMatch(r -> r.snippet().equals("I booked the place near the station.")), "bodies of the newest messages are prefetched for the preview");
        assertEquals(SyncService.State.IDLE, statuses.get(statuses.size() - 1).state());
        assertEquals(SyncService.State.SYNCING, statuses.get(0).state());
        assertFalse(changes.isEmpty());
        assertTrue(store.accountRow(accountId).orElseThrow().lastSync() != null);
        assertEquals(2, store.folders(String.valueOf(accountId)).get(0).unread());
    }

    @Test
    void aLaterSyncAddsOnlyTheNewMessages() throws Exception {
        user.deliver(mail("one", "1"));
        sync.sync(accountId);
        long firstId = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        user.deliver(mail("two", "2"));
        sync.sync(accountId);

        assertEquals(2, inboxSubjects().size());
        assertTrue(store.list(String.valueOf(accountId), "inbox").stream().anyMatch(r -> Long.parseLong(r.id()) == firstId), "the first message keeps its id");
    }

    @Test
    void onlyMailThatArrivesAfterTheFirstSyncIsReportedAsNew() throws Exception {
        List<SyncService.NewMail> arrived = Collections.synchronizedList(new ArrayList<>());
        sync.onNewMail(arrived::addAll);
        user.deliver(mail("old", "1"));
        sync.sync(accountId);
        assertTrue(arrived.isEmpty(), "the first sync loads history and must not notify about it");

        user.deliver(mail("fresh", "2"));
        sync.sync(accountId);
        assertEquals(1, arrived.size());
        assertEquals("fresh", arrived.get(0).subject());
        assertEquals("Elena", arrived.get(0).fromName());
        assertEquals(String.valueOf(accountId), arrived.get(0).account());

        sync.sync(accountId);
        assertEquals(1, arrived.size(), "the same message is not reported twice");
    }

    @Test
    void reschedulingAcceptsZeroAndLaterIntervals() {
        sync.start(java.time.Duration.ofMinutes(5));
        sync.reschedule(java.time.Duration.ZERO);
        sync.reschedule(java.time.Duration.ofMinutes(1));
    }

    @Test
    void readingOnAnotherDeviceMarksItReadHere() throws Exception {
        user.deliver(mail("read elsewhere", "x"));
        sync.sync(accountId);
        assertTrue(store.list(String.valueOf(accountId), "inbox").get(0).unread());

        asAnotherClient(inbox -> {
            try {
                inbox.getMessage(1).setFlag(Flags.Flag.SEEN, true);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        sync.sync(accountId);
        assertFalse(store.list(String.valueOf(accountId), "inbox").get(0).unread());
    }

    @Test
    void aMessageDeletedOnTheServerDisappearsHere() throws Exception {
        user.deliver(mail("keep", "1"));
        user.deliver(mail("delete me", "2"));
        sync.sync(accountId);
        assertEquals(2, inboxSubjects().size());

        asAnotherClient(inbox -> {
            try {
                for (Message m : inbox.getMessages()) {
                    if ("delete me".equals(m.getSubject())) {
                        m.setFlag(Flags.Flag.DELETED, true);
                    }
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        sync.sync(accountId);
        assertEquals(List.of("keep"), inboxSubjects());
    }

    @Test
    void messagesBeyondThePrefetchGetTheirBodyWhenOpened() throws Exception {
        for (int i = 1; i <= SyncService.PREFETCH_BODIES + 5; i++) {
            user.deliver(mail("message " + i, "body of message " + i));
        }
        sync.sync(accountId);

        List<Mail.Summary> rows = store.list(String.valueOf(accountId), "inbox");
        Mail.Summary withoutBody = rows.stream().filter(r -> store.detail(Long.parseLong(r.id())).isEmpty()).findFirst().orElseThrow();
        long id = Long.parseLong(withoutBody.id());

        sync.loadBody(id);
        Mail.Detail detail = store.detail(id).orElseThrow();
        assertTrue(detail.bodyHtml().contains("body of message"));
        assertTrue(detail.summary().unread(), "opening is not reading: only setRead changes that");
    }

    @Test
    void markingReadChangesTheCacheAtOnceAndTheServerSoon() throws Exception {
        user.deliver(mail("to read", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        sync.setRead(id, true);
        assertFalse(store.list(String.valueOf(accountId), "inbox").get(0).unread(), "immediate");

        boolean[] seenOnServer = {false};
        eventually(() -> {
            try {
                asAnotherClient(inbox -> {
                    try {
                        seenOnServer[0] = inbox.getMessage(1).isSet(Flags.Flag.SEEN);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return seenOnServer[0];
        });
    }

    private int serverMessageCount(String folder) throws Exception {
        Store imap = Session.getInstance(new Properties()).getStore("imap");
        imap.connect("127.0.0.1", ServerSetupTest.IMAP.getPort(), "will", "secret");
        try {
            Folder f = imap.getFolder(folder);
            f.open(Folder.READ_ONLY);
            int count = f.getMessageCount();
            f.close(false);
            return count;
        } finally {
            imap.close();
        }
    }

    @Test
    void starringChangesTheCacheAtOnceAndTheServerSoon() throws Exception {
        user.deliver(mail("to star", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        sync.setStarred(id, true);
        assertTrue(store.list(String.valueOf(accountId), "inbox").get(0).starred(), "immediate");
        assertEquals(1, store.list(String.valueOf(accountId), "starred").size());

        eventually(() -> {
            try {
                boolean[] flagged = {false};
                asAnotherClient(inbox -> {
                    try {
                        flagged[0] = inbox.getMessage(1).isSet(Flags.Flag.FLAGGED);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                return flagged[0];
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        sync.setStarred(id, false);
        assertTrue(store.list(String.valueOf(accountId), "starred").isEmpty());
    }

    @Test
    void importantIsKeptOnTheServerAsAKeywordAndComesBackOnSync() throws Exception {
        user.deliver(mail("urgent", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        sync.setImportant(id, true);

        assertTrue(store.list(String.valueOf(accountId), "inbox").get(0).important());
        boolean[] onServer = {false};
        asAnotherClient(inbox -> {
            try {
                onServer[0] = inbox.getMessage(1).getFlags().contains("$Important");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertTrue(onServer[0], "the keyword is on the server");

        // another device clears it: the next sync follows
        asAnotherClient(inbox -> {
            try {
                inbox.getMessage(1).setFlags(new Flags("$Important"), false);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        sync.sync(accountId);
        assertFalse(store.list(String.valueOf(accountId), "inbox").get(0).important());
    }

    @Test
    void importantSetOnAnotherDeviceShowsUpAfterSync() throws Exception {
        user.deliver(mail("flagged elsewhere", "x"));
        sync.sync(accountId);
        asAnotherClient(inbox -> {
            try {
                inbox.getMessage(1).setFlags(new Flags("$Important"), true);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        sync.sync(accountId);

        assertTrue(store.list(String.valueOf(accountId), "inbox").get(0).important());
    }

    @Test
    void archivingMovesTheMessageOnTheServerAndShowsItInArchive() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Archive");
        user.deliver(mail("old news", "x"));
        user.deliver(mail("keep me", "y"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").stream()
                .filter(m -> m.subject().equals("old news")).findFirst().orElseThrow().id());

        sync.archive(id);

        assertEquals(List.of("keep me"), inboxSubjects(), "gone from the inbox at once");
        assertEquals(1, serverMessageCount("INBOX"), "and from the server's inbox");
        assertEquals(1, serverMessageCount("Archive"));
        eventually(() -> store.list(String.valueOf(accountId), "archive").stream().anyMatch(m -> m.subject().equals("old news")));
    }

    @Test
    void archivingOnAServerWithoutAnArchiveFolderCreatesOne() throws Exception {
        user.deliver(mail("first", "x"));
        user.deliver(mail("second", "y"));
        sync.sync(accountId);
        assertTrue(store.folderRows(accountId).stream().noneMatch(f -> "archive".equals(f.role())));
        long first = Long.parseLong(store.list(String.valueOf(accountId), "inbox").stream()
                .filter(m -> m.subject().equals("first")).findFirst().orElseThrow().id());

        sync.archive(first);

        assertEquals(1, serverMessageCount("Archive"), "the folder was made and holds the message");
        assertEquals(1, serverMessageCount("INBOX"));
        eventually(() -> store.folderRows(accountId).stream().anyMatch(f -> "archive".equals(f.role())));

        // the second archive reuses the folder instead of trying to make it again
        long second = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());
        sync.archive(second);
        assertEquals(2, serverMessageCount("Archive"));
        assertEquals(0, serverMessageCount("INBOX"));
    }

    @Test
    void deletingMovesTheMessageToTheTrashFolderAndKeepsItThere() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Trash");
        user.deliver(mail("bin me", "x"));
        user.deliver(mail("keep me", "y"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").stream()
                .filter(m -> m.subject().equals("bin me")).findFirst().orElseThrow().id());

        sync.trash(id);

        assertEquals(List.of("keep me"), inboxSubjects());
        assertEquals(1, serverMessageCount("Trash"), "moved, not destroyed");
        assertEquals(1, serverMessageCount("INBOX"));
        assertTrue(store.list(String.valueOf(accountId), "trash").stream().anyMatch(m -> m.subject().equals("bin me")), "in the trash of the cache as soon as the move returns");

        // deleting what is already in the trash does nothing
        long inTrash = Long.parseLong(store.list(String.valueOf(accountId), "trash").get(0).id());
        sync.trash(inTrash);
        assertEquals(1, serverMessageCount("Trash"));
    }

    @Test
    void deletingSeveralMessagesUsesOneConnectionAndMovesThemAll() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Trash");
        for (int i = 0; i < 6; i++) {
            user.deliver(mail("bin " + i, "x"));
        }
        user.deliver(mail("keep me", "y"));
        sync.sync(accountId);
        List<Long> ids = store.list(String.valueOf(accountId), "inbox").stream()
                .filter(m -> m.subject().startsWith("bin ")).map(m -> Long.parseLong(m.id())).toList();
        assertEquals(6, ids.size());

        java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
        SyncService counting = new SyncService(store, CredentialBroker.withStore(secrets),
                (settings, password) -> {
                    connections.incrementAndGet();
                    return com.parsel.imap.ImapClient.connect(settings, password);
                }, statuses::add, changes::add);
        try {
            counting.moveMany(ids, "trash", null);

            assertEquals(1, connections.get(), "six messages, one connection to the server");
            assertEquals(List.of("keep me"), inboxSubjects());
            assertEquals(6, serverMessageCount("Trash"), "moved, not destroyed");
            assertEquals(1, serverMessageCount("INBOX"));
            assertEquals(6, store.list(String.valueOf(accountId), "trash").size(), "already in the trash folder of the cache when the move returns");
        } finally {
            counting.close();
        }
    }

    @Test
    void movingMessagesThatAreGoneOrAlreadyThereIsNotAnError() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Trash");
        user.deliver(mail("one", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());
        sync.moveMany(List.of(id), "trash", null);
        eventually(() -> store.list(String.valueOf(accountId), "trash").size() == 1);
        long inTrash = Long.parseLong(store.list(String.valueOf(accountId), "trash").get(0).id());

        sync.moveMany(List.of(inTrash, 987654L), "trash", null); // already in the trash, and one that does not exist
        assertEquals(1, serverMessageCount("Trash"));
        assertThrows(IllegalArgumentException.class, () -> sync.moveMany(List.of(987654L), "trash", null));
        assertThrows(IllegalArgumentException.class, () -> sync.moveMany(List.of(inTrash), "explode", null));
    }

    @Test
    void aMessageInTheTrashCanBeRestoredToTheInbox() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Trash");
        user.deliver(mail("oops", "x"));
        sync.sync(accountId);
        sync.trash(Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id()));
        eventually(() -> store.list(String.valueOf(accountId), "trash").size() == 1);
        long inTrash = Long.parseLong(store.list(String.valueOf(accountId), "trash").get(0).id());

        sync.restore(inTrash);

        assertTrue(store.list(String.valueOf(accountId), "trash").isEmpty(), "gone from the trash at once");
        assertEquals(0, serverMessageCount("Trash"));
        assertEquals(1, serverMessageCount("INBOX"));
        eventually(() -> inboxSubjects().contains("oops"));
    }

    @Test
    void archivingAMessageTheServerNoLongerHasFailsWithASentence() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Archive");
        user.deliver(mail("gone soon", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());
        asAnotherClient(inbox -> {
            try {
                inbox.getMessage(1).setFlag(Flags.Flag.DELETED, true);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sync.archive(id));

        assertTrue(e.getMessage().startsWith("Could not archive"), e.getMessage());
        assertEquals(List.of("gone soon"), inboxSubjects(), "still listed: nothing is dropped when the move fails");
    }

    @Test
    void aWrongSavedPasswordIsReportedAsAnErrorNotACrash() {
        secrets.put("will@localhost", "changed-on-the-server");
        sync.sync(accountId);
        SyncService.Status last = statuses.get(statuses.size() - 1);
        assertEquals(SyncService.State.ERROR, last.state());
        assertTrue(last.error().contains("refused the login"), last.error());
        assertTrue(store.accountRow(accountId).orElseThrow().lastError().contains("refused the login"));
    }

    @Test
    void aLockedVaultIsReportedAsLockedAndNothingIsTried() {
        SyncService locked = new SyncService(store, CredentialBroker.vaultOnly(dir.resolve("vault")), statuses::add, changes::add);
        try {
            locked.sync(accountId);
        } finally {
            locked.close();
        }
        assertEquals(SyncService.State.LOCKED, statuses.get(statuses.size() - 1).state());
        assertTrue(store.list(String.valueOf(accountId), "inbox").isEmpty());
    }

    @Test
    void anAccountThatNoLongerExistsIsIgnored() {
        sync.sync(9999);
        assertTrue(statuses.isEmpty());
    }

    @Test
    void anUnreachableServerIsAnErrorWithTheServerNamed() {
        long dead = store.addAccount(new AccountSettings("Dead", "dead@localhost", "dead", "127.0.0.1", 1, Security.NONE, "127.0.0.1", 1, Security.NONE));
        secrets.put("dead@localhost", "pw");
        sync.sync(dead);
        SyncService.Status last = statuses.get(statuses.size() - 1);
        assertEquals(SyncService.State.ERROR, last.state());
        assertTrue(last.error().contains("127.0.0.1:1"), last.error());
    }

    // ---- folders --------------------------------------------------------------------------------------------------

    private boolean serverHasFolder(String name) {
        return greenMail.getManagers().getImapHostManager().getFolder(user, name) != null;
    }

    @Test
    void aNewFolderExistsOnTheServerAndInTheSidebarAtOnce() throws Exception {
        sync.sync(accountId);

        Mail.Folder created = sync.createFolder(accountId, null, "  Receipts 2026 ");

        assertEquals("Receipts 2026", created.name(), "the name is trimmed");
        assertTrue(created.id().startsWith("f"), created.id());
        assertEquals(0, created.depth());
        assertTrue(serverHasFolder("Receipts 2026"));
        assertTrue(store.folders(String.valueOf(accountId)).stream().anyMatch(x -> x.id().equals(created.id())), "listed without waiting for a sync");
    }

    @Test
    void aFolderCanBeMadeInsideAnotherAndIsShownUnderIt() throws Exception {
        sync.sync(accountId);
        Mail.Folder work = sync.createFolder(accountId, null, "Work");
        Mail.Folder clients = sync.createFolder(accountId, work.id(), "Clients");
        sync.createFolder(accountId, null, "Zebra");

        assertTrue(serverHasFolder("Work.Clients"), "created inside Work on the server");
        assertEquals(1, clients.depth());
        List<String> sidebar = store.folders(String.valueOf(accountId)).stream().filter(x -> x.id().startsWith("f")).map(Mail.Folder::name).toList();
        assertEquals(List.of("Work", "Clients", "Zebra"), sidebar, "a folder sits right under its parent");
    }

    @Test
    void badOrTakenFolderNamesAreRefusedWithASentence() throws Exception {
        sync.sync(accountId);
        sync.createFolder(accountId, null, "Taken");

        for (String bad : new String[]{"", "   ", "a/b", "a*b", "back\\slash", "inbox", "x".repeat(101)}) {
            assertThrows(IllegalArgumentException.class, () -> sync.createFolder(accountId, null, bad), "[" + bad + "]");
        }
        IllegalArgumentException taken = assertThrows(IllegalArgumentException.class, () -> sync.createFolder(accountId, null, "Taken"));
        assertTrue(taken.getMessage().contains("already a folder"), taken.getMessage());
        Mail.Folder work = sync.createFolder(accountId, null, "Work");
        IllegalArgumentException dot = assertThrows(IllegalArgumentException.class, () -> sync.createFolder(accountId, work.id(), "a.b"));
        assertTrue(dot.getMessage().contains("cannot contain"), "the server's own separator is not allowed in a name: " + dot.getMessage());
    }

    @Test
    void movingToAFolderMovesTheMessageOnTheServerAndInTheCache() throws Exception {
        user.deliver(mail("receipt", "x"));
        user.deliver(mail("stays", "y"));
        sync.sync(accountId);
        Mail.Folder receipts = sync.createFolder(accountId, null, "Receipts");
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").stream()
                .filter(m -> m.subject().equals("receipt")).findFirst().orElseThrow().id());

        sync.moveToFolder(id, receipts.id());

        assertEquals(List.of("stays"), inboxSubjects(), "gone from the inbox at once");
        assertEquals(1, serverMessageCount("INBOX"));
        assertEquals(1, serverMessageCount("Receipts"));
        eventually(() -> store.list(String.valueOf(accountId), receipts.id()).stream().anyMatch(m -> m.subject().equals("receipt")));
    }

    @Test
    void movingToAStandardFolderByItsRoleWorksToo() throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(user, "Archive");
        user.deliver(mail("old", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        sync.moveToFolder(id, "archive");

        assertEquals(1, serverMessageCount("Archive"));
        assertEquals(0, serverMessageCount("INBOX"));
    }

    @Test
    void movingToAMissingFolderOrTheSameFolderChangesNothing() throws Exception {
        user.deliver(mail("here", "x"));
        sync.sync(accountId);
        long id = Long.parseLong(store.list(String.valueOf(accountId), "inbox").get(0).id());

        assertThrows(IllegalArgumentException.class, () -> sync.moveToFolder(id, "f99999"));
        assertThrows(IllegalArgumentException.class, () -> sync.moveToFolder(id, "trash"), "this account has no trash folder yet");
        sync.moveToFolder(id, "inbox"); // already there

        assertEquals(List.of("here"), inboxSubjects());
        assertEquals(1, serverMessageCount("INBOX"));
    }
}
