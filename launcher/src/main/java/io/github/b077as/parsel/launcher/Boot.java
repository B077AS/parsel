package io.github.b077as.parsel.launcher;

/**
 * Entry point of the launcher: the small window that runs before Parsel.
 *
 * <p>The jpack stub that started it (Parsel.exe / the AppImage) owns the installation. The launcher asks the stub for updates,
 * shows the download, and hands over to Parsel (see {@link UpdateManager}). It writes no log file: like Parsel, it only logs
 * through {@link System.Logger}, which nobody sees in an installed copy. The stub's own {@code jpack.log} is off too
 * ({@code "log": false} in src/jpack/jpack.json) except for a failure that stops the app.
 *
 * <p>{@code -Dlauncher.demo=true} plays every phase without a stub, to look at the window.
 */
public final class Boot {

    /** When the JVM started running this class, for the start-up timing. */
    static final long STARTED = System.currentTimeMillis();

    private Boot() {}

    public static void main(String[] args) {
        LauncherApp.run(Boolean.getBoolean("launcher.demo"));
    }
}
