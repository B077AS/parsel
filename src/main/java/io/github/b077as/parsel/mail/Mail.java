package io.github.b077as.parsel.mail;

import java.util.List;

/** The mail data the page sees. Plain records so Gson turns them into the JSON the page expects. */
public final class Mail {

    private Mail() {}

    /** @param id stable id such as {@code personal}; the page adds its own "all accounts" entry */
    public record Account(String id, String name, String email) {}

    /** @param id {@code inbox}, {@code sent}, ... for the standard folders; unread is how many messages are unread */
    /** {@code depth} is how many folders it sits inside (0 for a top-level one); the sidebar indents by it. */
    public record Folder(String id, String name, int unread, int depth) {

        public Folder(String id, String name, int unread) {
            this(id, name, unread, 0);
        }
    }

    public record Attachment(String name, long bytes) {}

    /** One row of the message list: everything except the body. {@code epochMillis} is UTC. */
    public record Summary(String id, String account, String fromName, String fromAddress, String subject, String snippet,
                          long epochMillis, boolean unread, boolean starred, int thread, boolean hasAttachments, boolean important) {

        public Summary(String id, String account, String fromName, String fromAddress, String subject, String snippet,
                       long epochMillis, boolean unread, boolean starred, int thread, boolean hasAttachments) {
            this(id, account, fromName, fromAddress, subject, snippet, epochMillis, unread, starred, thread, hasAttachments, false);
        }
    }

    /** A message opened in the reader. {@code bodyHtml} is untrusted: the page shows it in a sandboxed frame. */
    public record Detail(Summary summary, List<String> to, String bodyHtml, boolean hasRemoteImages,
                         List<Attachment> attachments) {}
}
