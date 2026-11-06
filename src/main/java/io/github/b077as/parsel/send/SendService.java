package io.github.b077as.parsel.send;

import io.github.b077as.parsel.account.AccountException;
import io.github.b077as.parsel.account.AccountService;
import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.attach.AttachmentService;
import io.github.b077as.parsel.account.Problems;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.imap.ImapClient;
import io.github.b077as.parsel.imap.ImapClient.ReplyHeaders;
import io.github.b077as.parsel.mail.Mail;
import io.github.b077as.parsel.store.MailStore;
import io.github.b077as.parsel.store.MailStore.AccountRow;
import io.github.b077as.parsel.store.MailStore.FolderRow;
import io.github.b077as.parsel.sync.SyncService;
import jakarta.mail.MessagingException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Sends mail and prepares replies. Sending logs in to the account's outgoing server, then files a copy in the Sent
 * folder (unless the server does that itself) and asks for a sync so the copy shows up in the list.
 */
public final class SendService {

    /**
     * What the page composed. {@code body} is plain text; {@code html} (when given) is the message as the rich editor wrote it and replaces
     * the body: it is cleaned, and the plain text sent along with it is made from it. {@code replyTo} is the id of the message being answered, or null.
     */
    public record Draft(String account, List<String> to, List<String> cc, List<String> bcc, String subject, String body, String replyTo,
                        List<String> attachments, String html) {

        public Draft(String account, List<String> to, List<String> cc, List<String> bcc, String subject, String body, String replyTo,
                     List<String> attachments) {
            this(account, to, cc, bcc, subject, body, replyTo, attachments, null);
        }

        public Draft(String account, List<String> to, List<String> cc, List<String> bcc, String subject, String body, String replyTo) {
            this(account, to, cc, bcc, subject, body, replyTo, List.of(), null);
        }
    }

    /** @param warning a sentence when the mail went out but something around it failed (no copy in Sent), otherwise null */
    public record Result(String warning) {}

    public enum Mode { REPLY, REPLY_ALL, FORWARD }

    /** A starting point for the composer. */
    public record Prefill(String account, List<String> to, List<String> cc, String subject, String body, String replyTo, String html) {

        public Prefill(String account, List<String> to, List<String> cc, String subject, String body, String replyTo) {
            this(account, to, cc, subject, body, replyTo, "");
        }
    }

    private final MailStore store;
    private final CredentialBroker credentials;
    private final SyncService.Connector connector;
    private final LongConsumer afterSend;
    private final AttachmentService attachments;

    /** @param afterSend called with the account id once a message is out, to refresh the cache */
    public SendService(MailStore store, CredentialBroker credentials, SyncService.Connector connector, LongConsumer afterSend) {
        this(store, credentials, connector, afterSend, null);
    }

    /** @param attachments reads the files the user attached; null where nothing can be attached */
    public SendService(MailStore store, CredentialBroker credentials, SyncService.Connector connector, LongConsumer afterSend,
                       AttachmentService attachments) {
        this.attachments = attachments;
        this.store = store;
        this.credentials = credentials;
        this.connector = connector;
        this.afterSend = afterSend;
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    /**
     * @throws AccountException         if the server refuses the login or the message
     * @throws IllegalArgumentException with a sentence for the user if an address is not valid or there is no recipient
     */
    public Result send(Draft draft) {
        AccountRow account = account(draft.account());
        AccountSettings settings = account.settings();
        String password = credentials.get(AccountService.key(settings.email()))
                .orElseThrow(() -> new AccountException("There is no saved password for this account. Remove it and add it again."));

        String inReplyTo = null;
        String references = null;
        if (draft.replyTo() != null && !draft.replyTo().isBlank()) {
            ReplyHeaders original = replyHeaders(draft.replyTo(), settings, password);
            if (original != null && original.messageId() != null) {
                inReplyTo = original.messageId();
                references = original.references() == null ? original.messageId() : original.references() + " " + original.messageId();
            }
        }

        List<MailSender.Attached> files = List.of();
        if (!draft.attachments().isEmpty()) {
            if (attachments == null) {
                throw new IllegalArgumentException("Attachments are not available here");
            }
            files = attachments.load(draft.attachments());
        }

        String html = draft.html() == null || draft.html().isBlank() ? null : OutgoingHtml.clean(draft.html());
        String text = html == null ? draft.body() : OutgoingHtml.plainText(html);
        MailSender.Outgoing outgoing = new MailSender.Outgoing(senderName(settings), settings.email(),
                draft.to(), draft.cc(), draft.bcc(), draft.subject(), text, inReplyTo, references, files, html);
        byte[] raw;
        try {
            raw = MailSender.send(settings, password, outgoing);
        } catch (MessagingException e) {
            throw new AccountException(Problems.explain(e, settings.smtpHost(), settings.smtpPort()), e);
        }

        if (attachments != null) {
            attachments.release(draft.attachments());
        }
        String warning = saveCopy(account, password, raw);
        afterSend.accept(account.id());
        return new Result(warning);
    }

    /** The name shown beside the address: the one the user gave the account, not the stand-in made from the address. */
    private static String senderName(AccountSettings settings) {
        String name = settings.name();
        return name == null || name.isBlank() || name.equals(localPart(settings.email())) ? null : name;
    }

    /** Files the sent message in the Sent folder. Servers such as Gmail do this themselves, and a second copy would be a duplicate. */
    private String saveCopy(AccountRow account, String password, byte[] raw) {
        String host = account.settings().smtpHost().toLowerCase(Locale.ROOT);
        if (host.endsWith("gmail.com") || host.endsWith("googlemail.com")) {
            return null;
        }
        FolderRow sent = store.folderRows(account.id()).stream().filter(f -> "sent".equals(f.role())).findFirst().orElse(null);
        if (sent == null) {
            return "The message was sent, but this account has no Sent folder to keep a copy in.";
        }
        try (ImapClient client = connector.connect(account.settings(), password)) {
            client.append(sent.path(), raw);
            return null;
        } catch (MessagingException | RuntimeException e) {
            return "The message was sent, but a copy could not be saved in the Sent folder: "
                    + Problems.explain(e, account.settings().imapHost(), account.settings().imapPort());
        }
    }

    // ---- replies --------------------------------------------------------------------------------------------------

    /**
     * The composer's starting point for answering or forwarding a cached message: recipients, subject and the original quoted.
     * The Reply-To, Cc and thread headers are read from the server; if it cannot be reached the reply is built from the cache.
     */
    public Prefill prefill(String messageId, Mode mode) {
        return prefill(messageId, mode, true);
    }

    /** With {@code live} false nothing touches the network: recipients come from the cache only, so the composer can open at once. */
    public Prefill prefill(String messageId, Mode mode, boolean live) {
        long id = parseId(messageId);
        MailStore.MessageRef ref = store.messageRef(id).orElseThrow(() -> new IllegalArgumentException("No such message"));
        Mail.Detail detail = store.detail(id).orElseThrow(() -> new IllegalStateException("Open the message first"));
        AccountRow account = store.accountRow(ref.accountId()).orElseThrow(() -> new IllegalStateException("The account of this message was removed"));
        Mail.Summary original = detail.summary();

        String password = live ? credentials.get(AccountService.key(account.settings().email())).orElse(null) : null;
        ReplyHeaders headers = password == null ? null : replyHeaders(messageId, account.settings(), password);

        String me = account.settings().email();
        Map<String, String> to = new LinkedHashMap<>();
        Map<String, String> cc = new LinkedHashMap<>();
        if (mode != Mode.FORWARD) {
            add(to, headers != null && headers.replyTo() != null ? headers.replyTo() : original.fromAddress(), me);
            if (mode == Mode.REPLY_ALL) {
                for (String address : headers != null ? headers.to() : detail.to()) {
                    add(to, address, me);
                }
                if (headers != null) {
                    for (String address : headers.cc()) {
                        add(cc, address, me);
                    }
                }
                cc.keySet().removeAll(to.keySet());
            }
        }

        String subject = mode == Mode.FORWARD ? prefixed("Fwd:", "(fwd|fw)", original.subject()) : prefixed("Re:", "re", original.subject());
        String quoted = Quote.text(detail.bodyHtml());
        String when = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault())
                .format(Instant.ofEpochMilli(original.epochMillis()).atZone(ZoneId.systemDefault()));
        String body = mode == Mode.FORWARD
                ? "\n\n---------- Forwarded message ----------\nFrom: " + original.fromName() + " <" + original.fromAddress() + ">\nDate: "
                    + when + "\nSubject: " + original.subject() + "\nTo: " + String.join(", ", detail.to()) + "\n\n" + quoted
                : "\n\nOn " + when + ", " + original.fromName() + " <" + original.fromAddress() + "> wrote:\n" + Quote.indent(quoted);
        // The same start as HTML for the rich editor: an empty line to type in, then the original (quoted, or as a forwarded message).
        String quotedHtml = OutgoingHtml.paragraphs(quoted);
        String html = mode == Mode.FORWARD
                ? "<p></p><p>---------- Forwarded message ----------<br>From: " + OutgoingHtml.escape(original.fromName() + " <" + original.fromAddress() + ">")
                    + "<br>Date: " + OutgoingHtml.escape(when) + "<br>Subject: " + OutgoingHtml.escape(original.subject())
                    + "<br>To: " + OutgoingHtml.escape(String.join(", ", detail.to())) + "</p>" + quotedHtml
                : "<p></p><p>" + OutgoingHtml.escape("On " + when + ", " + original.fromName() + " <" + original.fromAddress() + "> wrote:")
                    + "</p><blockquote>" + quotedHtml + "</blockquote>";
        return new Prefill(String.valueOf(account.id()), new ArrayList<>(to.values()), new ArrayList<>(cc.values()), subject, body,
                mode == Mode.FORWARD ? null : messageId, html);
    }

    /** Adds an address unless it is the account's own or already there (the key ignores case). */
    private static void add(Map<String, String> into, String address, String me) {
        if (address != null && !address.isBlank() && !address.equalsIgnoreCase(me)) {
            into.putIfAbsent(address.toLowerCase(Locale.ROOT), address);
        }
    }

    private static String prefixed(String prefix, String pattern, String subject) {
        return subject.matches("(?i)\\s*(" + pattern + ")\\s*:.*") ? subject : prefix + " " + subject;
    }

    /** Headers of a cached message as the server has them, or null if the server cannot be reached or the message is gone. */
    private ReplyHeaders replyHeaders(String messageId, AccountSettings settings, String password) {
        try {
            MailStore.MessageRef ref = store.messageRef(parseId(messageId)).orElse(null);
            if (ref == null) {
                return null;
            }
            try (ImapClient client = connector.connect(settings, password)) {
                return client.fetchReplyHeaders(ref.folderPath(), ref.uid());
            }
        } catch (MessagingException | RuntimeException e) {
            return null; // not worth failing a reply or a send over: the mail just will not be threaded
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------------------------

    private AccountRow account(String accountId) {
        return store.accountRow(parseId(accountId)).orElseThrow(() -> new IllegalArgumentException("Choose an account to send from"));
    }

    private static long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid id: " + id);
        }
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
