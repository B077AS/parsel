<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue';

// The emoji picker inside the toolbar's popover: search, categories, recently used and a skin tone. The emoji list
// (emojibase, English) is loaded the first time the picker opens, so it costs nothing until it is wanted.

const emit = defineEmits<{ pick: [emoji: string, keepOpen: boolean] }>();

interface Raw {
  label: string;
  emoji: string;
  tags?: string[];
  group?: number;
  order?: number;
  version: number | string;
  skins?: { emoji: string; tone?: number | number[] }[];
}
interface Entry {
  emoji: string;
  label: string;
  /** the words of the name, then the keywords */
  words: string[];
  /** how many of `words` come from the name */
  nameWords: number;
  /** the emoji in each skin tone (index 0 is tone 1, the lightest), when it has any */
  tones: string[] | null;
}

/**
 * Newer emoji show as an empty box on a system whose emoji font is older. 15.1 is what Windows 11 23H2 and current Linux fonts
 * draw; raise it when Windows 10 is no longer a concern.
 */
const MAX_VERSION = 15.1;

const CATEGORIES = [
  { group: 0, name: 'Smileys & emotion', icon: '😀' },
  { group: 1, name: 'People & body', icon: '👋' },
  { group: 3, name: 'Animals & nature', icon: '🐻' },
  { group: 4, name: 'Food & drink', icon: '🍔' },
  { group: 5, name: 'Travel & places', icon: '✈️' },
  { group: 6, name: 'Activities', icon: '⚽' },
  { group: 7, name: 'Objects', icon: '💡' },
  { group: 8, name: 'Symbols', icon: '🔣' },
  { group: 9, name: 'Flags', icon: '🏁' },
];
const TONES = ['👋', '👋🏻', '👋🏼', '👋🏽', '👋🏾', '👋🏿'];
const RECENT_KEY = 'parsel.emoji.recent';
const TONE_KEY = 'parsel.emoji.tone';
const RECENT_MAX = 24;
const SEARCH_MAX = 96;

const loading = ref(true);
const failed = ref(false);
const groups = ref(new Map<number, Entry[]>());
const everything = ref<Entry[]>([]);
const query = ref('');
const hovered = ref<Entry | null>(null);
const active = ref(CATEGORIES[0].group);
const scroller = ref<HTMLElement | null>(null);
const searchBox = ref<HTMLInputElement | null>(null);
const prefs = reactive({ tone: readTone(), recent: readRecent() });

function readTone(): number {
  const n = Number(localStorage.getItem(TONE_KEY));
  return Number.isInteger(n) && n >= 0 && n <= 5 ? n : 0;
}
function readRecent(): string[] {
  try {
    const list = JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    return Array.isArray(list) ? list.filter((e): e is string => typeof e === 'string').slice(0, RECENT_MAX) : [];
  } catch {
    return [];
  }
}

onMounted(async () => {
  void nextTick(() => searchBox.value?.focus());
  try {
    const raw = (await import('emojibase-data/en/data.json')).default as unknown as Raw[];
    const byGroup = new Map<number, Entry[]>();
    const all: Entry[] = [];
    for (const r of [...raw].sort((a, b) => (a.order ?? 0) - (b.order ?? 0))) {
      if (r.group === undefined || r.group === 2 || Number(r.version) > MAX_VERSION) continue;
      const tones = r.skins ? [1, 2, 3, 4, 5].map((t) => r.skins?.find((s) => s.tone === t)?.emoji) : null;
      const nameWords = r.label.toLowerCase().split(/[\s:,&-]+/).filter(Boolean);
      const entry: Entry = {
        emoji: r.emoji,
        label: r.label,
        words: [...nameWords, ...(r.tags ?? []).map((t) => t.toLowerCase())],
        nameWords: nameWords.length,
        tones: tones && tones.every((t) => t !== undefined) ? (tones as string[]) : null,
      };
      (byGroup.get(r.group) ?? byGroup.set(r.group, []).get(r.group)!).push(entry);
      all.push(entry);
    }
    groups.value = byGroup;
    everything.value = all;
  } catch {
    failed.value = true;
  } finally {
    loading.value = false;
  }
});

/** The emoji to insert for an entry, in the chosen skin tone when it has one. */
function shown(e: Entry): string {
  return e.tones && prefs.tone > 0 ? e.tones[prefs.tone - 1] : e.emoji;
}

const recent = computed(() => {
  const known = new Map(everything.value.map((e) => [e.emoji, e]));
  // Recents are stored as the base emoji, so a tone change applies to them as well.
  return prefs.recent.map((base) => known.get(base)).filter((e): e is Entry => e !== undefined);
});

/** How well a typed word fits an emoji: 0 is a whole word of its name, 3 a word that only starts a keyword; null is no match. */
function rank(e: Entry, word: string): number | null {
  let best: number | null = null;
  e.words.forEach((x, i) => {
    const inName = i < e.nameWords;
    const score = x === word ? (inName ? 0 : 2) : x.startsWith(word) ? (inName ? 1 : 3) : null;
    if (score !== null && (best === null || score < best)) best = score;
  });
  return best;
}

/** Every word typed must match the name or a keyword; the closest matches come first, then the usual emoji order. */
const results = computed(() => {
  const words = query.value.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return [];
  const scored: { entry: Entry; score: number }[] = [];
  for (const entry of everything.value) {
    let total = 0;
    for (const w of words) {
      const s = rank(entry, w);
      if (s === null) {
        total = -1;
        break;
      }
      total += s;
    }
    if (total >= 0) scored.push({ entry, score: total });
  }
  // sort() is stable, so ties keep the order of the list
  return scored.sort((a, b) => a.score - b.score).slice(0, SEARCH_MAX).map((s) => s.entry);
});
const searching = computed(() => query.value.trim() !== '');

function choose(e: Entry, event?: MouseEvent | KeyboardEvent): void {
  prefs.recent = [e.emoji, ...prefs.recent.filter((r) => r !== e.emoji)].slice(0, RECENT_MAX);
  localStorage.setItem(RECENT_KEY, JSON.stringify(prefs.recent));
  emit('pick', shown(e), event?.shiftKey ?? false);
}

function chooseFirst(): void {
  if (results.value.length > 0) choose(results.value[0]);
}

function setTone(i: number): void {
  prefs.tone = i;
  localStorage.setItem(TONE_KEY, String(i));
}

function jump(group: number): void {
  query.value = '';
  void nextTick(() => {
    const section = scroller.value?.querySelector<HTMLElement>(`[data-group="${group}"]`);
    if (scroller.value && section) scroller.value.scrollTop = section.offsetTop - scroller.value.offsetTop;
    active.value = group;
  });
}

function onScroll(): void {
  const box = scroller.value;
  if (!box || searching.value) return;
  let current = CATEGORIES[0].group;
  for (const section of box.querySelectorAll<HTMLElement>('[data-group]')) {
    if (section.offsetTop - box.offsetTop <= box.scrollTop + 12) current = Number(section.dataset.group);
  }
  active.value = current;
}
</script>

<template>
  <div class="picker">
    <input ref="searchBox" v-model="query" class="search" placeholder="Search emoji" spellcheck="false" autocomplete="off" aria-label="Search emoji" @keydown.enter.prevent="chooseFirst" />

    <div v-if="!searching" class="cats" role="tablist" aria-label="Categories">
      <button v-for="c in CATEGORIES" :key="c.group" class="cat" :class="{ on: active === c.group }" role="tab" :title="c.name" :aria-label="c.name" :aria-selected="active === c.group" @click="jump(c.group)">{{ c.icon }}</button>
    </div>

    <div ref="scroller" class="scroll" @scroll.passive="onScroll">
      <p v-if="loading" class="note">Loading…</p>
      <p v-else-if="failed" class="note">The emoji list could not be loaded.</p>

      <template v-else-if="searching">
        <p v-if="results.length === 0" class="note">No emoji match “{{ query.trim() }}”.</p>
        <div v-else class="grid">
          <button v-for="e in results" :key="e.emoji" class="em" tabindex="-1" :aria-label="e.label" @mouseenter="hovered = e" @mouseleave="hovered = null" @click="choose(e, $event)">{{ shown(e) }}</button>
        </div>
      </template>

      <template v-else>
        <section v-if="recent.length" data-group="-1">
          <h6>Recently used</h6>
          <div class="grid">
            <button v-for="e in recent" :key="e.emoji" class="em" tabindex="-1" :aria-label="e.label" @mouseenter="hovered = e" @mouseleave="hovered = null" @click="choose(e, $event)">{{ shown(e) }}</button>
          </div>
        </section>
        <section v-for="c in CATEGORIES" :key="c.group" :data-group="c.group">
          <h6>{{ c.name }}</h6>
          <div class="grid">
            <button v-for="e in groups.get(c.group)" :key="e.emoji" class="em" tabindex="-1" :aria-label="e.label" @mouseenter="hovered = e" @mouseleave="hovered = null" @click="choose(e, $event)">{{ shown(e) }}</button>
          </div>
        </section>
      </template>
    </div>

    <div class="foot">
      <span class="name">{{ hovered ? hovered.label : 'Shift-click: add several' }}</span>
      <span class="tones" role="radiogroup" aria-label="Skin tone">
        <button v-for="(t, i) in TONES" :key="i" class="tone" :class="{ on: prefs.tone === i }" role="radio" :aria-checked="prefs.tone === i" :title="i === 0 ? 'Default skin tone' : `Skin tone ${i}`" @click="setTone(i)">{{ t }}</button>
      </span>
    </div>
  </div>
</template>

<style scoped>
.picker {
  width: 352px;
  height: 380px;
  display: flex;
  flex-direction: column;
  font-family: var(--font);
}
.search {
  flex: none;
  margin: 8px 8px 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg);
  font: inherit;
  color: var(--text);
}
.search:focus {
  outline: 2px solid var(--accent);
  outline-offset: -1px;
  border-color: var(--accent);
}
.cats {
  flex: none;
  display: flex;
  justify-content: space-between;
  padding: 0 6px 4px;
  border-bottom: 1px solid var(--border);
}
.cat {
  width: 34px;
  height: 30px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
  filter: grayscale(1);
  opacity: 0.65;
}
.cat:hover,
.cat.on {
  filter: none;
  opacity: 1;
}
.cat.on {
  border-bottom-color: var(--accent);
}
.scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 8px;
}
section {
  /* Off-screen categories are not laid out until they are scrolled near. */
  content-visibility: auto;
  contain-intrinsic-size: auto 300px;
}
h6 {
  position: sticky;
  top: 0;
  z-index: 1;
  margin: 0;
  padding: 8px 2px 4px;
  background: var(--bg-raised);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}
.grid {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
}
.em {
  height: 38px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: none;
  font-family: 'Twemoji Country Flags', 'Segoe UI Emoji', 'Apple Color Emoji', 'Noto Color Emoji', sans-serif;
  font-size: 23px;
  line-height: 1;
  cursor: pointer;
}
.em:hover {
  background: var(--bg-hover);
}
.note {
  margin: 28px 0;
  text-align: center;
  color: var(--text-3);
}
.foot {
  flex: none;
  height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 8px 0 12px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-3);
}
.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tones {
  display: flex;
}
.tone {
  width: 26px;
  height: 26px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 6px;
  background: none;
  font-family: 'Twemoji Country Flags', 'Segoe UI Emoji', 'Apple Color Emoji', 'Noto Color Emoji', sans-serif;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}
.tone:hover {
  background: var(--bg-hover);
}
.tone.on {
  border-color: var(--accent);
  background: var(--accent-tint);
}
</style>
