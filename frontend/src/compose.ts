import { mdiEmailCheckOutline } from '@mdi/js';
import { reactive, watch } from 'vue';
import { api, type DraftState, type PickedFile, type Prefill, type ReplyMode } from './api';
import { openWindow } from './jolt';
import { ALL, mail } from './mail';
import { showToast, ui } from './ui';

// The message being written. It replaces the reader while it is open. Nothing is saved until it is sent: there are no drafts yet.

export const compose = reactive({
  open: false,
  account: '',
  to: '',
  cc: '',
  bcc: '',
  showCc: false,
  subject: '',
  /** the message text as HTML, as the editor last produced it */
  html: '',
  /** true once the user has changed the text; the editor rewrites the HTML it is given, so it cannot be compared with the start */
  bodyTouched: false,
  replyTo: null as string | null,
  attachments: [] as PickedFile[],
  picking: false,
  /** true once the message went out, so a composer in its own window can stay a moment to show the confirmation */
  justSent: false,
  sending: false,
  error: '',
  /** what the fields held when the composer opened; the draft counts as untouched while it still matches */
  initial: '',
});

function snapshot(): string {
  return JSON.stringify([compose.to, compose.cc, compose.bcc, compose.subject, compose.attachments.map((a) => a.id)]);
}

/** True once the user has typed something the composer did not put there itself. */
export function composeDirty(): boolean {
  return compose.open && (compose.bodyTouched || snapshot() !== compose.initial);
}

/** The account to send from: the one on screen, or the first when the view is "all accounts". */
function defaultAccount(): string {
  return mail.account !== ALL && mail.account ? mail.account : (mail.accounts[0]?.id ?? '');
}

export function openCompose(prefill?: Prefill): void {
  compose.account = prefill?.account ?? defaultAccount();
  compose.to = prefill?.to.join(', ') ?? '';
  compose.cc = prefill?.cc.join(', ') ?? '';
  compose.bcc = '';
  compose.showCc = compose.cc !== '';
  compose.subject = prefill?.subject ?? '';
  compose.html = prefill?.html ?? '';
  compose.bodyTouched = false;
  compose.replyTo = prefill?.replyTo ?? null;
  compose.attachments = [];
  compose.error = '';
  compose.sending = false;
  compose.justSent = false;
  compose.initial = snapshot();
  compose.open = true;
}

/** The message being written, as it travels between windows. */
export function currentDraft(): DraftState {
  return {
    account: compose.account,
    to: compose.to,
    cc: compose.cc,
    bcc: compose.bcc,
    showCc: compose.showCc,
    subject: compose.subject,
    html: compose.html,
    bodyTouched: compose.bodyTouched,
    replyTo: compose.replyTo,
    attachments: compose.attachments.map((a) => ({ ...a })),
    initial: compose.initial,
  };
}

/** Moves the message being written to a window of its own. The main window goes back to the reader; the text and files travel with it. */
export async function popOutCompose(): Promise<void> {
  if (!compose.open || compose.sending || compose.picking) return;
  try {
    const { token } = await api.stashDraft(currentDraft());
    const opened = await openWindow('/?compose=' + token, {
      key: 'compose-' + token,
      title: compose.subject || 'New message',
      width: 900,
      height: 740,
    }).catch(() => false);
    if (!opened) {
      await api.takeDraft(token).catch(() => {}); // nothing opened: do not leave the message behind
      showToast('Could not open a new window', 'The message is still here.', true);
      return;
    }
    closeCompose();
  } catch (e) {
    showToast('Could not open a new window', e instanceof Error ? e.message : String(e), true);
  }
}

/**
 * The window a message was moved to was closed without sending it: put the message back in the composer here. If a message is being
 * written here already, the returned one waits and comes back as soon as that one is sent or discarded.
 */
const waiting: string[] = [];
export async function returnDraft(token: string): Promise<void> {
  if (compose.open) {
    waiting.push(token);
    showToast('Your other message is waiting', 'It comes back when you send or discard this one.');
    return;
  }
  try {
    restoreDraft(await api.takeDraft(token));
    ui.screen = 'mail';
  } catch (e) {
    showToast('Could not bring the message back', e instanceof Error ? e.message : String(e), true);
  }
}
watch(
  () => compose.open,
  (open) => {
    if (!open && waiting.length > 0) void returnDraft(waiting.shift()!);
  },
);

/** In the window a message was moved to: fills the composer with what the first window left. */
export function restoreDraft(draft: DraftState): void {
  compose.account = draft.account;
  compose.to = draft.to;
  compose.cc = draft.cc;
  compose.bcc = draft.bcc;
  compose.showCc = draft.showCc;
  compose.subject = draft.subject;
  compose.html = draft.html;
  compose.bodyTouched = draft.bodyTouched;
  compose.replyTo = draft.replyTo;
  compose.attachments = draft.attachments;
  compose.error = '';
  compose.sending = false;
  compose.justSent = false;
  compose.initial = draft.initial;
  compose.open = true;
}

export function closeCompose(): void {
  compose.open = false;
  compose.error = '';
}

/** Asks Java to open the file dialog, then lists what was chosen. The files themselves are read only when the message is sent. */
export async function attachFiles(): Promise<void> {
  if (compose.picking) return;
  compose.picking = true;
  compose.error = '';
  try {
    const picked = await api.pickAttachments();
    compose.attachments.push(...picked.filter((p) => !compose.attachments.some((a) => a.id === p.id)));
  } catch (e) {
    compose.error = e instanceof Error ? e.message : String(e);
  } finally {
    compose.picking = false;
  }
}

export function removeAttachment(id: string): void {
  compose.attachments = compose.attachments.filter((a) => a.id !== id);
}

/** Opens a reply or a forward of the message on screen, or of the one with this id (from the list's context menu). */
export async function startReply(mode: ReplyMode, messageId?: string): Promise<void> {
  const id = messageId ?? mail.detail?.summary.id;
  if (!id) return;
  if (composeDirty()) {
    showToast('Message not sent yet', 'Send or discard it before starting another.');
    return;
  }
  try {
    // Java needs the body to quote it; this downloads it if it is not cached yet, and does not mark the message read.
    if (id !== mail.detail?.summary.id) await api.get(id);
    openCompose(await api.reply(id, mode));
  } catch (e) {
    showToast('Could not open the reply', e instanceof Error ? e.message : String(e), true);
    return;
  }
  if (mode !== 'forward') void refineRecipients(id, mode);
}

/**
 * The composer opens from the cache; the server's Reply-To and Cc arrive a moment later. They replace the recipients only if the user
 * has not touched them and is still in the same composer, otherwise what was typed wins.
 */
async function refineRecipients(id: string, mode: ReplyMode): Promise<void> {
  const before = { replyTo: id, to: compose.to, cc: compose.cc };
  try {
    const live = await api.replyRecipients(id, mode);
    if (!compose.open || compose.replyTo !== before.replyTo || compose.to !== before.to || compose.cc !== before.cc) return;
    const wasClean = !composeDirty();
    compose.to = live.to.join(', ');
    compose.cc = live.cc.join(', ');
    compose.showCc = compose.showCc || compose.cc !== '';
    if (wasClean) compose.initial = snapshot();
  } catch {
    // the cached recipients stay; sending still threads the reply
  }
}

/** Addresses typed as "a@x.com, b@y.com; c@z.com" (or one per line) into a list. */
function addresses(text: string): string[] {
  return text
    .split(/[,;\n]/)
    .map((a) => a.trim())
    .filter((a) => a !== '');
}

export function canSend(): boolean {
  return compose.open && !compose.sending && compose.account !== '' && addresses(compose.to + ',' + compose.cc + ',' + compose.bcc).length > 0;
}

export async function sendMessage(): Promise<void> {
  if (!canSend()) return;
  compose.sending = true;
  compose.error = '';
  const to = addresses(compose.to);
  const cc = addresses(compose.cc);
  const bcc = addresses(compose.bcc);
  const recipients = [...to, ...cc, ...bcc];
  try {
    const result = await api.send({
      account: compose.account,
      to,
      cc,
      bcc,
      subject: compose.subject,
      html: compose.html,
      replyTo: compose.replyTo,
      attachments: compose.attachments.map((a) => a.id),
    });
    compose.justSent = true;
    closeCompose();
    const first = recipients[0];
    const more = recipients.length - 1;
    // Java leaves a null warning out of the JSON, so it arrives as undefined, not null.
    if (result.warning) showToast('Message sent', result.warning, true);
    else showToast('Message sent', more > 0 ? `To ${first} and ${more} more` : `To ${first}`, false, mdiEmailCheckOutline);
  } catch (e) {
    compose.error = e instanceof Error ? e.message : String(e);
  } finally {
    compose.sending = false;
  }
}
