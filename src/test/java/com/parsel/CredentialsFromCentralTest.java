package com.parsel;

import io.github.b077as.jolt.credentials.CredentialStore;
import io.github.b077as.jolt.credentials.CredentialStoreUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Proves the released jolt-credentials artifact works inside Parsel: a secret goes into the real OS keystore and comes
 * back. Uses a throwaway service name and deletes what it stores. Skipped on a machine with no keystore.
 */
class CredentialsFromCentralTest {

    @Test
    void aSecretRoundTripsThroughTheOperatingSystemKeystore() {
        CredentialStore store;
        try {
            store = CredentialStore.open("com.parsel.app.test-" + UUID.randomUUID());
        } catch (CredentialStoreUnavailableException e) {
            abort("No keystore on this machine: " + e.getMessage());
            return;
        }
        String account = "will@example.com";
        try {
            store.put(account, "app password with spaces and ünïcode");
            assertEquals(Optional.of("app password with spaces and ünïcode"), store.get(account));
        } finally {
            assertTrue(store.delete(account));
        }
        assertEquals(Optional.empty(), store.get(account));
    }
}
