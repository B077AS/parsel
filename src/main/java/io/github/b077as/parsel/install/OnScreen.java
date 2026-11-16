package io.github.b077as.parsel.install;

import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jpack.Jpack;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shows the main window and tells the jpack stub that it is on screen. The launcher started Parsel next to itself
 * ({@code Jpack.startNow}) and stays open until it hears this, so there is no moment with nothing on screen between the two.
 *
 * <p>Said once per run. Without a stub (IDE, {@code mvn exec:exec}, {@code java -jar}) only the window is shown.
 */
public final class OnScreen {

    private static final AtomicBoolean SAID = new AtomicBoolean();

    private OnScreen() {}

    public static void show(Application app, WebWindow window) {
        window.show(); // harmless if the window is already showing
        if (!Jpack.isAvailable() || !SAID.compareAndSet(false, true)) {
            return;
        }
        // Queued on the UI thread after show(), so the window is visible before the launcher hears of it and closes.
        app.dispatch(() -> Thread.ofVirtual().name("jpack-shown").start(OnScreen::tellTheStub));
    }

    private static void tellTheStub() {
        try (Jpack jpack = Jpack.connect()) {
            jpack.notifyShown().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // The launcher closes on its own after a while; nothing the user needs to see.
            System.getLogger(OnScreen.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not tell the jpack stub that the window is on screen: " + e);
        }
    }
}
