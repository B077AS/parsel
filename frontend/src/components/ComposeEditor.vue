<script setup lang="ts">
import {
  mdiChevronDown,
  mdiCheck,
  mdiDotsHorizontal,
  mdiEmoticonOutline,
  mdiFormatAlignCenter,
  mdiFormatAlignLeft,
  mdiFormatAlignRight,
  mdiFormatBold,
  mdiFormatClear,
  mdiFormatColorHighlight,
  mdiFormatColorText,
  mdiFormatItalic,
  mdiFormatListBulleted,
  mdiFormatListNumbered,
  mdiFormatQuoteClose,
  mdiFormatStrikethroughVariant,
  mdiFormatUnderline,
  mdiLink,
  mdiRedo,
  mdiUndo,
} from '@mdi/js';
import { Color, FontFamily, TextStyle } from '@tiptap/extension-text-style';
import Highlight from '@tiptap/extension-highlight';
import TextAlign from '@tiptap/extension-text-align';
import StarterKit from '@tiptap/starter-kit';
import { EditorContent, useEditor } from '@tiptap/vue-3';
import Image from '@tiptap/extension-image';
import { showToast } from '../ui';
import {
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuPortal,
  DropdownMenuRoot,
  DropdownMenuTrigger,
  PopoverContent,
  PopoverPortal,
  PopoverRoot,
  PopoverTrigger,
} from 'reka-ui';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import EmojiPicker from './EmojiPicker.vue';
import Icon from './Icon.vue';

// The message body: a TipTap editor with a toolbar above it. The text goes out as HTML with a plain-text twin; Java cleans
// the HTML again before sending, so what is allowed here (and only that) is what recipients can get.

const props = defineProps<{ initial: string }>();
const emit = defineEmits<{ change: [html: string] }>();

// Fonts a recipient is likely to have. "Sans serif" is the recipient's own default, so it removes the setting.
const FONTS = [
  { name: 'Sans serif', value: null },
  { name: 'Serif', value: 'Georgia, serif' },
  { name: 'Monospace', value: "Consolas, 'Courier New', monospace" },
];
const COLORS = [
  { name: 'Default', value: null },
  { name: 'Grey', value: '#6b6b66' },
  { name: 'Red', value: '#c4332b' },
  { name: 'Orange', value: '#c2501a' },
  { name: 'Green', value: '#2f7d3f' },
  { name: 'Blue', value: '#2a62d9' },
  { name: 'Violet', value: '#6a4bc4' },
  { name: 'Pink', value: '#b8387b' },
];
const HIGHLIGHTS = [
  { name: 'Yellow', value: '#fff3a3' },
  { name: 'Green', value: '#c9f0c4' },
  { name: 'Blue', value: '#bcd6ff' },
  { name: 'Pink', value: '#f6c9e0' },
  { name: 'Orange', value: '#ffd9b3' },
  { name: 'None', value: null },
];

/** Pictures larger than this are refused: they travel inside the message. */
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const START_WIDTH = 480;
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

function unwrapSingleParagraph(html: string): string {
  const body = new DOMParser().parseFromString(html, 'text/html').body;
  const blocks = Array.from(body.childNodes).filter((n) => !(n.nodeType === Node.COMMENT_NODE || (n.nodeType === Node.TEXT_NODE && !n.textContent?.trim())));
  if (blocks.length === 1 && blocks[0] instanceof HTMLParagraphElement) {
    return blocks[0].innerHTML;
  }
  return html;
}

function pasteImages(files: FileList | undefined | null, event: Event): boolean {
  const images = Array.from(files ?? []).filter((f) => IMAGE_TYPES.includes(f.type));
  if (!images.length) return false;
  event.preventDefault();
  for (const file of images) {
    if (file.size > MAX_IMAGE_BYTES) {
      showToast('Picture not added', 'That picture is larger than 10 MB.', true);
      continue;
    }
    const reader = new FileReader();
    reader.onload = () => {
      const src = String(reader.result);
      // Start at a sensible size: a screenshot is often wider than the whole editor.
      const probe = new window.Image();
      probe.onload = () => {
        const scale = Math.min(1, START_WIDTH / (probe.naturalWidth || START_WIDTH));
        const width = Math.max(1, Math.round(probe.naturalWidth * scale));
        const height = Math.max(1, Math.round(probe.naturalHeight * scale));
        editor.value?.chain().focus().setImage({ src, width, height }).run();
      };
      probe.onerror = () => showToast('Picture not added', 'This picture could not be read.', true);
      probe.src = src;
    };
    reader.readAsDataURL(file);
  }
  return true;
}

const editor = useEditor({
  content: props.initial,
  extensions: [
    StarterKit.configure({ heading: { levels: [2, 3] }, link: { openOnClick: false, autolink: true } }),
    TextStyle,
    Color,
    FontFamily,
    Highlight.configure({ multicolor: true }),
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    Image.configure({
      inline: true,
      allowBase64: true,
      resize: { enabled: true, directions: ['bottom-right', 'bottom-left', 'top-right', 'top-left'], minWidth: 24, minHeight: 24, alwaysPreserveAspectRatio: true },
    }),
  ],
  editorProps: {
    attributes: { 'aria-label': 'Message', spellcheck: 'true' },
    // Copying one whole paragraph (Ctrl+A, Ctrl+C) and pasting it would otherwise start a new paragraph: paste its text in place.
    transformPastedHTML: unwrapSingleParagraph,
    handlePaste: (_view, event) => pasteImages(event.clipboardData?.files, event),
    handleDrop: (_view, event) => pasteImages(event.dataTransfer?.files, event),
  },
  onUpdate: ({ editor }) => emit('change', editor.getHTML()),
});
onBeforeUnmount(() => editor.value?.destroy());

// Everything fits in one row from about this width up (the reader pane is about 640 px in a 1280 px window). Below it the
// font, alignment and clear-formatting controls move into a "more" menu instead of wrapping onto a second row.
const FULL_ROW_WIDTH = 780;
const wrap = ref<HTMLElement | null>(null);
const compact = ref(false);
let resizeWatcher: ResizeObserver | undefined;
onMounted(() => {
  if (!wrap.value) return;
  resizeWatcher = new ResizeObserver(([entry]) => (compact.value = entry.contentRect.width < FULL_ROW_WIDTH));
  resizeWatcher.observe(wrap.value);
});
onBeforeUnmount(() => resizeWatcher?.disconnect());

/** The cursor goes above the quoted original (a reply) or into the empty text (a new message). */
function focusStart(): void {
  editor.value?.chain().focus('start').run();
}
defineExpose({ focusStart });

const STYLE_LABELS = ['Normal', 'Heading', 'Subheading', 'Code block'];
const styleLabel = computed(() => {
  const e = editor.value;
  if (!e) return 'Normal';
  if (e.isActive('heading', { level: 2 })) return 'Heading';
  if (e.isActive('heading', { level: 3 })) return 'Subheading';
  if (e.isActive('codeBlock')) return 'Code block';
  return 'Normal';
});
const fontLabel = computed(() => {
  const family: string = editor.value?.getAttributes('textStyle').fontFamily ?? '';
  if (/mono|consolas|courier/i.test(family)) return 'Monospace';
  if (/georgia|serif/i.test(family) && !/^sans/i.test(family)) return 'Serif';
  return 'Sans serif';
});
const alignIcon = computed(() => {
  const e = editor.value;
  if (e?.isActive({ textAlign: 'center' })) return mdiFormatAlignCenter;
  if (e?.isActive({ textAlign: 'right' })) return mdiFormatAlignRight;
  return mdiFormatAlignLeft;
});
const currentColor = computed<string | null>(() => editor.value?.getAttributes('textStyle').color ?? null);

function setStyle(style: 'p' | 'h2' | 'h3' | 'code'): void {
  const chain = editor.value?.chain().focus();
  if (!chain) return;
  if (style === 'p') chain.setParagraph().run();
  else if (style === 'code') chain.toggleCodeBlock().run();
  else chain.setHeading({ level: style === 'h2' ? 2 : 3 }).run();
}
function setFont(value: string | null): void {
  const chain = editor.value?.chain().focus();
  if (value) chain?.setFontFamily(value).run();
  else chain?.unsetFontFamily().run();
}
function setColor(value: string | null): void {
  const chain = editor.value?.chain().focus();
  if (value) chain?.setColor(value).run();
  else chain?.unsetColor().run();
}
function setHighlight(value: string | null): void {
  const chain = editor.value?.chain().focus();
  if (value) chain?.setHighlight({ color: value }).run();
  else chain?.unsetHighlight().run();
}
function clearFormatting(): void {
  editor.value?.chain().focus().unsetAllMarks().clearNodes().unsetTextAlign().run();
}

// ---- emoji ----
const emojiOpen = ref(false);
// Giving the editor focus would close the picker (a click outside it), so a Shift-click, which keeps it open for more, inserts
// without moving focus; the cursor is where it was.
function insertEmoji(emoji: string, keepOpen: boolean): void {
  if (keepOpen) {
    editor.value?.commands.insertContent(emoji);
    return;
  }
  emojiOpen.value = false;
  editor.value?.chain().focus().insertContent(emoji).run();
}

// ---- link ----
const linkOpen = ref(false);
const linkHref = ref('');
function onLinkOpen(open: boolean): void {
  linkOpen.value = open;
  if (open) linkHref.value = editor.value?.getAttributes('link').href ?? '';
}
function applyLink(): void {
  const chain = editor.value?.chain().focus().extendMarkRange('link');
  const value = linkHref.value.trim();
  if (!chain) return;
  if (value === '') chain.unsetLink().run();
  else chain.setLink({ href: /^[a-z][a-z0-9+.-]*:/i.test(value) ? value : value.includes('@') && !value.includes('/') ? `mailto:${value}` : `https://${value}` }).run();
  linkOpen.value = false;
}
function removeLink(): void {
  editor.value?.chain().focus().extendMarkRange('link').unsetLink().run();
  linkOpen.value = false;
}
</script>

<template>
  <div ref="wrap" class="wrap">
    <div v-if="editor" class="fmt" role="toolbar" aria-label="Formatting" @mousedown.prevent>
      <button class="tb" title="Undo (Ctrl+Z)" aria-label="Undo" :disabled="!editor.can().undo()" @click="editor.chain().focus().undo().run()"><Icon :path="mdiUndo" /></button>
      <button class="tb" title="Redo (Ctrl+Y)" aria-label="Redo" :disabled="!editor.can().redo()" @click="editor.chain().focus().redo().run()"><Icon :path="mdiRedo" /></button>
      <span class="gsep" />

      <DropdownMenuRoot>
        <DropdownMenuTrigger as-child>
          <button class="tb txt" title="Text style">
            <span class="lbl"><span v-for="l in STYLE_LABELS" :key="l" :class="{ ghost: l !== styleLabel }">{{ l }}</span></span><Icon :path="mdiChevronDown" :size="15" /></button>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="menu compact" align="start" :side-offset="4" @close-auto-focus.prevent>
            <DropdownMenuItem class="menu-item" @select="setStyle('p')">Normal<Icon v-if="styleLabel === 'Normal'" :path="mdiCheck" class="check" /></DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="setStyle('h2')"><b style="font-size: 16px">Heading</b><Icon v-if="styleLabel === 'Heading'" :path="mdiCheck" class="check" /></DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="setStyle('h3')"><b>Subheading</b><Icon v-if="styleLabel === 'Subheading'" :path="mdiCheck" class="check" /></DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="setStyle('code')"><span class="mono">Code block</span><Icon v-if="styleLabel === 'Code block'" :path="mdiCheck" class="check" /></DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>

      <DropdownMenuRoot v-if="!compact">
        <DropdownMenuTrigger as-child>
          <button class="tb txt" title="Font">
            <span class="lbl"><span v-for="f in FONTS" :key="f.name" :class="{ ghost: f.name !== fontLabel }">{{ f.name }}</span></span><Icon :path="mdiChevronDown" :size="15" /></button>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="menu compact" align="start" :side-offset="4" @close-auto-focus.prevent>
            <DropdownMenuItem v-for="f in FONTS" :key="f.name" class="menu-item" @select="setFont(f.value)">
              <span :style="{ fontFamily: f.value ?? 'inherit' }">{{ f.name }}</span>
              <Icon v-if="fontLabel === f.name" :path="mdiCheck" class="check" />
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>
      <span class="gsep" />

      <button class="tb" :class="{ on: editor.isActive('bold') }" title="Bold (Ctrl+B)" aria-label="Bold" :aria-pressed="editor.isActive('bold')" @click="editor.chain().focus().toggleBold().run()"><Icon :path="mdiFormatBold" /></button>
      <button class="tb" :class="{ on: editor.isActive('italic') }" title="Italic (Ctrl+I)" aria-label="Italic" :aria-pressed="editor.isActive('italic')" @click="editor.chain().focus().toggleItalic().run()"><Icon :path="mdiFormatItalic" /></button>
      <button class="tb" :class="{ on: editor.isActive('underline') }" title="Underline (Ctrl+U)" aria-label="Underline" :aria-pressed="editor.isActive('underline')" @click="editor.chain().focus().toggleUnderline().run()"><Icon :path="mdiFormatUnderline" /></button>
      <button v-if="!compact" class="tb" :class="{ on: editor.isActive('strike') }" title="Strikethrough" aria-label="Strikethrough" :aria-pressed="editor.isActive('strike')" @click="editor.chain().focus().toggleStrike().run()"><Icon :path="mdiFormatStrikethroughVariant" /></button>
      <span class="gsep" />

      <PopoverRoot>
        <PopoverTrigger as-child>
          <button class="tb" title="Text colour" aria-label="Text colour">
            <span class="tint"><Icon :path="mdiFormatColorText" /><i :style="{ background: currentColor ?? 'var(--text-2)' }" /></span>
            <Icon :path="mdiChevronDown" :size="13" />
          </button>
        </PopoverTrigger>
        <PopoverPortal>
          <PopoverContent class="menu swatches" align="start" :side-offset="4" @close-auto-focus.prevent>
            <div class="ph">Text colour</div>
            <div class="sw">
              <button v-for="c in COLORS" :key="c.name" :class="{ none: c.value === null }" :style="c.value ? { background: c.value } : {}" :title="c.name" :aria-label="c.name" @click="setColor(c.value)" />
            </div>
          </PopoverContent>
        </PopoverPortal>
      </PopoverRoot>

      <PopoverRoot>
        <PopoverTrigger as-child>
          <button class="tb" :class="{ on: editor.isActive('highlight') }" title="Highlight" aria-label="Highlight"><Icon :path="mdiFormatColorHighlight" /><Icon :path="mdiChevronDown" :size="13" /></button>
        </PopoverTrigger>
        <PopoverPortal>
          <PopoverContent class="menu swatches" align="start" :side-offset="4" @close-auto-focus.prevent>
            <div class="ph">Highlight</div>
            <div class="sw">
              <button v-for="c in HIGHLIGHTS" :key="c.name" :class="{ none: c.value === null }" :style="c.value ? { background: c.value } : {}" :title="c.name" :aria-label="c.name" @click="setHighlight(c.value)" />
            </div>
          </PopoverContent>
        </PopoverPortal>
      </PopoverRoot>
      <span class="gsep" />

      <button class="tb" :class="{ on: editor.isActive('bulletList') }" title="Bulleted list" aria-label="Bulleted list" :aria-pressed="editor.isActive('bulletList')" @click="editor.chain().focus().toggleBulletList().run()"><Icon :path="mdiFormatListBulleted" /></button>
      <button class="tb" :class="{ on: editor.isActive('orderedList') }" title="Numbered list" aria-label="Numbered list" :aria-pressed="editor.isActive('orderedList')" @click="editor.chain().focus().toggleOrderedList().run()"><Icon :path="mdiFormatListNumbered" /></button>
      <button class="tb" :class="{ on: editor.isActive('blockquote') }" title="Quote" aria-label="Quote" :aria-pressed="editor.isActive('blockquote')" @click="editor.chain().focus().toggleBlockquote().run()"><Icon :path="mdiFormatQuoteClose" /></button>
      <span class="gsep" />


      <DropdownMenuRoot v-if="!compact">
        <DropdownMenuTrigger as-child>
          <button class="tb" title="Align" aria-label="Align"><Icon :path="alignIcon" /><Icon :path="mdiChevronDown" :size="13" /></button>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="menu compact" align="start" :side-offset="4" @close-auto-focus.prevent>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('left').run()"><Icon :path="mdiFormatAlignLeft" />Left</DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('center').run()"><Icon :path="mdiFormatAlignCenter" />Centre</DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('right').run()"><Icon :path="mdiFormatAlignRight" />Right</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>

      <PopoverRoot :open="linkOpen" @update:open="onLinkOpen">
        <PopoverTrigger as-child>
          <button class="tb" :class="{ on: editor.isActive('link') }" title="Link" aria-label="Link"><Icon :path="mdiLink" /></button>
        </PopoverTrigger>
        <PopoverPortal>
          <PopoverContent class="menu linkpop" align="start" :side-offset="4" @close-auto-focus.prevent>
            <form @submit.prevent="applyLink">
              <input v-model="linkHref" placeholder="https://example.com" spellcheck="false" autocomplete="off" aria-label="Link address" />
              <button class="btn primary" type="submit">Apply</button>
              <button v-if="editor.isActive('link')" class="btn" type="button" @click="removeLink">Remove</button>
            </form>
          </PopoverContent>
        </PopoverPortal>
      </PopoverRoot>
      <PopoverRoot v-model:open="emojiOpen">
        <PopoverTrigger as-child>
          <button class="tb" :class="{ on: emojiOpen }" title="Emoji" aria-label="Emoji"><Icon :path="mdiEmoticonOutline" /></button>
        </PopoverTrigger>
        <PopoverPortal>
          <PopoverContent class="menu emoji" align="end" :side-offset="4" @close-auto-focus.prevent>
            <EmojiPicker @pick="insertEmoji" />
          </PopoverContent>
        </PopoverPortal>
      </PopoverRoot>
      <template v-if="!compact">
        <button class="tb" title="Clear formatting" aria-label="Clear formatting" @click="clearFormatting"><Icon :path="mdiFormatClear" /></button>
      </template>

      <DropdownMenuRoot v-else>
        <DropdownMenuTrigger as-child>
          <button class="tb" title="More formatting" aria-label="More formatting"><Icon :path="mdiDotsHorizontal" /></button>
        </DropdownMenuTrigger>
        <DropdownMenuPortal>
          <DropdownMenuContent class="menu compact" align="end" :side-offset="4" @close-auto-focus.prevent>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().toggleStrike().run()"><Icon :path="mdiFormatStrikethroughVariant" />Strikethrough<Icon v-if="editor.isActive('strike')" :path="mdiCheck" class="check" /></DropdownMenuItem>
            <div class="menu-sep" />
            <div class="ph">Font</div>
            <DropdownMenuItem v-for="f in FONTS" :key="f.name" class="menu-item" @select="setFont(f.value)">
              <span :style="{ fontFamily: f.value ?? 'inherit' }">{{ f.name }}</span>
              <Icon v-if="fontLabel === f.name" :path="mdiCheck" class="check" />
            </DropdownMenuItem>
            <div class="menu-sep" />
            <div class="ph">Align</div>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('left').run()"><Icon :path="mdiFormatAlignLeft" />Left</DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('center').run()"><Icon :path="mdiFormatAlignCenter" />Centre</DropdownMenuItem>
            <DropdownMenuItem class="menu-item" @select="editor.chain().focus().setTextAlign('right').run()"><Icon :path="mdiFormatAlignRight" />Right</DropdownMenuItem>
            <div class="menu-sep" />
            <DropdownMenuItem class="menu-item" @select="clearFormatting"><Icon :path="mdiFormatClear" />Clear formatting</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenuPortal>
      </DropdownMenuRoot>
    </div>

    <EditorContent :editor="editor" class="content" />
  </div>
</template>

<style scoped>
.wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.fmt {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1px;
  padding: 5px 12px;
  border-bottom: 1px solid var(--border);
}
.tb {
  min-width: 30px;
  height: 30px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border: 0;
  border-radius: var(--radius);
  background: none;
  color: var(--text-2);
  cursor: pointer;
}
.tb:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text);
}
.tb.on,
.tb[data-state='open'] {
  background: var(--accent-tint-strong);
  color: var(--text);
}
.tb:disabled {
  opacity: 0.4;
  cursor: default;
}
.tb.txt {
  padding: 0 8px;
  font-weight: 500;
}
/* Every label of a menu sits in the same grid cell and only the current one shows, so the button is always as wide as its longest
   label and nothing next to it moves when the choice changes. */
.lbl {
  display: inline-grid;
  text-align: left;
}
.lbl > span {
  grid-area: 1 / 1;
}
.lbl > .ghost {
  visibility: hidden;
}
.gsep {
  width: 1px;
  height: 18px;
  margin: 0 3px;
  background: var(--border-strong);
}
.tint {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
}
.tint i {
  width: 14px;
  height: 3px;
  margin-top: -3px;
  border-radius: 1px;
}
.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  cursor: text;
}
/* ProseMirror's own element: it is created by TipTap, so the styles reach it through :deep. */
.content :deep(.ProseMirror) {
  min-height: 100%;
  padding: 18px 24px;
  outline: 0;
  font-size: 14.5px;
  line-height: 1.6;
  color: var(--text);
  user-select: text;
}
.content :deep(p) {
  margin: 0 0 0.7em;
}
.content :deep(h2) {
  margin: 0.3em 0 0.4em;
  font-size: 1.35em;
  line-height: 1.25;
}
.content :deep(h3) {
  margin: 0.3em 0 0.3em;
  font-size: 1.12em;
  line-height: 1.3;
}
.content :deep(ul),
.content :deep(ol) {
  margin: 0 0 0.7em;
  padding-left: 1.5em;
}
.content :deep(li > p) {
  margin: 0;
}
.content :deep(blockquote) {
  margin: 0 0 0.7em;
  padding-left: 14px;
  border-left: 3px solid var(--border-strong);
  color: var(--text-2);
}
.content :deep(pre) {
  margin: 0 0 0.7em;
  padding: 9px 12px;
  border-radius: var(--radius);
  background: var(--bg-side);
  font: 13px/1.5 ui-monospace, Consolas, monospace;
  white-space: pre-wrap;
}
/* Pictures: never wider than the editor, with TipTap's resize handles (it adds them unstyled) shown on the selected one. */
.content :deep([data-resize-container]) {
  max-width: 100%;
  vertical-align: bottom;
}
.content :deep(img) {
  display: block;
  max-width: 100%;
  height: auto !important;
}
.content :deep([data-resize-container].ProseMirror-selectednode [data-resize-wrapper]) {
  outline: 2px solid var(--accent);
}
.content :deep([data-resize-handle]) {
  display: none;
  width: 10px;
  height: 10px;
  border: 2px solid var(--accent);
  border-radius: 50%;
  background: var(--bg);
  z-index: 2;
}
.content :deep([data-resize-container].ProseMirror-selectednode [data-resize-handle]) {
  display: block;
}
.content :deep([data-resize-handle="top-left"]) {
  margin: -5px 0 0 -5px;
  cursor: nwse-resize;
}
.content :deep([data-resize-handle="top-right"]) {
  margin: -5px -5px 0 0;
  cursor: nesw-resize;
}
.content :deep([data-resize-handle="bottom-left"]) {
  margin: 0 0 -5px -5px;
  cursor: nesw-resize;
}
.content :deep([data-resize-handle="bottom-right"]) {
  margin: 0 -5px -5px 0;
  cursor: nwse-resize;
}
.content :deep(a) {
  color: var(--accent);
  text-decoration: underline;
}
.content :deep(mark) {
  color: inherit;
  border-radius: 2px;
}
</style>

<style>
/* The popovers render in a portal, outside the component, so these cannot be scoped. */
.menu.mono,
.mono {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12.5px;
}
.menu.swatches {
  min-width: 0;
  padding: 6px 8px 8px;
}
.menu .ph {
  padding: 3px 2px 6px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}
.menu .sw {
  display: grid;
  grid-template-columns: repeat(4, 24px);
  gap: 6px;
}
.menu .sw button {
  width: 24px;
  height: 24px;
  padding: 0;
  border: 1px solid rgba(0, 0, 0, 0.2);
  border-radius: 50%;
  background: var(--bg-raised);
  cursor: pointer;
}
.menu .sw button:hover {
  outline: 2px solid var(--accent);
  outline-offset: 1px;
}
/* "Default" and "None": an empty circle with a slash through it. */
.menu .sw button.none {
  background: linear-gradient(135deg, transparent 46%, var(--danger) 46%, var(--danger) 54%, transparent 54%), var(--bg-raised);
}
.menu.emoji {
  min-width: 0;
  padding: 0;
  background: var(--bg-raised);
}
.menu.linkpop {
  min-width: 0;
  width: 320px;
  padding: 8px;
}
.menu.linkpop form {
  display: flex;
  gap: 6px;
}
.menu.linkpop input {
  flex: 1;
  min-width: 0;
  height: 32px;
  padding: 0 9px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg);
  font: inherit;
  color: var(--text);
}
.menu.linkpop input:focus {
  outline: 2px solid var(--accent);
  outline-offset: -1px;
  border-color: var(--accent);
}
.menu.linkpop .btn {
  height: 32px;
  padding: 0 12px;
}
</style>
