<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { api } from '../api';
import { scheme } from '../theme';
import { showToast } from '../ui';

// Email HTML is untrusted. It is shown in a frame with no scripts, and a CSP that blocks everything except inline styles
// (and images from the network only when the user has allowed them). allow-same-origin is there only so the page can
// measure the frame's height; without scripts nothing inside can use it.

const props = defineProps<{ html: string; loadImages: boolean }>();

const frame = ref<HTMLIFrameElement | null>(null);
const height = ref(120);

const srcdoc = computed(() => {
  // Read the colours from the page so the message matches the current theme. `scheme` makes this recompute on a theme change.
  void scheme.value;
  const css = getComputedStyle(document.documentElement);
  const text = css.getPropertyValue('--text').trim();
  const muted = css.getPropertyValue('--text-2').trim();
  const border = css.getPropertyValue('--border-strong').trim();
  const accent = css.getPropertyValue('--accent').trim();
  const bg = css.getPropertyValue('--bg').trim();
  const imageSources = props.loadImages ? 'https: data:' : 'data:';
  return `<!doctype html><html><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src ${imageSources}">
<style>
  html { color-scheme: ${scheme.value}; }
  body { margin: 0; font: 14.5px/1.6 system-ui, 'Segoe UI', 'Noto Sans', sans-serif; color: ${text}; background: transparent; overflow-wrap: anywhere; }
  ::selection { background: color-mix(in srgb, ${accent} 18%, ${bg}); }
  p { margin: 0 0 14px; }
  a { color: ${accent}; }
  blockquote { margin: 0 0 14px; padding-left: 12px; border-left: 2px solid ${border}; color: ${muted}; }
  img { max-width: 100%; height: auto; }
</style></head><body>${props.html}</body></html>`;
});

function fit(): void {
  const doc = frame.value?.contentDocument;
  // The height of the content itself: scrollHeight would never go below the frame's own height, so the frame could not shrink again.
  if (doc) height.value = Math.max(40, Math.ceil(doc.documentElement.getBoundingClientRect().height));
}

// The text reflows when the pane gets wider or narrower (the splitter, the window) and when images arrive, so the height
// is measured again whenever the message inside changes size, not only when it loads.
let watcher: ResizeObserver | undefined;
function watchSize(): void {
  watcher?.disconnect();
  const doc = frame.value?.contentDocument;
  if (!doc) return;
  doc.addEventListener('click', openLink);
  doc.addEventListener('auxclick', openLink);
  watcher =new ResizeObserver(fit);
  watcher.observe(doc.documentElement);
  fit();
}
onBeforeUnmount(() => watcher?.disconnect());

// A link in a message never navigates the frame or the app: it goes to the system browser. The frame has no scripts, so
// this listener (ours, added from the page) is the only code that sees the click. Middle-click is handled the same way.
function openLink(event: Event): void {
  const link = (event.target as Element | null)?.closest?.('a[href]');
  if (!link) return;
  event.preventDefault();
  if (event.type === 'auxclick' && (event as MouseEvent).button !== 1) return;
  api.openExternal((link as HTMLAnchorElement).href).catch((e) => {
    showToast('Could not open the link', e instanceof Error ? e.message : String(e), true);
  });
}

watch(srcdoc, () => nextTick(fit));
</script>

<template>
  <iframe ref="frame" class="body" sandbox="allow-same-origin" :srcdoc="srcdoc" :style="{ height: height + 'px' }" title="Message" @load="watchSize"></iframe>
</template>

<style scoped>
.body {
  display: block;
  width: 100%;
  border: 0;
  background: transparent;
}
</style>
