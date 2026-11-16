package io.github.b077as.parsel.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The theme and accent Parsel saved last ({@code settings.json} in its data folder, written by {@code SettingsStore}), so the
 * launcher opens in the same colours and never looks like a different app. Anything missing or odd falls back to Parsel's defaults.
 *
 * @param scheme     "light", "dark", or "system" when Parsel has never run (the page then follows {@code prefers-color-scheme})
 * @param accentKey  one of Parsel's accents ({@code frontend/src/accents.ts})
 * @param background the window background as {@code 0xRRGGBB} (Parsel's side-bar colour), shown before the page paints
 */
public record Appearance(String scheme, String accentKey, int background) {

    private static final String DEFAULT_ACCENT = "blue";
    /** {@code --bg-side} of frontend/src/styles/tokens.css. */
    static final int LIGHT_BACKGROUND = 0xF3F3F1;
    static final int DARK_BACKGROUND = 0x141413;

    /**
     * Parsel's accents: light, dark, text on light, text on dark. Copied from {@code frontend/src/accents.ts}: keep in sync
     * (and with {@code SettingsStore.ACCENTS}).
     */
    static final Map<String, String[]> ACCENTS = Map.of(
            "blue", new String[]{"#2a62d9", "#6b9bff", "#ffffff", "#0d1526"},
            "teal", new String[]{"#0b8575", "#3cc2ad", "#ffffff", "#04201b"},
            "green", new String[]{"#2f7d3f", "#6cc17e", "#ffffff", "#0a1f0e"},
            "orange", new String[]{"#c2501a", "#f08a55", "#ffffff", "#2a1004"},
            "red", new String[]{"#d8281e", "#ff5347", "#ffffff", "#2a0710"},
            "pink", new String[]{"#b8387b", "#ec7fb4", "#ffffff", "#2b0a1c"},
            "violet", new String[]{"#6a4bc4", "#a58cf0", "#ffffff", "#150c33"},
            "graphite", new String[]{"#44484e", "#c3c7ce", "#ffffff", "#14161a"});

    public static Appearance defaults() {
        return new Appearance("system", DEFAULT_ACCENT, LIGHT_BACKGROUND);
    }

    /** The four accent colours for the page: {@code {light, dark, onLight, onDark}}. */
    public String[] accentColours() {
        return ACCENTS.getOrDefault(accentKey, ACCENTS.get(DEFAULT_ACCENT));
    }

    public static Appearance read(Path settingsFile) {
        if (!Files.isRegularFile(settingsFile)) {
            return defaults();
        }
        try (Reader in = Files.newBufferedReader(settingsFile)) {
            JsonElement root = JsonParser.parseReader(in);
            if (!root.isJsonObject()) {
                return defaults();
            }
            JsonObject o = root.getAsJsonObject();
            String accent = string(o, "accent");
            if (accent == null || !ACCENTS.containsKey(accent)) {
                accent = DEFAULT_ACCENT;
            }
            // "scheme" is what the theme resolved to when Parsel last ran; "preference" may be "system".
            String scheme = string(o, "scheme");
            if (!"light".equals(scheme) && !"dark".equals(scheme)) {
                String preference = string(o, "preference");
                scheme = "light".equals(preference) || "dark".equals(preference) ? preference : "system";
            }
            int fallback = "dark".equals(scheme) ? DARK_BACKGROUND : LIGHT_BACKGROUND;
            int background = o.has("backgroundRgb") && o.get("backgroundRgb").isJsonPrimitive()
                    ? o.get("backgroundRgb").getAsInt() & 0xFFFFFF : 0;
            // 0 means "never saved" (SettingsStore's default), not black.
            return new Appearance(scheme, accent, background == 0 ? fallback : background);
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }
}
