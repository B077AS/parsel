package io.github.b077as.parsel.settings;

import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.RpcException;

import java.util.regex.Pattern;

/**
 * What the page sends to restyle the native window: {@code {preference, accent, scheme, background, text, accentColor, onAccent}}.
 *
 * <p>The page is untrusted input, so every field is checked; a bad value fails the request instead of reaching the
 * operating system or the settings file.
 */
public record ThemeRequest(String preference, String accent, ColorScheme scheme, int background, int text, int accentColor, int onAccent) {

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9a-fA-F]{6}");

    /** The request as the page sends it ({@code window.setTheme}); {@link #parse} checks it. */
    public record Wire(String preference, String accent, String scheme, String background, String text, String accentColor, String onAccent) {}

    /** @throws RpcException {@code bad_params} for a value the window must not be given */
    public static ThemeRequest parse(Wire wire) {
        String preference = text(wire.preference(), "preference");
        if (!SettingsStore.PREFERENCES.contains(preference)) {
            throw RpcException.badParams("preference must be system, light or dark: " + preference);
        }
        String accent = text(wire.accent(), "accent");
        if (!SettingsStore.ACCENTS.contains(accent)) {
            throw RpcException.badParams("unknown accent: " + accent);
        }
        ColorScheme scheme = switch (text(wire.scheme(), "scheme")) {
            case "light" -> ColorScheme.LIGHT;
            case "dark" -> ColorScheme.DARK;
            default -> throw RpcException.badParams("scheme must be light or dark");
        };
        return new ThemeRequest(preference, accent, scheme, color(wire.background(), "background"), color(wire.text(), "text"),
                color(wire.accentColor(), "accentColor"), color(wire.onAccent(), "onAccent"));
    }

    private static String text(String value, String key) {
        if (value == null) {
            throw RpcException.badParams(key + " is required and must be text");
        }
        return value;
    }

    private static int color(String value, String key) {
        String hex = text(value, key);
        if (!HEX_COLOR.matcher(hex).matches()) {
            throw RpcException.badParams(key + " must look like #1a2b3c: " + hex);
        }
        return Integer.parseInt(hex.substring(1), 16);
    }
}
