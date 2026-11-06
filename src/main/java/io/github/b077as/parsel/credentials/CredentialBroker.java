package io.github.b077as.parsel.credentials;

import io.github.b077as.jolt.credentials.CredentialStore;
import io.github.b077as.jolt.credentials.CredentialStoreUnavailableException;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides where Parsel keeps passwords: the operating system's keystore when there is one, otherwise an encrypted vault
 * file protected by a master password the user types once per launch.
 */
public final class CredentialBroker {

    public enum State {
        /** Secrets can be read and written now. */
        READY,
        /** No OS keystore and no vault yet: the user has to choose a master password. */
        VAULT_NEW,
        /** No OS keystore; the vault exists but has not been unlocked in this run. */
        VAULT_LOCKED
    }

    /** Thrown by {@link #store()} while the vault is locked or does not exist. */
    public static final class VaultLockedException extends RuntimeException {
        public VaultLockedException() {
            super("The password vault is locked");
        }
    }

    private final Path vaultFile;
    private final CredentialStore os;
    private final String osUnavailableReason;
    private VaultCredentialStore vault;

    /** Tries the OS keystore for {@code service}; remembers why it was not usable if it was not. */
    public static CredentialBroker open(String service, Path vaultFile) {
        try {
            return new CredentialBroker(CredentialStore.open(service), null, vaultFile);
        } catch (CredentialStoreUnavailableException e) {
            return new CredentialBroker(null, e.getMessage(), vaultFile);
        }
    }

    /** A broker that always uses {@code store}: for tests, and for embedding Parsel's account code elsewhere. */
    public static CredentialBroker withStore(CredentialStore store) {
        return new CredentialBroker(store, null, null);
    }

    /** A broker that never uses an OS keystore, only the vault at {@code vaultFile}: for tests, and to try the vault on a machine that has a keystore. */
    public static CredentialBroker vaultOnly(Path vaultFile) {
        return new CredentialBroker(null, "the OS keystore is not used", vaultFile);
    }

    /** @param os the OS keystore, or {@code null} to use the vault */
    CredentialBroker(CredentialStore os, String osUnavailableReason, Path vaultFile) {
        this.os = os;
        this.osUnavailableReason = osUnavailableReason;
        this.vaultFile = vaultFile;
    }

    public boolean usesVault() {
        return os == null;
    }

    /** Why the OS keystore was not used, or {@code null} if it is. */
    public String osUnavailableReason() {
        return osUnavailableReason;
    }

    public synchronized State state() {
        if (os != null || vault != null) {
            return State.READY;
        }
        return VaultCredentialStore.exists(vaultFile) ? State.VAULT_LOCKED : State.VAULT_NEW;
    }

    public synchronized void createVault(char[] password) {
        requireVaultMode();
        if (vault != null) {
            throw new IllegalStateException("The vault is already unlocked");
        }
        vault = VaultCredentialStore.create(vaultFile, password);
    }

    /** @throws WrongVaultPasswordException if the password is wrong */
    public synchronized void unlockVault(char[] password) {
        requireVaultMode();
        if (vault == null) {
            vault = VaultCredentialStore.open(vaultFile, password);
        }
    }

    /** The store to use right now. @throws VaultLockedException while the vault is not open */
    public synchronized CredentialStore store() {
        if (os != null) {
            return os;
        }
        if (vault == null) {
            throw new VaultLockedException();
        }
        return vault;
    }

    public Optional<String> get(String account) {
        return store().get(account);
    }

    public void put(String account, String secret) {
        store().put(account, secret);
    }

    public boolean delete(String account) {
        return store().delete(account);
    }

    private void requireVaultMode() {
        if (os != null) {
            throw new IllegalStateException("This machine has an OS keystore; no vault is used");
        }
    }
}
