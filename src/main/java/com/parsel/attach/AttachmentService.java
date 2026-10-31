package com.parsel.attach;

import com.parsel.account.AccountException;
import com.parsel.account.AccountService;
import com.parsel.account.AccountSettings;
import com.parsel.account.Problems;
import com.parsel.credentials.CredentialBroker;
import com.parsel.imap.ImapClient;
import com.parsel.mail.Mail;
import com.parsel.send.MailSender.Attached;
import com.parsel.store.MailStore;
import com.parsel.sync.SyncService;
import jakarta.mail.MessagingException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Attachments in both directions. The operating system's file dialogs give Java real paths, so the bytes never pass through the
 * page: a download goes from the server to the file the user chose, and a file to attach is read from disk only when the message is sent.
 * The page only ever sees names, sizes and the ids Java made up for the files that were picked.
 */
public final class AttachmentService {

    /** Opens the system's file dialogs. Blocks until the user answers. */
    public interface FileChooser {
        /** The file to save to, or empty if the user cancelled. */
        Optional<Path> save(String suggestedName);

        /** The files to attach; empty if the user cancelled. */
        List<Path> open();
    }

    /** A file the user picked to attach. */
    public record Picked(String id, String name, long bytes) {}

    /** @param saved false if the user cancelled the dialog */
    public record Saved(boolean saved, String name) {}

    /** What mail servers commonly accept: Gmail and Outlook stop at about 25 MB for the whole message. */
    public static final long MAX_TOTAL_BYTES = 25L * 1024 * 1024;

    private final MailStore store;
    private final CredentialBroker credentials;
    private final SyncService.Connector connector;
    private final FileChooser chooser;
    private final Map<String, Path> picked;

    public AttachmentService(MailStore store, CredentialBroker credentials, SyncService.Connector connector, FileChooser chooser) {
        this(store, credentials, connector, chooser, new ConcurrentHashMap<>());
    }

    private AttachmentService(MailStore store, CredentialBroker credentials, SyncService.Connector connector, FileChooser chooser,
                              Map<String, Path> picked) {
        this.store = store;
        this.credentials = credentials;
        this.connector = connector;
        this.chooser = chooser;
        this.picked = picked;
    }

    /**
     * The same service with the dialogs of another window. The files picked so far are shared, so a message written in one window
     * can be moved to another (a composer opened in its own window) and still send what was attached.
     */
    public AttachmentService forChooser(FileChooser other) {
        return new AttachmentService(store, credentials, connector, other, picked);
    }

    // ---- download -------------------------------------------------------------------------------------------------

    /** Asks where to save the attachment at {@code index} of a message, then downloads it from the server to there. */
    public Saved download(String messageId, int index) {
        long id = parseId(messageId);
        MailStore.MessageRef ref = store.messageRef(id).orElseThrow(() -> new IllegalArgumentException("No such message"));
        Mail.Detail detail = store.detail(id).orElseThrow(() -> new IllegalStateException("Open the message first"));
        if (index < 0 || index >= detail.attachments().size()) {
            throw new IllegalArgumentException("No such attachment");
        }
        String name = detail.attachments().get(index).name();
        Optional<Path> target = chooser.save(safeName(name));
        if (target.isEmpty()) {
            return new Saved(false, name);
        }
        var account = store.accountRow(ref.accountId()).orElseThrow(() -> new IllegalStateException("The account of this message was removed"));
        AccountSettings settings = account.settings();
        String password = credentials.get(AccountService.key(settings.email()))
                .orElseThrow(() -> new AccountException("There is no saved password for this account. Remove it and add it again."));
        try (ImapClient client = connector.connect(settings, password)) {
            client.saveAttachment(ref.folderPath(), ref.uid(), index, target.get());
        } catch (MessagingException | IOException e) {
            throw new AccountException("Could not download " + name + ": " + Problems.explain(e, settings.imapHost(), settings.imapPort()), e);
        }
        return new Saved(true, target.get().getFileName().toString());
    }

    /** A file name from someone else's mail, made safe to propose in a save dialog (no folders, no characters Windows refuses). */
    static String safeName(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip().replaceAll("[. ]+$", "");
        return clean.isEmpty() ? "attachment" : clean;
    }

    // ---- attaching ------------------------------------------------------------------------------------------------

    /** Lets the user pick files; returns what the page may show and the ids it sends back with the message. */
    public List<Picked> pick() {
        List<Picked> result = new ArrayList<>();
        for (Path path : chooser.open()) {
            long bytes;
            try {
                if (!Files.isRegularFile(path)) {
                    throw new AccountException(path.getFileName() + " is not a file.");
                }
                bytes = Files.size(path);
            } catch (IOException e) {
                throw new AccountException("Could not read " + path.getFileName() + ".", e);
            }
            if (bytes > MAX_TOTAL_BYTES) {
                throw new AccountException(path.getFileName() + " is " + size(bytes) + ". Mail servers accept about " + size(MAX_TOTAL_BYTES) + " at most.");
            }
            String id = UUID.randomUUID().toString();
            picked.put(id, path);
            result.add(new Picked(id, path.getFileName().toString(), bytes));
        }
        return result;
    }

    /** Reads the files behind the ids, at send time. */
    public List<Attached> load(List<String> ids) {
        List<Attached> result = new ArrayList<>();
        long total = 0;
        for (String id : ids) {
            Path path = picked.get(id);
            if (path == null) {
                throw new IllegalArgumentException("An attached file is no longer available. Remove it and attach it again.");
            }
            try {
                byte[] data = Files.readAllBytes(path);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new AccountException("The attachments are larger than " + size(MAX_TOTAL_BYTES) + " together. Mail servers refuse that much.");
                }
                String type = Files.probeContentType(path);
                result.add(new Attached(path.getFileName().toString(), type == null ? "application/octet-stream" : type, data));
            } catch (IOException e) {
                throw new AccountException("Could not read " + path.getFileName() + ". Has it been moved or deleted?", e);
            }
        }
        return result;
    }

    /** Forgets the files once the message is out. */
    public void release(List<String> ids) {
        ids.forEach(picked::remove);
    }

    private static String size(long bytes) {
        return bytes >= 1024 * 1024 ? Math.round(bytes / (1024.0 * 1024.0)) + " MB" : Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid id: " + id);
        }
    }
}
