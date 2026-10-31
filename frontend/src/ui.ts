import { reactive } from 'vue';

// Which top-level screen is showing. There is no router: the app has a handful of screens and no URLs worth keeping.
export type Screen = 'mail' | 'settings';

export const ui = reactive({
  screen: 'mail' as Screen,
  /** The add-account card is open. It is a modal: it sits over whichever screen is showing and closing it goes back to that screen. */
  addAccount: false,
  /** True while the add-account card is the very first one (nothing to show behind it); set by AddAccount when it opens. */
  addingFirst: false,
});

/**
 * Set when this window is a message opened in its own window ("pop-out"): the address is `/?popout=<message id>&folder=<folder id>`.
 * That window shows only the reader (and the composer for a reply), without the folder list or the message list.
 */
export const popout: { id: string; folder: string } | null = (() => {
  const query = new URLSearchParams(location.search);
  const id = query.get('popout');
  return id && /^[A-Za-z0-9_-]{1,40}$/.test(id) ? { id, folder: query.get('folder') || 'inbox' } : null;
})();

/** Set when this window is a message being written, moved here from the main window: the address is `/?compose=<token>`. */
export const composeToken: string | null = (() => {
  const token = new URLSearchParams(location.search).get('compose');
  return token && /^[0-9a-f-]{36}$/.test(token) ? token : null;
})();

/** True when Java runs with -Dparsel.demo=true: invented mail, no accounts, no keystore. */
export const demo = window.__PARSEL__?.demo ?? false;

/**
 * A notification in the top-right corner (a title and an optional line under it), as in Komm: it slides in, stays three
 * seconds, and slides out. Hovering it holds it open. One at a time; a new one replaces the old. The text stays while it slides out.
 */
export const toast = reactive({ title: '', text: '', warning: false, icon: '', visible: false });

const SHOWN_MS = 3000;
let toastTimer: ReturnType<typeof setTimeout> | undefined;

/** @param icon an @mdi/js path; the default is an info icon, or an alert icon for a warning */
export function showToast(title: string, text = '', warning = false, icon = ''): void {
  toast.title = title;
  toast.text = text;
  toast.warning = warning;
  toast.icon = icon;
  toast.visible = true;
  resumeToast();
}

export function hideToast(): void {
  clearTimeout(toastTimer);
  toast.visible = false;
}

export function holdToast(): void {
  clearTimeout(toastTimer);
}

export function resumeToast(): void {
  clearTimeout(toastTimer);
  toastTimer = setTimeout(hideToast, SHOWN_MS);
}
