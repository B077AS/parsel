<script setup lang="ts">
import { mdiAlertCircleOutline, mdiClose, mdiInformationOutline } from '@mdi/js';
import { hideToast, holdToast, resumeToast, toast } from '../ui';
import Icon from './Icon.vue';
</script>

<template>
  <div class="host" data-toast aria-live="polite">
    <Transition name="slide">
      <div v-if="toast.visible" class="note" :class="{ warn: toast.warning }" :role="toast.warning ? 'alert' : 'status'" @mouseenter="holdToast" @mouseleave="resumeToast">
        <Icon :path="toast.icon || (toast.warning ? mdiAlertCircleOutline : mdiInformationOutline)" :size="22" class="mark" />
        <div class="msg">
          <b>{{ toast.title }}</b>
          <span v-if="toast.text">{{ toast.text }}</span>
        </div>
        <button class="close" title="Dismiss" aria-label="Dismiss" @click="hideToast"><Icon :path="mdiClose" :size="15" /></button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
/* Same as Komm: top right under the toolbar, a card with an icon, a title and a muted line, sliding in from the right. */
.host {
  position: fixed;
  top: 56px;
  right: 10px;
  display: flex;
  justify-content: flex-end;
  pointer-events: none;
  z-index: 60;
}
.note {
  pointer-events: auto;
  width: 340px;
  max-width: calc(100vw - 20px);
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 8px 10px 12px;
  border: 1px solid var(--border-strong);
  border-left: 3px solid var(--accent);
  border-radius: 4px;
  background: var(--bg-raised);
  color: var(--text);
  box-shadow: 0 2px 3px rgba(0, 0, 0, 0.22);
}
.mark {
  flex: none;
  margin-top: 1px;
  color: var(--accent);
}
.warn {
  border-left-color: var(--danger);
}
.warn .mark {
  color: var(--danger);
}
.msg {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
  user-select: text;
}
.msg b {
  font-weight: 650;
}
.msg span {
  font-size: 12px;
  color: var(--text-3);
  overflow-wrap: anywhere;
}
.close {
  flex: none;
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: none;
  color: var(--text-2);
  cursor: pointer;
}
.close:hover {
  background: var(--bg-hover);
  color: var(--text);
}

/* Komm: 300px slide in over 200ms, out over 250ms. */
.slide-enter-active {
  transition: transform 0.2s ease-out;
}
.slide-leave-active {
  transition: transform 0.25s ease-in;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(380px);
}
@media (prefers-reduced-motion: reduce) {
  .slide-enter-active,
  .slide-leave-active {
    transition: opacity 0.1s;
  }
  .slide-enter-from,
  .slide-leave-to {
    transform: none;
    opacity: 0;
  }
}
</style>
