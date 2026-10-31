package com.parsel.imap;

import com.parsel.mail.Mail;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyParserTest {

    private static final Session SESSION = Session.getInstance(new Properties());

    /** Writes the message out and parses it again, so the parser sees what a server would send. */
    private static MimeMessage roundTrip(MimeMessage message) throws Exception {
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return new MimeMessage(SESSION, new ByteArrayInputStream(out.toByteArray()));
    }

    private static MimeMessage message() throws Exception {
        MimeMessage m = new MimeMessage(SESSION);
        m.setFrom(new InternetAddress("elena@example.com"));
        m.setSubject("hello");
        return m;
    }

    private static MimeBodyPart html(String html) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setContent(html, "text/html; charset=utf-8");
        return part;
    }

    private static MimeBodyPart text(String text) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "utf-8");
        return part;
    }

    @Test
    void aPlainTextMessageBecomesEscapedPreformattedHtml() throws Exception {
        MimeMessage m = message();
        m.setText("Line one\nLine <two> & more", "utf-8");
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertTrue(parsed.html().contains("white-space:pre-wrap"));
        assertTrue(parsed.html().contains("Line &lt;two&gt; &amp; more"));
        assertEquals("Line one Line <two> & more", parsed.snippet());
        assertTrue(parsed.attachments().isEmpty());
    }

    @Test
    void alternativePrefersHtmlButUsesTheTextForThePreview() throws Exception {
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text("plain version of the message"));
        alternative.addBodyPart(html("<p>HTML <b>version</b></p>"));
        MimeMessage m = message();
        m.setContent(alternative);
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertTrue(parsed.html().contains("<b>version</b>"));
        assertEquals("plain version of the message", parsed.snippet());
    }

    @Test
    void attachmentsAreListedNotInlined() throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(html("<p>see attached</p>"));
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource("%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII), "application/pdf")));
        file.setFileName("invoice 2026.pdf");
        file.setDisposition("attachment");
        mixed.addBodyPart(file);
        MimeMessage m = message();
        m.setContent(mixed);
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertEquals(1, parsed.attachments().size());
        assertEquals("invoice 2026.pdf", parsed.attachments().get(0).name());
        assertTrue(parsed.attachments().get(0).bytes() > 0);
        assertFalse(parsed.html().contains("PDF"));
    }

    @Test
    void anInlineImageByContentIdBecomesADataUriAndNeedsNoNetwork() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(html("<p>logo: <img src=\"cid:logo123\"></p>"));
        MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[]{1, 2, 3, 4}, "image/png")));
        image.setHeader("Content-ID", "<logo123>");
        image.setDisposition("inline");
        related.addBodyPart(image);
        MimeMessage m = message();
        m.setContent(related);
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertTrue(parsed.html().contains("data:image/png;base64,AQIDBA=="), parsed.html());
        assertFalse(parsed.html().contains("cid:"));
        assertFalse(parsed.hasRemoteImages());
        assertEquals(List.<Mail.Attachment>of(), parsed.attachments());
    }

    @Test
    void remoteImagesAreFlaggedAndScriptsAreGone() throws Exception {
        MimeMessage m = message();
        m.setContent("<p>hi</p><img src=\"https://tracker.example/pixel.gif\"><script>alert(1)</script>", "text/html; charset=utf-8");
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertTrue(parsed.hasRemoteImages());
        assertFalse(parsed.html().contains("script"));
    }

    @Test
    void anEmptyMessageDoesNotCrash() throws Exception {
        MimeMessage m = message();
        m.setText("", "utf-8");
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertEquals("", parsed.snippet());
    }

    @Test
    void aLongPreviewIsCutOffWithAnEllipsis() throws Exception {
        MimeMessage m = message();
        m.setText("word ".repeat(100), "utf-8");
        BodyParser.Parsed parsed = BodyParser.parse(roundTrip(m));
        assertTrue(parsed.snippet().endsWith("…"));
        assertTrue(parsed.snippet().length() <= 141);
    }
}
