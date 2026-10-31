package com.parsel.credentials;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.b077as.jolt.credentials.CredentialStore;
import io.github.b077as.jolt.credentials.CredentialStoreException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A {@link CredentialStore} kept in one encrypted file, for machines with no OS keystore (a Linux desktop without a Secret
 * Service). It is weaker than the OS keystore: anyone who gets the file can try passwords offline, so its strength is the
 * strength of the master password.
 *
 * <p>Only JDK cryptography: the key is derived from the password with PBKDF2-HMAC-SHA256, the contents are encrypted with
 * AES-256-GCM, and the header is authenticated as additional data.
 *
 * <pre>
 * file = "PARSELVT" | version(1) | kdf(1) | iterations(4) | salt(16) | nonce(12) | ciphertext+tag
 *        \_____________ header, 42 bytes, authenticated ______________/
 * </pre>
 *
 * <p>A fresh nonce is used for every write. The version and kdf bytes let a later release change the key derivation
 * (for example to Argon2id) and still read old vaults. The derived key stays in memory until {@link #close()}.
 */
public final class VaultCredentialStore implements CredentialStore, AutoCloseable {

    public static final int DEFAULT_ITERATIONS = 600_000;

    private static final byte[] MAGIC = "PARSELVT".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int KDF_PBKDF2_HMAC_SHA256 = 1;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int TAG_BITS = 128;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 1 + 4 + SALT_BYTES + NONCE_BYTES;
    private static final int MAX_ITERATIONS = 50_000_000; // a damaged header must not make us spin for hours

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final int iterations;
    private final byte[] salt;
    private byte[] key;
    private final Map<String, String> entries;

    private VaultCredentialStore(Path file, int iterations, byte[] salt, byte[] key, Map<String, String> entries) {
        this.file = file;
        this.iterations = iterations;
        this.salt = salt;
        this.key = key;
        this.entries = entries;
    }

    public static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    /** Creates a new, empty vault protected by {@code password}. The password array is not kept. */
    public static VaultCredentialStore create(Path file, char[] password) {
        return create(file, password, DEFAULT_ITERATIONS);
    }

    /** For tests, which cannot afford 600 000 iterations on every case. */
    static VaultCredentialStore create(Path file, char[] password, int iterations) {
        if (exists(file)) {
            throw new IllegalStateException("A vault already exists at " + file);
        }
        requirePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        VaultCredentialStore vault = new VaultCredentialStore(file, iterations, salt, deriveKey(password, salt, iterations), new TreeMap<>());
        vault.write();
        return vault;
    }

    /** Opens an existing vault. @throws WrongVaultPasswordException if the password is wrong or the file was changed */
    public static VaultCredentialStore open(Path file, char[] password) {
        requirePassword(password);
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new CredentialStoreException("Could not read the vault " + file + ": " + e.getMessage(), e);
        }
        if (data.length < HEADER_BYTES + TAG_BITS / 8 || !Arrays.equals(MAGIC, Arrays.copyOf(data, MAGIC.length))) {
            throw new CredentialStoreException("Not a Parsel vault: " + file);
        }
        ByteBuffer header = ByteBuffer.wrap(data, MAGIC.length, HEADER_BYTES - MAGIC.length);
        int version = header.get() & 0xff;
        int kdf = header.get() & 0xff;
        int iterations = header.getInt();
        if (version != VERSION || kdf != KDF_PBKDF2_HMAC_SHA256) {
            throw new CredentialStoreException("This vault was written by a newer Parsel (version " + version + ", kdf " + kdf + ")");
        }
        if (iterations < 1 || iterations > MAX_ITERATIONS) {
            throw new CredentialStoreException("The vault header is damaged");
        }
        byte[] salt = new byte[SALT_BYTES];
        header.get(salt);
        byte[] nonce = new byte[NONCE_BYTES];
        header.get(nonce);

        byte[] key = deriveKey(password, salt, iterations);
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce);
            cipher.updateAAD(data, 0, HEADER_BYTES);
            byte[] plain = cipher.doFinal(data, HEADER_BYTES, data.length - HEADER_BYTES);
            Map<String, String> entries = new TreeMap<>();
            Map<String, String> stored = GSON.fromJson(new String(plain, StandardCharsets.UTF_8), new TypeToken<Map<String, String>>() {}.getType());
            if (stored != null) {
                entries.putAll(stored);
            }
            Arrays.fill(plain, (byte) 0);
            return new VaultCredentialStore(file, iterations, salt, key, entries);
        } catch (AEADBadTagException e) {
            Arrays.fill(key, (byte) 0);
            throw new WrongVaultPasswordException();
        } catch (GeneralSecurityException e) {
            Arrays.fill(key, (byte) 0);
            throw new CredentialStoreException("Could not decrypt the vault: " + e, e);
        }
    }

    @Override
    public synchronized Optional<String> get(String account) {
        requireOpen();
        return Optional.ofNullable(entries.get(requireName(account)));
    }

    @Override
    public synchronized void put(String account, String secret) {
        requireOpen();
        requireName(account);
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must not be empty; use delete to remove one");
        }
        entries.put(account, secret);
        write();
    }

    @Override
    public synchronized boolean delete(String account) {
        requireOpen();
        boolean removed = entries.remove(requireName(account)) != null;
        if (removed) {
            write();
        }
        return removed;
    }

    /** Wipes the key from memory. The vault cannot be used afterwards; open it again with the password. */
    @Override
    public synchronized void close() {
        if (key != null) {
            Arrays.fill(key, (byte) 0);
            key = null;
        }
        entries.clear();
    }

    private void requireOpen() {
        if (key == null) {
            throw new IllegalStateException("The vault is closed");
        }
    }

    private static String requireName(String account) {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("account must not be blank");
        }
        return account;
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("The master password must not be empty");
        }
    }

    private void write() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        header.put(MAGIC).put((byte) VERSION).put((byte) KDF_PBKDF2_HMAC_SHA256).putInt(iterations).put(salt).put(nonce);
        byte[] plain = GSON.toJson(entries).getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce);
            cipher.updateAAD(header.array());
            byte[] encrypted = cipher.doFinal(plain);
            byte[] out = ByteBuffer.allocate(HEADER_BYTES + encrypted.length).put(header.array()).put(encrypted).array();
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temp, out);
            restrictToOwner(temp);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (GeneralSecurityException | IOException e) {
            throw new CredentialStoreException("Could not write the vault " + file + ": " + e, e);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows has no POSIX permissions; the file lives in the user's own profile folder there.
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    private static byte[] deriveKey(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new CredentialStoreException("This Java has no PBKDF2WithHmacSHA256: " + e, e);
        } finally {
            spec.clearPassword();
        }
    }
}
