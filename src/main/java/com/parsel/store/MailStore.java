package com.parsel.store;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.parsel.account.AccountSettings;
import com.parsel.account.Security;
import com.parsel.mail.Mail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and writes the local cache. Everything the UI shows comes from here; the sync service fills it.
 *
 * <p>Ids that leave this class are strings: an account is its row id ({@code "1"}), a message is its row id, a folder is
 * its role ({@code inbox}, {@code sent}, {@code drafts}, {@code archive}, {@code spam}, {@code trash}), the virtual
 * {@code starred}, or {@code f<rowid>} for any other folder.
 */
public final class MailStore {

    /** The standard folders in the order the sidebar shows them; {@code starred} is virtual (flagged messages of any folder). */
    public static final List<String> STANDARD = List.of("inbox", "starred", "sent", "drafts", "archive", "spam", "trash");
    private static final Set<String> ROLES = Set.of("inbox", "sent", "drafts", "archive", "spam", "trash");
    private static final int LIST_LIMIT = 1000;
    private static final Gson GSON = new Gson();

    /** An account as stored. */
    public record AccountRow(long id, AccountSettings settings, Long lastSync, String lastError) {}

    public record FolderRow(long id, long accountId, String path, String name, String role, Long uidValidity) {}

    /** A message header as read from the server. */
    public record HeaderRow(long uid, String fromName, String fromAddress, String to, String subject, long dateMillis,
                            boolean unread, boolean starred, boolean hasAttachments, boolean important) {

        public HeaderRow(long uid, String fromName, String fromAddress, String to, String subject, long dateMillis,
                         boolean unread, boolean starred, boolean hasAttachments) {
            this(uid, fromName, fromAddress, to, subject, dateMillis, unread, starred, hasAttachments, false);
        }
    }

    /** A cached message with what is needed to fetch its body from the server. */
    public record MessageRef(long id, long accountId, String folderPath, long uid, boolean bodyCached) {}

    private final Database db;

    public MailStore(Database db) {
        this.db = db;
    }

    // ---- accounts -------------------------------------------------------------------------------------------------

    public long addAccount(AccountSettings s) {
        String sql = "INSERT INTO accounts (name, email, username, imap_host, imap_port, imap_security, smtp_host, smtp_port, smtp_security, created_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, s.displayName());
            ps.setString(2, s.email().toLowerCase());
            ps.setString(3, s.username());
            ps.setString(4, s.imapHost());
            ps.setInt(5, s.imapPort());
            ps.setString(6, s.imapSecurity().name());
            ps.setString(7, s.smtpHost());
            ps.setInt(8, s.smtpPort());
            ps.setString(9, s.smtpSecurity().name());
            ps.setLong(10, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                throw new IllegalArgumentException("This account has already been added: " + s.email());
            }
            throw failure(e);
        }
    }

    /** Removes the account with its folders and messages. @return its email address, or empty if there was no such account */
    public Optional<String> removeAccount(long id) {
        try (Connection c = db.open()) {
            Optional<String> email = Optional.empty();
            try (PreparedStatement ps = c.prepareStatement("SELECT email FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        email = Optional.of(rs.getString(1));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return email;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public List<AccountRow> accountRows() {
        String sql = "SELECT id, name, email, username, imap_host, imap_port, imap_security, smtp_host, smtp_port, smtp_security, last_sync, last_error"
                + " FROM accounts ORDER BY id";
        List<AccountRow> rows = new ArrayList<>();
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                AccountSettings settings = new AccountSettings(rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), Security.valueOf(rs.getString(7)),
                        rs.getString(8), rs.getInt(9), Security.valueOf(rs.getString(10)));
                long lastSync = rs.getLong(11);
                Long last = rs.wasNull() ? null : lastSync;
                rows.add(new AccountRow(rs.getLong(1), settings, last, rs.getString(12)));
            }
            return rows;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public Optional<AccountRow> accountRow(long id) {
        return accountRows().stream().filter(a -> a.id() == id).findFirst();
    }

    public List<Mail.Account> accounts() {
        return accountRows().stream()
                .map(a -> new Mail.Account(String.valueOf(a.id()), a.settings().displayName(), a.settings().email()))
                .toList();
    }

    public void recordSync(long accountId, Long lastSync, String error) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET last_sync = COALESCE(?, last_sync), last_error = ? WHERE id = ?")) {
            if (lastSync == null) {
                ps.setNull(1, java.sql.Types.INTEGER);
            } else {
                ps.setLong(1, lastSync);
            }
            ps.setString(2, error);
            ps.setLong(3, accountId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ---- folders --------------------------------------------------------------------------------------------------

    public List<FolderRow> folderRows(long accountId) {
        List<FolderRow> rows = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT id, account_id, path, name, role, uid_validity FROM folders WHERE account_id = ? ORDER BY id")) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long validity = rs.getLong(6);
                    rows.add(new FolderRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.wasNull() ? null : validity));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Makes the stored folders of the account match {@code remote} (path, name, role): adds, renames, and drops the missing ones with their mail. */
    public void replaceFolders(long accountId, List<FolderRow> remote) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            Map<String, Long> existing = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT path, id FROM folders WHERE account_id = ?")) {
                ps.setLong(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existing.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            for (FolderRow folder : remote) {
                if (existing.remove(folder.path()) != null) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE folders SET name = ?, role = ? WHERE account_id = ? AND path = ?")) {
                        ps.setString(1, folder.name());
                        ps.setString(2, folder.role());
                        ps.setLong(3, accountId);
                        ps.setString(4, folder.path());
                        ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO folders (account_id, path, name, role) VALUES (?,?,?,?)")) {
                        ps.setLong(1, accountId);
                        ps.setString(2, folder.path());
                        ps.setString(3, folder.name());
                        ps.setString(4, folder.role());
                        ps.executeUpdate();
                    }
                }
            }
            for (long gone : existing.values()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM folders WHERE id = ?")) {
                    ps.setLong(1, gone);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Forgets every cached message of the folder and records its new UIDVALIDITY (the server renumbered it). */
    public void resetFolder(long folderId, long uidValidity) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE folder_id = ?")) {
                ps.setLong(1, folderId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE folders SET uid_validity = ? WHERE id = ?")) {
                ps.setLong(1, uidValidity);
                ps.setLong(2, folderId);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ---- message headers (sync) -----------------------------------------------------------------------------------

    public long maxUid(long folderId) {
        return scalarLong("SELECT COALESCE(MAX(uid), 0) FROM messages WHERE folder_id = ?", folderId);
    }

    public long messageCount(long folderId) {
        return scalarLong("SELECT COUNT(*) FROM messages WHERE folder_id = ?", folderId);
    }

    /** The UIDs of the newest {@code limit} cached messages, newest first. */
    public long[] recentUids(long folderId, int limit) {
        List<Long> uids = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT uid FROM messages WHERE folder_id = ? ORDER BY uid DESC LIMIT ?")) {
            ps.setLong(1, folderId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw failure(e);
        }
        return uids.stream().mapToLong(Long::longValue).toArray();
    }

    /** Inserts new headers; a header that is already cached only has its flags refreshed (the body is kept). */
    public void upsertHeaders(long folderId, List<HeaderRow> headers) {
        String sql = "INSERT INTO messages (folder_id, uid, from_name, from_addr, to_addrs, subject, date_ms, unread, starred, has_attachments, important)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (folder_id, uid) DO UPDATE SET unread = excluded.unread, starred = excluded.starred, important = excluded.important";
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (HeaderRow h : headers) {
                    ps.setLong(1, folderId);
                    ps.setLong(2, h.uid());
                    ps.setString(3, h.fromName());
                    ps.setString(4, h.fromAddress());
                    ps.setString(5, h.to());
                    ps.setString(6, h.subject());
                    ps.setLong(7, h.dateMillis());
                    ps.setInt(8, h.unread() ? 1 : 0);
                    ps.setInt(9, h.starred() ? 1 : 0);
                    ps.setInt(10, h.hasAttachments() ? 1 : 0);
                    ps.setInt(11, h.important() ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void updateFlags(long folderId, long uid, boolean unread, boolean starred) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE messages SET unread = ?, starred = ? WHERE folder_id = ? AND uid = ?")) {
            ps.setInt(1, unread ? 1 : 0);
            ps.setInt(2, starred ? 1 : 0);
            ps.setLong(3, folderId);
            ps.setLong(4, uid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** The flags of one message as the server has them. */
    public record Flags(boolean unread, boolean starred, boolean important) {}

    /** Applies the server's flags to many cached messages in one transaction. */
    public void applyFlags(long folderId, Map<Long, Flags> flags) {
        if (flags.isEmpty()) {
            return;
        }
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE messages SET unread = ?, starred = ?, important = ? WHERE folder_id = ? AND uid = ? AND (unread <> ? OR starred <> ? OR important <> ?)")) {
                for (Map.Entry<Long, Flags> entry : flags.entrySet()) {
                    int unread = entry.getValue().unread() ? 1 : 0;
                    int starred = entry.getValue().starred() ? 1 : 0;
                    int important = entry.getValue().important() ? 1 : 0;
                    ps.setInt(1, unread);
                    ps.setInt(2, starred);
                    ps.setInt(3, important);
                    ps.setLong(4, folderId);
                    ps.setLong(5, entry.getKey());
                    ps.setInt(6, unread);
                    ps.setInt(7, starred);
                    ps.setInt(8, important);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** The id of the cached message with this UID in the folder, if there is one. */
    public Optional<Long> messageId(long folderId, long uid) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT id FROM messages WHERE folder_id = ? AND uid = ?")) {
            ps.setLong(1, folderId);
            ps.setLong(2, uid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void deleteUids(long folderId, long[] uids) {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE folder_id = ? AND uid = ?")) {
                for (long uid : uids) {
                    ps.setLong(1, folderId);
                    ps.setLong(2, uid);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** UIDs of the newest messages in the folder that have no cached body yet, newest first. */
    public long[] uidsWithoutBody(long folderId, int limit) {
        List<Long> uids = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT uid FROM messages WHERE folder_id = ? AND body_html IS NULL ORDER BY date_ms DESC LIMIT ?")) {
            ps.setLong(1, folderId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    uids.add(rs.getLong(1));
                }
            }
        } catch (SQLException e) {
            throw failure(e);
        }
        return uids.stream().mapToLong(Long::longValue).toArray();
    }

    // ---- what the UI reads ----------------------------------------------------------------------------------------

    /** The sidebar: the standard folders that exist (the inbox always), then the account's own folders when one account is shown. */
    public List<Mail.Folder> folders(String accountId) {
        boolean all = accountId.equals("all");
        Map<String, Integer> unreadByRole = new LinkedHashMap<>();
        List<Mail.Folder> custom = new ArrayList<>();
        String sql = "SELECT f.id, f.name, f.role, COALESCE(SUM(m.unread), 0), f.path FROM folders f LEFT JOIN messages m ON m.folder_id = f.id"
                + (all ? "" : " WHERE f.account_id = ?") + " GROUP BY f.id ORDER BY f.path COLLATE NOCASE";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (!all) {
                ps.setLong(1, Long.parseLong(accountId));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String role = rs.getString(3);
                    int unread = rs.getInt(4);
                    if (role != null) {
                        unreadByRole.merge(role, unread, Integer::sum);
                    } else if (!all) {
                        custom.add(new Mail.Folder("f" + rs.getLong(1), rs.getString(2), unread, depthOf(rs.getString(5), rs.getString(2))));
                    }
                }
            }
        } catch (SQLException e) {
            throw failure(e);
        }
        List<Mail.Folder> result = new ArrayList<>();
        for (String role : STANDARD) {
            boolean present = role.equals("inbox") || role.equals("starred") || unreadByRole.containsKey(role);
            if (present) {
                int unread = role.equals("starred") ? 0 : unreadByRole.getOrDefault(role, 0);
                result.add(new Mail.Folder(role, label(role), unread));
            }
        }
        // A server that keeps every folder under "INBOX." would show them all indented; only the nesting among them counts.
        int shallowest = custom.stream().mapToInt(Mail.Folder::depth).min().orElse(0);
        for (Mail.Folder folder : custom) {
            result.add(new Mail.Folder(folder.id(), folder.name(), folder.unread(), folder.depth() - shallowest));
        }
        return result;
    }

    /** How many folders a path sits inside: the separator is the character in front of the folder's own name. */
    static int depthOf(String path, String name) {
        if (path.length() <= name.length() || !path.endsWith(name)) {
            return 0;
        }
        char separator = path.charAt(path.length() - name.length() - 1);
        int depth = 0;
        for (int i = 0; i < path.length() - name.length(); i++) {
            if (path.charAt(i) == separator) {
                depth++;
            }
        }
        return depth;
    }

    public List<Mail.Summary> list(String accountId, String folderKey) {
        boolean all = accountId.equals("all");
        String where;
        if (folderKey.equals("starred")) {
            where = "m.starred = 1";
        } else if (ROLES.contains(folderKey)) {
            where = "f.role = ?";
        } else if (folderKey.matches("f\\d+")) {
            where = "f.id = ?";
        } else {
            throw new IllegalArgumentException("Unknown folder: " + folderKey);
        }
        String sql = "SELECT m.id, f.account_id, m.from_name, m.from_addr, m.subject, m.snippet, m.date_ms, m.unread, m.starred, m.has_attachments, m.important"
                + " FROM messages m JOIN folders f ON f.id = m.folder_id WHERE " + where
                + (all ? "" : " AND f.account_id = ?") + " ORDER BY m.date_ms DESC LIMIT " + LIST_LIMIT;
        List<Mail.Summary> rows = new ArrayList<>();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (ROLES.contains(folderKey)) {
                ps.setString(i++, folderKey);
            } else if (!folderKey.equals("starred")) {
                ps.setLong(i++, Long.parseLong(folderKey.substring(1)));
            }
            if (!all) {
                ps.setLong(i, Long.parseLong(accountId));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(summary(rs, 11));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public Optional<Mail.Detail> detail(long messageId) {
        String sql = "SELECT m.id, f.account_id, m.from_name, m.from_addr, m.subject, m.snippet, m.date_ms, m.unread, m.starred, m.has_attachments,"
                + " m.to_addrs, m.body_html, m.has_remote, m.attachments, m.important"
                + " FROM messages m JOIN folders f ON f.id = m.folder_id WHERE m.id = ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String body = rs.getString(12);
                if (body == null) {
                    return Optional.empty(); // cached header, no body yet: the caller fetches it
                }
                List<String> to = rs.getString(11).isBlank() ? List.of() : List.of(rs.getString(11).split(", "));
                List<Mail.Attachment> attachments = parseAttachments(rs.getString(14));
                return Optional.of(new Mail.Detail(summary(rs, 15), to, body, rs.getInt(13) == 1, attachments));
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public Optional<MessageRef> messageRef(long messageId) {
        String sql = "SELECT m.id, f.account_id, f.path, m.uid, m.body_html IS NOT NULL FROM messages m JOIN folders f ON f.id = m.folder_id WHERE m.id = ?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new MessageRef(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getInt(5) == 1))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public Optional<FolderRow> folderByPath(long accountId, String path) {
        return folderRows(accountId).stream().filter(f -> f.path().equals(path)).findFirst();
    }

    public void setBody(long messageId, String html, String snippet, boolean hasRemote, List<Mail.Attachment> attachments) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE messages SET body_html = ?, snippet = ?, has_remote = ?, attachments = ?, has_attachments = ? WHERE id = ?")) {
            ps.setString(1, html);
            ps.setString(2, snippet);
            ps.setInt(3, hasRemote ? 1 : 0);
            ps.setString(4, GSON.toJson(attachments));
            ps.setInt(5, attachments.isEmpty() ? 0 : 1);
            ps.setLong(6, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void setUnread(long messageId, boolean unread) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("UPDATE messages SET unread = ? WHERE id = ?")) {
            ps.setInt(1, unread ? 1 : 0);
            ps.setLong(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void setStarred(long messageId, boolean starred) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("UPDATE messages SET starred = ? WHERE id = ?")) {
            ps.setInt(1, starred ? 1 : 0);
            ps.setLong(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void setImportant(long messageId, boolean important) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("UPDATE messages SET important = ? WHERE id = ?")) {
            ps.setInt(1, important ? 1 : 0);
            ps.setLong(2, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    /** Drops one message from the cache (the server moved or deleted it). */
    public void deleteMessage(long messageId) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM messages WHERE id = ?")) {
            ps.setLong(1, messageId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** @param importantColumn where the query put {@code m.important}, after the nine columns every query starts with */
    private static Mail.Summary summary(ResultSet rs, int importantColumn) throws SQLException {
        return new Mail.Summary(String.valueOf(rs.getLong(1)), String.valueOf(rs.getLong(2)), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getInt(8) == 1, rs.getInt(9) == 1, 0, rs.getInt(10) == 1,
                rs.getInt(importantColumn) == 1);
    }

    private static List<Mail.Attachment> parseAttachments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Mail.Attachment> parsed = GSON.fromJson(json, new TypeToken<List<Mail.Attachment>>() {}.getType());
        return parsed == null ? List.of() : parsed;
    }

    private static String label(String role) {
        return Character.toUpperCase(role.charAt(0)) + role.substring(1);
    }

    private long scalarLong(String sql, long arg) {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static IllegalStateException failure(SQLException e) {
        return new IllegalStateException("The mail database failed: " + e.getMessage(), e);
    }
}
