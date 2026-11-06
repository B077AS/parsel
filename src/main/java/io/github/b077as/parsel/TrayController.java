package io.github.b077as.parsel;

import io.github.b077as.parsel.settings.SettingsStore;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.CloseBehavior;
import io.github.b077as.jolt.Tray;
import io.github.b077as.jolt.TrayMenu;
import io.github.b077as.jolt.WebWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

/**
 * The "Close to tray" option: while it is on, Parsel has an icon in the system tray, the window's close button only hides the
 * window, and the icon's menu brings it back, writes a message, checks for mail, opens Settings or really quits.
 * While it is off there is no tray icon and closing the window ends the app. The menu is drawn by the operating system (Jolt
 * gives native menus), so it follows the system theme and looks like every other tray menu.
 */
final class TrayController {

    private final Application app;
    private final WebWindow window;
    private final SettingsStore settings;
    private final Runnable checkMail;
    private final boolean canCompose;
    private final BiConsumer<String, Object> pageEvent;

    private byte[] icon;
    private Tray tray; // UI thread only

    /**
     * @param checkMail   runs a sync of all accounts, or {@code null} when there is nothing to sync (demo)
     * @param canCompose  whether the menu offers "New message"
     * @param pageEvent   pushes an event to the page ({@code rpc.emit})
     */
    TrayController(Application app, WebWindow window, SettingsStore settings, byte[] icon,
                   Runnable checkMail, boolean canCompose, BiConsumer<String, Object> pageEvent) {
        this.app = app;
        this.window = window;
        this.settings = settings;
        this.icon = icon;
        this.checkMail = checkMail;
        this.canCompose = canCompose;
        this.pageEvent = pageEvent;
    }

    boolean isSupported() {
        return app.isTraySupported();
    }

    /** Applies the saved choice at startup. Call on the UI thread. A desktop with no tray just keeps the normal close button. */
    void start(boolean wanted) {
        if (wanted && isSupported()) {
            apply(true);
        }
    }

    /** Turns the option on or off and remembers it. Safe from any thread; throws if the desktop cannot show a tray icon. */
    void setEnabled(boolean on) {
        try {
            app.submit(() -> {
                change(on);
                return null;
            }).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** The tray icon follows the accent colour like the window icon does. Safe from any thread. */
    void setIcon(byte[] newIcon) {
        app.dispatch(() -> {
            icon = newIcon;
            if (tray != null) {
                tray.setIcon(newIcon);
            }
        });
    }

    private void change(boolean on) {
        apply(on);
        settings.save(settings.get().withCloseToTray(on));
        pageEvent.accept("settings.closeToTray", java.util.Map.of("enabled", on));
    }

    private void apply(boolean on) {
        if (on) {
            if (!isSupported()) {
                throw new IllegalStateException("This desktop does not show tray icons. On GNOME, the AppIndicator extension adds them.");
            }
            if (tray == null) {
                tray = app.createTray(icon, "Parsel");
                tray.onClick(this::open);
                tray.setMenu(menu());
            }
            window.setCloseBehavior(CloseBehavior.HIDE);
        } else {
            window.setCloseBehavior(CloseBehavior.CLOSE);
            if (tray != null) {
                tray.close();
                tray = null;
            }
            // Never leave the window hidden with no icon to bring it back.
            if (!window.isVisible()) {
                window.focus();
            }
        }
    }

    private void open() {
        window.focus();
    }

    private TrayMenu menu() {
        List<TrayMenu.Item> items = new ArrayList<>();
        items.add(TrayMenu.action("Open Parsel", this::open));
        if (canCompose) {
            items.add(TrayMenu.action("New message", () -> {
                open();
                pageEvent.accept("tray.compose", java.util.Map.of());
            }));
        }
        if (checkMail != null) {
            items.add(TrayMenu.action("Check for new mail", checkMail));
        }
        items.add(TrayMenu.separator());
        items.add(TrayMenu.action("Settings", () -> {
            open();
            pageEvent.accept("tray.settings", java.util.Map.of());
        }));
        items.add(TrayMenu.check("Keep running in the tray", true, on -> app.dispatch(() -> change(on))));
        items.add(TrayMenu.separator());
        items.add(TrayMenu.action("Quit Parsel", app::quit));
        return new TrayMenu(items);
    }
}
