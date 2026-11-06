package io.github.b077as.parsel.credentials;

/** The master password did not open the vault. A damaged or tampered file looks the same, because both fail authentication. */
public final class WrongVaultPasswordException extends RuntimeException {

    public WrongVaultPasswordException() {
        super("Wrong password, or the vault file is damaged");
    }
}
