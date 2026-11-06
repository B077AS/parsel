package io.github.b077as.parsel.dev;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.util.Date;
import java.util.Properties;

/**
 * A throwaway mail server with sample mail, for trying Parsel without a real account. Not a test: run it with
 * {@code mvn test-compile exec:java -Dexec.mainClass=io.github.b077as.parsel.dev.DevMailServer -Dexec.classpathScope=test}.
 *
 * <p>Then add an account in Parsel with: address {@code will@localhost}, password {@code secret}, and under Edit:
 * incoming server 127.0.0.1 port 3143, outgoing server 127.0.0.1 port 3025, both with security "None", username {@code will}.
 */
public final class DevMailServer {

    private static final Session SESSION = Session.getInstance(new Properties());

    private DevMailServer() {}

    public static void main(String[] args) throws Exception {
        GreenMail server = new GreenMail(ServerSetupTest.SMTP_IMAP);
        server.start();
        GreenMailUser user = server.setUser("will@localhost", "will", "secret");
        var imap = server.getManagers().getImapHostManager();
        imap.createMailbox(user, "Sent");
        imap.createMailbox(user, "Receipts");
        imap.createMailbox(user, "Travel");

        long hour = 3_600_000L;
        long now = System.currentTimeMillis();
        user.deliver(text("Elena Marchetti <elena@marchetti.example>", "Saturday dinner?",
                "Hi Will,\n\nI booked the place near the station for Saturday, 8pm. They had a table for four but I told them it might be three.\n\nCan you let me know by tomorrow evening?\n\nElena",
                now - 2 * hour));
        user.deliver(withAttachment("Northwind Hosting <billing@northwind.example>", "Invoice 20418 for October",
                "<p>Hello,</p><p>Your invoice for October is attached. The total is <b>&euro;14.90</b>.</p>", "invoice-20418.pdf", now - 5 * hour));
        user.deliver(html("Tomás Duarte <tomas@duarte.example>", "Re: Release checklist",
                "<p>Added the Linux packaging step. Can you check the order of 4 and 5?</p><blockquote>On Mon, Will wrote:<br>Looks good to me.</blockquote><p>T.</p>", now - 6 * hour));
        user.deliver(html("Calloway Weekly <hello@calloway.example>", "Issue 212: The quiet web",
                "<h2>The quiet web</h2><p>This week: small sites, RSS comes back.</p><img src=\"https://tracker.example/pixel.gif\" width=\"1\" height=\"1\"><script>alert('should be removed')</script>",
                now - 30 * hour));
        user.deliver(inlineImage("Linden Rail <tickets@linden.example>", "Your ticket to Turin", now - 26 * hour));
        user.deliver(text("Jonas Weber <jonas@weber.example>", "Quick question about the contract",
                "Section 4 says 30 days, the email said 14. Which one is right?\n\nJonas", now - 4 * 24 * hour));
        imap.getFolder(user, "Receipts").appendMessage(text("Shop <orders@shop.example>", "Your receipt", "Thanks for your order.", now - 3 * 24 * hour), new jakarta.mail.Flags(), new Date());
        imap.getFolder(user, "Sent").appendMessage(text("Will <will@localhost>", "Re: Saturday dinner?", "8pm works for me.", now - hour), new jakarta.mail.Flags(jakarta.mail.Flags.Flag.SEEN), new Date());

        System.out.println("Dev mail server running: IMAP 127.0.0.1:3143, SMTP 127.0.0.1:3025, login will / secret, no encryption. Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    private static MimeMessage base(String from, String subject, long when) throws Exception {
        MimeMessage m = new MimeMessage(SESSION);
        m.setFrom(new InternetAddress(from));
        m.setRecipients(Message.RecipientType.TO, "will@localhost");
        m.setSubject(subject, "utf-8");
        m.setSentDate(new Date(when));
        return m;
    }

    private static MimeMessage text(String from, String subject, String body, long when) throws Exception {
        MimeMessage m = base(from, subject, when);
        m.setText(body, "utf-8");
        m.saveChanges();
        return m;
    }

    private static MimeMessage html(String from, String subject, String body, long when) throws Exception {
        MimeMessage m = base(from, subject, when);
        m.setContent(body, "text/html; charset=utf-8");
        m.saveChanges();
        return m;
    }

    private static MimeMessage withAttachment(String from, String subject, String htmlBody, String fileName, long when) throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        MimeBodyPart body = new MimeBodyPart();
        body.setContent(htmlBody, "text/html; charset=utf-8");
        mixed.addBodyPart(body);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(new byte[182_000], "application/pdf")));
        file.setFileName(fileName);
        file.setDisposition("attachment");
        mixed.addBodyPart(file);
        MimeMessage m = base(from, subject, when);
        m.setContent(mixed);
        m.saveChanges();
        return m;
    }

    private static MimeMessage inlineImage(String from, String subject, long when) throws Exception {
        // a 1x1 red PNG, referenced by Content-ID from the HTML
        byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        MimeMultipart related = new MimeMultipart("related");
        MimeBodyPart body = new MimeBodyPart();
        body.setContent("<p>Your booking is confirmed.</p><p><img src=\"cid:logo1\" width=\"60\" height=\"20\" alt=\"logo\"></p><p><b>Departs 07:42</b> from platform 6.</p>", "text/html; charset=utf-8");
        related.addBodyPart(body);
        MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new jakarta.activation.DataHandler(new ByteArrayDataSource(png, "image/png")));
        image.setHeader("Content-ID", "<logo1>");
        image.setDisposition("inline");
        related.addBodyPart(image);
        MimeMessage m = base(from, subject, when);
        m.setContent(related);
        m.saveChanges();
        return m;
    }
}
