<script setup lang="ts">
import { mdiChevronDown, mdiCheck, mdiFolderPlusOutline } from '@mdi/js';
import {
  DialogContent,
  DialogDescription,
  DialogOverlay,
  DialogPortal,
  DialogRoot,
  DialogTitle,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuTrigger,
} from 'reka-ui';
import { computed, nextTick, ref, watch } from 'vue';
import type { Folder } from '../api';
import { chooseFolder, createFolderAndMove, folderDialog } from '../folders';
import { folderIcon } from '../folderIcons';
import { showToast } from '../ui';
import Icon from './Icon.vue';

// "Move to…": a search box over the account's folders (type, arrows, Enter), with "New folder…" at the bottom, which turns the same
// dialog into a small form. The sidebar's "+" opens it on that form directly.

const view = ref<'list' | 'create'>('list');
const query = ref('');
const active = ref(0);
const name = ref('');
const parent = ref<string | null>(null);
const busy = ref(false);
const searchBox = ref<HTMLInputElement | null>(null);
const nameBox = ref<HTMLInputElement | null>(null);

watch(
  () => folderDialog.open,
  (open) => {
    if (!open) return;
    view.value = folderDialog.mode === 'create' ? 'create' : 'list';
    query.value = '';
    active.value = 0;
    name.value = '';
    parent.value = null;
    busy.value = false;
    void nextTick(() => (view.value === 'list' ? searchBox.value : nameBox.value)?.focus());
  },
);

const searching = computed(() => query.value.trim() !== '');
const visible = computed(() => {
  const q = query.value.trim().toLowerCase();
  return q === '' ? folderDialog.folders : folderDialog.folders.filter((f) => f.name.toLowerCase().includes(q));
});
const custom = computed(() => folderDialog.folders.filter((f) => /^f\d+$/.test(f.id)));
const parentLabel = computed(() => custom.value.find((f) => f.id === parent.value)?.name ?? 'Top level');
const moving = computed(() => folderDialog.messageId !== null);

watch(query, () => (active.value = firstEnabled()));

function isCurrent(f: Folder): boolean {
  return f.id === folderDialog.current;
}
function firstEnabled(): number {
  const i = visible.value.findIndex((f) => !isCurrent(f));
  return i < 0 ? 0 : i;
}
/** Steps through the list with the arrow keys, skipping the folder the message is already in. */
function step(delta: number): void {
  const n = visible.value.length;
  for (let i = 1; i <= n; i++) {
    const next = (active.value + delta * i + n * i) % n;
    if (!isCurrent(visible.value[next])) {
      active.value = next;
      break;
    }
  }
  void nextTick(() => document.querySelector('.folder-dialog .row.active')?.scrollIntoView({ block: 'nearest' }));
}
function chooseActive(): void {
  const f = visible.value[active.value];
  if (f && !isCurrent(f)) void chooseFolder(f);
}

/** A click on a notification (to read or dismiss it) is outside the dialog but must not close it. */
function keepOpenOnToast(event: CustomEvent<{ originalEvent: Event }>): void {
  const target = event.detail.originalEvent.target;
  if (target instanceof Element && target.closest('[data-toast]')) event.preventDefault();
}

function showCreate(): void {
  view.value = 'create';
  name.value = query.value.trim();
  void nextTick(() => nameBox.value?.focus());
}
function back(): void {
  if (moving.value) {
    view.value = 'list';
    void nextTick(() => searchBox.value?.focus());
  } else {
    folderDialog.open = false;
  }
}

async function create(): Promise<void> {
  if (busy.value || name.value.trim() === '') return;
  busy.value = true;
  try {
    await createFolderAndMove(name.value, parent.value);
  } catch (e) {
    showToast('Could not create the folder', e instanceof Error ? e.message : String(e), true);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <DialogRoot v-model:open="folderDialog.open">
    <DialogPortal>
      <DialogOverlay class="folder-overlay" />
      <DialogContent class="folder-dialog" @open-auto-focus.prevent @close-auto-focus.prevent @interact-outside="keepOpenOnToast">
        <DialogTitle class="title">{{ view === 'list' ? 'Move to' : moving ? 'New folder, then move' : 'New folder' }}</DialogTitle>
        <DialogDescription class="sr-only">
          {{ view === 'list' ? 'Choose the folder to move the message to.' : 'Name the new folder and choose where it goes.' }}
        </DialogDescription>

        <template v-if="view === 'list'">
          <input
            ref="searchBox"
            v-model="query"
            class="field"
            placeholder="Find a folder"
            spellcheck="false"
            autocomplete="off"
            aria-label="Find a folder"
            @keydown.down.prevent="step(1)"
            @keydown.up.prevent="step(-1)"
            @keydown.enter.prevent="chooseActive"
          />
          <div class="list" role="listbox" aria-label="Folders">
            <p v-if="folderDialog.loading" class="note">Loading…</p>
            <p v-else-if="visible.length === 0" class="note">No folder matches “{{ query.trim() }}”.</p>
            <button
              v-for="(f, i) in visible"
              :key="f.id"
              class="row"
              :class="{ active: i === active, current: isCurrent(f) }"
              role="option"
              :aria-selected="i === active"
              :disabled="isCurrent(f)"
              :style="{ paddingLeft: 10 + (searching ? 0 : f.depth) * 16 + 'px' }"
              @mousemove="active = i"
              @click="chooseFolder(f)"
            >
              <Icon :path="folderIcon(f.id)" :size="18" />
              <span class="name">{{ f.name }}</span>
              <span v-if="isCurrent(f)" class="tag">here</span>
            </button>
          </div>
          <div class="foot">
            <button class="btn ghost" type="button" @click="showCreate"><Icon :path="mdiFolderPlusOutline" />New folder…</button>
          </div>
        </template>

        <form v-else class="form" @submit.prevent="create">
          <label class="in">
            <span>Name</span>
            <input ref="nameBox" v-model="name" spellcheck="false" autocomplete="off" maxlength="100" :disabled="busy" />
          </label>
          <div class="in">
            <span>Create in</span>
            <DropdownMenuRoot>
              <DropdownMenuTrigger as-child>
                <button class="where" type="button" :disabled="busy"><span class="name">{{ parentLabel }}</span><Icon :path="mdiChevronDown" :size="16" /></button>
              </DropdownMenuTrigger>
              <DropdownMenuPortal>
                <DropdownMenuContent class="menu where-menu" align="start" :side-offset="4">
                  <DropdownMenuItem class="menu-item" @select="parent = null">Top level<Icon v-if="parent === null" :path="mdiCheck" class="check" /></DropdownMenuItem>
                  <DropdownMenuItem v-for="f in custom" :key="f.id" class="menu-item" :style="{ paddingLeft: 10 + f.depth * 16 + 'px' }" @select="parent = f.id">
                    {{ f.name }}<Icon v-if="parent === f.id" :path="mdiCheck" class="check" />
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenuPortal>
            </DropdownMenuRoot>
          </div>
          <div class="actions">
            <button class="btn" type="button" :disabled="busy" @click="back">{{ moving ? 'Back' : 'Cancel' }}</button>
            <button class="btn primary" type="submit" :disabled="busy || name.trim() === ''">{{ busy ? 'Creating…' : moving ? 'Create and move' : 'Create' }}</button>
          </div>
        </form>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style>
/* Rendered in a portal, so not scoped. */
.folder-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: rgba(0, 0, 0, 0.4);
}
.folder-dialog {
  position: fixed;
  z-index: 41;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 380px;
  max-width: calc(100vw - 32px);
  max-height: 68vh;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-lg);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.28);
  color: var(--text);
}
.folder-dialog .title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}
.folder-dialog .sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}
.folder-dialog .field,
.folder-dialog .in input {
  height: 34px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg);
  font: inherit;
  color: var(--text);
}
.folder-dialog .field:focus,
.folder-dialog .in input:focus {
  outline: 2px solid var(--accent);
  outline-offset: -1px;
  border-color: var(--accent);
}
.folder-dialog .list {
  flex: 1;
  min-height: 0;
  max-height: 300px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin: 0 -6px;
  padding: 0 6px;
}
.folder-dialog .row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
  height: 34px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--radius);
  background: none;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.folder-dialog .row svg {
  color: var(--text-3);
}
.folder-dialog .row.active {
  background: var(--bg-hover);
}
.folder-dialog .row.current {
  color: var(--text-3);
  cursor: default;
}
.folder-dialog .name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.folder-dialog .tag {
  font-size: 12px;
  color: var(--text-3);
}
.folder-dialog .note {
  margin: 14px 0;
  text-align: center;
  color: var(--text-3);
}
.folder-dialog .foot {
  display: flex;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.folder-dialog .form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.folder-dialog .in {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.folder-dialog .in > span {
  font-weight: 550;
  font-size: 12.5px;
}
.folder-dialog .where {
  height: 34px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg);
  font: inherit;
  color: var(--text);
  text-align: left;
  cursor: pointer;
}
.folder-dialog .where:disabled {
  opacity: 0.6;
}
.folder-dialog .actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.menu.where-menu {
  width: var(--reka-dropdown-menu-trigger-width);
  max-height: 260px;
  overflow-y: auto;
}
</style>
