<script setup lang="ts">
import { computed, nextTick, onMounted, watch } from 'vue';
import { api } from './api';
import AddAccount from './components/AddAccount.vue';
import Compose from './components/Compose.vue';
import FolderDialog from './components/FolderDialog.vue';
import MessageList from './components/MessageList.vue';
import Reader from './components/Reader.vue';
import Settings from './components/Settings.vue';
import Sidebar from './components/Sidebar.vue';
import Splitter from './components/Splitter.vue';
import TextMenu from './components/TextMenu.vue';
import Toast from './components/Toast.vue';
import Toolbar from './components/Toolbar.vue';
import VaultGate from './components/VaultGate.vue';
import { compose, currentDraft, openCompose, restoreDraft, returnDraft } from './compose';
import { onEvent } from './jolt';
import { initMail, initPopout, mail, moveState, openMessage } from './mail';
import { tray } from './theme';
import { composeToken, demo, popout, showToast, ui } from './ui';
import { refreshVault, vault } from './vault';

// A message in its own window closes itself once it has been archived, deleted or moved away (after the server has answered,
// or the move would be lost with the window).
watch(
  () => [mail.selectedId, moveState.pending, mail.loaded] as const,
  ([selected, pending, loaded]) => {
    if (popout && loaded && selected === null && pending === 0) api.closeWindow().catch(() => {});
  },
);

// A message being written in a window of its own: that window closes when the message is sent (after a moment, to show the
// confirmation) or thrown away.
watch(
  () => compose.open,
  (open) => {
    if (!composeToken || open || !mail.loaded) return;
    // Sent or thrown away: nothing is to come back to the main window when this one closes.
    const gone = api.discardDraft(composeToken).catch(() => {});
    setTimeout(() => void gone.then(() => api.closeWindow().catch(() => {})), compose.justSent ? 1500 : 0);
  },
);

// While a message is written in a window of its own, Java keeps a copy that is a moment behind the text, so closing the window
// (any way, even the X) can hand the message back to the main window.
let draftTimer: ReturnType<typeof setTimeout> | undefined;
function startKeepingDraft(token: string): void {
  const save = (): void => {
    clearTimeout(draftTimer);
    if (compose.open) api.updateDraft(token, currentDraft()).catch(() => {});
  };
  watch(
    () => [compose.account, compose.to, compose.cc, compose.bcc, compose.subject, compose.html, compose.attachments.length, compose.bodyTouched],
    () => {
      clearTimeout(draftTimer);
      draftTimer = setTimeout(save, 250);
    },
  );
  window.addEventListener('pagehide', save);
  document.addEventListener('visibilitychange', save);
}

onMounted(async () => {
  onEvent<{ token: string }>('compose.returned', (e) => void returnDraft(e.token));
  if (composeToken) {
    await refreshVault();
    try {
      mail.accounts = await api.accounts();
      restoreDraft(await api.peekDraft(composeToken));
      startKeepingDraft(composeToken);
    } catch (e) {
      showToast('Could not open this message', e instanceof Error ? e.message : String(e), true);
    }
    mail.loaded = true;
    await nextTick();
    setTimeout(() => api.windowReady().catch(() => {}), 60);
    return;
  }
  if (popout) {
    await refreshVault();
    await initPopout(popout.id, popout.folder);
    await nextTick();
    setTimeout(() => api.windowReady().catch(() => {}), 60);
    return;
  }
  // The tray menu: its New message and Settings items, and its own "Keep running" switch.
  onEvent('tray.compose', () => {
    ui.screen = 'mail';
    if (!compose.open) openCompose();
  });
  onEvent<{ account: string; message: string }>('notify.open', (e) => void openMessage(e.account, e.message));
  onEvent('tray.settings', () => {
    ui.screen = 'settings';
  });
  onEvent<{ enabled: boolean }>('settings.closeToTray', (e) => {
    tray.closeToTray = e.enabled;
  });
  await refreshVault();
  await initMail();
  // First run: nothing to show until an account exists.
  if (!demo && mail.accounts.length === 0) ui.addAccount = true;
  // The window starts hidden: show it once the content is rendered. A short timeout rather than requestAnimationFrame,
  // because a hidden web view runs no animation frames.
  await nextTick();
  setTimeout(() => api.windowReady().catch(() => {}), 60);
});

// On a machine with no system keychain, nothing works until the master password has been given.
const locked = computed(() => !demo && vault.loaded && vault.state !== 'ready');
const ready = computed(() => vault.loaded && mail.loaded);
// Not on the add-account screen: the toolbar appearing the moment the account is saved made the screen jump.
// Adding a first account has no app to show behind the card (AddAccount draws an empty outline); adding another one keeps the
// real app on screen, blurred, with the card on top.
const addingOver = computed(() => ui.addAccount && mail.accounts.length > 0 && !ui.addingFirst);
const showToolbar = computed(() => mail.accounts.length > 0 && (!ui.addAccount || addingOver.value));
</script>

<template>
  <div class="app">
    <template v-if="ready">
      <VaultGate v-if="locked" />
      <template v-else>
        <Toolbar v-if="showToolbar && !popout && !composeToken" :inert="addingOver" />
        <Settings v-if="ui.screen === 'settings' && (!ui.addAccount || addingOver)" :inert="addingOver" />
        <div v-else-if="composeToken" class="panes">
          <Compose v-if="compose.open" />
          <div v-else class="empty-pop"></div>
        </div>
        <div v-else-if="popout" class="panes">
          <Compose v-if="compose.open" />
          <Reader v-else />
        </div>
        <div v-else-if="ui.screen === 'mail' && (!ui.addAccount || addingOver)" class="panes" :inert="addingOver">
          <Sidebar />
          <MessageList />
          <Splitter />
          <Compose v-if="compose.open" />
          <Reader v-else />
        </div>
        <AddAccount v-if="ui.addAccount" />
      </template>
    </template>
    <Toast />
    <TextMenu />
    <FolderDialog />
  </div>
</template>

<style scoped>
.app {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.panes {
  flex: 1;
  min-height: 0;
  display: flex;
}
</style>
