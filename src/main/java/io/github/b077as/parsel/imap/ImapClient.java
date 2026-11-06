package io.github.b077as.parsel.imap;

import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.store.MailStore.HeaderRow;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.IMAPFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * One IMAP connection to one mailbox. It offers the few operations the sync needs and nothing else; the algorithm that
 * decides what to fetch lives in {@code SyncService}. Each call selects the folder it needs, read-only, so a client is
 * not tied to a "current folder". Passwords are used to log in and not kept.
 */
public final class ImapClient implements AutoCloseable {

    /** A folder on the server. {@code role} is one of inbox, sent, drafts, archive, spam, trash, or null for the user's own. */
    public record RemoteFolder(String path, String name, String role) {}

    public record RemoteFlags(boolean unread, boolean starred, boolean important) {}

    /** The headers a reply needs and the cache does not keep. Lists hold plain addresses. */
    public record ReplyHeaders(String messageId, String references, String replyTo, List<String> to, List<String> cc) {}

    private static final String CONNECT_TIMEOUT_MS = "15000";
    private static final String IO_TIMEOUT_MS = "30000";

    private final Store store;

    private ImapClient(Store store) {
        this.store = store;
    }

    /** @throws MessagingException if the server cannot be reached or refuses the login (an AuthenticationFailedException for a bad password) */
    public static ImapClient connect(AccountSettings settings, String password) throws MessagingException {
        String protocol = settings.imapSecurity() == Security.SSL ? "imaps" : "imap";
        String prefix = "mail." + protocol + ".";
        Properties props = new Properties();
        props.put(prefix + "host", settings.imapHost());
        props.put(prefix + "port", String.valueOf(settings.imapPort()));
        props.put(prefix + "connectiontimeout", CONNECT_TIMEOUT_MS);
        props.put(prefix + "timeout", IO_TIMEOUT_MS);
        props.put(prefix + "writetimeout", IO_TIMEOUT_MS);
        // Reading a message must not mark it read: that is the user's decision, made explicitly by the UI.
        props.put(prefix + "peek", "true");
        switch (settings.imapSecurity()) {
            case SSL -> {
                props.put(prefix + "ssl.enable", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
                props.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            case STARTTLS -> {
                props.put(prefix + "starttls.enable", "true");
                props.put(prefix + "starttls.required", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
                props.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            case NONE -> { /* localhost only, enforced by AccountSettings.validate() */ }
        }
        Store store = Session.getInstance(props).getStore(protocol);
        store.connect(settings.imapHost(), settings.imapPort(), settings.username(), password);
        return new ImapClient(store);
    }

    @Override
    public void close() {
        try {
            store.close();
        } catch (MessagingException ignored) {
            // nothing useful to do with a failed goodbye
        }
    }

    // ---- folders --------------------------------------------------------------------------------------------------

    /** Every folder that can hold messages, with its role worked out from the server's SPECIAL-USE flags or, failing that, its name. */
    public List<RemoteFolder> folders() throws MessagingException {
        List<RemoteFolder> result = new ArrayList<>();
        Set<String> rolesTaken = new HashSet<>();
        Folder[] all = store.getDefaultFolder().list("*");
        // Roles from SPECIAL-USE flags first, so that a folder that is only named "Sent" cannot take the role from the real one.
        Map<Folder, String> byFlag = new HashMap<>();
        for (Folder folder : all) {
            if (!holdsMessages(folder)) {
                continue;
            }
            String role = roleFromFlags(folder);
            if (role != null && rolesTaken.add(role)) {
                byFlag.put(folder, role);
            }
        }
        for (Folder folder : all) {
            if (!holdsMessages(folder) || isVirtual(folder)) {
                continue;
            }
            String role = byFlag.get(folder);
            if (role == null && folder.getFullName().equalsIgnoreCase("INBOX")) {
                role = "inbox";
                rolesTaken.add(role);
            }
            if (role == null && roleFromFlags(folder) == null) {
                String byName = roleFromName(folder.getName());
                if (byName != null && rolesTaken.add(byName)) {
                    role = byName;
                }
            }
            result.add(new RemoteFolder(folder.getFullName(), folder.getName(), role));
        }
        return result;
    }

    private static boolean holdsMessages(Folder folder) throws MessagingException {
        if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
            return false;
        }
        return Arrays.stream(attributes(folder)).noneMatch(a -> a.equalsIgnoreCase("\\Noselect") || a.equalsIgnoreCase("\\NonExistent"));
    }

    /** Some servers add folders for flagged or important mail that only show messages living elsewhere; the app has its own starred view. */
    private static boolean isVirtual(Folder folder) throws MessagingException {
        return Arrays.stream(attributes(folder)).anyMatch(a -> a.equalsIgnoreCase("\\Flagged") || a.equalsIgnoreCase("\\Important"));
    }

    private static String[] attributes(Folder folder) throws MessagingException {
        return folder instanceof IMAPFolder imap ? imap.getAttributes() : new String[0];
    }

    private static String roleFromFlags(Folder folder) throws MessagingException {
        for (String attribute : attributes(folder)) {
            switch (attribute.toLowerCase(Locale.ROOT)) {
                case "\\sent" -> { return "sent"; }
                case "\\drafts" -> { return "drafts"; }
                case "\\trash" -> { return "trash"; }
                case "\\junk" -> { return "spam"; }
                case "\\archive", "\\all" -> { return "archive"; }
                default -> { /* not a special-use flag */ }
            }
        }
        return null;
    }

    private static String roleFromName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "sent", "sent items", "sent mail", "sent messages" -> "sent";
            case "drafts", "draft" -> "drafts";
            case "trash", "deleted items", "deleted messages", "bin" -> "trash";
            case "junk", "spam", "junk email", "junk e-mail" -> "spam";
            case "archive", "archives", "all mail" -> "archive";
            default -> null;
        };
    }

    // ---- headers --------------------------------------------------------------------------------------------------

    public long uidValidity(String path) throws MessagingException {
        try (OpenFolder open = open(path)) {
            return open.folder.getUIDValidity();
        }
    }

    /** The newest {@code count} messages of the folder: the first look at a folder, with no history to build on. */
    public List<HeaderRow> fetchNewest(String path, int count) throws MessagingException {
        try (OpenFolder open = open(path)) {
            int total = open.folder.getMessageCount();
            if (total == 0) {
                return List.of();
            }
            return headers(open.folder, open.folder.getMessages(Math.max(1, total - count + 1), total));
        }
    }

    /** Messages with a UID above {@code afterUid}, at most the newest {@code max} of them. */
    public List<HeaderRow> fetchSince(String path, long afterUid, int max) throws MessagingException {
        try (OpenFolder open = open(path)) {
            Message[] found = open.folder.getMessagesByUID(afterUid + 1, UIDFolder.LASTUID);
            List<Message> fresh = new ArrayList<>();
            for (Message message : found) {
                // "n:*" always includes the last message, even when its UID is not above n
                if (message != null && open.folder.getUID(message) > afterUid) {
                    fresh.add(message);
                }
            }
            if (fresh.size() > max) {
                fresh = fresh.subList(fresh.size() - max, fresh.size());
            }
            return headers(open.folder, fresh.toArray(new Message[0]));
        }
    }

    /** The current flags of the given UIDs. A UID the server no longer has is missing from the result: that message was deleted. */
    public Map<Long, RemoteFlags> fetchFlags(String path, long[] uids) throws MessagingException {
        Map<Long, RemoteFlags> flags = new HashMap<>();
        if (uids.length == 0) {
            return flags;
        }
        try (OpenFolder open = open(path)) {
            Message[] messages = open.folder.getMessagesByUID(uids);
            List<Message> existing = new ArrayList<>();
            for (Message message : messages) {
                if (message != null) {
                    existing.add(message);
                }
            }
            FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.FLAGS);
            profile.add(UIDFolder.FetchProfileItem.UID);
            open.folder.fetch(existing.toArray(new Message[0]), profile);
            for (Message message : existing) {
                flags.put(open.folder.getUID(message),
                        new RemoteFlags(!message.isSet(Flags.Flag.SEEN), message.isSet(Flags.Flag.FLAGGED), isImportant(message)));
            }
            return flags;
        }
    }

    private static List<HeaderRow> headers(IMAPFolder folder, Message[] messages) throws MessagingException {
        if (messages.length == 0) {
            return List.of();
        }
        FetchProfile profile = new FetchProfile();
        profile.add(FetchProfile.Item.ENVELOPE);
        profile.add(FetchProfile.Item.FLAGS);
        profile.add(FetchProfile.Item.CONTENT_INFO);
        profile.add(UIDFolder.FetchProfileItem.UID);
        folder.fetch(messages, profile);

        List<HeaderRow> rows = new ArrayList<>();
        for (Message message : messages) {
            jakarta.mail.Address[] from = message.getFrom();
            String name = "";
            String address = "";
            if (from != null && from.length > 0 && from[0] instanceof InternetAddress internet) {
                address = internet.getAddress() == null ? "" : internet.getAddress();
                name = internet.getPersonal() == null || internet.getPersonal().isBlank() ? address : internet.getPersonal();
            }
            String subject = message.getSubject() == null ? "" : message.getSubject();
            rows.add(new HeaderRow(folder.getUID(message), name, address, recipients(message), subject, dateOf(message),
                    !message.isSet(Flags.Flag.SEEN), message.isSet(Flags.Flag.FLAGGED), hasAttachment(message), isImportant(message)));
        }
        return rows;
    }

    /** The standard keyword for "important" (RFC 8457). */
    static final String IMPORTANT_KEYWORD = "$Important";

    private static boolean isImportant(Message message) throws MessagingException {
        return message.getFlags().contains(IMPORTANT_KEYWORD);
    }

    private static String recipients(Message message) throws MessagingException {
        jakarta.mail.Address[] to = message.getRecipients(Message.RecipientType.TO);
        if (to == null) {
            return "";
        }
        List<String> addresses = new ArrayList<>();
        for (jakarta.mail.Address a : to) {
            addresses.add(a instanceof InternetAddress internet && internet.getAddress() != null ? internet.getAddress() : a.toString());
        }
        return String.join(", ", addresses);
    }

    private static long dateOf(Message message) throws MessagingException {
        Date date = message.getSentDate();
        if (date == null) {
            date = message.getReceivedDate();
        }
        return date == null ? System.currentTimeMillis() : date.getTime();
    }

    /** Whether the message has a real attachment, judged from the BODYSTRUCTURE the server already sent (no body is downloaded). */
    private static boolean hasAttachment(Part part) {
        try {
            if (part.isMimeType("multipart/*")) {
                Multipart multipart = (Multipart) part.getContent();
                for (int i = 0; i < multipart.getCount(); i++) {
                    if (hasAttachment(multipart.getBodyPart(i))) {
                        return true;
                    }
                }
                return false;
            }
            return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                    || (part.getFileName() != null && !part.isMimeType("image/*") && !part.isMimeType("text/*"));
        } catch (MessagingException | IOException | RuntimeException e) {
            return false;
        }
    }

    // ---- bodies and flags -----------------------------------------------------------------------------------------

    /** Downloads one message and turns it into the reader's form. */
    public BodyParser.Parsed fetchBody(String path, long uid) throws MessagingException, IOException {
        try (OpenFolder open = open(path)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("The message is no longer on the server");
            }
            return BodyParser.parse(message);
        }
    }

    /**
     * Writes one attachment to {@code target}: to a temporary file next to it first, so a dropped connection leaves nothing half written.
     * {@code index} is the attachment's position in the list of the message's {@code Mail.Detail}.
     */
    public void saveAttachment(String path, long uid, int index, Path target) throws MessagingException, IOException {
        try (OpenFolder open = open(path)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("The message is no longer on the server");
            }
            Part part = BodyParser.attachmentPart(message, index);
            if (part == null) {
                throw new MessagingException("The attachment is no longer in the message");
            }
            Path temp = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = part.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    public void setSeen(String path, long uid, boolean seen) throws MessagingException {
        try (OpenFolder open = open(path, Folder.READ_WRITE)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message != null) {
                message.setFlag(Flags.Flag.SEEN, seen);
            }
        }
    }

    public void setFlagged(String path, long uid, boolean flagged) throws MessagingException {
        try (OpenFolder open = open(path, Folder.READ_WRITE)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message != null) {
                message.setFlag(Flags.Flag.FLAGGED, flagged);
            }
        }
    }

    /** Creates a top-level folder (and subscribes to it so other mail clients list it) unless it already exists. */
    public void createFolder(String name) throws MessagingException {
        Folder folder = store.getFolder(name);
        if (!folder.exists() && !folder.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("The server would not create the folder " + name);
        }
        try {
            folder.setSubscribed(true);
        } catch (MessagingException ignored) {
            // some servers do not support subscriptions; the folder works without
        }
    }

    /**
     * Creates a folder inside {@code parentPath}, or at the top level when that is null, subscribes to it and returns its full path.
     *
     * @throws IllegalArgumentException with a sentence for the user if the name does not fit this server or the folder already exists
     * @throws MessagingException       if the parent is gone or the server refuses
     */
    public String createFolder(String parentPath, String name) throws MessagingException {
        Folder parent;
        if (parentPath == null) {
            parent = personalRoot();
        } else {
            parent = store.getFolder(parentPath);
            if (!parent.exists()) {
                throw new MessagingException("The folder " + parentPath + " no longer exists on the server");
            }
        }
        char separator = parent.getSeparator();
        if (separator != 0 && name.indexOf(separator) >= 0) {
            throw new IllegalArgumentException("A folder name cannot contain \"" + separator + "\" on this server");
        }
        Folder child = parent.getFolder(name);
        if (child.exists()) {
            throw new IllegalArgumentException("There is already a folder called \"" + name + "\" there");
        }
        if (!child.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("The server would not create the folder " + name);
        }
        try {
            child.setSubscribed(true);
        } catch (MessagingException ignored) {
            // some servers do not support subscriptions; the folder works without
        }
        return child.getFullName();
    }

    /** Where a user's own top-level folders go: the personal namespace (some servers want "INBOX." in front), else the root. */
    private Folder personalRoot() throws MessagingException {
        try {
            Folder[] namespaces = store.getPersonalNamespaces();
            if (namespaces.length > 0) {
                return namespaces[0];
            }
        } catch (MessagingException | RuntimeException ignored) {
            // a server without the NAMESPACE command: the root is right
        }
        return store.getDefaultFolder();
    }

    /**
     * Sets or clears the {@code $Important} keyword.
     *
     * @throws MessagingException if the server does not let clients add their own flags to messages in this folder
     */
    public void setImportant(String path, long uid, boolean important) throws MessagingException {
        try (OpenFolder open = open(path, Folder.READ_WRITE)) {
            if (!open.folder.getPermanentFlags().contains(Flags.Flag.USER)) {
                throw new MessagingException("This server does not let mail clients add their own flags, so it cannot keep the Important mark.");
            }
            Message message = open.folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("The message is no longer on the server");
            }
            message.setFlags(new Flags(IMPORTANT_KEYWORD), important);
        }
    }

    /**
     * Moves one message to another folder of the same account. Uses the server's MOVE command when it has one; otherwise copies,
     * marks the original deleted and expunges just that message.
     *
     * @throws MessagingException if the message or the destination is gone, or the server refuses
     */
    public void move(String path, long uid, String destination) throws MessagingException {
        try (OpenFolder open = open(path, Folder.READ_WRITE)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("The message is no longer on the server");
            }
            Folder target = store.getFolder(destination);
            if (!target.exists()) {
                throw new MessagingException("The folder " + destination + " no longer exists on the server");
            }
            Message[] one = {message};
            if (store instanceof org.eclipse.angus.mail.imap.IMAPStore imap && imap.hasCapability("MOVE")) {
                open.folder.moveMessages(one, target);
            } else {
                open.folder.copyMessages(one, target);
                message.setFlag(Flags.Flag.DELETED, true);
                open.folder.expunge(one);
            }
        }
    }

    /** The Message-ID, References, Reply-To, To and Cc of one message, read from the server. */
    public ReplyHeaders fetchReplyHeaders(String path, long uid) throws MessagingException {
        try (OpenFolder open = open(path)) {
            Message message = open.folder.getMessageByUID(uid);
            if (message == null) {
                throw new MessagingException("The message is no longer on the server");
            }
            String[] id = message.getHeader("Message-ID");
            String[] references = message.getHeader("References");
            return new ReplyHeaders(id == null || id.length == 0 ? null : id[0].strip(),
                    references == null || references.length == 0 ? null : references[0].strip(),
                    firstAddress(message.getReplyTo()),
                    addresses(message.getRecipients(Message.RecipientType.TO)),
                    addresses(message.getRecipients(Message.RecipientType.CC)));
        }
    }

    private static String firstAddress(jakarta.mail.Address[] found) {
        List<String> all = addresses(found);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<String> addresses(jakarta.mail.Address[] found) {
        List<String> result = new ArrayList<>();
        if (found != null) {
            for (jakarta.mail.Address a : found) {
                result.add(a instanceof InternetAddress internet && internet.getAddress() != null ? internet.getAddress() : a.toString());
            }
        }
        return result;
    }

    /** Files a copy of a sent message (its raw bytes) in the folder, marked as read. */
    public void append(String path, byte[] raw) throws MessagingException {
        IMAPFolder folder = (IMAPFolder) store.getFolder(path);
        MimeMessage copy = new MimeMessage(Session.getInstance(new Properties()), new java.io.ByteArrayInputStream(raw));
        copy.setFlag(Flags.Flag.SEEN, true);
        folder.appendMessages(new Message[]{copy});
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    private static final class OpenFolder implements AutoCloseable {
        final IMAPFolder folder;

        OpenFolder(IMAPFolder folder) {
            this.folder = folder;
        }

        @Override
        public void close() {
            try {
                if (folder.isOpen()) {
                    folder.close(false);
                }
            } catch (MessagingException ignored) {
                // the connection is going away anyway
            }
        }
    }

    private OpenFolder open(String path) throws MessagingException {
        return open(path, Folder.READ_ONLY);
    }

    private OpenFolder open(String path, int mode) throws MessagingException {
        IMAPFolder folder = (IMAPFolder) store.getFolder(path);
        folder.open(mode);
        return new OpenFolder(folder);
    }
}
