package io.github.b077as.parsel;

import io.github.b077as.jolt.Rpc;
import io.github.b077as.jolt.RpcException;
import io.github.b077as.parsel.account.AccountService;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import io.github.b077as.parsel.account.UserFacingException;
import io.github.b077as.parsel.attach.AttachmentService;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.mail.MailService;
import io.github.b077as.parsel.send.SendService;
import io.github.b077as.parsel.sync.SyncService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The mail API the page can call. Each name is what the page passes to {@code call('...')}; {@code frontend/src/api.ts} has one typed
 * function per handler and must be kept in sync by hand. In demo mode (fake data) the account, sync and vault parts are {@code null} and
 * the handlers say so instead of failing obscurely.
 *
 * <p>Every handler that takes parameters names them as a record below, which Jolt reads from the page's JSON before the handler runs: a
 * missing text field or a wrong shape never reaches the mail layer, it is answered with {@code bad_params}. What a failure tells the page
 * is decided in {@link ParselRpc}: throw a {@link UserFacingException} for a sentence the user should read.
 */
final class Handlers {

    private Handlers() {}

    /** The answer of a call that has nothing to return. */
    static final Map<String, Object> OK = Map.of("ok", true);

    // ------------------------------------------------------------------------------------------------- parameters

    record Account(String account) {
        Account { required(account, "account"); }
    }

    record FolderOf(String account, String folder) {
        FolderOf { required(account, "account"); required(folder, "folder"); }
    }

    record Id(String id) {
        Id { required(id, "id"); }
    }

    record Starred(String id, boolean starred) {
        Starred { required(id, "id"); }
    }

    record Important(String id, boolean important) {
        Important { required(id, "id"); }
    }

    record Read(String id, boolean read) {
        Read { required(id, "id"); }
    }

    record MoveTo(String id, String folder) {
        MoveTo { required(id, "id"); required(folder, "folder"); }
    }

    /** {@code parent} is the folder to create it in, or null for the top level. */
    record NewFolder(String account, String parent, String name) {
        NewFolder { required(account, "account"); required(name, "name"); }
    }

    /** {@code folder} only with the action {@code move}. */
    record MoveMany(List<String> ids, String action, String folder) {
        MoveMany { required(ids, "ids"); required(action, "action"); }
    }

    record Attachment(String id, int index) {
        Attachment { required(id, "id"); }
    }

    record Reply(String id, String mode) {
        Reply { required(id, "id"); required(mode, "mode"); }
    }

    record Email(String email) {
        Email { required(email, "email"); }
    }

    record Password(String password) {
        Password { required(password, "password"); }
    }

    /** The Add account form: the address, the login and the two servers. {@code password} is empty where it is not needed. */
    record AccountForm(String name, String email, String username, String imapHost, int imapPort, String imapSecurity,
                       String smtpHost, int smtpPort, String smtpSecurity, String password) {
        AccountForm {
            required(email, "email");
            required(username, "username");
            required(imapHost, "imapHost");
            required(imapSecurity, "imapSecurity");
            required(smtpHost, "smtpHost");
            required(smtpSecurity, "smtpSecurity");
        }

        AccountSettings settings() {
            return new AccountSettings(name == null ? "" : name.strip(), email.strip(), username.strip(),
                    imapHost.strip(), imapPort, security(imapSecurity, "imapSecurity"),
                    smtpHost.strip(), smtpPort, security(smtpSecurity, "smtpSecurity"));
        }
    }

    /** A message to send. Lists and text the page leaves out are empty. */
    record Outgoing(String account, List<String> to, List<String> cc, List<String> bcc, String subject, String body, String replyTo,
                    List<String> attachments, String html) {
        Outgoing {
            required(account, "account");
            to = to == null ? List.of() : to;
            cc = cc == null ? List.of() : cc;
            bcc = bcc == null ? List.of() : bcc;
            subject = subject == null ? "" : subject;
            body = body == null ? "" : body;
            attachments = attachments == null ? List.of() : attachments;
            html = html == null ? "" : html;
        }

        SendService.Draft toDraft() {
            return new SendService.Draft(account, to, cc, bcc, subject, body, replyTo, attachments, html);
        }
    }

    // ------------------------------------------------------------------------------------------------- handlers

    static void register(Rpc rpc, MailService mail, AccountService accounts, SyncService sync, CredentialBroker credentials, SendService send,
                         AttachmentService attachments) {
        rpc.on("mail.accounts", call -> mail.accounts())
                .on("mail.folders", Account.class, p -> mail.folders(p.account()))
                .on("mail.list", FolderOf.class, p -> mail.list(p.account(), p.folder()))
                .on("mail.get", Id.class, p -> mail.get(p.id()))
                .on("mail.setStarred", Starred.class, p -> {
                    mail.setStarred(p.id(), p.starred());
                    return OK;
                })
                .on("mail.setImportant", Important.class, p -> {
                    mail.setImportant(p.id(), p.important());
                    return OK;
                })
                .on("mail.archive", Id.class, p -> {
                    mail.archive(p.id());
                    return OK;
                })
                .on("mail.moveTo", MoveTo.class, p -> {
                    mail.moveTo(p.id(), p.folder());
                    return OK;
                })
                .on("mail.createFolder", NewFolder.class, p -> mail.createFolder(p.account(), p.parent(), p.name()))
                .on("mail.moveMany", MoveMany.class, p -> {
                    if (p.ids().isEmpty() || p.ids().size() > 1000) {
                        throw RpcException.badParams("Choose between 1 and 1000 messages");
                    }
                    mail.moveMany(p.ids(), p.action(), p.folder());
                    return OK;
                })
                .on("mail.restore", Id.class, p -> {
                    mail.restore(p.id());
                    return OK;
                })
                .on("mail.trash", Id.class, p -> {
                    mail.trash(p.id());
                    return OK;
                })
                .on("mail.markRead", Read.class, p -> {
                    mail.markRead(p.id(), p.read());
                    return OK;
                });

        rpc.on("mail.send", Outgoing.class, p -> {
            requireReal(send);
            return send.send(p.toDraft());
        });
        // Both open a system dialog and wait for the answer, which is fine on the virtual thread a handler runs on.
        rpc.on("mail.saveAttachment", Attachment.class, p -> {
            requireReal(attachments);
            return attachments.download(p.id(), p.index());
        });
        rpc.on("compose.attach", call -> {
            requireReal(attachments);
            return attachments.pick();
        });
        rpc.on("mail.reply", Reply.class, p -> {
            requireReal(send);
            return send.prefill(p.id(), mode(p.mode()), false);
        });
        rpc.on("mail.replyRecipients", Reply.class, p -> {
            requireReal(send);
            return send.prefill(p.id(), mode(p.mode()), true);
        });

        rpc.on("accounts.detect", Email.class, p -> {
            requireReal(accounts);
            try {
                return accounts.detect(p.email().strip());
            } catch (IllegalArgumentException e) {
                throw new UserFacingException(e.getMessage(), e);
            }
        });
        rpc.on("accounts.test", AccountForm.class, p -> {
            requireReal(accounts);
            try {
                return accounts.test(p.settings(), required(p.password(), "password"));
            } catch (IllegalArgumentException e) {
                throw new UserFacingException(e.getMessage(), e);
            }
        });
        rpc.on("accounts.add", AccountForm.class, p -> {
            requireReal(accounts);
            var account = accounts.add(p.settings(), required(p.password(), "password"));
            sync.syncAsync(Long.parseLong(account.id()));
            return account;
        });
        rpc.on("accounts.remove", Id.class, p -> {
            requireReal(accounts);
            accounts.remove(Long.parseLong(p.id()));
            return OK;
        });

        rpc.on("sync.now", call -> {
            if (sync != null) {
                sync.syncAllAsync();
            }
            return OK;
        });
        rpc.on("sync.status", call -> sync == null ? List.of() : sync.statuses());

        rpc.on("vault.status", call -> vaultStatus(credentials));
        // A wrong master password is answered by ParselRpc (WrongVaultPasswordException -> wrong_password).
        rpc.on("vault.create", Password.class, p -> {
            requireReal(credentials);
            char[] password = p.password().toCharArray();
            try {
                credentials.createVault(password);
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new UserFacingException(e.getMessage(), e);
            } finally {
                Arrays.fill(password, '\0');
            }
            sync.syncAllAsync();
            return vaultStatus(credentials);
        });
        rpc.on("vault.unlock", Password.class, p -> {
            requireReal(credentials);
            char[] password = p.password().toCharArray();
            try {
                credentials.unlockVault(password);
            } finally {
                Arrays.fill(password, '\0');
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
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", credentials.usesVault() ? "vault" : "os");
        status.put("state", credentials.state().name().equals("READY") ? "ready" : credentials.state() == CredentialBroker.State.VAULT_NEW ? "new" : "locked");
        if (credentials.osUnavailableReason() != null) {
            status.put("reason", credentials.osUnavailableReason());
        }
        return status;
    }

    private static void requireReal(Object service) {
        if (service == null) {
            throw new UserFacingException("This is the demo: accounts and sending are not available. Start Parsel without -Dparsel.demo=true to use them.");
        }
    }

    /** A parameter the page must send; Jolt answers a record that refuses its input with {@code bad_params}. */
    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " is required");
    }

    private static SendService.Mode mode(String name) {
        return switch (name) {
            case "reply" -> SendService.Mode.REPLY;
            case "replyAll" -> SendService.Mode.REPLY_ALL;
            case "forward" -> SendService.Mode.FORWARD;
            default -> throw RpcException.badParams("mode must be reply, replyAll or forward");
        };
    }

    private static Security security(String value, String name) {
        try {
            return Security.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw RpcException.badParams(name + " must be SSL, STARTTLS or NONE");
        }
    }
}
