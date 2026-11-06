package io.github.b077as.parsel;

import io.github.b077as.parsel.sync.SyncService.NewMail;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.Notification;

import java.util.List;
import java.util.function.Consumer;

/**
 * Turns mail that just arrived into one system notification (a toast on Windows, a desktop notification on Linux).
 * One message shows who sent it and the subject; several are folded into a single "N new messages" so a busy inbox
 * does not bury the screen. Clicking it brings Parsel forward and opens the (first) message.
 */
final class NewMailNotifier {

    private static final int MAX_LINES = 3;
    private static final int MAX_LINE = 100;

    private NewMailNotifier() {}

    /** @param open runs on the UI thread when the notification is clicked */
    static void show(Application app, List<NewMail> mails, Consumer<NewMail> open) {
        if (mails.isEmpty()) {
            return;
        }
        NewMail first = mails.get(0);
        Notification notification;
        if (mails.size() == 1) {
            notification = new Notification(cut(sender(first), 200), cut(subject(first), 400));
        } else {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < Math.min(mails.size(), MAX_LINES); i++) {
                if (i > 0) {
                    body.append('\n');
                }
                body.append(cut(sender(mails.get(i)) + ": " + subject(mails.get(i)), MAX_LINE));
            }
            if (mails.size() > MAX_LINES) {
                body.append("\nand ").append(mails.size() - MAX_LINES).append(" more");
            }
            notification = new Notification(mails.size() + " new messages", body.toString());
        }
        try {
            app.notify(notification.withOnClick(() -> open.accept(first)));
        } catch (RuntimeException e) {
            // A platform with no notifications simply shows none.
        }
    }

    private static String sender(NewMail mail) {
        if (mail.fromName() != null && !mail.fromName().isBlank()) {
            return mail.fromName().strip();
        }
        if (mail.fromAddress() != null && !mail.fromAddress().isBlank()) {
            return mail.fromAddress().strip();
        }
        return "New message";
    }

    private static String subject(NewMail mail) {
        return mail.subject() == null || mail.subject().isBlank() ? "(no subject)" : mail.subject().strip();
    }

    private static String cut(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}
