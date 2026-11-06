package io.github.b077as.parsel.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The SQLite file that caches accounts, folders, message headers and bodies, so the UI never waits on the network.
 * Connections are opened per piece of work (cheap for SQLite); WAL mode lets a reader and the sync writer run together.
 * The schema is versioned with {@code PRAGMA user_version} and upgraded in order by {@link #migrate()}.
 */
public final class Database {

    private static final String[] MIGRATIONS = {
            // 1: accounts, folders, messages
            """
            CREATE TABLE accounts (
              id            INTEGER PRIMARY KEY,
              name          TEXT NOT NULL,
              email         TEXT NOT NULL UNIQUE,
              username      TEXT NOT NULL,
              imap_host     TEXT NOT NULL,
              imap_port     INTEGER NOT NULL,
              imap_security TEXT NOT NULL,
              smtp_host     TEXT NOT NULL,
              smtp_port     INTEGER NOT NULL,
              smtp_security TEXT NOT NULL,
              created_at    INTEGER NOT NULL,
              last_sync     INTEGER,
              last_error    TEXT
            );
            CREATE TABLE folders (
              id           INTEGER PRIMARY KEY,
              account_id   INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
              path         TEXT NOT NULL,
              name         TEXT NOT NULL,
              role         TEXT,
              uid_validity INTEGER,
              UNIQUE (account_id, path)
            );
            CREATE TABLE messages (
              id              INTEGER PRIMARY KEY,
              folder_id       INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
              uid             INTEGER NOT NULL,
              from_name       TEXT NOT NULL,
              from_addr       TEXT NOT NULL,
              to_addrs        TEXT NOT NULL,
              subject         TEXT NOT NULL,
              snippet         TEXT NOT NULL DEFAULT '',
              date_ms         INTEGER NOT NULL,
              unread          INTEGER NOT NULL,
              starred         INTEGER NOT NULL,
              has_attachments INTEGER NOT NULL,
              body_html       TEXT,
              has_remote      INTEGER NOT NULL DEFAULT 0,
              attachments     TEXT,
              UNIQUE (folder_id, uid)
            );
            CREATE INDEX messages_by_folder_date ON messages (folder_id, date_ms DESC);
            """,
            // 2: the Important mark (the IMAP keyword $Important)
            """
            ALTER TABLE messages ADD COLUMN important INTEGER NOT NULL DEFAULT 0;
            """,
    };

    private final String url;

    /** Opens (creating and upgrading if needed) the database file at {@code file}. */
    public Database(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the folder for " + file, e);
        }
        this.url = "jdbc:sqlite:" + file.toAbsolutePath();
        migrate();
    }

    /** A new connection with foreign keys on and a wait instead of an immediate "database is locked". */
    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void migrate() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            int current;
            try (var rs = statement.executeQuery("PRAGMA user_version")) {
                current = rs.next() ? rs.getInt(1) : 0;
            }
            if (current > MIGRATIONS.length) {
                throw new IllegalStateException("The mail database is from a newer Parsel (schema " + current + ")");
            }
            for (int version = current; version < MIGRATIONS.length; version++) {
                connection.setAutoCommit(false);
                try {
                    for (String sql : MIGRATIONS[version].split(";\\s*\\n")) {
                        if (!sql.isBlank()) {
                            statement.execute(sql);
                        }
                    }
                    statement.execute("PRAGMA user_version = " + (version + 1));
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not open the mail database: " + e.getMessage(), e);
        }
    }
}
