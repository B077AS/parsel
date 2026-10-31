<script setup lang="ts">
import {
  mdiArchiveOutline,
  mdiDeleteOutline,
  mdiDeleteRestore,
  mdiDotsHorizontal,
  mdiEmailOpenOutline,
  mdiEmailOutline,
  mdiOpenInNew,
  mdiFlag,
  mdiFlagOutline,
  mdiPaperclip,
  mdiImageOutline,
  mdiReplyAllOutline,
  mdiReplyOutline,
  mdiShareOutline,
  mdiStar,
  mdiStarOutline,
  mdiFolderMoveOutline,
} from '@mdi/js';
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuTrigger } from 'reka-ui';
import { ref, watch } from 'vue';
import { api } from '../api';
import { startReply } from '../compose';
import { openMoveTo } from '../folders';
import { fileSize, fullTime } from '../format';
import {
  archiveSelected,
  mail,
  markSelectedUnread,
  openInNewWindow,
  restoreSelected,
  toggleImportant,
  toggleStar,
  trashSelected,
  unreadCount,
} from '../mail';
import { demo, popout, showToast } from '../ui';
import Icon from './Icon.vue';
import MessageBody from './MessageBody.vue';

const loadImages = ref(false);
// Remote images are blocked again whenever another message is opened.
watch(
  () => mail.detail?.summary.id,
  () => {
    loadImages.value = false;
  },
);

// The download goes from the server straight to the file the user picks in the system's save dialog; the page never holds the bytes.
const saving = ref<number | null>(null);
async function save(index: number): Promise<void> {
  const id = mail.detail?.summary.id;
  if (!id || saving.value !== null) return;
  saving.value = index;
  try {
    const result = await api.saveAttachment(id, index);
    if (result.saved) showToast('Attachment saved', result.name);
  } catch (e) {
    showToast('Could not save the attachment', e instanceof Error ? e.message : String(e), true);
  } finally {
    saving.value = null;
  }
}
</script>

<template>
  <section class="reader">
    <template v-if="mail.detail">
      <div class="bar">
        <button class="btn ghost" :disabled="demo" @click="startReply('reply')"><Icon :path="mdiReplyOutline" />Reply</button>
        <button class="btn ghost icon" :disabled="demo" title="Reply all" @click="startReply('replyAll')"><Icon :path="mdiReplyAllOutline" /></button>
        <button class="btn ghost icon" :disabled="demo" title="Forward" @click="startReply('forward')"><Icon :path="mdiShareOutline" /></button>
        <span class="sep"></span>
        <button class="btn ghost icon" :disabled="mail.folder === 'archive'" title="Archive" @click="archiveSelected"><Icon :path="mdiArchiveOutline" /></button>
        <button v-if="mail.folder === 'trash'" class="btn ghost icon" title="Move back to the inbox" @click="restoreSelected"><Icon :path="mdiDeleteRestore" /></button>
        <button v-else class="btn ghost icon" title="Move to Trash" @click="trashSelected"><Icon :path="mdiDeleteOutline" /></button>
        <button class="btn ghost icon" :disabled="demo" title="Move to… (v)" @click="openMoveTo(mail.selectedId)"><Icon :path="mdiFolderMoveOutline" /></button>
        <span class="grow"></span>
        <button class="btn ghost icon" :class="{ flagged: mail.detail.summary.important }" :title="mail.detail.summary.important ? 'Remove the Important mark' : 'Mark as important'" @click="toggleImportant">
          <Icon :path="mail.detail.summary.important ? mdiFlag : mdiFlagOutline" />
        </button>
        <button class="btn ghost icon" :class="{ starred: mail.detail.summary.starred }" :title="mail.detail.summary.starred ? 'Remove the star' : 'Star'" @click="toggleStar">
          <Icon :path="mail.detail.summary.starred ? mdiStar : mdiStarOutline" />
        </button>
        <DropdownMenuRoot>
          <DropdownMenuTrigger as-child>
            <button class="btn ghost icon" title="More" aria-label="More actions"><Icon :path="mdiDotsHorizontal" /></button>
          </DropdownMenuTrigger>
          <DropdownMenuPortal>
            <DropdownMenuContent class="menu" align="end" :side-offset="4">
              <DropdownMenuItem v-if="!popout" class="menu-item" @select="openInNewWindow(mail.selectedId)">
                <Icon :path="mdiOpenInNew" />Open in new window
              </DropdownMenuItem>
              <DropdownMenuItem class="menu-item" :disabled="demo" @select="markSelectedUnread">
                <Icon :path="mdiEmailOutline" />Mark as unread
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenuPortal>
        </DropdownMenuRoot>
      </div>

      <div class="scroll">
        <article class="inner">
          <h1>{{ mail.detail.summary.subject }}</h1>
          <div class="who">
            <div class="av">{{ mail.detail.summary.fromName[0] }}</div>
            <div class="l">
              <b>{{ mail.detail.summary.fromName }}</b>
              <span class="muted"> &lt;{{ mail.detail.summary.fromAddress }}&gt;</span>
              <br />
              <span class="muted">to {{ mail.detail.to.join(', ') }}</span>
            </div>
            <div class="d">{{ fullTime(mail.detail.summary.epochMillis) }}</div>
          </div>

          <div v-if="mail.detail.hasRemoteImages && !loadImages" class="banner">
            <Icon :path="mdiImageOutline" />
            <span class="grow">Remote images are blocked so the sender can't see that you opened this.</span>
            <button class="btn" @click="loadImages = true">Load images</button>
          </div>

          <div v-if="mail.detail.attachments.length" class="files">
            <button
              v-for="(a, i) in mail.detail.attachments"
              :key="i"
              class="file"
              :disabled="demo || saving !== null"
              :title="demo ? 'Not available in the demo' : 'Save ' + a.name"
              @click="save(i)"
            >
              <Icon :path="mdiPaperclip" class="muted" />
              {{ saving === i ? 'Saving…' : a.name }}
              <small class="muted">{{ fileSize(a.bytes) }}</small>
            </button>
          </div>

          <MessageBody :html="mail.detail.bodyHtml" :load-images="loadImages" />
        </article>
      </div>
    </template>

    <div v-else-if="mail.loadingDetail" class="empty">
      <div class="muted">Loading message…</div>
    </div>

    <div v-else-if="mail.detailError" class="empty">
      <div class="big">Could not open this message</div>
      <div>{{ mail.detailError }}</div>
    </div>

    <div v-else class="empty">
      <Icon :path="mdiEmailOpenOutline" :size="40" />
      <div class="big">{{ unreadCount === 0 ? 'Nothing unread' : unreadCount === 1 ? '1 unread message' : `${unreadCount} unread messages` }}</div>
      <div>Select a message to read it. Nothing is selected on purpose, so opening the app never marks mail as read.</div>
    </div>
  </section>
</template>

<style scoped>
.reader {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg);
}
.bar {
  flex: none;
  height: 46px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 12px;
  border-bottom: 1px solid var(--border);
}
.sep {
  width: 1px;
  height: 18px;
  background: var(--border);
  margin: 0 6px;
}
.grow {
  flex: 1;
}
.btn.starred {
  color: var(--star);
}
.btn.flagged {
  color: var(--danger);
}
.scroll {
  flex: 1;
  overflow: auto;
  padding: 22px 32px 40px;
}
.inner {
  user-select: text;
}
h1 {
  margin: 0 0 14px;
  font-size: 21px;
  line-height: 1.25;
  font-weight: 650;
}
.who {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 18px;
}
.av {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  background: var(--bg-hover);
  display: grid;
  place-items: center;
  font-weight: 650;
  color: var(--text-2);
}
.l {
  flex: 1;
  line-height: 1.35;
}
.d {
  color: var(--text-3);
  font-size: 12px;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  background: var(--bg-side);
  border-radius: var(--radius);
  margin-bottom: 16px;
  color: var(--text-2);
}
.banner .btn {
  height: 26px;
  padding: 0 10px;
}
.files {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-raised);
  font: inherit;
  color: var(--text);
  cursor: pointer;
}
.file:hover:not(:disabled) {
  background: var(--bg-hover);
}
.file:disabled {
  cursor: default;
}
.empty {
  margin: auto;
  text-align: center;
  color: var(--text-3);
  max-width: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.big {
  font-size: 15px;
  color: var(--text);
  font-weight: 600;
}
</style>
