package io.github.b077as.parsel.credentials;

import io.github.b077as.jolt.credentials.CredentialStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CredentialBrokerTest {

    @TempDir
    Path dir;

    /** A keystore in memory, standing in for the OS one. */
    private static final class MemoryStore implements CredentialStore {
        final Map<String, String> map = new HashMap<>();

        public Optional<String> get(String account) { return Optional.ofNullable(map.get(account)); }
        public void put(String account, String secret) { map.put(account, secret); }
        public boolean delete(String account) { return map.remove(account) != null; }
    }

    @Test
    void withAnOsKeystoreNoVaultIsUsedAndTheStoreIsReadyAtOnce() {
        MemoryStore os = new MemoryStore();
        CredentialBroker broker = new CredentialBroker(os, null, dir.resolve("vault"));
        assertFalse(broker.usesVault());
        assertEquals(CredentialBroker.State.READY, broker.state());
        broker.put("a", "secret");
        assertEquals(Optional.of("secret"), os.get("a"));
        assertThrows(IllegalStateException.class, () -> broker.createVault("x".toCharArray()));
    }

    @Test
    void withoutAKeystoreTheVaultMustBeCreatedThenUnlockedOnTheNextRun() {
        Path file = dir.resolve("vault");

        CredentialBroker first = new CredentialBroker(null, "no Secret Service", file);
        assertTrue(first.usesVault());
        assertEquals("no Secret Service", first.osUnavailableReason());
        assertEquals(CredentialBroker.State.VAULT_NEW, first.state());
        assertThrows(CredentialBroker.VaultLockedException.class, () -> first.put("a", "secret"));

        first.createVault("master".toCharArray());
        assertEquals(CredentialBroker.State.READY, first.state());
        first.put("a", "secret");

        // a new run: the vault file exists but nothing is unlocked yet
        CredentialBroker second = new CredentialBroker(null, "no Secret Service", file);
        assertEquals(CredentialBroker.State.VAULT_LOCKED, second.state());
        assertThrows(CredentialBroker.VaultLockedException.class, () -> second.get("a"));
        assertThrows(WrongVaultPasswordException.class, () -> second.unlockVault("wrong".toCharArray()));
        assertEquals(CredentialBroker.State.VAULT_LOCKED, second.state());
        second.unlockVault("master".toCharArray());
        assertEquals(Optional.of("secret"), second.get("a"));
    }
}
