package io.github.b077as.parsel.store;

import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.mail.Mail;
import io.github.b077as.parsel.store.MailStore.FolderRow;
import io.github.b077as.parsel.store.MailStore.HeaderRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailStoreTest {

    @TempDir
    Path dir;

    MailStore store;
    long account;

    static AccountSettings settings(String email) {
        return new AccountSettings("Will", email, email, "imap.example.com", 993, Security.SSL, "smtp.example.com", 465, Security.SSL);
    }

    static HeaderRow header(long uid, String subject, long date, boolean unread) {
        return new HeaderRow(uid, "Elena", "elena@example.com", "will@example.com", subject, date, unread, false, false);
    }

    @BeforeEach
    void setUp() {
        store = new MailStore(new Database(dir.resolve("mail.db")));
        account = store.addAccount(settings("will@example.com"));
    }

    @Test
    void anAccountComesBackWithItsSettingsAndCannotBeAddedTwice() {
        MailStore.AccountRow row = store.accountRow(account).orElseThrow();
        assertEquals("imap.example.com", row.settings().imapHost());
        assertEquals(Security.SSL, row.settings().imapSecurity());
        assertEquals(List.of(new Mail.Account(String.valueOf(account), "Will", "will@example.com")), store.accounts());
        assertThrows(IllegalArgumentException.class, () -> store.addAccount(settings("WILL@example.com")));
    }

    @Test
    void reopeningTheDatabaseKeepsTheData() {
        MailStore again = new MailStore(new Database(dir.resolve("mail.db")));
        assertEquals(1, again.accounts().size());
    }

    @Test
    void foldersAreAddedRenamedAndDroppedWithTheirMail() {
        store.replaceFolders(account, List.of(
                new FolderRow(0, account, "INBOX", "Inbox", "inbox", null),
                new FolderRow(0, account, "Receipts", "Receipts", null, null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        long receipts = store.folderByPath(account, "Receipts").orElseThrow().id();
        store.upsertHeaders(receipts, List.of(header(1, "A receipt", 1000, true)));
        assertEquals(1, store.messageCount(receipts));

        store.replaceFolders(account, List.of(new FolderRow(0, account, "INBOX", "Inbox", "inbox", null)));
        assertEquals(inbox, store.folderByPath(account, "INBOX").orElseThrow().id(), "an existing folder keeps its id");
        assertTrue(store.folderByPath(account, "Receipts").isEmpty());
        assertEquals(0, store.messageCount(receipts), "messages of a dropped folder go with it");
    }

    @Test
    void listsNewestFirstAndCountsUnreadPerFolder() {
        store.replaceFolders(account, List.of(
                new FolderRow(0, account, "INBOX", "Inbox", "inbox", null),
                new FolderRow(0, account, "Sent", "Sent", "sent", null),
                new FolderRow(0, account, "Travel", "Travel", null, null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        long travel = store.folderByPath(account, "Travel").orElseThrow().id();
        store.upsertHeaders(inbox, List.of(header(1, "old", 1000, false), header(2, "new", 3000, true), header(3, "middle", 2000, true)));
        store.upsertHeaders(travel, List.of(header(1, "ticket", 500, true)));

        List<Mail.Summary> rows = store.list(String.valueOf(account), "inbox");
        assertEquals(List.of("new", "middle", "old"), rows.stream().map(Mail.Summary::subject).toList());

        List<Mail.Folder> folders = store.folders(String.valueOf(account));
        assertEquals(List.of("inbox", "starred", "sent", "f" + travel), folders.stream().map(Mail.Folder::id).toList());
        assertEquals(2, folders.get(0).unread());
        assertEquals(1, folders.get(3).unread());
        assertEquals(List.of("ticket"), store.list(String.valueOf(account), "f" + travel).stream().map(Mail.Summary::subject).toList());
    }

    @Test
    void theCombinedViewMergesStandardFoldersAcrossAccountsAndHidesCustomOnes() {
        long other = store.addAccount(settings("work@example.com"));
        for (long a : new long[]{account, other}) {
            store.replaceFolders(a, List.of(new FolderRow(0, a, "INBOX", "Inbox", "inbox", null), new FolderRow(0, a, "X", "X", null, null)));
        }
        store.upsertHeaders(store.folderByPath(account, "INBOX").orElseThrow().id(), List.of(header(1, "one", 1000, true)));
        store.upsertHeaders(store.folderByPath(other, "INBOX").orElseThrow().id(), List.of(header(1, "two", 2000, true)));

        assertEquals(List.of("two", "one"), store.list("all", "inbox").stream().map(Mail.Summary::subject).toList());
        assertEquals(List.of("inbox", "starred"), store.folders("all").stream().map(Mail.Folder::id).toList());
        assertEquals(2, store.folders("all").get(0).unread());
    }

    @Test
    void anExistingHeaderOnlyGetsItsFlagsRefreshedAndKeepsItsBody() {
        store.replaceFolders(account, List.of(new FolderRow(0, account, "INBOX", "Inbox", "inbox", null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        store.upsertHeaders(inbox, List.of(header(7, "hello", 1000, true)));
        long id = Long.parseLong(store.list(String.valueOf(account), "inbox").get(0).id());
        assertTrue(store.detail(id).isEmpty(), "no body yet");

        store.setBody(id, "<p>body</p>", "body", true, List.of(new Mail.Attachment("a.pdf", 10)));
        store.upsertHeaders(inbox, List.of(new HeaderRow(7, "Elena", "elena@example.com", "will@example.com", "hello", 1000, false, true, false)));

        Mail.Detail detail = store.detail(id).orElseThrow();
        assertEquals("<p>body</p>", detail.bodyHtml());
        assertTrue(detail.hasRemoteImages());
        assertEquals(List.of(new Mail.Attachment("a.pdf", 10)), detail.attachments());
        assertFalse(detail.summary().unread());
        assertTrue(detail.summary().starred());
        assertTrue(detail.summary().hasAttachments(), "setBody records that the message has attachments");
    }

    @Test
    void starredIsAVirtualFolderAcrossFolders() {
        store.replaceFolders(account, List.of(new FolderRow(0, account, "INBOX", "Inbox", "inbox", null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        store.upsertHeaders(inbox, List.of(header(1, "plain", 1000, false),
                new HeaderRow(2, "E", "e@example.com", "", "flagged", 2000, false, true, false)));
        assertEquals(List.of("flagged"), store.list(String.valueOf(account), "starred").stream().map(Mail.Summary::subject).toList());
    }

    @Test
    void uidHelpersSupportIncrementalSync() {
        store.replaceFolders(account, List.of(new FolderRow(0, account, "INBOX", "Inbox", "inbox", null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        store.upsertHeaders(inbox, List.of(header(10, "a", 1, true), header(11, "b", 2, true), header(12, "c", 3, true)));
        assertEquals(12, store.maxUid(inbox));
        assertArrayEquals(new long[]{12, 11}, store.recentUids(inbox, 2));
        store.deleteUids(inbox, new long[]{11});
        assertEquals(2, store.messageCount(inbox));
        store.resetFolder(inbox, 99);
        assertEquals(0, store.messageCount(inbox));
        assertEquals(Optional.of(99L), Optional.of(store.folderByPath(account, "INBOX").orElseThrow().uidValidity()));
    }

    @Test
    void removingAnAccountRemovesEverythingOfIt() {
        store.replaceFolders(account, List.of(new FolderRow(0, account, "INBOX", "Inbox", "inbox", null)));
        long inbox = store.folderByPath(account, "INBOX").orElseThrow().id();
        store.upsertHeaders(inbox, List.of(header(1, "a", 1, true)));
        assertEquals(Optional.of("will@example.com"), store.removeAccount(account));
        assertTrue(store.accounts().isEmpty());
        assertEquals(0, store.messageCount(inbox));
        assertEquals(Optional.empty(), store.removeAccount(account));
    }
}
