/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<object, object, unknown>;
  export default component;
}

interface Window {
  /** Injected by App.java before the page's scripts run: what the user chose last time, and whether this is the demo. */
  __PARSEL__?: { preference: 'system' | 'light' | 'dark'; accent: string; density: 'comfortable' | 'compact'; listWidth: number; closeToTray: boolean; refreshMinutes: number; notifyNewMail: boolean; trayAvailable: boolean; demo: boolean };
}
