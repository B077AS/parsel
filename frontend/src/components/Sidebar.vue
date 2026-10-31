<script setup lang="ts">
import { mdiFolderOutline, mdiPencilOutline, mdiPlus } from '@mdi/js';
import { computed } from 'vue';
import { openCompose } from '../compose';
import { folderIcon } from '../folderIcons';
import { openNewFolder } from '../folders';
import { ALL, mail, selectFolder } from '../mail';
import { demo } from '../ui';
import Icon from './Icon.vue';

// Java sends the standard folders first and then the account's own, whose ids start with "f".
const standard = computed(() => mail.folders.filter((f) => !/^f\d+$/.test(f.id)));
const custom = computed(() => mail.folders.filter((f) => /^f\d+$/.test(f.id)));
// A folder belongs to one account, so new ones are offered when a single account is shown.
const canCreate = computed(() => !demo && mail.account !== ALL && mail.accounts.length > 0);
</script>

<template>
  <aside class="side">
    <button class="btn primary compose" :disabled="demo" :title="demo ? 'Sending is not available in the demo' : 'New message (c)'" @click="openCompose()"><Icon :path="mdiPencilOutline" />Compose</button>
    <nav>
      <a
        v-for="f in standard"
        :key="f.id"
        :class="{ on: f.id === mail.folder }"
        :aria-current="f.id === mail.folder ? 'page' : undefined"
        tabindex="0"
        @click="selectFolder(f.id)"
        @keydown.enter="selectFolder(f.id)"
      >
        <Icon :path="folderIcon(f.id)" />
        {{ f.name }}
        <span v-if="f.unread > 0" class="n">{{ f.unread }}</span>
      </a>
    </nav>
    <template v-if="custom.length || canCreate">
      <div class="head">
        <h6>Folders</h6>
        <button v-if="canCreate" class="add" title="New folder" aria-label="New folder" @click="openNewFolder"><Icon :path="mdiPlus" :size="16" /></button>
      </div>
      <nav class="folders">
        <a
          v-for="f in custom"
          :key="f.id"
          :class="{ on: f.id === mail.folder }"
          :aria-current="f.id === mail.folder ? 'page' : undefined"
          tabindex="0"
          :style="{ paddingLeft: 10 + f.depth * 14 + 'px' }"
          @click="selectFolder(f.id)"
          @keydown.enter="selectFolder(f.id)"
        >
          <Icon :path="mdiFolderOutline" />
          <span class="name">{{ f.name }}</span>
          <span v-if="f.unread > 0" class="n">{{ f.unread }}</span>
        </a>
      </nav>
    </template>
  </aside>
</template>

<style scoped>
.side {
  width: var(--sidebar-width);
  flex: none;
  background: var(--bg-side);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  padding: 10px;
  gap: 10px;
  overflow: auto;
}
.compose {
  width: 100%;
  justify-content: flex-start;
}
nav {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
nav a {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 30px;
  padding: 0 10px;
  border-radius: var(--radius);
  color: var(--text-2);
  cursor: pointer;
}
nav a:hover {
  background: var(--bg-hover);
}
nav a.on {
  background: var(--bg-hover);
  color: var(--text);
  font-weight: 600;
}
nav a.on svg {
  color: var(--accent);
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 8px 4px 0 0;
}
.add {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: var(--radius);
  background: none;
  color: var(--text-3);
  cursor: pointer;
}
.add:hover {
  background: var(--bg-hover);
  color: var(--text);
}
h6 {
  margin: 0 10px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}
.n {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
nav a.on .n {
  color: var(--text);
}
</style>
