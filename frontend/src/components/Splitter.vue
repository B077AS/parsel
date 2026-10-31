<script setup lang="ts">
import { api } from '../api';
import { prefs } from '../theme';

// The drag handle between the message list and the reader. It sits on the list's right border; the width is remembered in Java.
const MIN = 260;
const MAX = 800;
const DEFAULT = 380;
const READER_MIN = 360;

let startX = 0;
let startWidth = 0;
let dragging = false;

function clamp(width: number, room: number): number {
  return Math.round(Math.max(MIN, Math.min(MAX, room - READER_MIN, width)));
}

/** Room for list + reader: the panes row minus the sidebar. */
function room(el: HTMLElement): number {
  const panes = el.parentElement;
  const sidebar = panes?.firstElementChild as HTMLElement | null;
  return (panes?.clientWidth ?? 0) - (sidebar?.offsetWidth ?? 0);
}

function save(): void {
  api.setListWidth(prefs.listWidth).catch((e) => console.warn('Could not save the list width:', e));
}

function down(event: PointerEvent): void {
  dragging = true;
  startX = event.clientX;
  startWidth = prefs.listWidth;
  (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
}

function move(event: PointerEvent): void {
  if (!dragging) return;
  prefs.listWidth = clamp(startWidth + event.clientX - startX, room(event.currentTarget as HTMLElement));
}

function up(): void {
  if (!dragging) return;
  dragging = false;
  save();
}

function reset(event: MouseEvent): void {
  prefs.listWidth = clamp(DEFAULT, room(event.currentTarget as HTMLElement));
  save();
}

function key(event: KeyboardEvent): void {
  const step = event.key === 'ArrowLeft' ? -16 : event.key === 'ArrowRight' ? 16 : 0;
  if (step === 0) return;
  event.preventDefault();
  prefs.listWidth = clamp(prefs.listWidth + step, room(event.currentTarget as HTMLElement));
  save();
}
</script>

<template>
  <div
    class="splitter"
    role="separator"
    aria-orientation="vertical"
    aria-label="Resize the message list"
    :aria-valuenow="prefs.listWidth"
    :aria-valuemin="MIN"
    :aria-valuemax="MAX"
    tabindex="0"
    title="Drag to resize, double-click to reset"
    @pointerdown="down"
    @pointermove="move"
    @pointerup="up"
    @pointercancel="up"
    @dblclick="reset"
    @keydown="key"
  ></div>
</template>

<style scoped>
/* Zero layout width; the 9px hit area straddles the list's border. */
.splitter {
  flex: none;
  position: relative;
  width: 0;
  z-index: 5;
  cursor: col-resize;
  outline: none;
}
.splitter::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -5px;
  width: 9px;
}
.splitter::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -1px;
  width: 2px;
  background: var(--accent);
  opacity: 0;
  transition: opacity 0.12s;
}
.splitter:hover::after,
.splitter:active::after,
.splitter:focus-visible::after {
  opacity: 1;
}
</style>
