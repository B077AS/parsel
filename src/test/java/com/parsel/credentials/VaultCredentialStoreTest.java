package com.parsel.credentials;

import io.github.b077as.jolt.credentials.CredentialStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultCredentialStoreTest {

    /** Few iterations: these tests are about the format and the behaviour, not about how slow a guess is. */
    private static final int FAST = 1000;

    @TempDir
    Path dir;

    private char[] password(String text) {
        return text.toCharArray();
    }

    @Test
    void aSecretSurvivesClosingAndReopening() {
        Path file = dir.resolve("vault");
        try (VaultCredentialStore vault = VaultCredentialStore.create(file, password("master"), FAST)) {
            vault.put("will@example.com", "pässwörd 日本語 🔑");
        }
        try (VaultCredentialStore vault = VaultCredentialStore.open(file, password("master"))) {
            assertEquals(Optional.of("pässwörd 日本語 🔑"), vault.get("will@example.com"));
        }
    }

    @Test
    void aWrongPasswordIsRefused() {
        Path file = dir.resolve("vault");
        VaultCredentialStore.create(file, password("right"), FAST).close();
        assertThrows(WrongVaultPasswordException.class, () -> VaultCredentialStore.open(file, password("wrong")));
    }

    @Test
    void aChangedFileIsRefusedNotReadAsGarbage() throws IOException {
        Path file = dir.resolve("vault");
        try (VaultCredentialStore vault = VaultCredentialStore.create(file, password("master"), FAST)) {
            vault.put("a", "secret");
        }
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 3] ^= 0x01; // inside the ciphertext
        Files.write(file, bytes);
        assertThrows(WrongVaultPasswordException.class, () -> VaultCredentialStore.open(file, password("master")));
    }

    @Test
    void theHeaderIsAuthenticatedToo() throws IOException {
        Path file = dir.resolve("vault");
        VaultCredentialStore.create(file, password("master"), FAST).close();
        byte[] bytes = Files.readAllBytes(file);
        bytes[20] ^= 0x01; // inside the salt: a different key is derived, so authentication fails
        Files.write(file, bytes);
        assertThrows(WrongVaultPasswordException.class, () -> VaultCredentialStore.open(file, password("master")));
    }

    @Test
    void theFileDoesNotContainThePlaintext() throws IOException {
        Path file = dir.resolve("vault");
        try (VaultCredentialStore vault = VaultCredentialStore.create(file, password("master"), FAST)) {
            vault.put("will@example.com", "a-very-recognisable-secret");
        }
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("a-very-recognisable-secret"));
        assertFalse(raw.contains("will@example.com"));
    }

    @Test
    void everyWriteUsesAFreshNonce() throws IOException {
        Path file = dir.resolve("vault");
        try (VaultCredentialStore vault = VaultCredentialStore.create(file, password("master"), FAST)) {
            byte[] first = Files.readAllBytes(file);
            vault.put("a", "1");
            byte[] second = Files.readAllBytes(file);
            // the nonce sits at offset 8 + 1 + 1 + 4 + 16 = 30, 12 bytes long
            assertFalse(java.util.Arrays.equals(java.util.Arrays.copyOfRange(first, 30, 42), java.util.Arrays.copyOfRange(second, 30, 42)));
        }
    }

    @Test
    void deleteAndReplaceBehaveLikeAnyCredentialStore() {
        Path file = dir.resolve("vault");
        try (VaultCredentialStore vault = VaultCredentialStore.create(file, password("master"), FAST)) {
            assertEquals(Optional.empty(), vault.get("nobody"));
            assertFalse(vault.delete("nobody"));
            vault.put("a", "first");
            vault.put("a", "second");
            assertEquals(Optional.of("second"), vault.get("a"));
            assertTrue(vault.delete("a"));
            assertEquals(Optional.empty(), vault.get("a"));
        }
    }

    @Test
    void aClosedVaultCannotBeUsed() {
        VaultCredentialStore vault = VaultCredentialStore.create(dir.resolve("vault"), password("master"), FAST);
        vault.close();
        assertThrows(IllegalStateException.class, () -> vault.get("a"));
    }

    @Test
    void anExistingVaultIsNotOverwrittenByCreate() {
        Path file = dir.resolve("vault");
        VaultCredentialStore.create(file, password("master"), FAST).close();
        assertThrows(IllegalStateException.class, () -> VaultCredentialStore.create(file, password("other"), FAST));
    }

    @Test
    void notAVaultIsReportedAsSuch() throws IOException {
        Path file = dir.resolve("vault");
        Files.writeString(file, "this is not a vault at all, just some text that is long enough");
        assertThrows(CredentialStoreException.class, () -> VaultCredentialStore.open(file, password("master")));
    }

    @Test
    void twoVaultsWithTheSamePasswordHaveDifferentSalts() throws IOException {
        Path one = dir.resolve("one");
        Path two = dir.resolve("two");
        VaultCredentialStore.create(one, password("master"), FAST).close();
        VaultCredentialStore.create(two, password("master"), FAST).close();
        byte[] a = java.util.Arrays.copyOfRange(Files.readAllBytes(one), 14, 30);
        byte[] b = java.util.Arrays.copyOfRange(Files.readAllBytes(two), 14, 30);
        assertNotEquals(java.util.HexFormat.of().formatHex(a), java.util.HexFormat.of().formatHex(b));
    }

    @Test
    void theDefaultIterationCountIsUsedForRealVaults() throws IOException {
        Path file = dir.resolve("vault");
        VaultCredentialStore.create(file, password("master")).close();
        byte[] bytes = Files.readAllBytes(file);
        int iterations = java.nio.ByteBuffer.wrap(bytes, 10, 4).getInt();
        assertEquals(VaultCredentialStore.DEFAULT_ITERATIONS, iterations);
        assertArrayEquals("PARSELVT".getBytes(StandardCharsets.US_ASCII), java.util.Arrays.copyOf(bytes, 8));
    }
}
