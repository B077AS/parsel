package io.github.b077as.parsel;

import com.google.gson.Gson;
import io.github.b077as.jolt.JsonCodec;
import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.jolt.RpcOptions;
import io.github.b077as.parsel.account.UserFacingException;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.credentials.WrongVaultPasswordException;

/**
 * Makes the {@link Rpc} each window talks to Java through (Jolt 0.5.0): the page calls {@code jolt.call(method, params)} and listens with
 * {@code jolt.onEvent}; {@code frontend/src/jolt.ts} is the page half.
 *
 * <p><b>JSON</b> is Gson, as it was before Jolt had an RPC of its own, so the records in {@code Mail} reach the page exactly as the
 * page's types in {@code api.ts} describe them (a null is left out).
 *
 * <p><b>Errors.</b> What the page is told about a failed call is decided here, per exception type, because the default is to say only
 * "The call to X failed" (a message can name files, SQL or classes):
 * <ul>
 *   <li>{@link UserFacingException}: its message was written for the user, so it goes through as {@code problem};</li>
 *   <li>{@link WrongVaultPasswordException} and {@link CredentialBroker.VaultLockedException}: codes the vault screen can tell apart;</li>
 *   <li>anything else is a bug: the page gets a generic {@code internal} error and Jolt logs it with the stack trace.</li>
 * </ul>
 * With {@code -Dparsel.dev=true} the page gets the real class and message of those bugs too.
 */
final class ParselRpc {

    /** The error code for a message meant to be shown to the user as it is. */
    static final String PROBLEM = "problem";
    static final String WRONG_PASSWORD = "wrong_password";
    static final String VAULT_LOCKED = "vault_locked";

    private ParselRpc() {}

    static Rpc create(boolean dev) {
        Gson gson = new Gson();
        Rpc rpc = Rpc.create(RpcOptions.defaults().withCodec(JsonCodec.of(gson::toJson, (json, type) -> gson.fromJson(json, type))))
                .onException(UserFacingException.class, (call, e) -> new RpcException(PROBLEM, e.getMessage()))
                .onException(WrongVaultPasswordException.class, (call, e) -> new RpcException(WRONG_PASSWORD, "That is not the right password."))
                .onException(CredentialBroker.VaultLockedException.class, (call, e) -> new RpcException(VAULT_LOCKED, e.getMessage()));
        if (dev) {
            rpc.onException(Throwable.class, (call, e) -> RpcException.exposing(e));
        }
        return rpc;
    }
}
