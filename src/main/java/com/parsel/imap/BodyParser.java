package com.parsel.imap;

import com.parsel.mail.Mail;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeUtility;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns the MIME structure of a message into what the reader shows: cleaned HTML, a short preview and the attachment list. */
public final class BodyParser {

    /** @param html cleaned body HTML (never null) */
    public record Parsed(String html, String snippet, boolean hasRemoteImages, List<Mail.Attachment> attachments) {}

    private static final int SNIPPET_CHARS = 140;
    private static final int MAX_INLINE_IMAGE_BYTES = 1_500_000;

    private BodyParser() {}

    private static final class Collected {
        String html;
        String text;
        final Map<String, String> inlineImages = new HashMap<>();
        final List<Mail.Attachment> attachments = new ArrayList<>();
        /** The parts behind {@code attachments}, same order: the position in the list is how an attachment is asked for later. */
        final List<Part> attachmentParts = new ArrayList<>();

        void addAttachment(Part part, String name) throws MessagingException {
            attachments.add(new Mail.Attachment(name, Math.max(0, part.getSize())));
            attachmentParts.add(part);
        }
    }

    /** The attachment at {@code index} in the list {@link #parse} returns for the same message, or null if there is none. */
    public static Part attachmentPart(Part message, int index) throws MessagingException, IOException {
        Collected c = new Collected();
        collect(message, c);
        return index >= 0 && index < c.attachmentParts.size() ? c.attachmentParts.get(index) : null;
    }

    public static Parsed parse(Part message) throws MessagingException, IOException {
        Collected c = new Collected();
        collect(message, c);

        String html;
        if (c.html != null) {
            html = c.html;
            for (Map.Entry<String, String> image : c.inlineImages.entrySet()) {
                html = html.replace("cid:" + image.getKey(), image.getValue());
            }
        } else if (c.text != null) {
            html = "<div style=\"white-space:pre-wrap\">" + escape(c.text) + "</div>";
        } else {
            html = "";
        }
        String cleaned = HtmlSanitizer.clean(html);
        String preview = c.text != null && !c.text.isBlank() ? c.text : HtmlSanitizer.text(cleaned);
        return new Parsed(cleaned, snippet(preview), HtmlSanitizer.hasRemoteContent(cleaned), List.copyOf(c.attachments));
    }

    private static void collect(Part part, Collected c) throws MessagingException, IOException {
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), c);
            }
            return;
        }
        if (part.isMimeType("message/rfc822")) {
            c.addAttachment(part, name(part, "message.eml"));
            return;
        }
        boolean asAttachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition());
        if (part.isMimeType("text/html") && !asAttachment) {
            if (c.html == null) {
                c.html = text(part);
            }
        } else if (part.isMimeType("text/plain") && !asAttachment) {
            if (c.text == null) {
                c.text = text(part);
            }
        } else if (part.isMimeType("image/*") && !asAttachment && contentId(part) != null) {
            if (part.getSize() <= MAX_INLINE_IMAGE_BYTES) {
                try (InputStream in = part.getInputStream()) {
                    String mime = new ContentType(part.getContentType()).getBaseType().toLowerCase(Locale.ROOT);
                    c.inlineImages.put(contentId(part), "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(in.readAllBytes()));
                }
            }
        } else {
            c.addAttachment(part, name(part, "attachment"));
        }
    }

    private static String contentId(Part part) throws MessagingException {
        String[] ids = part.getHeader("Content-ID");
        if (ids == null || ids.length == 0) {
            return null;
        }
        String id = ids[0].strip();
        return id.startsWith("<") && id.endsWith(">") ? id.substring(1, id.length() - 1) : id;
    }

    private static String name(Part part, String fallback) throws MessagingException {
        String file = part.getFileName();
        if (file == null || file.isBlank()) {
            return fallback;
        }
        try {
            return MimeUtility.decodeText(file);
        } catch (UnsupportedEncodingException e) {
            return file;
        }
    }

    /** The text of a text/* part, tolerating a charset Java does not know. */
    private static String text(Part part) throws MessagingException, IOException {
        try {
            Object content = part.getContent();
            if (content instanceof String s) {
                return s;
            }
        } catch (UnsupportedEncodingException e) {
            // fall through to reading the raw bytes
        }
        try (InputStream in = part.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            Charset charset = StandardCharsets.UTF_8;
            try {
                String declared = new ContentType(part.getContentType()).getParameter("charset");
                if (declared != null && Charset.isSupported(declared)) {
                    charset = Charset.forName(declared);
                }
            } catch (RuntimeException ignored) {
                // keep UTF-8
            }
            return new String(bytes, charset);
        }
    }

    private static String snippet(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS).stripTrailing() + "…";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
