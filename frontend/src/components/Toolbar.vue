<script setup lang="ts">
import {
  mdiAlertCircleOutline,
  mdiCheck,
  mdiChevronDown,
  mdiCogOutline,
  mdiMagnify,
  mdiMonitorDashboard,
  mdiPlus,
  mdiSync,
  mdiWeatherNight,
  mdiWhiteBalanceSunny,
} from '@mdi/js';
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuTrigger } from 'reka-ui';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import type { Preference } from '../api';
import { compose, openCompose } from '../compose';
import { openMoveTo } from '../folders';
import { accountLabel, currentAccount, mail, selectAccount, switcherAccounts, syncNow, syncSummary } from '../mail';
import { theme } from '../theme';
import { demo, ui } from '../ui';
import Icon from './Icon.vue';

const search = ref<HTMLInputElement | null>(null);

const THEME_ORDER: Preference[] = ['system', 'light', 'dark'];
const themeIcon = computed(() => ({ system: mdiMonitorDashboard, light: mdiWhiteBalanceSunny, dark: mdiWeatherNight })[theme.preference]);

function cycleTheme(): void {
  theme.preference = THEME_ORDER[(THEME_ORDER.indexOf(theme.preference) + 1) % THEME_ORDER.length];
}

// "Synced 2 min ago" has to keep counting: re-read the clock every 30 seconds.
const now = ref(Date.now());
let clock: ReturnType<typeof setInterval>;
onMounted(() => {
  clock = setInterval(() => (now.value = Date.now()), 30_000);
  window.addEventListener('keydown', onKey);
});
onBeforeUnmount(() => {
  clearInterval(clock);
  window.removeEventListener('keydown', onKey);
});

function ago(ms: number): string {
  const minutes = Math.floor((now.value - ms) / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours} h ago` : new Date(ms).toLocaleDateString();
}

const syncText = computed(() => {
  if (demo) return 'Demo data';
  const s = syncSummary.value;
  if (s.state === 'syncing') return 'Syncing…';
  if (s.state === 'error') return 'Sync failed';
  return s.lastSync === null ? 'Not synced yet' : `Synced ${ago(s.lastSync)}`;
});
const syncTitle = computed(() => (demo ? 'Parsel is showing invented mail' : (syncSummary.value.error ?? 'Sync now')));

function onKey(event: KeyboardEvent): void {
  const target = event.target as HTMLElement;
  if (ui.addAccount || event.ctrlKey || event.metaKey || event.altKey || target.closest('input, textarea, [contenteditable]')) return;
  if (event.key === '/') {
    event.preventDefault();
    ui.screen = 'mail';
    search.value?.focus();
  } else if (event.key === 'c' && !demo && ui.screen === 'mail' && !compose.open) {
    event.preventDefault();
    openCompose();
  } else if (event.key === 'v' && !demo && ui.screen === 'mail' && !compose.open && mail.selectedId) {
    event.preventDefault();
    void openMoveTo(mail.selectedId);
  }
}
</script>

<template>
  <header class="toolbar">
    <div class="left">
      <DropdownMenuRoot>
        <DropdownMenuTrigger as-child>
          <button class="acct" aria-label="Switch account" :title="currentAccount?.email">
            <span class="mono">{{ currentAccount?.id === 'all' ? '∗' : (currentAccount ? accountLabel(currentAccount)[0]?.toUpperCase() : '?') }}</span>
            <span class="meta">
              <b>{{ currentAccount ? accountLabel(currentAccount) : '…' }}</b>
              <span>{{ currentAccount?.email }}</span>
            </span>
            <Icon :path="mdiChevronDown" :size="15" class="muted" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="menu" align="start" :side-offset="4">
            <DropdownMenuItem v-for="a in switcherAccounts" :key="a.id" class="menu-item" @select="selectAccount(a.id)">
              <span>
                <b>{{ accountLabel(a) }}</b>
                <small>{{ a.email }}</small>
              </span>
              <Icon v-if="a.id === mail.account" :path="mdiCheck" class="check" />
            </DropdownMenuItem>
            <template v-if="!demo">
              <div class="menu-sep"></div>
              <DropdownMenuItem class="menu-item" @select="ui.addAccount = true"><Icon :path="mdiPlus" />Add account…</DropdownMenuItem>
            </template>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>
    </div>

    <label class="search">
      <Icon :path="mdiMagnify" :size="15" />
      <input ref="search" v-model="mail.query" placeholder="Search mail" spellcheck="false" />
      <kbd>/</kbd>
    </label>

    <div class="right">
      <span class="status" :class="{ bad: syncSummary.state === 'error' && !demo }" :title="syncTitle">
        <Icon v-if="syncSummary.state === 'error' && !demo" :path="mdiAlertCircleOutline" :size="15" />
        {{ syncText }}
      </span>
      <button class="btn ghost icon" :class="{ spin: syncSummary.state === 'syncing' && !demo }" title="Sync now" :disabled="demo" @click="syncNow">
        <Icon :path="mdiSync" />
      </button>
      <button class="btn ghost icon" :title="`Theme: ${theme.preference}`" @click="cycleTheme"><Icon :path="themeIcon" /></button>
      <button class="btn ghost icon" title="Settings" @click="ui.screen = 'settings'"><Icon :path="mdiCogOutline" /></button>
    </div>
  </header>
</template>

<style scoped>
.toolbar {
  flex: none;
  height: 48px;
  display: flex;
  align-items: center;
  background: var(--bg-side);
  border-bottom: 1px solid var(--border);
}
.left {
  width: var(--sidebar-width);
  flex: none;
  height: 100%;
  display: flex;
  align-items: center;
  padding: 0 10px;
  border-right: 1px solid var(--border);
}
.acct {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border: 0;
  background: none;
  border-radius: var(--radius);
  cursor: pointer;
  text-align: left;
}
.acct:hover {
  background: var(--bg-hover);
}
.mono {
  width: 26px;
  height: 26px;
  border-radius: 5px;
  background: var(--accent);
  color: var(--on-accent);
  display: grid;
  place-items: center;
  font-weight: 700;
  font-size: 12px;
  flex: none;
}
.meta {
  min-width: 0;
  flex: 1;
  line-height: 1.25;
}
.meta b {
  display: block;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta span {
  display: block;
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.search {
  margin-left: 14px;
  width: min(560px, 100% - 300px);
}
.right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 12px;
}
.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--text-3);
  font-size: 12px;
  margin-right: 8px;
}
.status.bad {
  color: var(--danger);
}
.spin :deep(svg) {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
