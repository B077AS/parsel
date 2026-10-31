<script setup lang="ts">
import { mdiLockOutline } from '@mdi/js';
import { ToggleGroupItem, ToggleGroupRoot } from 'reka-ui';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { api, type AccountForm, type Security, type TestResult } from '../api';
import { accountAdded, mail } from '../mail';
import { ui } from '../ui';
import Icon from './Icon.vue';
import Logo from './Logo.vue';

// Every setting is typed in by hand: Parsel is for people who run their own mail server and know its details.
// A card over the app: on the first run the app behind it is only its empty outline; when adding another account the real app
// (App.vue keeps it on screen, inert) is blurred behind the card, so the page never feels like a separate website. The connection
// check reports under each server line.

const form = reactive<AccountForm>({
  name: '',
  email: '',
  username: '',
  imapHost: '',
  imapPort: 993,
  imapSecurity: 'SSL',
  smtpHost: '',
  smtpPort: 465,
  smtpSecurity: 'SSL',
});
const password = ref('');
const stage = ref<'form' | 'working' | 'done'>('form');
const result = ref<TestResult | null>(null);
const error = ref('');
const screen = ref<HTMLElement | null>(null);
const editingNames = ref(false);

// Fixed when the screen opens: adding the account must not change the heading or the buttons under the user's eyes.
const first = mail.accounts.length === 0;

// The empty outline for the first run: enough rows to fill a tall window (the rest is clipped), in uneven lengths so it reads as a list.
const OUTLINE_FOLDERS = ['58%', '40%', '52%', '36%', '46%', '30%', '50%', '38%', '44%'];
const OUTLINE_ROWS = Array.from({ length: 16 }, (_, i) => [62 + ((i * 17) % 32) + '%', 34 + ((i * 29) % 40) + '%', 48 + ((i * 13) % 36) + '%']);

ui.addingFirst = first; // tells App.vue whether to keep the real app on screen behind the card
onBeforeUnmount(() => (ui.addingFirst = false));

// The username is usually the address itself, so it follows the address until the user types a different one.
const usernameEdited = ref(false);
watch(
  () => form.email,
  (email) => {
    if (!usernameEdited.value) form.username = email.trim();
  },
);

// The usual port for each way of securing a connection. Switching the security moves the port only if it still holds
// the usual port of the other choice, so a port the user typed is never overwritten.
const IMAP_PORTS: Partial<Record<Security, number>> = { SSL: 993, STARTTLS: 143 };
const SMTP_PORTS: Partial<Record<Security, number>> = { SSL: 465, STARTTLS: 587 };

function setSecurity(which: 'imap' | 'smtp', value: unknown): void {
  if (value !== 'SSL' && value !== 'STARTTLS') return; // clicking the active choice gives nothing back
  const ports = which === 'imap' ? IMAP_PORTS : SMTP_PORTS;
  const key = which === 'imap' ? 'imapPort' : 'smtpPort';
  const security = which === 'imap' ? 'imapSecurity' : 'smtpSecurity';
  const next = ports[value];
  if (next !== undefined && Object.values(ports).includes(form[key])) form[key] = next;
  form[security] = value;
}

const looksLikeAddress = computed(() => /^[^@\s]+@([^@\s]+\.[^@\s]+|localhost)$/.test(form.email.trim()));
const ready = computed(
  () =>
    looksLikeAddress.value &&
    password.value !== '' &&
    form.username.trim() !== '' &&
    form.imapHost.trim() !== '' &&
    form.imapPort > 0 &&
    form.smtpHost.trim() !== '' &&
    form.smtpPort > 0,
);
const busy = computed(() => stage.value === 'working');
const failed = computed(() => result.value !== null && !result.value.imapOk);

// What each server line says under its heading. The page asks for both checks in one call, so while it runs the second only waits.
const incoming = computed(() => {
  if (busy.value) return { state: 'run', text: 'Connecting…' };
  if (!result.value) return { state: 'idle', text: 'Not checked yet' };
  return result.value.imapOk
    ? { state: 'ok', text: `Signed in · found ${result.value.folders} folders` }
    : { state: 'bad', text: result.value.imapError };
});
const outgoing = computed(() => {
  if (busy.value) return { state: 'wait', text: 'Waiting' };
  if (!result.value) return { state: 'idle', text: 'Not checked yet' };
  if (!result.value.imapOk) return { state: 'wait', text: 'Not checked' };
  return result.value.smtpOk ? { state: 'ok', text: 'Signed in' } : { state: 'bad', text: result.value.smtpError };
});

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

async function submit(): Promise<void> {
  if (stage.value === 'done') {
    openInbox();
    return;
  }
  if (!ready.value || stage.value !== 'form') return;
  stage.value = 'working';
  error.value = '';
  result.value = null;
  form.email = form.email.trim();
  form.username = form.username.trim();
  form.imapHost = form.imapHost.trim();
  form.smtpHost = form.smtpHost.trim();
  try {
    result.value = await api.testAccount(form, password.value);
    if (!result.value.imapOk) {
      stage.value = 'form';
      return;
    }
    await accountAdded(await api.addAccount(form, password.value));
    password.value = '';
    stage.value = 'done';
  } catch (e) {
    error.value = message(e);
    stage.value = 'form';
  }
}

function openInbox(): void {
  ui.screen = 'mail';
  ui.addAccount = false;
}

function cancel(): void {
  if (!first && !busy.value && stage.value !== 'done') ui.addAccount = false;
}

onMounted(() => void nextTick(() => screen.value?.querySelector('input')?.focus()));
</script>

<template>
  <form ref="screen" class="screen" :class="{ over: !first }" @submit.prevent="submit" @keydown.esc="cancel">
    <!-- first run: only the outline of the app behind the card (when adding another account, the real app is behind it) -->
    <div v-if="first" class="shell" aria-hidden="true">
      <div class="g-side">
        <i class="bar button" />
        <i v-for="(w, i) in OUTLINE_FOLDERS" :key="i" class="bar" :style="{ width: w }" />
      </div>
      <div class="g-list">
        <div v-for="(w, i) in OUTLINE_ROWS" :key="i" class="g-row">
          <i class="bar" :style="{ width: w[0] }" /><i class="bar thin" :style="{ width: w[1] }" /><i class="bar thin" :style="{ width: w[2] }" />
        </div>
      </div>
    </div>

    <div class="veil">
      <fieldset class="card" :disabled="stage !== 'form'">
        <header>
          <Logo :size="34" />
          <div>
            <h1>{{ first ? 'Connect your mailbox' : 'Add an account' }}</h1>
            <p>{{ first ? 'Type the details your mail provider gave you.' : 'It will appear in your account switcher.' }}</p>
          </div>
        </header>

        <div class="body">
          <div class="two">
            <label class="in">
              <span>Email address</span>
              <input v-model="form.email" type="email" autocomplete="off" spellcheck="false" placeholder="you@example.org" />
            </label>
            <label class="in">
              <span>Password</span>
              <input v-model="password" type="password" autocomplete="off" />
            </label>
          </div>

          <div v-if="!editingNames" class="more">
            <span>
              Username <b>{{ form.username || 'same as the address' }}</b>
              <template v-if="form.name"> · Name <b>{{ form.name }}</b></template>
            </span>
            <a tabindex="0" @click="editingNames = true" @keydown.enter="editingNames = true">Edit</a>
          </div>
          <div v-else class="two">
            <label class="in">
              <span>Username</span>
              <input v-model="form.username" spellcheck="false" placeholder="same as the address" @input="usernameEdited = true" />
            </label>
            <label class="in">
              <span>Account name <small>(optional)</small></span>
              <input v-model="form.name" placeholder="Shown in the account switcher" />
            </label>
          </div>

          <div class="srv">
            <div class="top">
              <span class="lab">Incoming server <small>IMAP</small></span>
              <span class="st" :class="incoming.state"><i class="d" />{{ incoming.text }}</span>
            </div>
            <div class="line">
              <input v-model="form.imapHost" placeholder="imap.example.com" spellcheck="false" aria-label="Incoming server" />
              <input v-model.number="form.imapPort" type="number" min="1" max="65535" aria-label="Incoming port" />
              <ToggleGroupRoot class="seg" type="single" :model-value="form.imapSecurity" aria-label="Incoming security" @update:model-value="setSecurity('imap', $event)">
                <ToggleGroupItem value="SSL">SSL/TLS</ToggleGroupItem>
                <ToggleGroupItem value="STARTTLS">STARTTLS</ToggleGroupItem>
              </ToggleGroupRoot>
            </div>
          </div>

          <div class="srv">
            <div class="top">
              <span class="lab">Outgoing server <small>SMTP</small></span>
              <span class="st" :class="outgoing.state"><i class="d" />{{ outgoing.text }}</span>
            </div>
            <div class="line">
              <input v-model="form.smtpHost" placeholder="smtp.example.com" spellcheck="false" aria-label="Outgoing server" />
              <input v-model.number="form.smtpPort" type="number" min="1" max="65535" aria-label="Outgoing port" />
              <ToggleGroupRoot class="seg" type="single" :model-value="form.smtpSecurity" aria-label="Outgoing security" @update:model-value="setSecurity('smtp', $event)">
                <ToggleGroupItem value="SSL">SSL/TLS</ToggleGroupItem>
                <ToggleGroupItem value="STARTTLS">STARTTLS</ToggleGroupItem>
              </ToggleGroupRoot>
            </div>
          </div>

          <p v-if="failed" class="problem" role="alert">Nothing was saved. Check the details above, then try again.</p>
          <p v-else-if="error" class="problem" role="alert">{{ error }}</p>
          <p v-else-if="stage === 'done' && result && !result.smtpOk" class="note-line">
            The account was added and its mail is syncing. Sending will not work until the outgoing server is fixed.
          </p>
          <p v-else-if="stage === 'done'" class="note-line">The account was added and its mail is syncing in the background.</p>
        </div>

        <footer>
          <span class="note"><Icon :path="mdiLockOutline" :size="15" />Your password goes to the system keystore.</span>
          <button v-if="!first && stage !== 'done'" class="btn ghost" type="button" :disabled="busy" @click="cancel">Cancel</button>
          <button v-if="stage === 'done'" class="btn primary" type="button" @click="openInbox">Open inbox</button>
          <button v-else class="btn primary" type="submit" :disabled="!ready || busy">
            {{ busy ? 'Checking…' : failed ? 'Try again' : 'Add account' }}
          </button>
        </footer>
      </fieldset>
    </div>
  </form>
</template>

<style scoped>
.screen {
  flex: 1;
  min-height: 0;
  position: relative;
  display: flex;
  background: var(--bg);
}
/* Over a real app: the card and its veil cover the whole window, toolbar included; the app below is inert and shows through the blur. */
.screen.over {
  position: fixed;
  inset: 0;
  z-index: 35;
  background: none;
}

/* ---- the app behind the card ---- */
.shell {
  position: absolute;
  inset: 0;
  display: flex;
  pointer-events: none;
  user-select: none;
}
.g-side {
  width: var(--sidebar-width);
  flex: none;
  background: var(--bg-side);
  border-right: 1px solid var(--border);
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: hidden;
}
.g-list {
  width: 380px;
  flex: none;
  border-right: 1px solid var(--border);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  overflow: hidden;
}
.bar {
  display: block;
  flex: none;
  height: 11px;
  border-radius: 5px;
  background: color-mix(in srgb, var(--border-strong) 75%, transparent);
}
.bar.button {
  width: 100%;
  height: 34px;
  margin-bottom: 8px;
  border-radius: var(--radius);
}
.bar.thin {
  height: 8px;
  opacity: 0.65;
}
.g-row {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--border);
}

/* ---- the card ---- */
.over .veil {
  background: color-mix(in srgb, var(--bg) 36%, transparent); /* a real app is behind it: let it show */
}
.veil {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 20px;
  overflow: auto;
  background: color-mix(in srgb, var(--bg) 58%, transparent);
  backdrop-filter: blur(3px);
}
.card {
  width: 600px;
  max-width: 100%;
  margin: 0;
  padding: 0;
  min-width: 0;
  background: var(--bg-raised);
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  box-shadow: 0 18px 50px rgb(0 0 0 / 0.2);
}
.card:disabled .in input,
.card:disabled .line input,
.card:disabled .seg {
  opacity: 0.6;
}
header {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 20px 24px 16px;
}
h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
}
p {
  margin: 0;
  color: var(--text-2);
}
.body {
  padding: 0 24px 6px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.in {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}
.in > span,
.lab {
  font-weight: 550;
  font-size: 12.5px;
}
.lab {
  flex: none;
  white-space: nowrap;
}
small {
  color: var(--text-3);
  font-size: 12px;
  font-weight: 400;
}
.in input,
.line input {
  height: 34px;
  width: 100%;
  min-width: 0;
  padding: 0 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius);
  background: var(--bg-raised);
  font: inherit;
  color: var(--text);
}
.in input:focus,
.line input:focus {
  outline: 2px solid var(--accent);
  outline-offset: -1px;
  border-color: var(--accent);
}
.more {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: 12.5px;
}
.more b {
  font-weight: 550;
  color: var(--text);
}
.more a {
  color: var(--accent);
  cursor: pointer;
}
.srv {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 16px;
}
.line {
  display: grid;
  grid-template-columns: 1fr 78px auto;
  gap: 8px;
  align-items: center;
}
.seg button {
  height: 34px;
}

/* a check result: a dot (or a spinner) and words */
.st {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  font-size: 12.5px;
  color: var(--text-3);
  text-align: right;
}
.st .d {
  width: 8px;
  height: 8px;
  flex: none;
  border-radius: 50%;
  background: var(--border-strong);
}
.st.ok {
  color: var(--ok);
}
.st.ok .d {
  background: var(--ok);
}
.st.bad {
  color: var(--danger);
}
.st.bad .d {
  background: var(--danger);
}
.st.run {
  color: var(--text-2);
}
.st.run .d {
  width: 12px;
  height: 12px;
  background: none;
  border: 2px solid var(--border-strong);
  border-top-color: var(--accent);
  animation: spin 0.8s linear infinite;
}
.st.wait .d {
  background: none;
  border: 2px solid var(--border-strong);
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
.problem {
  color: var(--danger);
  font-size: 12.5px;
}
.note-line {
  font-size: 12.5px;
}

footer {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 14px 24px 18px;
  border-top: 1px solid var(--border);
}
.note {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--text-3);
  font-size: 12px;
}
footer .btn {
  height: 34px;
}

/* The window never gets smaller than 1280x720, so this only matters if that limit is ever lowered. */
@media (max-width: 900px) {
  .g-list {
    display: none;
  }
}
</style>
