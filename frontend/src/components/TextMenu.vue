<script setup lang="ts">
import { mdiContentCopy, mdiContentCut, mdiContentPaste, mdiSelectAll } from '@mdi/js';
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuSeparator, DropdownMenuTrigger } from 'reka-ui';
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { api } from '../api';
import { showToast } from '../ui';
import Icon from './Icon.vue';

// The menu on a right click in any text field (address, subject, search, password, the message editor): Cut, Copy, Paste and
// Select all. It replaces the web view's own menu, which looks different on Windows and Linux. Cut and Copy go through the web
// view; Paste reads the clipboard in Java, because the web view does not let the page read it (Ctrl+V is not affected).

type Field = HTMLInputElement | HTMLTextAreaElement | HTMLElement;

const open = ref(false);
const anchor = reactive({ x: 0, y: 0 });
const can = reactive({ cut: false, copy: false, paste: false });
let target: Field | null = null;

const TEXT_INPUTS = new Set(['text', 'email', 'search', 'password', 'url', 'tel', 'number']);

/** The text field a right click landed in, or null for anything else (those keep their own behaviour). */
function fieldAt(el: EventTarget | null): Field | null {
  if (!(el instanceof HTMLElement)) return null;
  const field = el.closest<HTMLElement>('input, textarea, [contenteditable="true"]');
  if (!field) return null;
  if (field instanceof HTMLInputElement && !TEXT_INPUTS.has(field.type)) return null;
  return field;
}

function isReadOnly(field: Field): boolean {
  return (field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement) && (field.readOnly || field.disabled);
}

function hasSelection(field: Field): boolean {
  if (field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement) return field.selectionStart !== field.selectionEnd;
  return (getSelection()?.toString() ?? '') !== '';
}

function onContextMenu(event: MouseEvent): void {
  const field = fieldAt(event.target);
  if (!field) return;
  event.preventDefault();
  target = field;
  const selected = hasSelection(field);
  const readOnly = isReadOnly(field);
  const secret = field instanceof HTMLInputElement && field.type === 'password'; // a password is never copied out
  can.copy = selected && !secret;
  can.cut = selected && !secret && !readOnly;
  can.paste = !readOnly;
  anchor.x = event.clientX;
  anchor.y = event.clientY;
  open.value = true;
}

/** The editor TipTap keeps on its element, if the field is the message editor. */
function editorOf(field: Field): { view: { pasteText(text: string): boolean }; chain(): { focus(): { selectAll(): { run(): boolean } } } } | null {
  return (field.closest('.ProseMirror') as (HTMLElement & { editor?: never }) | null)?.editor ?? null;
}

async function run(action: 'cut' | 'copy' | 'paste' | 'selectAll'): Promise<void> {
  const field = target;
  if (!field) return;
  // The menu is still closing when this runs and hands focus back to the page when it is done; wait for that, or it
  // would undo the selection or the focus the action needs.
  await nextTick();
  await new Promise((resolve) => setTimeout(resolve, 0));
  field.focus({ preventScroll: true });
  const editor = editorOf(field);
  try {
    if (action === 'cut' || action === 'copy') {
      document.execCommand(action);
    } else if (action === 'selectAll') {
      if (editor) editor.chain().focus().selectAll().run();
      else if (field instanceof HTMLInputElement || field instanceof HTMLTextAreaElement) field.select();
      else document.execCommand('selectAll');
    } else {
      const text = await api.readClipboardText();
      if (text === '') return;
      field.focus({ preventScroll: true });
      if (editor) editor.view.pasteText(text);
      else document.execCommand('insertText', false, text);
    }
  } catch (e) {
    showToast('Could not paste', e instanceof Error ? e.message : String(e), true);
  }
}

onMounted(() => document.addEventListener('contextmenu', onContextMenu, true));
onBeforeUnmount(() => document.removeEventListener('contextmenu', onContextMenu, true));
</script>

<template>
  <DropdownMenuRoot v-model:open="open">
    <DropdownMenuTrigger as-child>
      <span class="anchor" :style="{ left: anchor.x + 'px', top: anchor.y + 'px' }" />
    </DropdownMenuTrigger>
    <DropdownMenuPortal>
      <!-- Focus goes back to the field through run(), not to the invisible anchor. -->
      <DropdownMenuContent class="menu compact" side="bottom" align="start" :side-offset="2" @close-auto-focus.prevent>
        <DropdownMenuItem class="menu-item" :disabled="!can.cut" @select="run('cut')"><Icon :path="mdiContentCut" />Cut<span class="hint">Ctrl+X</span></DropdownMenuItem>
        <DropdownMenuItem class="menu-item" :disabled="!can.copy" @select="run('copy')"><Icon :path="mdiContentCopy" />Copy<span class="hint">Ctrl+C</span></DropdownMenuItem>
        <DropdownMenuItem class="menu-item" :disabled="!can.paste" @select="run('paste')"><Icon :path="mdiContentPaste" />Paste<span class="hint">Ctrl+V</span></DropdownMenuItem>
        <DropdownMenuSeparator class="menu-sep" />
        <DropdownMenuItem class="menu-item" @select="run('selectAll')"><Icon :path="mdiSelectAll" />Select all<span class="hint">Ctrl+A</span></DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>

<style scoped>
.anchor {
  position: fixed;
  width: 1px;
  height: 1px;
  pointer-events: none;
}
</style>

<style>
/* Shortcut hint at the right edge of a menu item. */
.menu-item .hint {
  margin-left: auto;
  padding-left: 24px;
  font-size: 12px;
  color: var(--text-3);
}
</style>
