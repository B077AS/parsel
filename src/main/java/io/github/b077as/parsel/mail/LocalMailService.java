package io.github.b077as.parsel.mail;

import io.github.b077as.parsel.store.MailStore;
import io.github.b077as.parsel.sync.SyncService;

import java.util.List;
import java.util.Optional;

/** What the UI reads, answered from the local cache; a message whose body has not been downloaded yet is fetched on demand. */
public final class LocalMailService implements MailService {

    private final MailStore store;
    private final SyncService sync;

    public LocalMailService(MailStore store, SyncService sync) {
        this.store = store;
        this.sync = sync;
    }

    @Override
    public List<Mail.Account> accounts() {
        return store.accounts();
    }

    @Override
    public List<Mail.Folder> folders(String accountId) {
        return store.folders(accountId);
    }

    @Override
    public List<Mail.Summary> list(String accountId, String folderId) {
        return store.list(accountId, folderId);
    }

    @Override
    public Mail.Detail get(String messageId) {
        long id = parse(messageId);
        Optional<Mail.Detail> cached = store.detail(id);
        if (cached.isEmpty()) {
            sync.loadBody(id); // blocks this worker thread (not the UI) while the body downloads
            cached = store.detail(id);
        }
        return cached.orElseThrow(() -> new IllegalArgumentException("No such message: " + messageId));
    }

    @Override
    public void markRead(String messageId, boolean read) {
        sync.setRead(parse(messageId), read);
    }

    @Override
    public void setStarred(String messageId, boolean starred) {
        sync.setStarred(parse(messageId), starred);
    }

    @Override
    public void setImportant(String messageId, boolean important) {
        sync.setImportant(parse(messageId), important);
    }

    @Override
    public void archive(String messageId) {
        sync.archive(parse(messageId));
    }

    @Override
    public void moveTo(String messageId, String folderId) {
        sync.moveToFolder(parse(messageId), folderId);
    }

    @Override
    public Mail.Folder createFolder(String accountId, String parentFolderId, String name) {
        return sync.createFolder(parse(accountId), parentFolderId, name);
    }

    @Override
    public void trash(String messageId) {
        sync.trash(parse(messageId));
    }

    @Override
    public void moveMany(java.util.List<String> messageIds, String action, String folderId) {
        sync.moveMany(messageIds.stream().map(LocalMailService::parse).toList(), action, folderId);
    }

    @Override
    public void restore(String messageId) {
        sync.restore(parse(messageId));
    }

    private static long parse(String messageId) {
        try {
            return Long.parseLong(messageId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a message id: " + messageId);
        }
    }
}
