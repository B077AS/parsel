<script setup lang="ts">
import { mdiAccountOutline, mdiArrowLeft, mdiCogOutline, mdiMonitorDashboard, mdiPaletteOutline, mdiPlus, mdiWeatherNight, mdiWhiteBalanceSunny } from '@mdi/js';
import { ToggleGroupItem, ToggleGroupRoot } from 'reka-ui';
import { ref } from 'vue';
import { ACCENTS } from '../accents';
import type { Density, Preference } from '../api';
import { accountLabel, mail, removeAccount } from '../mail';
import { prefs, REFRESH_CHOICES, refresh, scheme, setCloseToTray, setNotifyNewMail, setRefreshMinutes, theme, tray } from '../theme';
import { demo, ui } from '../ui';
import Icon from './Icon.vue';

const section = ref<'accounts' | 'appearance' | 'general'>('accounts');
const confirming = ref<string | null>(null);
const removeError = ref('');

async function remove(id: string): Promise<void> {
  removeError.value = '';
  try {
    await removeAccount(id);
    confirming.value = null;
  } catch (e) {
    removeError.value = e instanceof Error ? e.message : String(e);
  }
}

// ToggleGroup gives back nothing when the active item is clicked again; a setting must always have a value.
function setPreference(value: unknown): void {
  if (typeof value === 'string') theme.preference = value as Preference;
}
function setDensity(value: unknown): void {
  if (typeof value === 'string') prefs.density = value as Density;
}
function setTray(value: unknown): void {
  if (value === 'on' || value === 'off') void setCloseToTray(value === 'on');
}
function setRefresh(value: unknown): void {
  if (typeof value === 'string') void setRefreshMinutes(Number(value));
}
function setNotify(value: unknown): void {
  if (value === 'on' || value === 'off') void setNotifyNewMail(value === 'on');
}
const swatch = (a: (typeof ACCENTS)[number]) => (scheme.value === 'dark' ? a.dark : a.light);
</script>

<template>
  <div class="settings">
    <nav class="nav">
      <h2>Settings</h2>
      <a tabindex="0" @click="ui.screen = 'mail'" @keydown.enter="ui.screen = 'mail'"><Icon :path="mdiArrowLeft" />Back to mail</a>
      <hr />
      <a :class="{ on: section === 'accounts' }" tabindex="0" @click="section = 'accounts'" @keydown.enter="section = 'accounts'"><Icon :path="mdiAccountOutline" />Accounts</a>
      <a :class="{ on: section === 'appearance' }" tabindex="0" @click="section = 'appearance'" @keydown.enter="section = 'appearance'"><Icon :path="mdiPaletteOutline" />Appearance</a>
      <a :class="{ on: section === 'general' }" tabindex="0" @click="section = 'general'" @keydown.enter="section = 'general'"><Icon :path="mdiCogOutline" />General</a>
    </nav>

    <div class="main">
      <div v-if="section === 'accounts'" class="inner">
        <h1>Accounts</h1>
        <section>
          <div v-for="a in mail.accounts" :key="a.id" class="account">
            <div class="who">
              <b>{{ accountLabel(a) }}</b>
              <small>{{ a.email }}</small>
            </div>
            <template v-if="!demo">
              <template v-if="confirming === a.id">
                <span class="muted">Remove it and its downloaded mail from this computer?</span>
                <button class="btn danger" @click="remove(a.id)">Remove</button>
                <button class="btn" @click="confirming = null">Keep</button>
              </template>
              <button v-else class="btn" @click="confirming = a.id">Remove</button>
            </template>
          </div>
          <p v-if="removeError" class="bad">{{ removeError }}</p>
          <p v-if="!demo" class="muted note">Removing an account deletes its saved password and the mail Parsel downloaded. Your mail on the server is not touched.</p>
          <button v-if="!demo" class="btn" @click="ui.addAccount = true"><Icon :path="mdiPlus" />Add account</button>
          <p v-else class="muted note">This is the demo, so accounts cannot be added or removed.</p>
        </section>
      </div>

      <div v-else-if="section === 'general'" class="inner">
        <h1>General</h1>
        <section>
          <h3>Check for new mail</h3>
          <p>How often Parsel looks for new mail in the background, also while it sits in the tray. The sync button in the toolbar always checks right away.</p>
          <ToggleGroupRoot class="seg" type="single" :model-value="String(refresh.refreshMinutes)" @update:model-value="setRefresh">
            <ToggleGroupItem v-for="c in REFRESH_CHOICES" :key="c.minutes" :value="String(c.minutes)">{{ c.label }}</ToggleGroupItem>
          </ToggleGroupRoot>
        </section>

        <section>
          <h3>Notifications</h3>
          <p>Show a system notification when new mail arrives in an inbox. Clicking it opens the message.</p>
          <ToggleGroupRoot class="seg" type="single" :model-value="refresh.notifyNewMail ? 'on' : 'off'" @update:model-value="setNotify">
            <ToggleGroupItem value="on">On</ToggleGroupItem>
            <ToggleGroupItem value="off">Off</ToggleGroupItem>
          </ToggleGroupRoot>
        </section>

        <section>
          <h3>Close button</h3>
          <p v-if="tray.available">
            When on, closing the window keeps Parsel running in the system tray, so mail keeps arriving. Click the tray icon to open it
            again, or right-click it for the menu. Quit from that menu to close Parsel for good.
          </p>
          <p v-else>This desktop shows no tray icons, so closing the window always quits Parsel. On GNOME, the AppIndicator extension adds a tray.</p>
          <ToggleGroupRoot class="seg" type="single" :model-value="tray.closeToTray ? 'on' : 'off'" :disabled="!tray.available" @update:model-value="setTray">
            <ToggleGroupItem value="off">Quit Parsel</ToggleGroupItem>
            <ToggleGroupItem value="on">Keep running in the tray</ToggleGroupItem>
          </ToggleGroupRoot>
          <p v-if="tray.error" class="bad note">{{ tray.error }}</p>
        </section>
      </div>

      <div v-else class="inner">
        <h1>Appearance</h1>

        <section>
          <h3>Theme</h3>
          <p>System follows your operating system and switches automatically.</p>
          <ToggleGroupRoot class="seg" type="single" :model-value="theme.preference" @update:model-value="setPreference">
            <ToggleGroupItem value="system"><Icon :path="mdiMonitorDashboard" :size="15" />System</ToggleGroupItem>
            <ToggleGroupItem value="light"><Icon :path="mdiWhiteBalanceSunny" :size="15" />Light</ToggleGroupItem>
            <ToggleGroupItem value="dark"><Icon :path="mdiWeatherNight" :size="15" />Dark</ToggleGroupItem>
          </ToggleGroupRoot>
        </section>

        <section>
          <h3>Accent colour</h3>
          <p>Used for the selected message, unread dots, links, focus and the main buttons. Applies immediately.</p>
          <div class="tiles">
            <button
              v-for="a in ACCENTS"
              :key="a.key"
              class="tile"
              :class="{ on: a.key === theme.accent }"
              :style="{ '--tc': swatch(a) }"
              :aria-pressed="a.key === theme.accent"
              @click="theme.accent = a.key"
            >
              <div class="pv"><i style="width: 70%"></i><i class="a"></i><i style="width: 52%"></i><i class="b"></i></div>
              <div class="nm">{{ a.name }}<span>{{ swatch(a) }}</span></div>
            </button>
          </div>
        </section>

        <section>
          <h3>Message list</h3>
          <ToggleGroupRoot class="seg" type="single" :model-value="prefs.density" @update:model-value="setDensity">
            <ToggleGroupItem value="comfortable">Comfortable</ToggleGroupItem>
            <ToggleGroupItem value="compact">Compact</ToggleGroupItem>
          </ToggleGroupRoot>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings {
  flex: 1;
  display: flex;
  min-height: 0;
}
.nav {
  width: var(--sidebar-width);
  flex: none;
  background: var(--bg-side);
  border-right: 1px solid var(--border);
  padding: 14px 10px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.nav h2 {
  font-size: 16px;
  margin: 0 10px 10px;
}
.nav a {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 30px;
  padding: 0 10px;
  border-radius: var(--radius);
  color: var(--text-2);
  cursor: pointer;
}
.nav a:hover,
.nav a.on {
  background: var(--bg-hover);
}
.nav a.on {
  color: var(--text);
  font-weight: 600;
}
.nav a.on svg {
  color: var(--accent);
}
hr {
  border: 0;
  border-top: 1px solid var(--border);
  margin: 6px 0;
  width: 100%;
}
.main {
  flex: 1;
  overflow: auto;
  padding: 28px 40px 60px;
}
.inner {
  max-width: 700px;
  display: flex;
  flex-direction: column;
  gap: 28px;
}
h1 {
  margin: 0;
  font-size: 20px;
}
section h3 {
  margin: 0 0 4px;
  font-size: 14px;
}
section > p {
  margin: 0 0 12px;
  color: var(--text-3);
}
.tiles {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.tile {
  border: 2px solid var(--border);
  border-radius: 8px;
  padding: 10px;
  background: var(--bg-raised);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 9px;
  text-align: left;
}
.tile:hover {
  border-color: var(--border-strong);
}
.tile.on {
  border-color: var(--tc);
}
.pv {
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 7px;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.pv i {
  display: block;
  height: 6px;
  border-radius: 2px;
  background: var(--border-strong);
}
.pv i.a {
  background: color-mix(in srgb, var(--tc) 28%, transparent);
  box-shadow: inset 2px 0 0 var(--tc);
}
.pv i.b {
  height: 12px;
  width: 46%;
  background: var(--tc);
  border-radius: 3px;
}
.nm {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
}
.nm span {
  color: var(--text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
.account {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--border);
}
.account .who {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.account small,
.note {
  color: var(--text-3);
}
.note {
  margin: 10px 0;
}
.bad {
  color: var(--danger);
}
.btn.danger {
  background: var(--danger);
  border-color: var(--danger);
  color: #fff;
}
</style>
