package io.github.b077as.parsel.sync;

import io.github.b077as.parsel.account.AccountService;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Problems;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.imap.BodyParser;
import io.github.b077as.parsel.imap.ImapClient;
import io.github.b077as.parsel.imap.ImapClient.RemoteFlags;
import io.github.b077as.parsel.mail.Mail;
import io.github.b077as.parsel.store.MailStore;
import io.github.b077as.parsel.store.MailStore.AccountRow;
import io.github.b077as.parsel.store.MailStore.FolderRow;
import io.github.b077as.parsel.store.MailStore.HeaderRow;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Keeps the local cache in step with the servers. A sync of an account lists its folders, fetches the headers of new
 * messages (the newest ones the first time), refreshes the flags of the recent ones and drops what the server deleted,
 * then downloads the bodies of the newest inbox messages so the list can show a preview. It never touches the UI: it
 * reports through the two callbacks, and the app turns those into events for the page.
 *
 * <p>Known limit: a first sync loads the newest messages of each folder, not the whole history, and there is no "load
 * older messages" yet.
 */
public final class SyncService implements AutoCloseable {

    public enum State { IDLE, SYNCING, ERROR, LOCKED }

    /** @param error a sentence for the user when {@code state} is ERROR, otherwise null; @param lastSync epoch milliseconds, or null if never */
    public record Status(String account, State state, Long lastSync, String error) {}

    /** An unread message that arrived in an inbox since the last sync (never reported for the first sync of a folder). */
    public record NewMail(String account, String messageId, String fromName, String fromAddress, String subject) {}

    /** How a connection to a server is made; replaced in tests. */
    @FunctionalInterface
    public interface Connector {
        ImapClient connect(AccountSettings settings, String password) throws MessagingException;
    }

    /** Name of the folder made when a server has no archive folder. */
    static final String ARCHIVE_FOLDER = "Archive";
    static final String TRASH_FOLDER = "Trash";

    static final int FIRST_SYNC_INBOX = 200;
    static final int FIRST_SYNC_OTHER = 50;
    static final int MAX_NEW_PER_SYNC = 500;
    static final int FLAG_WINDOW = 300;
    static final int PREFETCH_BODIES = 25;

    private final MailStore store;
    private final CredentialBroker credentials;
    private final Connector connector;
    private final Consumer<Status> onStatus;
    private final Consumer<String> onMailChanged;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "parsel-sync-timer");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<Long, Status> statuses = new ConcurrentHashMap<>();
    private volatile Consumer<List<NewMail>> onNewMail = mails -> {};
    private ScheduledFuture<?> scheduled;

    public SyncService(MailStore store, CredentialBroker credentials, Consumer<Status> onStatus, Consumer<String> onMailChanged) {
        this(store, credentials, ImapClient::connect, onStatus, onMailChanged);
    }

    public SyncService(MailStore store, CredentialBroker credentials, Connector connector, Consumer<Status> onStatus, Consumer<String> onMailChanged) {
        this.store = store;
        this.credentials = credentials;
        this.connector = connector;
        this.onStatus = onStatus;
        this.onMailChanged = onMailChanged;
    }

    // ---- scheduling -----------------------------------------------------------------------------------------------

    /** Syncs every account now and then again every {@code interval} ({@link Duration#ZERO}: only when asked). */
    public synchronized void start(Duration interval) {
        syncAllAsync();
        reschedule(interval);
    }

    /** Changes how often the background sync runs; the next run is one {@code interval} from now. {@link Duration#ZERO} stops it. */
    public synchronized void reschedule(Duration interval) {
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        if (!interval.isZero()) {
            long seconds = Math.max(interval.toSeconds(), 1);
            scheduled = timer.scheduleWithFixedDelay(this::syncAllAsync, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    /** Called after a sync that found unread mail in an inbox, with that mail. Runs on the sync's thread. */
    public void onNewMail(Consumer<List<NewMail>> listener) {
        this.onNewMail = listener;
    }

    public CompletableFuture<Void> syncAllAsync() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        for (AccountRow account : store.accountRows()) {
            all.add(syncAsync(account.id()));
        }
        return CompletableFuture.allOf(all.toArray(new CompletableFuture<?>[0]));
    }

    public CompletableFuture<Void> syncAsync(long accountId) {
        return CompletableFuture.runAsync(() -> sync(accountId), workers);
    }

    /** The last known state of every account that has had one. */
    public List<Status> statuses() {
        List<Status> result = new ArrayList<>(statuses.values());
        result.sort((a, b) -> a.account().compareTo(b.account()));
        return result;
    }

    // ---- one account ----------------------------------------------------------------------------------------------

    /** Syncs one account and returns when done. Two syncs of the same account never overlap: the second waits. */
    public void sync(long accountId) {
        ReentrantLock lock = locks.computeIfAbsent(accountId, id -> new ReentrantLock());
        lock.lock();
        try {
            Optional<AccountRow> found = store.accountRow(accountId);
            if (found.isEmpty()) {
                statuses.remove(accountId);
                return;
            }
            AccountRow account = found.get();
            String id = String.valueOf(accountId);
            String password;
            try {
                password = credentials.get(AccountService.key(account.settings().email())).orElse(null);
            } catch (CredentialBroker.VaultLockedException e) {
                publish(new Status(id, State.LOCKED, account.lastSync(), null));
                return;
            }
            if (password == null) {
                fail(account, "There is no saved password for this account. Remove it and add it again.");
                return;
            }
            publish(new Status(id, State.SYNCING, account.lastSync(), null));
            try (ImapClient client = connector.connect(account.settings(), password)) {
                List<NewMail> arrived = syncFolders(account, client);
                prefetchBodies(account, client);
                long now = System.currentTimeMillis();
                store.recordSync(accountId, now, null);
                publish(new Status(id, State.IDLE, now, null));
                if (!arrived.isEmpty()) {
                    onMailChanged.accept(id); // the list shows the message before the notification can be clicked
                    onNewMail.accept(arrived);
                }
            } catch (Exception e) {
                fail(account, Problems.explain(e, account.settings().imapHost(), account.settings().imapPort()));
            }
            onMailChanged.accept(id);
        } finally {
            lock.unlock();
        }
    }

    private void fail(AccountRow account, String message) {
        store.recordSync(account.id(), null, message);
        publish(new Status(String.valueOf(account.id()), State.ERROR, account.lastSync(), message));
    }

    private void publish(Status status) {
        statuses.put(Long.parseLong(status.account()), status);
        onStatus.accept(status);
    }

    /** Reads the server's folder list into the cache, without touching any messages. */
    private void storeRemoteFolders(AccountRow account, ImapClient client) throws MessagingException {
        List<FolderRow> rows = new ArrayList<>();
        for (ImapClient.RemoteFolder folder : client.folders()) {
            rows.add(new FolderRow(0, account.id(), folder.path(), folder.name(), folder.role(), null));
        }
        store.replaceFolders(account.id(), rows);
        onMailChanged.accept(String.valueOf(account.id())); // the sidebar can show the folders while their mail is still loading
    }

    private List<NewMail> syncFolders(AccountRow account, ImapClient client) throws MessagingException {
        storeRemoteFolders(account, client);

        // The inbox first, so what the user is looking at fills in before the rest.
        List<FolderRow> folders = new ArrayList<>(store.folderRows(account.id()));
        folders.sort((a, b) -> Boolean.compare(!"inbox".equals(a.role()), !"inbox".equals(b.role())));
        List<NewMail> arrived = new ArrayList<>();
        for (FolderRow folder : folders) {
            for (HeaderRow header : syncFolder(client, folder)) {
                store.messageId(folder.id(), header.uid()).ifPresent(messageId -> arrived.add(
                        new NewMail(String.valueOf(account.id()), String.valueOf(messageId), header.fromName(), header.fromAddress(), header.subject())));
            }
            onMailChanged.accept(String.valueOf(account.id()));
        }
        return arrived;
    }

    /** @return the unread messages that are new in this folder, only for an inbox that has been synced before */
    private List<HeaderRow> syncFolder(ImapClient client, FolderRow folder) throws MessagingException {
        long validity = client.uidValidity(folder.path());
        if (folder.uidValidity() == null || folder.uidValidity() != validity) {
            // First visit, or the server renumbered the folder: the cached UIDs mean nothing any more.
            store.resetFolder(folder.id(), validity);
        }
        long newest = store.maxUid(folder.id());
        List<HeaderRow> fresh = newest == 0
                ? client.fetchNewest(folder.path(), "inbox".equals(folder.role()) ? FIRST_SYNC_INBOX : FIRST_SYNC_OTHER)
                : client.fetchSince(folder.path(), newest, MAX_NEW_PER_SYNC);
        store.upsertHeaders(folder.id(), fresh);
        List<HeaderRow> arrived = newest == 0 || !"inbox".equals(folder.role())
                ? List.of() : fresh.stream().filter(HeaderRow::unread).toList();

        // Flags change on the server (read on another device) and messages get deleted: look at the recent window.
        long[] recent = store.recentUids(folder.id(), FLAG_WINDOW);
        Map<Long, RemoteFlags> remoteFlags = client.fetchFlags(folder.path(), recent);
        Map<Long, MailStore.Flags> changes = new HashMap<>();
        List<Long> deleted = new ArrayList<>();
        for (long uid : recent) {
            RemoteFlags flags = remoteFlags.get(uid);
            if (flags == null) {
                deleted.add(uid);
            } else {
                changes.put(uid, new MailStore.Flags(flags.unread(), flags.starred(), flags.important()));
            }
        }
        store.applyFlags(folder.id(), changes);
        if (!deleted.isEmpty()) {
            store.deleteUids(folder.id(), deleted.stream().mapToLong(Long::longValue).toArray());
        }
        return arrived;
    }

    /** Downloads the newest inbox bodies so the list has previews without the user opening each message. */
    private void prefetchBodies(AccountRow account, ImapClient client) {
        for (FolderRow folder : store.folderRows(account.id())) {
            if (!"inbox".equals(folder.role())) {
                continue;
            }
            for (long uid : store.uidsWithoutBody(folder.id(), PREFETCH_BODIES)) {
                try {
                    storeBody(client, folder, uid);
                } catch (MessagingException | IOException | RuntimeException e) {
                    // One unreadable message must not stop the rest; it is fetched again when the user opens it.
                }
            }
            onMailChanged.accept(String.valueOf(account.id()));
        }
    }

    private void storeBody(ImapClient client, FolderRow folder, long uid) throws MessagingException, IOException {
        Optional<Long> id = store.messageId(folder.id(), uid);
        if (id.isEmpty()) {
            return;
        }
        BodyParser.Parsed parsed = client.fetchBody(folder.path(), uid);
        store.setBody(id.get(), parsed.html(), parsed.snippet(), parsed.hasRemoteImages(), parsed.attachments());
    }

    // ---- on demand ------------------------------------------------------------------------------------------------

    /** Downloads and caches the body of one message the user opened. Uses its own connection, so it does not wait for a running sync. */
    public void loadBody(long messageId) {
        MailStore.MessageRef ref = store.messageRef(messageId).orElseThrow(() -> new IllegalArgumentException("No such message"));
        if (ref.bodyCached()) {
            return;
        }
        AccountRow account = store.accountRow(ref.accountId()).orElseThrow(() -> new IllegalStateException("The account of this message was removed"));
        String password = credentials.get(AccountService.key(account.settings().email()))
                .orElseThrow(() -> new IllegalStateException("There is no saved password for this account"));
        try (ImapClient client = connector.connect(account.settings(), password)) {
            BodyParser.Parsed parsed = client.fetchBody(ref.folderPath(), ref.uid());
            store.setBody(messageId, parsed.html(), parsed.snippet(), parsed.hasRemoteImages(), parsed.attachments());
        } catch (MessagingException | IOException e) {
            throw new IllegalStateException(Problems.explain(e, account.settings().imapHost(), account.settings().imapPort()), e);
        }
    }

    /** Marks a message read or unread here at once, and on the server in the background. */
    public void setRead(long messageId, boolean read) {
        Optional<MailStore.MessageRef> ref = store.messageRef(messageId);
        store.setUnread(messageId, !read);
        if (ref.isEmpty()) {
            return;
        }
        workers.execute(() -> {
            try {
                AccountRow account = store.accountRow(ref.get().accountId()).orElse(null);
                if (account == null) {
                    return;
                }
                String password = credentials.get(AccountService.key(account.settings().email())).orElse(null);
                if (password == null) {
                    return;
                }
                try (ImapClient client = connector.connect(account.settings(), password)) {
                    client.setSeen(ref.get().folderPath(), ref.get().uid(), read);
                }
            } catch (MessagingException | RuntimeException e) {
                // Best effort: the next sync reads the server's flags and puts the local copy right.
            }
        });
    }

    /** Stars or unstars a message here at once, and on the server in the background. */
    public void setStarred(long messageId, boolean starred) {
        Optional<MailStore.MessageRef> ref = store.messageRef(messageId);
        store.setStarred(messageId, starred);
        if (ref.isEmpty()) {
            return;
        }
        workers.execute(() -> {
            try {
                AccountRow account = store.accountRow(ref.get().accountId()).orElse(null);
                String password = account == null ? null : credentials.get(AccountService.key(account.settings().email())).orElse(null);
                if (password == null) {
                    return;
                }
                try (ImapClient client = connector.connect(account.settings(), password)) {
                    client.setFlagged(ref.get().folderPath(), ref.get().uid(), starred);
                }
            } catch (MessagingException | RuntimeException e) {
                // Best effort: the next sync reads the server's flags and puts the local copy right.
            }
        });
    }

    /**
     * Marks a message important or not: the cache changes at once, then the server is asked and this waits for it. If the server
     * refuses (some do not accept custom flags) the cache is put back and the reason is thrown, so the mark never flips back by itself later.
     *
     * @throws IllegalStateException with a sentence for the user
     */
    public void setImportant(long messageId, boolean important) {
        MailStore.MessageRef ref = store.messageRef(messageId).orElseThrow(() -> new IllegalArgumentException("No such message"));
        AccountRow account = store.accountRow(ref.accountId()).orElseThrow(() -> new IllegalStateException("The account of this message was removed"));
        String password = credentials.get(AccountService.key(account.settings().email()))
                .orElseThrow(() -> new IllegalStateException("There is no saved password for this account"));
        store.setImportant(messageId, important);
        try (ImapClient client = connector.connect(account.settings(), password)) {
            client.setImportant(ref.folderPath(), ref.uid(), important);
        } catch (MessagingException e) {
            store.setImportant(messageId, !important);
            throw new IllegalStateException(Problems.explain(e, account.settings().imapHost(), account.settings().imapPort()), e);
        }
    }

    /**
     * Moves a message to the account's archive folder on the server, then drops it from the cache and syncs so it shows up there.
     * Waits for the server (so a failure can be reported and nothing is lost silently); the page hides the row meanwhile.
     *
     * If the account has no archive folder, an {@value #ARCHIVE_FOLDER} folder is created on the server first.
     *
     * @throws IllegalStateException with a sentence for the user if the server refuses
     */
    public void archive(long messageId) {
        moveToRole(messageId, "archive", ARCHIVE_FOLDER, "archive");
    }

    /**
     * Moves a message to the account's trash folder, the same way as {@link #archive}. If the server has none, a {@value #TRASH_FOLDER}
     * folder is created first.
     */
    public void trash(long messageId) {
        moveToRole(messageId, "trash", TRASH_FOLDER, "delete");
    }

    /** Takes a message out of the trash (or any folder) and puts it back in the inbox; the server does not remember where it came from. */
    public void restore(long messageId) {
        moveToRole(messageId, "inbox", "INBOX", "restore");
    }

    /**
     * Moves a message to a folder of its own account, on the server and in the cache. Waits for the server, like {@link #archive}.
     *
     * @param folderId a standard folder's id ("inbox", "archive", ...) or the id of one of the account's own folders ("f12")
     * @throws IllegalArgumentException if the folder is not one of this account's
     * @throws IllegalStateException    with a sentence for the user if the server refuses
     */
    public void moveToFolder(long messageId, String folderId) {
        moveAll(List.of(messageId), "move the message", accountId -> new Destination(folderRow(accountId, folderId).path(), false));
    }

    /**
     * Creates a folder on the server, inside another one or at the top level, and adds it to the cache so it can be used at once.
     *
     * @param parentFolderId the folder to create it in (an id as for {@link #moveToFolder}), or null for the top level
     * @return the new folder, as the sidebar shows it
     * @throws IllegalArgumentException with a sentence for the user if the name is not usable or the folder exists
     * @throws IllegalStateException    with a sentence for the user if the server refuses
     */
    public Mail.Folder createFolder(long accountId, String parentFolderId, String name) {
        String clean = folderName(name);
        AccountRow account = store.accountRow(accountId).orElseThrow(() -> new IllegalArgumentException("Choose an account first"));
        String parentPath = parentFolderId == null || parentFolderId.isBlank() ? null : folderRow(accountId, parentFolderId).path();
        String password = credentials.get(AccountService.key(account.settings().email()))
                .orElseThrow(() -> new IllegalStateException("There is no saved password for this account"));
        String path;
        try (ImapClient client = connector.connect(account.settings(), password)) {
            path = client.createFolder(parentPath, clean);
            storeRemoteFolders(account, client);
        } catch (MessagingException e) {
            throw new IllegalStateException("Could not create the folder: " + Problems.explain(e, account.settings().imapHost(), account.settings().imapPort()), e);
        }
        FolderRow created = store.folderRows(accountId).stream().filter(f -> f.path().equals(path)).findFirst()
                .orElseThrow(() -> new IllegalStateException("The folder was created but the server did not list it"));
        return store.folders(String.valueOf(accountId)).stream().filter(f -> f.id().equals("f" + created.id())).findFirst()
                .orElse(new Mail.Folder("f" + created.id(), created.name(), 0));
    }

    /** A folder name the user typed, made safe to send to a server. */
    static String folderName(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Give the folder a name");
        }
        if (clean.length() > 100) {
            throw new IllegalArgumentException("A folder name can be at most 100 characters");
        }
        if (clean.chars().anyMatch(c -> c < 32 || c == 127 || "/\\*%#".indexOf(c) >= 0)) {
            throw new IllegalArgumentException("A folder name cannot contain / \\ * % # or control characters");
        }
        if (clean.equalsIgnoreCase("INBOX") || clean.equals(".") || clean.equals("..")) {
            throw new IllegalArgumentException("\"" + clean + "\" cannot be used as a folder name");
        }
        return clean;
    }

    /** The account's folder with this id: a standard role ("inbox") or "f" and the row id. */
    private FolderRow folderRow(long accountId, String folderId) {
        List<FolderRow> rows = store.folderRows(accountId);
        if (folderId.matches("f\\d+")) {
            long id = Long.parseLong(folderId.substring(1));
            return rows.stream().filter(f -> f.id() == id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That folder no longer exists"));
        }
        return rows.stream().filter(f -> folderId.equals(f.role())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This account has no folder like that"));
    }

    private void moveToRole(long messageId, String role, String createName, String verb) {
        moveAll(List.of(messageId), verb, accountId -> roleDestination(accountId, role, createName));
    }

    /** Where a standard folder is on this account; with none on the server, the usual one is made first, as other mail clients do. */
    private Destination roleDestination(long accountId, String role, String createName) {
        FolderRow existing = store.folderRows(accountId).stream().filter(f -> role.equals(f.role())).findFirst().orElse(null);
        return existing != null ? new Destination(existing.path(), false) : new Destination(createName, true);
    }

    /** @param create whether the folder has to be made on the server first */
    private record Destination(String path, boolean create) {}

    /**
     * Moves several messages in one go: archive, trash, restore or a folder of the account. One connection and one sync per account,
     * however many messages, instead of a connection and a full sync for each (which made deleting a handful of mails slow).
     * Waits for the server. A message that is already gone or already in the destination is skipped; if some could not be moved the
     * others still are, and the exception says how many failed and why.
     *
     * @param action   {@code archive}, {@code trash}, {@code restore} or {@code move}
     * @param folderId for {@code move}: a standard folder's id or one of the account's own ("f12"); ignored otherwise
     */
    public void moveMany(List<Long> messageIds, String action, String folderId) {
        switch (action) {
            case "archive" -> moveAll(messageIds, "archive", accountId -> roleDestination(accountId, "archive", ARCHIVE_FOLDER));
            case "trash" -> moveAll(messageIds, "delete", accountId -> roleDestination(accountId, "trash", TRASH_FOLDER));
            case "restore" -> moveAll(messageIds, "restore", accountId -> roleDestination(accountId, "inbox", "INBOX"));
            case "move" -> moveAll(messageIds, "move the message", accountId -> new Destination(folderRow(accountId, folderId).path(), false));
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }

    private void moveAll(List<Long> messageIds, String verb, java.util.function.LongFunction<Destination> destinationFor) {
        record Item(long id, MailStore.MessageRef ref) {}
        Map<Long, List<Item>> byAccount = new java.util.LinkedHashMap<>();
        for (long id : messageIds) {
            store.messageRef(id).ifPresent(ref -> byAccount.computeIfAbsent(ref.accountId(), a -> new ArrayList<>()).add(new Item(id, ref)));
        }
        if (byAccount.isEmpty()) {
            throw new IllegalArgumentException("No such message");
        }
        int total = 0;
        int failed = 0;
        String firstProblem = null;
        for (Map.Entry<Long, List<Item>> entry : byAccount.entrySet()) {
            long accountId = entry.getKey();
            List<Item> items = entry.getValue();
            total += items.size();
            AccountRow account = store.accountRow(accountId).orElseThrow(() -> new IllegalStateException("The account of this message was removed"));
            Destination destination = destinationFor.apply(accountId);
            List<Item> todo = items.stream().filter(i -> !i.ref().folderPath().equals(destination.path())).toList();
            if (todo.isEmpty()) {
                continue; // already there
            }
            String password = credentials.get(AccountService.key(account.settings().email()))
                    .orElseThrow(() -> new IllegalStateException("There is no saved password for this account"));
            int moved = 0;
            int accountFailed = 0;
            boolean refreshed = false;
            try (ImapClient client = connector.connect(account.settings(), password)) {
                if (destination.create()) {
                    client.createFolder(destination.path());
                }
                for (Item item : todo) {
                    try {
                        client.move(item.ref().folderPath(), item.ref().uid(), destination.path());
                        store.deleteMessage(item.id());
                        moved++;
                    } catch (MessagingException e) {
                        failed++;
                        accountFailed++;
                        firstProblem = firstProblem != null ? firstProblem
                                : Problems.explain(e, account.settings().imapHost(), account.settings().imapPort());
                    }
                }
                if (moved > 0) {
                    // Read the destination folder now, over this connection, so the messages are already in it when the page looks:
                    // waiting for a whole-account sync left them in neither folder for a few seconds.
                    refreshed = refreshFolder(account, client, destination);
                }
            } catch (MessagingException e) {
                failed += todo.size() - moved - accountFailed; // the connection or the folder failed before the rest could be tried
                firstProblem = firstProblem != null ? firstProblem
                        : Problems.explain(e, account.settings().imapHost(), account.settings().imapPort());
            }
            if (moved > 0 && !refreshed) {
                syncAsync(accountId); // could not read the folder just now (a sync was running, or the server balked): the full sync will
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("Could not " + verb + (total > 1 ? " " + failed + " of " + total + " messages" : "") + ": " + firstProblem);
        }
    }

    /**
     * Brings one folder of the cache up to date over an open connection (a folder list refresh first if the folder was just made).
     * Does not wait for a sync that is already running on the account, because that sync may be long and may not reach this folder
     * for a while; it then returns false and the caller starts a normal sync instead.
     */
    private boolean refreshFolder(AccountRow account, ImapClient client, Destination destination) {
        ReentrantLock lock = locks.computeIfAbsent(account.id(), id -> new ReentrantLock());
        if (!lock.tryLock()) {
            return false;
        }
        try {
            if (destination.create()) {
                storeRemoteFolders(account, client);
            }
            FolderRow row = store.folderRows(account.id()).stream().filter(f -> f.path().equals(destination.path())).findFirst().orElse(null);
            if (row == null) {
                return false;
            }
            syncFolder(client, row); // what arrives here is moved mail, not new mail: nothing is announced
            onMailChanged.accept(String.valueOf(account.id()));
            return true;
        } catch (MessagingException | RuntimeException e) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        timer.shutdownNow();
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
