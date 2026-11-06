package io.github.b077as.parsel.mail;

import java.util.List;

/**
 * What the UI needs from the mail layer. {@link FakeMailService} implements it with invented data; the real one will
 * read the local SQLite cache that the IMAP sync fills.
 */
public interface MailService {

    List<Mail.Account> accounts();

    /** @param accountId an account id, or {@code all} for the combined view */
    List<Mail.Folder> folders(String accountId);

    List<Mail.Summary> list(String accountId, String folderId);

    Mail.Detail get(String messageId);

    void markRead(String messageId, boolean read);

    void setStarred(String messageId, boolean starred);

    /** Waits for the server; fails with a sentence for the user if it cannot keep the mark. */
    void setImportant(String messageId, boolean important);

    /** Moves the message to the account's archive folder. Waits for the server; fails with a sentence for the user if it cannot. */
    void archive(String messageId);

    /** Moves the message to the account's trash folder; same contract as {@link #archive}. */
    void trash(String messageId);

    /**
     * Moves the message to another folder of its account; same contract as {@link #archive}.
     *
     * @param folderId a standard folder ("inbox", "archive", ...) or one of the account's own ("f12")
     */
    void moveTo(String messageId, String folderId);

    /**
     * Creates a folder, inside another one or at the top level, and returns it.
     *
     * @param parentFolderId the folder to create it in, or null for the top level
     */
    Mail.Folder createFolder(String accountId, String parentFolderId, String name);

    /**
     * Moves several messages at once: {@code action} is archive, trash, restore or move (to {@code folderId}). Same contract as
     * {@link #archive}, but one connection to the server for the whole batch. The default does them one by one.
     */
    default void moveMany(java.util.List<String> messageIds, String action, String folderId) {
        for (String id : messageIds) {
            switch (action) {
                case "archive" -> archive(id);
                case "trash" -> trash(id);
                case "restore" -> restore(id);
                case "move" -> moveTo(id, folderId);
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
        }
    }

    /** Moves the message back to the inbox; same contract as {@link #archive}. */
    void restore(String messageId);
}
