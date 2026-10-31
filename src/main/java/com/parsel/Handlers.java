package com.parsel;

import com.google.gson.JsonObject;
import com.parsel.account.AccountException;
import com.parsel.account.AccountService;
import com.parsel.account.AccountSettings;
import com.parsel.account.Security;
import com.parsel.attach.AttachmentService;
import com.parsel.credentials.CredentialBroker;
import com.parsel.credentials.WrongVaultPasswordException;
import com.parsel.mail.MailService;
import com.parsel.send.SendService;
import com.parsel.sync.SyncService;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The mail API the page can call. Each name is what the page passes to {@code call('...')}; {@code frontend/src/api.ts}
 * has one typed function per handler and must be kept in sync by hand. In demo mode (fake data) the account, sync and
 * vault parts are {@code null} and the handlers say so instead of failing obscurely.
 */
final class Handlers {

    private Handlers() {}

    static void register(Rpc rpc, MailService mail, AccountService accounts, SyncService sync, CredentialBroker credentials, SendService send,
                         AttachmentService attachments) {
        rpc.on("mail.accounts", params -> mail.accounts())
                .on("mail.folders", params -> mail.folders(Params.string(params, "account")))
                .on("mail.list", params -> mail.list(Params.string(params, "account"), Params.string(params, "folder")))
                .on("mail.get", params -> mail.get(Params.string(params, "id")))
                .on("mail.setStarred", params -> {
                    mail.setStarred(Params.string(params, "id"), Params.bool(params, "starred"));
                    return Map.of("ok", true);
                })
                .on("mail.setImportant", params -> {
                    mail.setImportant(Params.string(params, "id"), Params.bool(params, "important"));
                    return Map.of("ok", true);
                })
                .on("mail.archive", params -> {
                    mail.archive(Params.string(params, "id"));
                    return Map.of("ok", true);
                })
                .on("mail.moveTo", params -> {
                    mail.moveTo(Params.string(params, "id"), Params.string(params, "folder"));
                    return Map.of("ok", true);
                })
                .on("mail.createFolder", params -> mail.createFolder(Params.string(params, "account"),
                        params.has("parent") && !params.get("parent").isJsonNull() ? Params.string(params, "parent") : null,
                        Params.string(params, "name")))
                .on("mail.moveMany", params -> {
                    var ids = new java.util.ArrayList<String>();
                    params.getAsJsonArray("ids").forEach(e -> ids.add(e.getAsString()));
                    if (ids.isEmpty() || ids.size() > 1000) {
                        throw new IllegalArgumentException("Choose between 1 and 1000 messages");
                    }
                    mail.moveMany(ids, Params.string(params, "action"), params.has("folder") ? Params.string(params, "folder") : null);
                    return Map.of("ok", true);
                })
                .on("mail.restore", params -> {
                    mail.restore(Params.string(params, "id"));
                    return Map.of("ok", true);
                })
                .on("mail.trash", params -> {
                    mail.trash(Params.string(params, "id"));
                    return Map.of("ok", true);
                })
                .on("mail.markRead", params -> {
                    mail.markRead(Params.string(params, "id"), Params.bool(params, "read"));
                    return Map.of("ok", true);
                });

        rpc.on("mail.send", params -> {
            requireReal(send);
            return send.send(new SendService.Draft(Params.string(params, "account"),
                    Params.optionalStrings(params, "to"), Params.optionalStrings(params, "cc"), Params.optionalStrings(params, "bcc"),
                    Params.optionalString(params, "subject"), Params.optionalString(params, "body"),
                    params.has("replyTo") && !params.get("replyTo").isJsonNull() ? Params.string(params, "replyTo") : null,
                    Params.optionalStrings(params, "attachments"), Params.optionalString(params, "html")));
        });
        // Both open a system dialog and wait for the answer, which is fine on the virtual thread a handler runs on.
        rpc.on("mail.saveAttachment", params -> {
            requireReal(attachments);
            return attachments.download(Params.string(params, "id"), Params.integer(params, "index"));
        });
        rpc.on("compose.attach", params -> {
            requireReal(attachments);
            return attachments.pick();
        });
        rpc.on("mail.reply", params -> {
            requireReal(send);
            return send.prefill(Params.string(params, "id"), mode(Params.string(params, "mode")), false);
        });
        rpc.on("mail.replyRecipients", params -> {
            requireReal(send);
            return send.prefill(Params.string(params, "id"), mode(Params.string(params, "mode")), true);
        });

        rpc.on("accounts.detect", params -> {
            requireReal(accounts);
            try {
                return accounts.detect(Params.string(params, "email").strip());
            } catch (IllegalArgumentException e) {
                throw new AccountException(e.getMessage(), e);
            }
        });
        rpc.on("accounts.test", params -> {
            requireReal(accounts);
            try {
                return accounts.test(settings(params), Params.string(params, "password"));
            } catch (IllegalArgumentException e) {
                throw new AccountException(e.getMessage(), e);
            }
        });
        rpc.on("accounts.add", params -> {
            requireReal(accounts);
            var account = accounts.add(settings(params), Params.string(params, "password"));
            sync.syncAsync(Long.parseLong(account.id()));
            return account;
        });
        rpc.on("accounts.remove", params -> {
            requireReal(accounts);
            accounts.remove(Long.parseLong(Params.string(params, "id")));
            return Map.of("ok", true);
        });

        rpc.on("sync.now", params -> {
            if (sync != null) {
                sync.syncAllAsync();
            }
            return Map.of("ok", true);
        });
        rpc.on("sync.status", params -> sync == null ? List.of() : sync.statuses());

        rpc.on("vault.status", params -> vaultStatus(credentials));
        rpc.on("vault.create", params -> {
            requireReal(credentials);
            char[] password = Params.string(params, "password").toCharArray();
            try {
                credentials.createVault(password);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new AccountException(e.getMessage(), e);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
            sync.syncAllAsync();
            return vaultStatus(credentials);
        });
        rpc.on("vault.unlock", params -> {
            requireReal(credentials);
            char[] password = Params.string(params, "password").toCharArray();
            try {
                credentials.unlockVault(password);
            } catch (WrongVaultPasswordException e) {
                throw new AccountException("That is not the right password.", e);
            } finally {
                java.util.Arrays.fill(password, '\0');
            }
            sync.syncAllAsync();
            return vaultStatus(credentials);
        });
    }

    /** {@code mode}: where passwords live ("os" or "vault"); {@code state}: ready, new (no vault yet) or locked; {@code reason}: why the OS keystore is not used. */
    private static Map<String, Object> vaultStatus(CredentialBroker credentials) {
        if (credentials == null) {
            return Map.of("mode", "os", "state", "ready");
        }
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("mode", credentials.usesVault() ? "vault" : "os");
        status.put("state", credentials.state().name().equals("READY") ? "ready" : credentials.state() == CredentialBroker.State.VAULT_NEW ? "new" : "locked");
        if (credentials.osUnavailableReason() != null) {
            status.put("reason", credentials.osUnavailableReason());
        }
        return status;
    }

    private static void requireReal(Object service) {
        if (service == null) {
            throw new AccountException("This is the demo: accounts and sending are not available. Start Parsel without -Dparsel.demo=true to use them.");
        }
    }

    /** The account form of the page: the address, the login and the two servers. The password is read separately and never stored here. */
    static AccountSettings settings(JsonObject p) {
        return new AccountSettings(
                Params.optionalString(p, "name").strip(),
                Params.string(p, "email").strip(),
                Params.string(p, "username").strip(),
                Params.string(p, "imapHost").strip(), Params.integer(p, "imapPort"), security(p, "imapSecurity"),
                Params.string(p, "smtpHost").strip(), Params.integer(p, "smtpPort"), security(p, "smtpSecurity"));
    }

    private static SendService.Mode mode(String name) {
        return switch (name) {
            case "reply" -> SendService.Mode.REPLY;
            case "replyAll" -> SendService.Mode.REPLY_ALL;
            case "forward" -> SendService.Mode.FORWARD;
            default -> throw new IllegalArgumentException("mode must be reply, replyAll or forward");
        };
    }

    private static Security security(JsonObject p, String key) {
        try {
            return Security.valueOf(Params.string(p, key).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " must be SSL, STARTTLS or NONE");
        }
    }
}
