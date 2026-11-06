package io.github.b077as.parsel;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Holds a message being written while it lives in a window of its own. The main window leaves it here and gets a token; the new
 * window is opened on that token, reads the message, and keeps this copy up to date as the user types. If that window is closed
 * without sending or discarding, the copy is what goes back to the main window. Nothing is written to disk.
 */
final class DraftStash {

    static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** A message body is HTML the user typed; this is generous and stops a page from filling the memory. */
    static final int MAX_CHARS = 8_000_000;
    private static final int MAX_ENTRIES = 8;

    private final Map<String, JsonObject> drafts = new LinkedHashMap<>();

    /** @return the token the new window is opened with */
    synchronized String put(JsonObject draft) {
        checkSize(draft);
        while (drafts.size() >= MAX_ENTRIES) {
            drafts.remove(drafts.keySet().iterator().next()); // the oldest was never picked up
        }
        String token = UUID.randomUUID().toString();
        drafts.put(token, draft);
        return token;
    }

    /** The draft behind the token, which stays in the stash. */
    synchronized JsonObject peek(String token) {
        return require(drafts.get(valid(token)));
    }

    /** Replaces the draft behind the token (the user typed more). Does nothing if it is gone: it was sent or discarded. */
    synchronized void update(String token, JsonObject draft) {
        checkSize(draft);
        if (drafts.containsKey(valid(token))) {
            drafts.put(token, draft);
        }
    }

    /** Forgets the draft: it was sent, or thrown away. */
    synchronized void discard(String token) {
        drafts.remove(valid(token));
    }

    synchronized boolean has(String token) {
        return TOKEN.matcher(token).matches() && drafts.containsKey(token);
    }

    /** The draft behind the token, which is then forgotten. */
    synchronized JsonObject take(String token) {
        return require(drafts.remove(valid(token)));
    }

    private static String valid(String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("Not a draft token");
        }
        return token;
    }

    private static JsonObject require(JsonObject draft) {
        if (draft == null) {
            throw new IllegalStateException("This message is no longer available. Start it again in the main window.");
        }
        return draft;
    }

    private static void checkSize(JsonObject draft) {
        if (draft.toString().length() > MAX_CHARS) {
            throw new IllegalArgumentException("This message is too large to move to another window.");
        }
    }
}
