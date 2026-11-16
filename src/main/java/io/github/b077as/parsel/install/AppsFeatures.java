package io.github.b077as.parsel.install;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the Windows "Apps &amp; features" entry at the version that is really installed.
 *
 * <p>The Inno Setup installer writes {@code DisplayVersion} once, when it runs. Updates are swapped in by the jpack stub and never
 * run the installer again, so without this the list would show the first version forever. Parsel runs after every swap, so it
 * corrects that one value at start. Nothing else Inno Setup owns is touched.
 *
 * <p>Best effort and silent. Only a copy started by a jpack stub takes part (the stub sets {@code JPACK_VERSION}): an IDE run would
 * otherwise write its own version over the installed one. A machine without the Inno Setup key is skipped, so this never invents an
 * "installed program" entry. The AppImage has no such list.
 */
public final class AppsFeatures {

    /** Must match {@code AppId} in packaging/windows/parsel.iss: Inno Setup names the key {@code <AppId>_is1}. Per-user install, so HKCU. */
    private static final String UNINSTALL_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\{B8F3C1DE-2B1A-4F0E-9A52-6D2E7A1C4F10}_is1";

    private AppsFeatures() {}

    /** Never throws. Blocks for a moment (runs {@code reg}), so call it off the UI thread. */
    public static void syncVersion() {
        String version = System.getenv("JPACK_VERSION");
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") || version == null || version.isBlank()) {
            return;
        }
        try {
            if (reg("query", UNINSTALL_KEY, "/v", "DisplayVersion") != 0) {
                return; // not an installed copy
            }
            reg("add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", version, "/f");
        } catch (Exception e) {
            System.getLogger(AppsFeatures.class.getName()).log(System.Logger.Level.DEBUG,
                    "Could not update the Apps & features version: " + e);
        }
    }

    private static int reg(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("reg");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("reg " + arguments[0] + " timed out");
        }
        return process.exitValue();
    }
}
