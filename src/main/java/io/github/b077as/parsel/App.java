package io.github.b077as.parsel;

import com.google.gson.Gson;
import io.github.b077as.parsel.account.AccountService;
import io.github.b077as.parsel.account.AutoConfig;
import io.github.b077as.parsel.attach.AttachmentService;
import io.github.b077as.parsel.credentials.CredentialBroker;
import io.github.b077as.parsel.mail.FakeMailService;
import io.github.b077as.parsel.mail.LocalMailService;
import io.github.b077as.parsel.mail.MailService;
import io.github.b077as.parsel.imap.ImapClient;
import io.github.b077as.parsel.send.SendService;
import io.github.b077as.parsel.settings.SettingsStore;
import io.github.b077as.parsel.settings.SettingsStore.Settings;
import io.github.b077as.parsel.settings.ThemeRequest;
import io.github.b077as.parsel.store.Database;
import io.github.b077as.parsel.store.MailStore;
import io.github.b077as.parsel.sync.SyncService;
import io.github.b077as.jolt.AppOptions;
import io.github.b077as.jolt.AppResources;
import io.github.b077as.jolt.Application;
import io.github.b077as.jolt.ColorScheme;
import io.github.b077as.jolt.FileDialogOptions;
import io.github.b077as.jolt.ResourceOptions;
import io.github.b077as.jolt.WebWindow;
import io.github.b077as.jolt.WindowOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsel: a desktop webmail client. Java owns mail, storage and logic; the Vue page in {@code frontend/} is the UI.
 *
 * <p>Normally the page is the build output in {@code src/main/resources/ui}, served from inside the web view. With
 * {@code -Dparsel.dev=true} it loads the Vite dev server instead ({@code npm run dev} in {@code frontend/}) for hot reload.
 * {@code -Dparsel.vault=true} keeps passwords in the encrypted vault even where an OS keystore exists. {@code -Dparsel.data=<folder>} uses that folder instead of the real profile. With {@code -Dparsel.demo=true} it shows invented mail and touches no account, database or keystore. With
 * {@code -Dparsel.smoke=true} (which implies demo) it checks that the page rendered, prints the result and exits.
 */
public final class App {

    private static final String APP_ID = "com.parsel.app";
    private static final String DEV_SERVER = "http://127.0.0.1:5173";
    /** The normal size of the window (what Restore gives after maximizing), and the smallest it can be resized to. */
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 720;
    /** The envelope of the window, taskbar and tray icon, as 0xRRGGBB: white in every theme. */
    private static final int ICON_ENVELOPE = 0xFFFFFF;
    private static final Gson GSON = new Gson();

    private App() {}

    public static void main(String[] args) {
        boolean dev = Boolean.getBoolean("parsel.dev");
        boolean smoke = Boolean.getBoolean("parsel.smoke");
        boolean demo = Boolean.getBoolean("parsel.demo") || smoke;

        // Before any window: lets the taskbar show the window's accent icon instead of the installer shortcut's (see WindowsTaskbar).
        WindowsTaskbar.setAppId(APP_ID);

        // GTK's file chooser prints harmless "GFileInfo created without standard::size" warnings on Linux; hide just those.
        AppOptions appOptions = AppOptions.of(APP_ID).withQuietNativeLogs(true);
        // -Dparsel.data=<folder> keeps everything (settings, mail, vault, web view data) apart from the real profile: for tests and trying things out.
        String dataOverride = System.getProperty("parsel.data", "");
        if (!dataOverride.isBlank()) {
            appOptions = appOptions.withDataDirectory(Path.of(dataOverride));
        }
        Path dataDir = appOptions.dataDirectory();
        SettingsStore settings = new SettingsStore(dataDir.resolve("settings.json"));

        Application app = Application.create(appOptions);
        SyncService[] syncToClose = {null};
        try {
            app.run(() -> {
                Settings saved = settings.get();
                // Hidden until the page says its content is on screen (window.ready, below): while the web view starts, a visible
                // window shows an empty body for a second or two. A timer shows it anyway if the page never answers.
                WindowOptions options = inSavedColours(WindowOptions.defaults()
                        .withTitle("Parsel")
                        .withSize(WINDOW_WIDTH, WINDOW_HEIGHT)
                        .withMinSize(WINDOW_WIDTH, WINDOW_HEIGHT)
                        .withMaximized(true)
                        .withIcon(readIcon())
                        .withDevTools(dev)
                        .withVisible(false), saved);
                WebWindow window = app.createWindow(options);
                showAnywayLater(window);
                window.addInitScript(bootScript(saved, app, demo));

                Rpc rpc = new Rpc(window);
                TrayController tray = new TrayController(app, window, settings, readIcon(),
                        demo ? null : () -> syncToClose[0].syncAllAsync(), !demo, rpc::emit);
                tray.start(saved.closeToTray());
                rpc.on("settings.setCloseToTray", params -> {
                    tray.setEnabled(params.get("enabled").getAsBoolean());
                    return Map.of("ok", true);
                });
                registerWindowRpc(app, window, rpc, settings, tray);

                rpc.on("settings.setRefreshMinutes", params -> {
                    int minutes = params.get("minutes").getAsInt();
                    if (!SettingsStore.REFRESH_CHOICES.contains(minutes)) {
                        throw new IllegalArgumentException("unknown refresh interval: " + minutes);
                    }
                    settings.save(settings.get().withRefreshMinutes(minutes));
                    if (syncToClose[0] != null) {
                        syncToClose[0].reschedule(Duration.ofMinutes(minutes));
                    }
                    return Map.of("ok", true);
                });
                rpc.on("settings.setNotifyNewMail", params -> {
                    settings.save(settings.get().withNotifyNewMail(params.get("enabled").getAsBoolean()));
                    return Map.of("ok", true);
                });

                // What a window's page can ask of the mail layer. Every window (the main one and each message opened in its own window)
                // gets the same services; only the file dialogs, which belong to a window, are made per window.
                BiConsumer<Rpc, WebWindow> mailWiring;
                if (demo) {
                    MailService fake = new FakeMailService();
                    mailWiring = (r, w) -> Handlers.register(r, fake, null, null, null, null, null);
                } else {
                    Database database = new Database(dataDir.resolve("mail.db"));
                    MailStore store = new MailStore(database);
                    Path vaultFile = dataDir.resolve("vault.bin");
                    // -Dparsel.vault=true skips the OS keystore and uses the master-password vault (to try it on a machine that has a keystore).
                    CredentialBroker credentials = Boolean.getBoolean("parsel.vault")
                            ? CredentialBroker.vaultOnly(vaultFile)
                            : CredentialBroker.open(APP_ID, vaultFile);
                    SyncService sync = new SyncService(store, credentials,
                            status -> rpc.emit("sync.state", status),
                            accountId -> rpc.emit("mail.changed", Map.of("account", accountId)));
                    syncToClose[0] = sync;
                    AccountService accounts = new AccountService(store, credentials, AutoConfig.online());
                    MailService mail = new LocalMailService(store, sync);
                    AttachmentService sharedFiles = new AttachmentService(store, credentials, ImapClient::connect, fileChooser(window));
                    mailWiring = (r, w) -> {
                        // One list of picked files for all windows (a composer can move to a window of its own), each with its own dialogs.
                        AttachmentService attachments = sharedFiles.forChooser(fileChooser(w));
                        SendService send = new SendService(store, credentials, ImapClient::connect, sync::syncAsync, attachments);
                        Handlers.register(r, mail, accounts, sync, credentials, send, attachments);
                    };
                    sync.onNewMail(mails -> {
                        // No toast while Parsel is the window in front: the list already shows the mail.
                        if (settings.get().notifyNewMail() && !window.isFocused()) {
                            NewMailNotifier.show(app, mails, mail1 -> {
                                window.focus();
                                rpc.emit("notify.open", Map.of("account", mail1.account(), "message", mail1.messageId()));
                            });
                        }
                    });
                    sync.start(Duration.ofMinutes(saved.refreshMinutes()));
                }
                mailWiring.accept(rpc, window);

                // A message in a window of its own: the page asks with jolt.openWindow("/?popout=<id>&folder=<folder>"). Only the main
                // window may ask and only for exactly that address; anything else is refused.
                window.enableOpenWindow(app, request -> {
                    if (request.opener() != window || !POPOUT_PATH.matcher(request.path()).matches()) {
                        return null;
                    }
                    Settings now = settings.get();
                    WebWindow popout = request.createWindow(app, inSavedColours(request.options()
                            .withMinSize(POPOUT_MIN_WIDTH, POPOUT_MIN_HEIGHT)
                            .withIcon(readIcon())
                            .withDevTools(dev)
                            .withVisible(false), now));
                    showAnywayLater(popout);
                    popout.addInitScript(bootScript(now, app, demo));
                    Rpc popoutRpc = new Rpc(popout);
                    registerWindowRpc(app, popout, popoutRpc, settings, tray);
                    mailWiring.accept(popoutRpc, popout);
                    window.onClosed(popout::close); // the app is going away: so are the messages open in their own windows
                    Matcher composing = COMPOSE_PATH.matcher(request.path());
                    if (composing.find()) {
                        String token = composing.group(1);
                        popout.onClosed(() -> {
                            // Closed without sending or discarding: the message goes back to where it was written.
                            if (DRAFTS.has(token)) {
                                try {
                                    window.focus();
                                    rpc.emit("compose.returned", Map.of("token", token));
                                } catch (RuntimeException mainIsGone) {
                                    // the whole app is closing
                                }
                            }
                        });
                    }
                    if (dev) {
                        popout.setSecurity(popout.security().withTrustedOrigins(DEV_SERVER));
                        popout.loadUrl(DEV_SERVER + request.path());
                    } else {
                        popout.loadResources(AppResources.classpath("ui"), null, ResourceOptions.defaults().withStartPath(request.path()));
                    }
                    return popout;
                });

                if (dev) {
                    // The dev server is another origin, so the (secure by default) policy must be told to trust it.
                    window.setSecurity(window.security().withTrustedOrigins(DEV_SERVER));
                    window.loadUrl(DEV_SERVER);
                } else {
                    window.loadResources(AppResources.classpath("ui"));
                }

                if (smoke) {
                    startSmokeCheck(window);
                }
            });
        } finally {
            if (syncToClose[0] != null) {
                syncToClose[0].close();
            }
            app.close();
        }
    }

    /** The only addresses a pop-out window may be opened on: `/?popout=<message id>&folder=<folder id>` or `/?compose=<token>`. */
    private static final Pattern POPOUT_PATH = Pattern.compile(
            "/\\?(popout=[A-Za-z0-9_-]{1,40}&folder=[A-Za-z0-9_-]{1,40}|compose=" + DraftStash.TOKEN.pattern() + ")");
    private static final DraftStash DRAFTS = new DraftStash();
    private static final Pattern COMPOSE_PATH = Pattern.compile("compose=(" + DraftStash.TOKEN.pattern() + ")");
    private static final int POPOUT_MIN_WIDTH = 560;
    private static final int POPOUT_MIN_HEIGHT = 420;

    /** Starts a window in the colours the user last had, so a dark app never flashes white. */
    private static WindowOptions inSavedColours(WindowOptions options, Settings saved) {
        if (saved.scheme() == null) {
            return options;
        }
        return options
                .withTitleBarScheme(saved.scheme().equals("dark") ? ColorScheme.DARK : ColorScheme.LIGHT)
                .withBackgroundColor(saved.backgroundRgb());
    }

    /** What the page reads before its first paint (`window.__PARSEL__`). Every value was validated when it was saved. */
    private static String bootScript(Settings saved, Application app, boolean demo) {
        return "window.__PARSEL__ = " + GSON.toJson(Map.of(
                "preference", saved.preference(), "accent", saved.accent(),
                "density", saved.density(), "listWidth", saved.listWidth(),
                "closeToTray", saved.closeToTray(), "trayAvailable", app.isTraySupported(),
                "refreshMinutes", saved.refreshMinutes(), "notifyNewMail", saved.notifyNewMail(),
                "demo", demo)) + ";";
    }

    /** What every window's page can ask of Java besides mail: info, theme, clipboard paste, opening links, closing itself. */
    private static void registerWindowRpc(Application app, WebWindow window, Rpc rpc, SettingsStore settings, TrayController tray) {
        rpc.on("app.info", params -> Map.of(
                "os", System.getProperty("os.name"),
                "java", System.getProperty("java.version")));
        registerTheme(rpc, window, settings, tray);
        // A message being written moves to a window of its own through here (see DraftStash).
        rpc.on("compose.stash", params -> {
            if (!params.has("draft") || !params.get("draft").isJsonObject()) {
                throw new IllegalArgumentException("Missing draft");
            }
            return Map.of("token", DRAFTS.put(params.getAsJsonObject("draft")));
        });
        // The new window reads the message (it stays here), keeps this copy current while the user types, and drops it when the
        // message is sent or thrown away. Closing the window any other way hands it back to the main window (see the opener).
        rpc.on("compose.peek", params -> DRAFTS.peek(Params.string(params, "token")));
        rpc.on("compose.update", params -> {
            DRAFTS.update(Params.string(params, "token"), params.getAsJsonObject("draft"));
            return Map.of("ok", true);
        });
        rpc.on("compose.discard", params -> {
            DRAFTS.discard(Params.string(params, "token"));
            return Map.of("ok", true);
        });
        rpc.on("compose.take", params -> DRAFTS.take(Params.string(params, "token")));
        // Used by a message in its own window once it has been archived, deleted or moved away.
        rpc.on("window.close", params -> {
            app.dispatch(window::close);
            return Map.of("ok", true);
        });
        // The Paste item of the page's context menu (read with Jolt's clipboard API, plain text).
        rpc.on("clipboard.readText", params -> {
            try {
                return Map.of("text", app.clipboard().readText().get().orElse(""));
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("The clipboard could not be read. Use Ctrl+V to paste.", e.getCause());
            }
        });
        // A link clicked in a message. Jolt only opens http, https and mailto, so a mail cannot start a program with a file: or custom link.
        rpc.on("app.openExternal", params -> {
            URI uri;
            try {
                uri = new URI(Params.string(params, "url"));
                if (!app.openExternal(uri).get()) {
                    throw new IllegalStateException("The system could not open this link.");
                }
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("This is not a link that can be opened.");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Only web and email links can be opened.");
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException("The system could not open this link.");
            }
            return Map.of("ok", true);
        });
    }

    /** How long to wait for the page before showing the window anyway, so a broken page can never leave the app invisible. */
    private static final Duration SHOW_ANYWAY_AFTER = Duration.ofSeconds(10);

    private static void showAnywayLater(WebWindow window) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(SHOW_ANYWAY_AFTER);
            } catch (InterruptedException e) {
                return;
            }
            window.show(); // harmless if the window is already showing
        });
    }

    /** The window icon: one .ico holding the same picture drawn at 16 to 256 px, so Windows never has to scale it. */
    private static byte[] readIcon() {
        try (InputStream in = App.class.getResourceAsStream("/icon.ico")) {
            return Objects.requireNonNull(in, "icon.ico is missing from the resources").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The system's file dialogs, owned by the window. Each call waits for the user, so it must not run on the UI thread. */
    private static AttachmentService.FileChooser fileChooser(WebWindow window) {
        return new AttachmentService.FileChooser() {
            @Override
            public Optional<Path> save(String suggestedName) {
                return await(window.dialogs().saveFile(FileDialogOptions.defaults().withTitle("Save attachment").withFileName(suggestedName)));
            }

            @Override
            public List<Path> open() {
                return await(window.dialogs().openFiles(FileDialogOptions.defaults().withTitle("Attach files")));
            }
        };
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the file dialog", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The file dialog failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static void registerTheme(Rpc rpc, WebWindow window, SettingsStore settings, TrayController tray) {
        int[][] lastIconColours = {null};
        rpc
                // The page has its content on screen: now the window can appear (maximized) without a blank flash.
                .on("window.ready", params -> {
                    window.show();
                    return Map.of("ok", true);
                })
                // The page cannot restyle the operating system's title bar, but it can ask Java to.
                .on("window.setTheme", params -> {
                    ThemeRequest theme = ThemeRequest.parse(params);
                    window.setTitleBarScheme(theme.scheme());
                    window.setTitleBarColors(theme.background(), theme.text());
                    window.setBackgroundColor(theme.background());
                    // The title bar, taskbar and tray icon follow the accent too, with an envelope that is always white (also in dark mode,
                    // where the page uses a dark colour on the accent). Drawn only when the colours changed.
                    int[] colours = {theme.accentColor(), ICON_ENVELOPE};
                    if (!java.util.Arrays.equals(colours, lastIconColours[0])) {
                        lastIconColours[0] = colours;
                        byte[] ico = AppIcon.ico(colours[0], colours[1]);
                        window.setIcon(ico);
                        tray.setIcon(ico);
                    }
                    settings.save(settings.get().withTheme(theme.preference(), theme.accent(),
                            theme.scheme().name().toLowerCase(java.util.Locale.ROOT), theme.background(), theme.text()));
                    return Map.of("ok", true);
                })
                .on("settings.setDensity", params -> {
                    String density = ThemeRequest.string(params, "density");
                    if (!SettingsStore.DENSITIES.contains(density)) {
                        throw new IllegalArgumentException("unknown density: " + density);
                    }
                    settings.save(settings.get().withDensity(density));
                    return Map.of("ok", true);
                })
                .on("settings.setListWidth", params -> {
                    int width = params.get("width").getAsInt();
                    if (width < SettingsStore.LIST_WIDTH_MIN || width > SettingsStore.LIST_WIDTH_MAX) {
                        throw new IllegalArgumentException("list width out of range: " + width);
                    }
                    settings.save(settings.get().withListWidth(width));
                    return Map.of("ok", true);
                });
    }

    /** Waits for the page to load and fetch its mail, then reports what it rendered and closes the window. */
    private static void startSmokeCheck(WebWindow window) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(5_000);
                String script = "JSON.stringify({rows: document.querySelectorAll('[data-msg]').length,"
                        + " theme: document.documentElement.dataset.theme,"
                        + " accent: getComputedStyle(document.documentElement).getPropertyValue('--accent').trim(),"
                        + " title: document.title})";
                System.out.println("SMOKE " + window.eval(script).get());
                // Open the first message and look at the reader, including the sandboxed frame the body is shown in.
                window.eval("document.querySelector('[data-msg]').click()").get();
                Thread.sleep(1_500);
                String reader = "(() => { const f = document.querySelector('iframe'); const d = f && f.contentDocument;"
                        + " return JSON.stringify({subject: (document.querySelector('h1') || {}).textContent,"
                        + " sandbox: f && f.getAttribute('sandbox'), frameHeight: f && f.getBoundingClientRect().height,"
                        + " bodyText: d && d.body.innerText.slice(0, 40),"
                        + " bodyColor: d && getComputedStyle(d.body).color}); })()";
                System.out.println("SMOKE reader " + window.eval(reader).get());
            } catch (Exception e) {
                System.out.println("SMOKE failed: " + e);
            } finally {
                window.close();
            }
        });
    }
}
