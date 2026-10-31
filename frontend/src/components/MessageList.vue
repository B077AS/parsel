<script setup lang="ts">
import {
  mdiArchiveOutline,
  mdiDeleteOutline,
  mdiDeleteRestore,
  mdiEmailOpenOutline,
  mdiEmailOutline,
  mdiFlag,
  mdiFlagOutline,
  mdiFolderMoveOutline,
  mdiPaperclip,
  mdiReplyAllOutline,
  mdiReplyOutline,
  mdiShareOutline,
  mdiStar,
  mdiStarOutline,
} from '@mdi/js';
import { ContextMenuContent, ContextMenuItem, ContextMenuPortal, ContextMenuRoot, ContextMenuSeparator, ContextMenuTrigger } from 'reka-ui';
import { computed, ref } from 'vue';
import type { Summary } from '../api';
import { dayGroup, listTime } from '../format';
import { closeCompose, composeDirty, startReply } from '../compose';
import { openMoveTo } from '../folders';
import {
  accountLabel,
  ALL,
  archiveMessage,
  type Filter,
  folderTitle,
  mail,
  restoreMessage,
  selectMessage,
  setRead,
  syncSummary,
  toggleImportantOf,
  toggleStarOf,
  trashMessage,
  unreadCount,
  visibleMessages,
} from '../mail';
import { prefs } from '../theme';
import { demo, showToast } from '../ui';
import Icon from './Icon.vue';

/** Opening a message replaces the composer, so an unsent message is never thrown away by a stray click. */
function open(id: string): void {
  if (composeDirty()) {
    showToast('Message not sent yet', 'Send or discard it before opening another message.');
    return;
  }
  closeCompose();
  void selectMessage(id);
}

// One menu for the whole list: the row under the pointer is remembered when the menu opens, and every action acts on that
// message, not on the one that happens to be open in the reader. Right-clicking does not open (and so never marks read) a message.
const menuId = ref<string | null>(null);
const menuRow = computed(() => mail.messages.find((m) => m.id === menuId.value) ?? null);

function onContextMenu(event: MouseEvent): void {
  const id = (event.target as HTMLElement | null)?.closest<HTMLElement>('[data-msg]')?.dataset.msg ?? null;
  menuId.value = id;
  // Not on a message: no menu at all (and not the browser's own one either).
  if (!id) {
    event.preventDefault();
    event.stopPropagation();
  }
}

function onMenuOpen(open: boolean): void {
  if (!open) menuId.value = null;
}

const FILTERS: { key: Filter; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'unread', label: 'Unread' },
  { key: 'starred', label: 'Starred' },
  { key: 'important', label: 'Important' },
  { key: 'attachments', label: 'Attachments' },
];

// The list is sorted newest first by Java, so each group is a run of neighbours.
const groups = computed(() => {
  const result: { title: string; rows: Summary[] }[] = [];
  for (const row of visibleMessages.value) {
    const title = dayGroup(row.epochMillis);
    const last = result[result.length - 1];
    if (last && last.title === title) last.rows.push(row);
    else result.push({ title, rows: [row] });
  }
  return result;
});

const accountName = (id: string) => {
  const account = mail.accounts.find((a) => a.id === id);
  return account ? accountLabel(account) : id;
};

const emptyText = computed(() => {
  if (mail.error) return mail.error;
  if (mail.messages.length > 0) return 'Nothing matches';
  return syncSummary.value.state === 'syncing' ? 'Syncing your mail…' : 'No messages';
});
</script>

<template>
  <section class="list" :class="{ compact: prefs.density === 'compact' }" :style="{ width: prefs.listWidth + 'px' }">
    <div class="head">
      <div class="row">
        <h2>{{ folderTitle }}</h2>
        <span class="muted">{{ unreadCount > 0 ? `${unreadCount} unread` : '' }}</span>
      </div>
      <div class="chips">
        <button v-for="f in FILTERS" :key="f.key" class="chip" :aria-pressed="mail.filter === f.key" @click="mail.filter = f.key">
          {{ f.label }}
        </button>
      </div>
    </div>

    <ContextMenuRoot @update:open="onMenuOpen">
    <ContextMenuTrigger as-child>
    <div class="scroll" role="listbox" aria-label="Messages" @contextmenu.capture="onContextMenu">
      <template v-for="g in groups" :key="g.title">
        <div class="group">{{ g.title }}</div>
        <div
          v-for="m in g.rows"
          :key="m.id"
          :data-msg="m.id"
          class="msg"
          :class="{ unread: m.unread, important: m.important, sel: m.id === mail.selectedId, ctx: m.id === menuId }"
          role="option"
          tabindex="0"
          :aria-selected="m.id === mail.selectedId"
          @click="open(m.id)"
          @keydown.enter="open(m.id)"
        >
          <span class="dot"></span>
          <div class="from">
            {{ m.fromName }}
            <span v-if="m.thread > 1" class="muted">{{ m.thread }}</span>
            <span v-if="mail.account === ALL" class="tag">{{ accountName(m.account) }}</span>
          </div>
          <div class="when">{{ listTime(m.epochMillis) }}</div>
          <div class="sub">
            <div class="t">
              <span class="subj">{{ m.subject }}</span>
            </div>
            <Icon v-if="m.hasAttachments" :path="mdiPaperclip" :size="14" />
            <Icon v-if="m.important" :path="mdiFlag" :size="17" class="flag" />
            <Icon v-if="m.starred" :path="mdiStar" :size="14" class="star" />
          </div>
        </div>
      </template>
      <p v-if="groups.length === 0" class="none" :class="mail.error ? 'bad' : 'muted'">{{ emptyText }}</p>
    </div>
    </ContextMenuTrigger>
    <ContextMenuPortal>
      <ContextMenuContent v-if="menuRow" class="menu compact">
        <ContextMenuItem class="menu-item" @select="open(menuRow.id)"><Icon :path="mdiEmailOpenOutline" />Open</ContextMenuItem>
        <ContextMenuSeparator class="menu-sep" />
        <ContextMenuItem class="menu-item" :disabled="demo" @select="startReply('reply', menuRow.id)"><Icon :path="mdiReplyOutline" />Reply</ContextMenuItem>
        <ContextMenuItem class="menu-item" :disabled="demo" @select="startReply('replyAll', menuRow.id)"><Icon :path="mdiReplyAllOutline" />Reply all</ContextMenuItem>
        <ContextMenuItem class="menu-item" :disabled="demo" @select="startReply('forward', menuRow.id)"><Icon :path="mdiShareOutline" />Forward</ContextMenuItem>
        <ContextMenuSeparator class="menu-sep" />
        <ContextMenuItem class="menu-item" @select="setRead(menuRow.id, menuRow.unread)">
          <Icon :path="menuRow.unread ? mdiEmailOpenOutline : mdiEmailOutline" />{{ menuRow.unread ? 'Mark as read' : 'Mark as unread' }}
        </ContextMenuItem>
        <ContextMenuItem class="menu-item" @select="toggleStarOf(menuRow.id)">
          <Icon :path="menuRow.starred ? mdiStar : mdiStarOutline" />{{ menuRow.starred ? 'Remove the star' : 'Star' }}
        </ContextMenuItem>
        <ContextMenuItem class="menu-item" @select="toggleImportantOf(menuRow.id)">
          <Icon :path="menuRow.important ? mdiFlag : mdiFlagOutline" />{{ menuRow.important ? 'Remove the Important mark' : 'Mark as important' }}
        </ContextMenuItem>
        <ContextMenuSeparator class="menu-sep" />
        <ContextMenuItem class="menu-item" :disabled="demo" @select="openMoveTo(menuRow.id)"><Icon :path="mdiFolderMoveOutline" />Move to…</ContextMenuItem>
        <ContextMenuItem class="menu-item" :disabled="mail.folder === 'archive'" @select="archiveMessage(menuRow.id)">
          <Icon :path="mdiArchiveOutline" />Archive
        </ContextMenuItem>
        <ContextMenuItem v-if="mail.folder === 'trash'" class="menu-item" @select="restoreMessage(menuRow.id)">
          <Icon :path="mdiDeleteRestore" />Move back to the inbox
        </ContextMenuItem>
        <ContextMenuItem v-else class="menu-item" @select="trashMessage(menuRow.id)"><Icon :path="mdiDeleteOutline" />Move to Trash</ContextMenuItem>
      </ContextMenuContent>
    </ContextMenuPortal>
    </ContextMenuRoot>
  </section>
</template>

<style scoped>
.list {
  flex: none;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg);
}
.head {
  flex: none;
  padding: 12px 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  flex: 1;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.scroll {
  overflow: auto;
  flex: 1;
  min-height: 0;
}
.group {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--bg);
  padding: 10px 14px 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--text-3);
}
.msg {
  position: relative;
  display: grid;
  grid-template-columns: 8px 1fr auto;
  column-gap: 8px;
  padding: 12px 14px 12px 10px;
  row-gap: 2px;
  border-bottom: 1px solid var(--border);
  cursor: pointer;
}
.list.compact .msg {
  padding-top: 4px;
  padding-bottom: 4px;
  row-gap: 0;
}
.list.compact .dot {
  margin-top: 6px;
}
/* Important: a faint red wash behind the row. Hover, the context-menu highlight and the selection come after and win. */
.msg.important {
  background: color-mix(in srgb, var(--danger) 7%, var(--bg));
}
.msg:hover {
  background: var(--bg-hover);
}
.msg.ctx {
  background: var(--bg-hover);
}
.msg.sel {
  background: var(--accent-tint);
  box-shadow: inset 2px 0 0 var(--accent);
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 7px;
}
.msg.unread .dot {
  background: var(--accent);
}
.from {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: flex;
  gap: 6px;
  align-items: baseline;
}
.msg.unread .from,
.msg.unread .subj {
  font-weight: 650;
}
.when {
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
.sub {
  grid-column: 2 / 4;
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--text-2);
}
.t {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}
.sub svg {
  color: var(--text-3);
}
.sub svg.flag {
  color: var(--danger);
}
.sub svg.star {
  color: var(--star);
}
.tag {
  font-size: 10.5px;
  padding: 0 5px;
  border: 1px solid var(--border-strong);
  border-radius: 3px;
  color: var(--text-3);
  font-weight: 500;
}
.none {
  padding: 24px 14px;
  text-align: center;
}
.none.bad {
  color: var(--danger);
}
</style>
