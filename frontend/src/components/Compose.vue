<script setup lang="ts">
import { mdiCheck, mdiChevronDown, mdiClose, mdiOpenInNew, mdiPaperclip, mdiSend } from '@mdi/js';
import { DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuTrigger } from 'reka-ui';
import { computed, nextTick, onMounted, ref } from 'vue';
import { attachFiles, canSend, closeCompose, compose, composeDirty, popOutCompose, removeAttachment, sendMessage } from '../compose';
import { fileSize } from '../format';
import { accountLabel, mail } from '../mail';
import { composeToken } from '../ui';
import ComposeEditor from './ComposeEditor.vue';
import Icon from './Icon.vue';

const toInput = ref<HTMLInputElement | null>(null);
const editor = ref<InstanceType<typeof ComposeEditor> | null>(null);

const account = computed(() => mail.accounts.find((a) => a.id === compose.account) ?? null);
const isReply = computed(() => compose.replyTo !== null || compose.to !== '');
const heading = computed(() => (compose.replyTo !== null ? 'Reply' : compose.subject.startsWith('Fwd:') ? 'Forward' : 'New message'));

// A reply starts in the body, above the quoted original; a new message starts at the recipient.
onMounted(async () => {
  await nextTick();
  if (isReply.value || compose.subject !== '') {
    editor.value?.focusStart();
  } else {
    toInput.value?.focus();
  }
});

// Discarding something already written asks once: the first press arms the button, the second one throws the message away.
const armed = ref(false);
function discard(): void {
  if (composeDirty() && !armed.value) {
    armed.value = true;
    setTimeout(() => (armed.value = false), 3000);
    return;
  }
  closeCompose();
}
</script>

<template>
  <section class="compose">
    <div class="bar">
      <b>{{ heading }}</b>
      <span class="grow"></span>
      <button class="act" :disabled="compose.picking || compose.sending" title="Attach files" @click="attachFiles">
        <Icon :path="mdiPaperclip" :size="16" />Attach
      </button>
      <span v-if="!composeToken" class="sep"></span>
      <button v-if="!composeToken" class="act" :disabled="compose.picking || compose.sending" title="Open this message in its own window" @click="popOutCompose">
        <Icon :path="mdiOpenInNew" :size="16" />Pop out
      </button>
      <span class="sep"></span>
      <button class="act" :class="{ armed }" :title="armed ? 'Press again to throw this message away' : 'Discard this message'" @click="discard">
        <Icon :path="mdiClose" :size="16" />{{ armed ? 'Discard draft?' : 'Cancel' }}
      </button>
      <span class="sep"></span>
      <button class="act send" :disabled="!canSend()" @click="sendMessage">
        <Icon :path="mdiSend" :size="16" />{{ compose.sending ? 'Sending…' : 'Send' }}
      </button>
    </div>
    <div v-if="compose.error" class="error" role="alert">{{ compose.error }}</div>

    <div class="field">
      <label>From</label>
      <div class="val">
        <DropdownMenuRoot v-if="mail.accounts.length > 1">
          <DropdownMenuTrigger as-child>
            <button class="from" type="button">
              {{ account ? `${accountLabel(account)} <${account.email}>` : 'Choose an account' }}
              <Icon :path="mdiChevronDown" :size="15" class="muted" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuPortal>
            <DropdownMenuContent class="menu" align="start" :side-offset="4">
              <DropdownMenuItem v-for="a in mail.accounts" :key="a.id" class="menu-item" @select="compose.account = a.id">
                <span>
                  <b>{{ accountLabel(a) }}</b>
                  <small>{{ a.email }}</small>
                </span>
                <Icon v-if="a.id === compose.account" :path="mdiCheck" class="check" />
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenuPortal>
        </DropdownMenuRoot>
        <span v-else>{{ account ? `${accountLabel(account)} <${account.email}>` : '' }}</span>
      </div>
    </div>

    <div class="field">
      <label for="c-to">To</label>
      <div class="val"><input id="c-to" ref="toInput" v-model="compose.to" spellcheck="false" autocomplete="off" placeholder="name@example.com, another@example.com" /></div>
      <div v-if="!compose.showCc" class="tools"><a @click="compose.showCc = true">Cc / Bcc</a></div>
    </div>
    <template v-if="compose.showCc">
      <div class="field">
        <label for="c-cc">Cc</label>
        <div class="val"><input id="c-cc" v-model="compose.cc" spellcheck="false" autocomplete="off" /></div>
      </div>
      <div class="field">
        <label for="c-bcc">Bcc</label>
        <div class="val"><input id="c-bcc" v-model="compose.bcc" spellcheck="false" autocomplete="off" /></div>
      </div>
    </template>
    <div class="field">
      <label for="c-subject">Subject</label>
      <div class="val"><input id="c-subject" v-model="compose.subject" class="subject" autocomplete="off" /></div>
    </div>

    <div v-if="compose.attachments.length" class="files">
      <span v-for="a in compose.attachments" :key="a.id" class="chip">
        <Icon :path="mdiPaperclip" :size="14" class="muted" />
        <span class="name">{{ a.name }}</span>
        <small class="muted">{{ fileSize(a.bytes) }}</small>
        <button class="x" :title="'Remove ' + a.name" :aria-label="'Remove ' + a.name" :disabled="compose.sending" @click="removeAttachment(a.id)">
          <Icon :path="mdiClose" :size="14" />
        </button>
      </span>
    </div>

    <ComposeEditor ref="editor" :initial="compose.html" @change="compose.html = $event; compose.bodyTouched = true" />
  </section>
</template>

<style scoped>
.compose {
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
  gap: 6px;
  padding: 0 16px 0 24px;
  border-bottom: 1px solid var(--border);
}
.grow {
  flex: 1;
}
.act {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 12px;
  border: 0;
  border-radius: var(--radius);
  background: none;
  color: var(--text-2);
  font-weight: 500;
  cursor: pointer;
}
.act:hover {
  background: var(--bg-hover);
  color: var(--text);
}
.act.send {
  color: var(--accent);
  font-weight: 600;
}
.act.send:hover {
  background: var(--accent-tint);
}
.act:disabled {
  opacity: 0.5;
  cursor: default;
  background: none;
}
.sep {
  width: 1px;
  height: 18px;
  background: var(--border-strong);
}
.bar b {
  font-size: 14px;
}
.act.armed {
  color: var(--danger);
}
.field {
  flex: none;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 7px 24px;
  border-bottom: 1px solid var(--border);
  min-height: 40px;
}
.field > label {
  width: 56px;
  flex: none;
  color: var(--text-3);
  padding-top: 5px;
}
.val {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  min-height: 26px;
}
.val > span {
  padding-top: 4px;
}
.val input {
  flex: 1;
  min-width: 0;
  height: 26px;
  border: 0;
  outline: 0;
  background: none;
  font: inherit;
  color: var(--text);
}
.subject {
  font-weight: 600;
}
.tools {
  padding-top: 5px;
  color: var(--text-3);
}
.tools a {
  cursor: pointer;
}
.tools a:hover {
  color: var(--text);
}
.from {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: none;
  padding: 4px 0 0;
  font: inherit;
  color: var(--text);
  cursor: pointer;
}
.files {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 24px;
  border-bottom: 1px solid var(--border);
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  height: 28px;
  padding: 0 4px 0 9px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--bg-raised);
}
.chip .name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chip .x {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 4px;
  background: none;
  color: var(--text-3);
  cursor: pointer;
}
.chip .x:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--text);
}
.error {
  flex: none;
  padding: 8px 24px;
  color: var(--danger);
  border-bottom: 1px solid var(--border);
}
</style>
