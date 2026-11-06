package io.github.b077as.parsel;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Gives the process its own AppUserModelID on Windows.
 *
 * <p>Without one, Windows works out an ID from the exe's path and matches the running window to the Start menu and desktop shortcuts
 * that point at that exe, and then the taskbar button shows the <em>shortcut's</em> (fixed blue) icon instead of the icon the window
 * set with WM_SETICON, so the accent colour never reaches the taskbar of an installed Parsel. (Run from a folder with no shortcut, or
 * through java.exe, it did.) An explicit ID that no shortcut carries makes Windows use the window's own icon.
 *
 * <p>Jolt does not set an AppUserModelID; this is a stopgap until it does (see "Jolt issues found" in CLAUDE.md).
 */
final class WindowsTaskbar {

    private WindowsTaskbar() {}

    /** Call once, before any window exists. Does nothing off Windows; a failure only means the taskbar keeps the shortcut's icon. */
    static void setAppId(String id) {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", arena);
            MethodHandle set = linker.downcallHandle(
                    shell32.find("SetCurrentProcessExplicitAppUserModelID").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            var wide = arena.allocateFrom(ValueLayout.JAVA_CHAR, (id + "\0").toCharArray());
            int hr = (int) set.invokeExact(wide);
            if (hr < 0) {
                System.getLogger(WindowsTaskbar.class.getName()).log(System.Logger.Level.WARNING,
                        "SetCurrentProcessExplicitAppUserModelID failed: 0x" + Integer.toHexString(hr));
            }
        } catch (Throwable e) {
            System.getLogger(WindowsTaskbar.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not set the taskbar identity: " + e);
        }
    }
}
