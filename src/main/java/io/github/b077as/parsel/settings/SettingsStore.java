package io.github.b077as.parsel.settings;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

/**
 * The app's remembered settings. The window needs the theme ones at startup, before any page has loaded, to be created
 * with the right title bar and background from its first frame, so this lives in Java and not in the page's storage.
 */
public final class SettingsStore {

    public static final Set<String> PREFERENCES = Set.of("system", "light", "dark");
    public static final Set<String> ACCENTS = Set.of("blue", "teal", "green", "orange", "red", "pink", "violet", "graphite");
    public static final Set<String> DENSITIES = Set.of("comfortable", "compact");
    /** Minutes between background syncs; 0 means only when asked. */
    public static final Set<Integer> REFRESH_CHOICES = Set.of(0, 1, 5, 15, 30, 60);
    public static final int REFRESH_DEFAULT = 5;
    public static final int LIST_WIDTH_MIN = 260;
    public static final int LIST_WIDTH_MAX = 800;
    public static final int LIST_WIDTH_DEFAULT = 380;

    /**
     * @param preference    what the user picked: {@code system}, {@code light} or {@code dark}
     * @param accent        one of {@link #ACCENTS}
     * @param density       one of {@link #DENSITIES}
     * @param scheme        what the preference resolved to the last time the page ran, or {@code null} before the first run
     * @param backgroundRgb the window background as {@code 0xRRGGBB}
     * @param textRgb       the window text colour as {@code 0xRRGGBB}
     * @param listWidth     width in pixels of the message list, between {@link #LIST_WIDTH_MIN} and {@link #LIST_WIDTH_MAX}
     * @param refreshMinutes how often to check for mail in the background (one of {@link #REFRESH_CHOICES}, 0 = never automatically)
     * @param notifyNewMail  whether a system notification is shown for new mail
     * @param closeToTray   whether the close button hides the window to the tray icon instead of quitting
     */
    public record Settings(String preference, String accent, String density,
                           String scheme, int backgroundRgb, int textRgb, int listWidth, boolean closeToTray,
                           Integer refreshMinutes, Boolean notifyNewMail) {

        static Settings defaults() {
            return new Settings("system", "blue", "comfortable", null, 0, 0, LIST_WIDTH_DEFAULT, false, REFRESH_DEFAULT, true);
        }

        public Settings withTheme(String preference, String accent, String scheme, int backgroundRgb, int textRgb) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, refreshMinutes, notifyNewMail);
        }

        public Settings withDensity(String density) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, refreshMinutes, notifyNewMail);
        }

        public Settings withCloseToTray(boolean closeToTray) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, refreshMinutes, notifyNewMail);
        }

        public Settings withRefreshMinutes(int minutes) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, minutes, notifyNewMail);
        }

        public Settings withNotifyNewMail(boolean notify) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, refreshMinutes, notify);
        }

        public Settings withListWidth(int listWidth) {
            return new Settings(preference, accent, density, scheme, backgroundRgb, textRgb, listWidth, closeToTray, refreshMinutes, notifyNewMail);
        }
    }

    private static final Gson GSON = new Gson();

    private final Path file;
    private Settings current;

    public SettingsStore(Path file) {
        this.file = file;
        this.current = load(file);
    }

    public synchronized Settings get() {
        return current;
    }

    public synchronized void save(Settings settings) {
        current = settings;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(settings), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save settings to " + file, e);
        }
    }

    private static Settings load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Settings.defaults();
        }
        try {
            Settings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Settings.class);
            // A hand-edited or damaged file must not stop the app from starting.
            boolean valid = loaded != null
                    && PREFERENCES.contains(loaded.preference())
                    && ACCENTS.contains(loaded.accent())
                    && DENSITIES.contains(loaded.density());
            if (!valid) {
                return Settings.defaults();
            }
            // Files written before the width existed read back as 0.
            boolean widthOk = loaded.listWidth() >= LIST_WIDTH_MIN && loaded.listWidth() <= LIST_WIDTH_MAX;
            Settings result = widthOk ? loaded : loaded.withListWidth(LIST_WIDTH_DEFAULT);
            // Files written before these existed read back as null.
            if (result.refreshMinutes() == null || !REFRESH_CHOICES.contains(result.refreshMinutes())) {
                result = result.withRefreshMinutes(REFRESH_DEFAULT);
            }
            return result.notifyNewMail() == null ? result.withNotifyNewMail(true) : result;
        } catch (IOException | RuntimeException e) {
            return Settings.defaults();
        }
    }
}
