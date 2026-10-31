package com.parsel.send;

import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailSenderRichTest {

    private static MailSender.Outgoing outgoing(String html, List<MailSender.Attached> files) {
        return new MailSender.Outgoing("Will", "will@example.com", List.of("elena@example.com"), List.of(), List.of(), "Hi", "Hello\n",
                null, null, files, html);
    }

    @Test
    void plainTextAndHtmlAreAlternativesWithTheTextFirst() throws Exception {
        MimeMessage m = MailSender.build(outgoing("<p>Hello</p>", List.of()));
        assertTrue(m.isMimeType("multipart/alternative"), m.getContentType());
        Multipart parts = (Multipart) m.getContent();
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/html"));
        assertTrue(((String) parts.getBodyPart(1).getContent()).contains("<p>Hello</p>"));
    }

    @Test
    void withFilesTheAlternativesSitInsideAMixedMessage() throws Exception {
        MimeMessage m = MailSender.build(outgoing("<p>Hello</p>", List.of(new MailSender.Attached("a.txt", "text/plain", "data".getBytes()))));
        assertTrue(m.isMimeType("multipart/mixed"), m.getContentType());
        Multipart mixed = (Multipart) m.getContent();
        assertEquals(2, mixed.getCount());
        assertTrue(mixed.getBodyPart(0).isMimeType("multipart/alternative"), mixed.getBodyPart(0).getContentType());
        assertEquals("a.txt", mixed.getBodyPart(1).getFileName());
    }

    @Test
    void emojiSurviveInBothPartsOfTheMessage() throws Exception {
        String html = OutgoingHtml.clean("<p>Thursday works \uD83C\uDF89 see you \uD83D\uDC4B\uD83C\uDFFD</p>");
        MimeMessage m = MailSender.build(new MailSender.Outgoing("Will", "will@example.com", List.of("elena@example.com"), List.of(), List.of(), "Hi",
                OutgoingHtml.plainText(html), null, null, List.of(), html));
        // Written out and read back, as a mail client would receive it.
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        m.writeTo(raw);
        MimeMessage received = new MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()), new java.io.ByteArrayInputStream(raw.toByteArray()));
        Multipart parts = (Multipart) received.getContent();
        String text = ((String) parts.getBodyPart(0).getContent()).strip();
        assertEquals("Thursday works \uD83C\uDF89 see you \uD83D\uDC4B\uD83C\uDFFD", text);
        assertTrue(((String) parts.getBodyPart(1).getContent()).contains("\uD83C\uDF89"));
    }

    @Test
    void withoutHtmlTheMessageStaysPlainText() throws Exception {
        MimeMessage m = MailSender.build(outgoing(null, List.of()));
        assertTrue(m.isMimeType("text/plain"), m.getContentType());
    }
}
