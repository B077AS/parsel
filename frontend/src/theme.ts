import { computed, reactive, watch } from 'vue';
import { accentByKey } from './accents';
import { api, type Density, type Preference } from './api';

// The user's theme choice (system, light or dark), accent and list preferences. Java remembers them and injects them
// as window.__PARSEL__ so the first paint is right; here they are applied to the page (data-theme + CSS variables)
// and pushed back so the native title bar matches the page.

const boot = window.__PARSEL__;
const darkQuery = matchMedia('(prefers-color-scheme: dark)');

export const theme = reactive({
  preference: (boot?.preference ?? 'system') as Preference,
  accent: boot?.accent ?? 'blue',
  systemDark: darkQuery.matches,
});

export const prefs = reactive({
  density: (boot?.density ?? 'comfortable') as Density,
  listWidth: boot?.listWidth ?? 380,
});

/** The tray option. `available` is false on desktops that show no tray icons (GNOME without the AppIndicator extension). */
export const tray = reactive({
  closeToTray: boot?.closeToTray ?? false,
  available: boot?.trayAvailable ?? false,
  error: '',
});

/** Turns "close to tray" on or off; Java checks the desktop and remembers it, so the switch only moves once it agreed. */
export async function setCloseToTray(enabled: boolean): Promise<void> {
  tray.error = '';
  try {
    await api.setCloseToTray(enabled);
    tray.closeToTray = enabled;
  } catch (e) {
    tray.error = e instanceof Error ? e.message : String(e);
  }
}

/** How often Parsel checks for mail (minutes, 0 = only when asked) and whether new mail raises a system notification. */
export const refresh = reactive({
  refreshMinutes: boot?.refreshMinutes ?? 5,
  notifyNewMail: boot?.notifyNewMail ?? true,
});

export const REFRESH_CHOICES: { minutes: number; label: string }[] = [
  { minutes: 1, label: '1 min' },
  { minutes: 5, label: '5 min' },
  { minutes: 15, label: '15 min' },
  { minutes: 30, label: '30 min' },
  { minutes: 60, label: '1 hour' },
  { minutes: 0, label: 'Manual' },
];

export async function setRefreshMinutes(minutes: number): Promise<void> {
  const before = refresh.refreshMinutes;
  refresh.refreshMinutes = minutes;
  try {
    await api.setRefreshMinutes(minutes);
  } catch (e) {
    refresh.refreshMinutes = before;
    console.warn('Could not save the refresh interval:', e);
  }
}

export async function setNotifyNewMail(enabled: boolean): Promise<void> {
  const before = refresh.notifyNewMail;
  refresh.notifyNewMail = enabled;
  try {
    await api.setNotifyNewMail(enabled);
  } catch (e) {
    refresh.notifyNewMail = before;
    console.warn('Could not save the notification setting:', e);
  }
}

export const scheme = computed<'light' | 'dark'>(() =>
  theme.preference === 'system' ? (theme.systemDark ? 'dark' : 'light') : theme.preference,
);

const HEX = /^#[0-9a-f]{6}$/i;

function applyTheme(): void {
  const root = document.documentElement;
  const dark = scheme.value === 'dark';
  const accent = accentByKey(theme.accent);
  root.dataset.theme = scheme.value;
  root.style.setProperty('--accent', dark ? accent.dark : accent.light);
  root.style.setProperty('--on-accent', dark ? accent.onDark : accent.onLight);

  // The native title bar and the web view background are painted by Java with the page's own colours.
  const css = getComputedStyle(root);
  const background = css.getPropertyValue('--bg-side').trim();
  const text = css.getPropertyValue('--text').trim();
  const accentColor = css.getPropertyValue('--accent').trim();
  const onAccent = css.getPropertyValue('--on-accent').trim();
  if (![background, text, accentColor, onAccent].every((c) => HEX.test(c))) return;
  api
    .setTheme({ preference: theme.preference, accent: theme.accent, scheme: scheme.value, background, text, accentColor, onAccent })
    .catch((e) => console.warn('Could not update the window theme:', e));
}

let started = false;

export function initTheme(): void {
  if (started) return;
  started = true;
  darkQuery.addEventListener('change', (event) => {
    theme.systemDark = event.matches;
  });
  watch([scheme, () => theme.accent, () => theme.preference], applyTheme, { immediate: true, flush: 'sync' });
  watch(
    () => prefs.density,
    (density, old) => {
      // The first run only applies what Java already has; later runs are the user changing something.
      if (old !== undefined) api.setDensity(density).catch((e) => console.warn('Could not save the list settings:', e));
    },
    { immediate: true, flush: 'sync' },
  );
}
