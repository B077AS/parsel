package io.github.b077as.parsel.mail;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invented mail so the UI can be built before the IMAP layer exists. Dates are relative to now so "Today" stays today. */
public final class FakeMailService implements MailService {

    private record Entry(Mail.Detail detail, String folder) {}

    private static final List<Mail.Account> ACCOUNTS = List.of(
            new Mail.Account("personal", "Personal", "will@example.com"),
            new Mail.Account("work", "Work", "will@northfield.example"));

    private final Map<String, Entry> messages = new LinkedHashMap<>();

    public FakeMailService() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        add("m1", "personal", "Elena Marchetti", "elena@marchetti.example", "Saturday dinner?",
                "I booked the place near the station, 8pm works if you can both make it.",
                at(today, 9, 41, zone), true, false, 0, false, List.of(),
                "<p>Hi Will,</p><p>I booked the place near the station for Saturday, 8pm. They had a table for four but I told them it might be three.</p>"
                        + "<p>Can you let me know by tomorrow evening? If Tom&aacute;s can't come I'll move it to the small table by the window, which is nicer anyway.</p>"
                        + "<p>Also: are you still vegetarian? The menu changed and I want to be sure before I say yes to the tasting one.</p><p>Elena</p>");
        add("m2", "personal", "Northwind Hosting", "billing@northwind.example", "Invoice 20418 for October",
                "Your invoice is attached. Payment is collected automatically on the 3rd.",
                at(today, 8, 12, zone), false, false, 0, false, List.of(new Mail.Attachment("invoice-20418.pdf", 182_000)),
                "<p>Hello,</p><p>Your invoice for October is attached. The total is <b>&euro;14.90</b>, collected automatically from the card ending 4417 on the 3rd.</p>"
                        + "<p>If anything looks wrong, reply to this message and we'll sort it out.</p><p>Northwind Hosting billing</p>");
        add("m3", "work", "Tomás Duarte", "tomas@duarte.example", "Re: Release checklist",
                "Added the Linux packaging step. Can you check the order of 4 and 5?",
                at(today, 7, 55, zone), true, false, 3, false, List.of(),
                "<p>Added the Linux packaging step. Can you check the order of 4 and 5? I think signing has to happen before we generate the checksums, otherwise they won't match.</p>"
                        + "<blockquote>On Mon, Will wrote:<br>Looks good to me, except I'd move the changelog earlier.</blockquote><p>T.</p>");
        add("m4", "personal", "Pine Street Library", "noreply@pinestreet.example", "Your hold is ready for pickup",
                "“The Design of Everyday Things” is waiting at the front desk.",
                at(today.minusDays(1), 18, 20, zone), false, false, 0, false, List.of(),
                "<p>The following item is ready for pickup at the front desk for the next seven days:</p><p><b>The Design of Everyday Things</b>, Don Norman</p>");
        add("m5", "personal", "Priya Raman", "priya@raman.example", "Photos from the weekend",
                "Here are the ones I liked. The harbour one came out better than I expected.",
                at(today.minusDays(1), 12, 3, zone), false, false, 0, false,
                List.of(new Mail.Attachment("harbour.jpg", 3_400_000), new Mail.Attachment("market.jpg", 2_900_000)),
                "<p>Here are the ones I liked. The harbour one came out better than I expected, so I attached it at full size.</p><p>Let me know which you want printed.</p><p>P</p>");
        add("m6", "personal", "Linden Rail", "tickets@linden.example", "Your ticket to Turin",
                "Departs 07:42 from platform 6. Seat 41 in coach 3.",
                at(today.minusDays(1), 9, 30, zone), false, true, 0, false, List.of(new Mail.Attachment("ticket.pdf", 96_000)),
                "<p>Your booking is confirmed.</p><p><b>Departs 07:42</b> from platform 6, arrives 10:58.<br>Coach 3, seat 41.</p>");
        add("m7", "personal", "Calloway Weekly", "hello@calloway.example", "Issue 212: The quiet web",
                "This week: small sites, RSS comes back, and a long read about printing.",
                at(today.minusDays(3), 10, 2, zone), false, false, 0, true, List.of(),
                "<p><b>The quiet web</b></p><p>This week: small sites that have been running for twenty years, why RSS keeps coming back, and a long read about how books are really printed.</p>"
                        + "<p>Thanks for reading,<br>The Calloway team</p>");
        add("m8", "work", "Jonas Weber", "jonas@weber.example", "Quick question about the contract",
                "Section 4 says 30 days, the email said 14. Which one is right?",
                at(today.minusDays(4), 16, 47, zone), false, false, 0, false, List.of(),
                "<p>Hi Will,</p><p>Section 4 of the contract says 30 days notice, but your email said 14. Which one is right? I'd like to get this signed before Friday.</p><p>Jonas</p>");
    }

    private static long at(LocalDate day, int hour, int minute, ZoneId zone) {
        return day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli();
    }

    private void add(String id, String account, String fromName, String fromAddress, String subject, String snippet,
                     long when, boolean unread, boolean starred, int thread, boolean remoteImages,
                     List<Mail.Attachment> attachments, String body) {
        Mail.Summary summary = new Mail.Summary(id, account, fromName, fromAddress, subject, snippet, when, unread, starred,
                thread, !attachments.isEmpty());
        messages.put(id, new Entry(new Mail.Detail(summary, List.of("will@example.com"), body, remoteImages, attachments), "inbox"));
    }

    @Override
    public List<Mail.Account> accounts() {
        return ACCOUNTS;
    }

    @Override
    public synchronized List<Mail.Folder> folders(String accountId) {
        int unread = (int) inFolder(accountId, "inbox").stream().filter(Mail.Summary::unread).count();
        return List.of(new Mail.Folder("inbox", "Inbox", unread), new Mail.Folder("starred", "Starred", 0),
                new Mail.Folder("sent", "Sent", 0), new Mail.Folder("drafts", "Drafts", 0),
                new Mail.Folder("archive", "Archive", 0), new Mail.Folder("spam", "Spam", 0),
                new Mail.Folder("trash", "Trash", 0));
    }

    @Override
    public synchronized List<Mail.Summary> list(String accountId, String folderId) {
        List<Mail.Summary> rows = inFolder(accountId, folderId);
        rows.sort((a, b) -> Long.compare(b.epochMillis(), a.epochMillis()));
        return rows;
    }

    private List<Mail.Summary> inFolder(String accountId, String folderId) {
        List<Mail.Summary> rows = new ArrayList<>();
        for (Entry entry : messages.values()) {
            Mail.Summary summary = entry.detail().summary();
            boolean accountMatches = accountId.equals("all") || summary.account().equals(accountId);
            boolean folderMatches = folderId.equals("starred") ? summary.starred() : entry.folder().equals(folderId);
            if (accountMatches && folderMatches) {
                rows.add(summary);
            }
        }
        return rows;
    }

    @Override
    public synchronized Mail.Detail get(String messageId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        return entry.detail();
    }

    @Override
    public synchronized void setStarred(String messageId, boolean starred) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        Mail.Detail d = entry.detail();
        Mail.Summary s = d.summary();
        Mail.Summary updated = new Mail.Summary(s.id(), s.account(), s.fromName(), s.fromAddress(), s.subject(), s.snippet(),
                s.epochMillis(), s.unread(), starred, s.thread(), s.hasAttachments(), s.important());
        messages.put(messageId, new Entry(new Mail.Detail(updated, d.to(), d.bodyHtml(), d.hasRemoteImages(), d.attachments()),
                entry.folder()));
    }

    @Override
    public synchronized void setImportant(String messageId, boolean important) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        Mail.Detail d = entry.detail();
        Mail.Summary s = d.summary();
        Mail.Summary updated = new Mail.Summary(s.id(), s.account(), s.fromName(), s.fromAddress(), s.subject(), s.snippet(),
                s.epochMillis(), s.unread(), s.starred(), s.thread(), s.hasAttachments(), important);
        messages.put(messageId, new Entry(new Mail.Detail(updated, d.to(), d.bodyHtml(), d.hasRemoteImages(), d.attachments()),
                entry.folder()));
    }

    @Override
    public synchronized void archive(String messageId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        messages.put(messageId, new Entry(entry.detail(), "archive"));
    }

    @Override
    public synchronized void moveTo(String messageId, String folderId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        if (folders("all").stream().noneMatch(f -> f.id().equals(folderId) && !f.id().equals("starred"))) {
            throw new IllegalArgumentException("No such folder: " + folderId);
        }
        messages.put(messageId, new Entry(entry.detail(), folderId));
    }

    @Override
    public Mail.Folder createFolder(String accountId, String parentFolderId, String name) {
        throw new IllegalStateException("This is the demo: folders cannot be created in it.");
    }

    @Override
    public synchronized void trash(String messageId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        messages.put(messageId, new Entry(entry.detail(), "trash"));
    }

    @Override
    public synchronized void restore(String messageId) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        messages.put(messageId, new Entry(entry.detail(), "inbox"));
    }

    @Override
    public synchronized void markRead(String messageId, boolean read) {
        Entry entry = messages.get(messageId);
        if (entry == null) {
            throw new IllegalArgumentException("No such message: " + messageId);
        }
        Mail.Detail d = entry.detail();
        Mail.Summary s = d.summary();
        Mail.Summary updated = new Mail.Summary(s.id(), s.account(), s.fromName(), s.fromAddress(), s.subject(), s.snippet(),
                s.epochMillis(), !read, s.starred(), s.thread(), s.hasAttachments(), s.important());
        messages.put(messageId, new Entry(new Mail.Detail(updated, d.to(), d.bodyHtml(), d.hasRemoteImages(), d.attachments()),
                entry.folder()));
    }
}
