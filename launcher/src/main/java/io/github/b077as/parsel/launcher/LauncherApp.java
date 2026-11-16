package io.github.b077as.parsel.launcher;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.TaskbarProgress;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The launcher window: a small frameless Jolt window whose page ({@code launcher.html}) draws a letter that is written while the
 * update downloads, slides into its envelope and is sealed as Parsel starts. Java owns the update ({@link UpdateManager}); the page
 * only draws what it is told. The download also shows on the taskbar button (Windows; best effort on Linux).
 *
 * <p>Messages are JSON strings. Java to page: {@code {type:"phase", phase}}, {@code installed}, {@code update}, {@code progress},
 * {@code note}, {@code error}. Page to Java: {@code ready} (the page is in place), {@code retry}, {@code quit}.
 */
final class LauncherApp implements UpdateManager.Listener {

    private static final System.Logger LOG = System.getLogger(LauncherApp.class.getName());

    /** Parsel's own id: the same data folder, and on Wayland the same app id (the compositor groups the two windows). */
    private static final String APP_ID = "com.parsel.app";
    private static final int WIDTH = 360;
    private static final int HEIGHT = 340;
    /** Show the window even if the page never says it is ready, so a broken page cannot leave a hidden process behind. */
    private static final Duration SHOW_ANYWAY_AFTER = Duration.ofSeconds(6);
    private static final Gson GSON = new Gson();

    private final Application app;
    private final boolean demo;
    private WebWindow window;
    /** The last whole percent shown on the taskbar, so a fast download does not call the shell for every chunk. */
    private int taskbarPercent = -1;
    private double lastFraction;

    private LauncherApp(Application app, boolean demo) {
        this.app = app;
        this.demo = demo;
    }

    static void run(boolean demo) {
        // The folder Parsel uses (Jolt's per-app data folder, or -Dparsel.data=<folder> like Parsel itself).
        String override = System.getProperty("parsel.data", "");
        Path parselData = override.isBlank() ? AppOptions.of(APP_ID).dataDirectory() : Path.of(override);
        Appearance look = Appearance.read(parselData.resolve("settings.json"));
        // The launcher's own web view data sits in a folder of its own, so it never shares a WebView2 profile with a running Parsel.
        AppOptions options = AppOptions.of(APP_ID).withQuietNativeLogs(true).withDataDirectory(parselData.resolve("launcher"));
        Application app = Application.create(options);
        LauncherApp launcher = new LauncherApp(app, demo);
        try {
            app.run(() -> launcher.open(look));
        } finally {
            app.close();
        }
        // The stub waits for this process to end before it swaps files in: leave at once, whatever threads are still around.
        System.exit(0);
    }

    private void open(Appearance look) {
        WindowOptions options = WindowOptions.defaults()
                .withTitle("Parsel")
                .withSize(WIDTH, HEIGHT)
                .withMinSize(WIDTH, HEIGHT)
                .withFrameless(true)
                .withCentered(true)
                .withVisible(false)
                .withIcon(readResource("/icon.ico"))
                .withTitleBarScheme("dark".equals(look.scheme()) ? ColorScheme.DARK : ColorScheme.LIGHT)
                .withBackgroundColor(look.background());
        window = app.createWindow(options);

        String[] accent = look.accentColours();
        Map<String, Object> boot = new LinkedHashMap<>();
        boot.put("scheme", look.scheme());
        boot.put("accent", accent[0]);
        boot.put("accentDark", accent[1]);
        boot.put("onAccent", accent[2]);
        boot.put("onAccentDark", accent[3]);
        window.addInitScript("window.__LAUNCHER__ = " + GSON.toJson(boot) + ";");
        window.onMessage(this::fromPage);
        window.loadHtml(new String(readResource("/launcher.html"), StandardCharsets.UTF_8));

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(SHOW_ANYWAY_AFTER);
            } catch (InterruptedException e) {
                return;
            }
            window.show();
        });
    }

    // ------------------------------------------------------------------------------------ page to Java

    private void fromPage(String message) {
        switch (message) {
            case "ready" -> {
                LOG.log(System.Logger.Level.DEBUG, "Page ready " + (System.currentTimeMillis() - Boot.STARTED) + " ms after the JVM started");
                window.show();
                startFlow();
            }
            case "retry" -> startFlow();
            case "quit" -> app.dispatch(app::quit);
            default -> LOG.log(System.Logger.Level.DEBUG, "Ignored a message from the page: " + message);
        }
    }

    private void startFlow() {
        taskbarPercent = -1;
        window.setTaskbarProgress(TaskbarProgress.none());
        UpdateManager manager = new UpdateManager(this);
        Thread.ofVirtual().name("update-flow").start(demo ? manager::runDemo : manager::run);
    }

    // ------------------------------------------------------------------------------------ Java to page (UpdateManager.Listener)

    @Override
    public void onInstalled(String version) {
        send("installed", Map.of("version", version));
    }

    @Override
    public void onPhase(Phase phase) {
        switch (phase) {
            case DOWNLOADING -> window.setTaskbarProgress(TaskbarProgress.normal(0));
            case INSTALLING -> window.setTaskbarProgress(TaskbarProgress.indeterminate());
            default -> window.setTaskbarProgress(TaskbarProgress.none());
        }
        send("phase", Map.of("phase", phase.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    public void onUpdate(String current, String latest, long bytes) {
        send("update", Map.of("current", current, "latest", latest, "bytes", bytes));
    }

    @Override
    public void onProgress(long received, long total) {
        lastFraction = total > 0 ? Math.min(1, (double) received / total) : 0;
        int percent = (int) (lastFraction * 100);
        if (percent != taskbarPercent) {
            taskbarPercent = percent;
            window.setTaskbarProgress(TaskbarProgress.normal(lastFraction));
        }
        send("progress", Map.of("received", received, "total", total));
    }

    @Override
    public void onNote(String message) {
        send("note", Map.of("message", message));
    }

    @Override
    public void onError(String message) {
        window.setTaskbarProgress(TaskbarProgress.error(Math.max(lastFraction, 0.05)));
        send("error", Map.of("message", message));
    }

    @Override
    public void onLaunched() {
        app.dispatch(app::quit);
    }

    private void send(String type, Map<String, ?> fields) {
        JsonObject o = GSON.toJsonTree(fields).getAsJsonObject();
        o.addProperty("type", type);
        window.postMessage(o.toString());
    }

    private static byte[] readResource(String name) {
        try (InputStream in = LauncherApp.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the launcher jar");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + name, e);
        }
    }
}
