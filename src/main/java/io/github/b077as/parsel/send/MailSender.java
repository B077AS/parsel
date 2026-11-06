package io.github.b077as.parsel.send;

import io.github.b077as.parsel.account.AccountSettings;
import io.github.b077as.parsel.account.Security;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.activation.DataHandler;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Builds an outgoing message and hands it to the SMTP server. Passwords are used to log in and not kept. */
public final class MailSender {

    /**
     * A message to send. Addresses are plain {@code a@b.c} strings. {@code inReplyTo} and {@code references} are the
     * Message-ID headers that tie a reply to the thread, null for a new message. {@code body} is the plain text; with {@code html}
     * (already cleaned, see {@link OutgoingHtml}) the message carries both as alternatives, the plain text first.
     */
    public record Outgoing(String fromName, String fromAddress, List<String> to, List<String> cc, List<String> bcc,
                           String subject, String body, String inReplyTo, String references, List<Attached> attachments, String html) {

        public Outgoing(String fromName, String fromAddress, List<String> to, List<String> cc, List<String> bcc,
                        String subject, String body, String inReplyTo, String references, List<Attached> attachments) {
            this(fromName, fromAddress, to, cc, bcc, subject, body, inReplyTo, references, attachments, null);
        }

        public Outgoing(String fromName, String fromAddress, List<String> to, List<String> cc, List<String> bcc,
                        String subject, String body, String inReplyTo, String references) {
            this(fromName, fromAddress, to, cc, bcc, subject, body, inReplyTo, references, List.of(), null);
        }
    }

    /** A file travelling with the message. */
    public record Attached(String name, String contentType, byte[] data) {}

    private static final String CONNECT_TIMEOUT_MS = "15000";
    private static final String IO_TIMEOUT_MS = "30000";

    private MailSender() {}

    /** Logs in to the outgoing server; the caller closes the transport. Also used to test an account's settings. */
    public static Transport connect(AccountSettings s, String password) throws MessagingException {
        String protocol = s.smtpSecurity() == Security.SSL ? "smtps" : "smtp";
        String prefix = "mail." + protocol + ".";
        Properties props = new Properties();
        props.put(prefix + "connectiontimeout", CONNECT_TIMEOUT_MS);
        props.put(prefix + "timeout", IO_TIMEOUT_MS);
        props.put(prefix + "writetimeout", IO_TIMEOUT_MS);
        props.put(prefix + "auth", "true");
        switch (s.smtpSecurity()) {
            case SSL -> {
                props.put(prefix + "ssl.enable", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
                props.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            case STARTTLS -> {
                props.put(prefix + "starttls.enable", "true");
                props.put(prefix + "starttls.required", "true");
                props.put(prefix + "ssl.checkserveridentity", "true");
                props.put(prefix + "ssl.protocols", "TLSv1.2 TLSv1.3");
            }
            case NONE -> { /* localhost only, enforced by AccountSettings.validate() */ }
        }
        Transport transport = Session.getInstance(props).getTransport(protocol);
        try {
            transport.connect(s.smtpHost(), s.smtpPort(), s.username(), password);
        } catch (MessagingException | RuntimeException e) {
            try {
                transport.close();
            } catch (MessagingException ignored) {
                // the original failure is the one to report
            }
            throw e;
        }
        return transport;
    }

    /**
     * Sends the message.
     *
     * @return the message as sent (with its Message-ID and Bcc), for the copy in the Sent folder
     * @throws IllegalArgumentException with a sentence for the user if an address or the recipient list is not valid
     */
    public static byte[] send(AccountSettings settings, String password, Outgoing outgoing) throws MessagingException {
        MimeMessage message = build(outgoing);
        try (Transport transport = connect(settings, password)) {
            transport.sendMessage(message, message.getAllRecipients());
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            message.writeTo(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return raw.toByteArray();
    }

    static MimeMessage build(Outgoing o) throws MessagingException {
        String domain = domainOf(o.fromAddress());
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
            @Override
            protected void updateMessageID() throws MessagingException {
                // The default id contains this computer's host name; the sender's own domain is what mail clients expect.
                setHeader("Message-ID", "<" + UUID.randomUUID() + "@" + domain + ">");
            }
        };
        try {
            message.setFrom(new InternetAddress(o.fromAddress(), blankToNull(o.fromName()), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 is always there
        }
        List<InternetAddress> to = addresses(o.to(), "To");
        List<InternetAddress> cc = addresses(o.cc(), "Cc");
        List<InternetAddress> bcc = addresses(o.bcc(), "Bcc");
        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            throw new IllegalArgumentException("Add at least one recipient");
        }
        message.setRecipients(Message.RecipientType.TO, to.toArray(new InternetAddress[0]));
        message.setRecipients(Message.RecipientType.CC, cc.toArray(new InternetAddress[0]));
        message.setRecipients(Message.RecipientType.BCC, bcc.toArray(new InternetAddress[0]));
        // A line break in a header value would let the text smuggle in headers of its own.
        message.setSubject(o.subject().replaceAll("[\\r\\n]+", " ").strip(), "UTF-8");
        message.setSentDate(new Date());
        if (o.inReplyTo() != null && !o.inReplyTo().isBlank()) {
            message.setHeader("In-Reply-To", headerValue(o.inReplyTo()));
        }
        if (o.references() != null && !o.references().isBlank()) {
            message.setHeader("References", headerValue(o.references()));
        }
        boolean rich = o.html() != null;
        if (o.attachments().isEmpty()) {
            if (rich) {
                message.setContent(alternatives(o));
            } else {
                message.setText(o.body(), "UTF-8");
            }
        } else {
            MimeMultipart parts = new MimeMultipart("mixed");
            MimeBodyPart text = new MimeBodyPart();
            if (rich) {
                text.setContent(alternatives(o));
            } else {
                text.setText(o.body(), "UTF-8");
            }
            parts.addBodyPart(text);
            for (Attached file : o.attachments()) {
                MimeBodyPart part = new MimeBodyPart();
                part.setDataHandler(new DataHandler(new ByteArrayDataSource(file.data(), file.contentType())));
                // Jakarta Mail encodes a non-ASCII name as an RFC 2231 parameter; a line break in it would break the header.
                part.setFileName(file.name().replaceAll("[\\r\\n]+", " ").strip());
                part.setDisposition(MimeBodyPart.ATTACHMENT);
                parts.addBodyPart(part);
            }
            message.setContent(parts);
        }
        message.saveChanges();
        return message;
    }

    /** The same message twice: plain text first, then the HTML, which is the one a mail client shows when it can. */
    private static MimeMultipart alternatives(Outgoing o) throws MessagingException {
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart text = new MimeBodyPart();
        text.setText(o.body(), "UTF-8");
        alternative.addBodyPart(text);
        MimeBodyPart html = new MimeBodyPart();
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parseBodyFragment(o.html());
        doc.outputSettings().prettyPrint(false);
        List<MimeBodyPart> pictures = new ArrayList<>();
        for (org.jsoup.nodes.Element img : doc.select("img")) {
            var m = OutgoingHtml.DATA_IMAGE.matcher(img.attr("src"));
            if (!m.matches()) {
                img.remove();
                continue;
            }
            String cid = UUID.randomUUID() + "@parsel";
            MimeBodyPart picture = new MimeBodyPart();
            picture.setDataHandler(new DataHandler(new ByteArrayDataSource(java.util.Base64.getDecoder().decode(m.group(2)), "image/" + m.group(1))));
            picture.setContentID("<" + cid + ">");
            picture.setDisposition(MimeBodyPart.INLINE);
            pictures.add(picture);
            img.attr("src", "cid:" + cid);
        }
        String page = "<html><body>" + doc.body().html() + "</body></html>";
        if (pictures.isEmpty()) {
            html.setContent(page, "text/html; charset=UTF-8");
        } else {
            MimeBodyPart pageBody = new MimeBodyPart();
            pageBody.setContent(page, "text/html; charset=UTF-8");
            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(pageBody);
            pictures.forEach(p -> { try { related.addBodyPart(p); } catch (MessagingException e) { throw new IllegalStateException(e); } });
            html.setContent(related);
        }
        alternative.addBodyPart(html);
        return alternative;
    }

    private static List<InternetAddress> addresses(List<String> values, String field) {
        List<InternetAddress> result = new ArrayList<>();
        for (String value : values) {
            String address = value.strip();
            if (address.isEmpty()) {
                continue;
            }
            try {
                InternetAddress parsed = new InternetAddress(address, true);
                parsed.validate();
                result.add(parsed);
            } catch (AddressException e) {
                throw new IllegalArgumentException("\"" + address + "\" in " + field + " is not a valid email address");
            }
        }
        return result;
    }

    private static String domainOf(String address) {
        int at = address.lastIndexOf('@');
        return at < 0 ? "localhost" : address.substring(at + 1);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Header values come from the other party's mail: fold them onto one line. */
    private static String headerValue(String value) {
        return value.replaceAll("[\\r\\n]+\\s*", " ").strip();
    }
}
