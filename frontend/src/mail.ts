import { computed, reactive } from 'vue';
import { api, type MoveAction, type Account, type Detail, type Folder, type Summary, type SyncStatus } from './api';
import { onEvent } from './jolt';
import { openWindow } from './jolt';
import { popout, showToast, ui } from './ui';

// The mail the UI is showing. Java owns the data; this only holds what is on screen and asks Java for the rest.

export const ALL = 'all';

export type Filter = 'all' | 'unread' | 'starred' | 'important' | 'attachments';

export const mail = reactive({
  /** false until the first answer from Java, so the first-run screen does not flash up before we know there are no accounts */
  loaded: false,
  accounts: [] as Account[],
  /** the account on screen; empty until the accounts are known, then the first one ("All accounts" is a choice, not the default) */
  account: '',
  folders: [] as Folder[],
  folder: 'inbox',
  messages: [] as Summary[],
  selectedId: null as string | null,
  detail: null as Detail | null,
  loadingDetail: false,
  detailError: null as string | null,
  query: '',
  filter: 'all' as Filter,
  error: null as string | null,
});

export const sync = reactive({ statuses: {} as Record<string, SyncStatus> });

/** What the toolbar says about syncing, from the status of every account. */
export const syncSummary = computed(() => {
  const all = Object.values(sync.statuses);
  if (all.some((s) => s.state === 'SYNCING')) return { state: 'syncing' as const, lastSync: null, error: null };
  const failed = all.find((s) => s.state === 'ERROR');
  if (failed) return { state: 'error' as const, lastSync: null, error: failed.error };
  const times = all.map((s) => s.lastSync).filter((t): t is number => t !== null);
  return { state: 'idle' as const, lastSync: times.length ? Math.max(...times) : null, error: null };
});

/** The accounts as the switcher shows them: each one, plus a combined entry when there is more than one. */
export const switcherAccounts = computed<Account[]>(() =>
  mail.accounts.length > 1
    ? [...mail.accounts, { id: ALL, name: 'All accounts', email: `${mail.accounts.length} accounts` }]
    : mail.accounts,
);

/** The name to show for an account: the one the user gave, or the part of the address before the @ (never the whole address twice). */
export function accountLabel(account: Account): string {
  return account.name && account.name !== account.email ? account.name : account.email.split('@')[0];
}

export const currentAccount = computed(() => switcherAccounts.value.find((a) => a.id === mail.account) ?? null);

export const folderTitle = computed(() => {
  if (mail.folder === 'inbox' && mail.account === ALL) return 'All inboxes';
  return mail.folders.find((f) => f.id === mail.folder)?.name ?? '';
});

export const unreadCount = computed(() => mail.messages.filter((m) => m.unread).length);

export const visibleMessages = computed(() => {
  const query = mail.query.trim().toLowerCase();
  // Messages that were just moved here and whose move is still going on the server: shown now, replaced by the real ones after it.
  const arrivals = Object.values(arriving)
    .filter((a) => a.folder === mail.folder && (mail.account === ALL || a.row.account === mail.account))
    .map((a) => a.row)
    // The real copy can arrive (a sync event) a moment before the move reports back: then the stand-in is not shown twice.
    .filter((row) => !mail.messages.some((m) => m.id === row.id
      || (m.account === row.account && m.epochMillis === row.epochMillis && m.subject === row.subject && m.fromAddress === row.fromAddress)));
  const rows = arrivals.length === 0 ? mail.messages : [...arrivals, ...mail.messages].sort((a, b) => b.epochMillis - a.epochMillis);
  return rows.filter((m) => {
    if (mail.filter === 'unread' && !m.unread) return false;
    if (mail.filter === 'starred' && !m.starred) return false;
    if (mail.filter === 'important' && !m.important) return false;
    if (mail.filter === 'attachments' && !m.hasAttachments) return false;
    if (!query) return true;
    return [m.fromName, m.fromAddress, m.subject, m.snippet].some((field) => field.toLowerCase().includes(query));
  });
});

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

let reloadCount = 0;
/** Ids of messages that were moved away on screen and whose move on the server has not finished. */
const moving = new Set<string>();
/** Messages moved on screen whose move is still going, by their id: the row and the id of the folder they are on their way to. */
const arriving = reactive({} as Record<string, { row: Summary; folder: string }>);

/** The folder id a move is heading for, as the sidebar knows it (a standard folder's id or "f12"). */
function destinationOf(action: MoveAction, folder?: string): string {
  return action === 'trash' ? 'trash' : action === 'archive' ? 'archive' : action === 'restore' ? 'inbox' : (folder ?? '');
}

/** Re-reads the folder list and the open folder. Keeps the selection unless that message is gone. */
async function reload(): Promise<void> {
  if (mail.accounts.length === 0) {
    mail.folders = [];
    mail.messages = [];
    return;
  }
  const mine = ++reloadCount;
  try {
    const [folders, messages] = await Promise.all([api.folders(mail.account), api.list(mail.account, mail.folder)]);
    if (mine !== reloadCount) return; // a newer reload is already on its way
    mail.folders = folders;
    // A message whose move to the server is still running is not in the list: the cache still has it until the server agrees.
    mail.messages = moving.size === 0 ? messages : messages.filter((m) => !moving.has(m.id));
    mail.error = null;
    if (mail.selectedId && !messages.some((m) => m.id === mail.selectedId)) clearSelection();
  } catch (e) {
    mail.error = message(e);
  }
}

let reloadTimer: ReturnType<typeof setTimeout> | undefined;

/** Sync reports progress folder by folder; this folds a burst of those into one reload. */
function reloadSoon(): void {
  clearTimeout(reloadTimer);
  reloadTimer = setTimeout(() => void reload(), 250);
}

async function refreshAccounts(): Promise<void> {
  mail.accounts = await api.accounts();
  if (!switcherAccounts.value.some((a) => a.id === mail.account)) mail.account = mail.accounts[0]?.id ?? ALL;
}

export async function initMail(): Promise<void> {
  // A message changed in its own window is only known here after the next sync; coming back to this window reads the cache again.
  window.addEventListener('focus', reloadSoon);
  onEvent<{ account: string }>('mail.changed', reloadSoon);
  onEvent<SyncStatus>('sync.state', (status) => {
    sync.statuses[status.account] = status;
  });
  try {
    await refreshAccounts();
    for (const status of await api.syncStatus()) sync.statuses[status.account] = status;
    await reload();
  } catch (e) {
    mail.error = message(e);
  }
  mail.loaded = true;
}

/** A window that shows one message (see `popout` in ui.ts): its account, its folder (for the Archive/Trash buttons) and the message. */
export async function initPopout(id: string, folder: string): Promise<void> {
  try {
    const detail = await api.get(id);
    mail.accounts = await api.accounts();
    mail.account = detail.summary.account;
    mail.folder = folder;
    await selectMessage(id);
  } catch (e) {
    mail.error = message(e);
    mail.detailError = message(e);
  }
  mail.loaded = true;
}

/** Opens the message in a window of its own (one window per message: asking again brings that window to the front). */
export async function openInNewWindow(id: string | null): Promise<void> {
  const subject = mail.detail?.summary.id === id ? mail.detail.summary.subject : '';
  if (!id) return;
  const path = '/?popout=' + encodeURIComponent(id) + '&folder=' + encodeURIComponent(mail.folder);
  const opened = await openWindow(path, { key: 'message-' + id, title: subject || 'Message', width: 960, height: 760 }).catch(() => false);
  if (!opened) showToast('Could not open a new window', undefined, true);
}

/** Marks the open message unread. It stays open; the list shows it as unread again. */
export async function markSelectedUnread(): Promise<void> {
  const id = mail.selectedId;
  if (!id) return;
  try {
    if (mail.messages.some((m) => m.id === id)) await setRead(id, false);
    else await api.markRead(id, false);
    showToast('Marked as unread');
  } catch (e) {
    showToast('Could not change the message', message(e), true);
  }
}

export async function selectAccount(id: string): Promise<void> {
  mail.account = id;
  mail.folder = 'inbox';
  clearSelection();
  await reload();
}

/** Opens a message from a notification: its account's inbox, with the message in the reader. */
export async function openMessage(account: string, id: string): Promise<void> {
  ui.screen = 'mail';
  if (mail.account !== account || mail.folder !== 'inbox') await selectAccount(account);
  await selectMessage(id);
}

export async function selectFolder(id: string): Promise<void> {
  mail.folder = id;
  clearSelection();
  await reload();
}

function clearSelection(): void {
  mail.selectedId = null;
  mail.detail = null;
  mail.detailError = null;
}

export async function selectMessage(id: string): Promise<void> {
  mail.selectedId = id;
  mail.detail = null;
  mail.detailError = null;
  mail.loadingDetail = true;
  try {
    const detail = await api.get(id);
    if (mail.selectedId !== id) return; // the user clicked something else meanwhile
    mail.detail = detail;
    const row = mail.messages.find((m) => m.id === id);
    if (row?.unread) {
      row.unread = false;
      await api.markRead(id, true);
      mail.folders = await api.folders(mail.account);
    }
  } catch (e) {
    if (mail.selectedId === id) mail.detailError = message(e);
  } finally {
    if (mail.selectedId === id) mail.loadingDetail = false;
  }
}

/** Stars or unstars a message: the page changes at once, Java files it on the server in the background. */
export async function toggleStarOf(id: string | null): Promise<void> {
  if (!id) return;
  const row = mail.messages.find((m) => m.id === id);
  const summary = mail.detail?.summary.id === id ? mail.detail.summary : null;
  const current = row?.starred ?? summary?.starred;
  if (current === undefined) return;
  const set = (value: boolean): void => {
    if (row) row.starred = value;
    if (summary) summary.starred = value;
  };
  set(!current);
  try {
    await api.setStarred(id, !current);
  } catch (e) {
    set(current);
    showToast('Could not change the star', message(e), true);
  }
}

export const toggleStar = (): Promise<void> => toggleStarOf(mail.selectedId);

/** Marks a message important or not. The page changes at once and goes back, with the server's reason, if the server cannot keep it. */
export async function toggleImportantOf(id: string | null): Promise<void> {
  if (!id) return;
  const row = mail.messages.find((m) => m.id === id);
  const summary = mail.detail?.summary.id === id ? mail.detail.summary : null;
  const current = row?.important ?? summary?.important;
  if (current === undefined) return;
  const set = (value: boolean): void => {
    if (row) row.important = value;
    if (summary) summary.important = value;
  };
  set(!current);
  try {
    await api.setImportant(id, !current);
  } catch (e) {
    set(current);
    showToast('Could not change the Important mark', message(e), true);
  }
}

export const toggleImportant = (): Promise<void> => toggleImportantOf(mail.selectedId);

/** Marks a message read or unread without opening it. */
export async function setRead(id: string, read: boolean): Promise<void> {
  const row = mail.messages.find((m) => m.id === id);
  if (!row || row.unread === !read) return;
  row.unread = !read;
  try {
    await api.markRead(id, read);
    mail.folders = await api.folders(mail.account);
  } catch (e) {
    row.unread = read;
    showToast('Could not change the message', message(e), true);
  }
}

/** Moves wait this long for company before they go to the server together: invisible to the user, whose rows are already gone. */
const MOVE_BATCH_DELAY_MS = 250;

interface MoveBatch {
  action: MoveAction;
  folder?: string;
  failure: string;
  ids: string[];
  done: Array<(moved: boolean) => void>;
}

const waitingMoves = new Map<string, MoveBatch>();
let moveTimer: ReturnType<typeof setTimeout> | undefined;
let sending: Promise<void> = Promise.resolve();
const finished: string[] = [];
/** How many moves of this window have not been answered yet; a pop-out waits for 0 before it closes itself. */
export const moveState = reactive({ pending: 0 });

/**
 * Moves a message to another folder (archive, trash, ...). The row leaves the list at once and stays gone through any reload;
 * the moves of a moment are sent to Java together, which moves them on the server over one connection and answers when that is done.
 * If the server refuses, the message comes back with a sentence saying why instead of vanishing. Deleting a dozen messages in a row
 * is one trip to the server and one refresh of the list, not a dozen.
 */
function moveMessage(id: string | null, action: MoveAction, failure: string, folder?: string): Promise<boolean> {
  if (!id) return Promise.resolve(false);
  moving.add(id);
  const row = mail.messages.find((m) => m.id === id);
  if (row) arriving[id] = { row: { ...row }, folder: destinationOf(action, folder) };
  mail.messages = mail.messages.filter((m) => m.id !== id);
  if (mail.selectedId === id) clearSelection();
  moveState.pending++;
  const key = action + '|' + (folder ?? '');
  let batch = waitingMoves.get(key);
  if (!batch) {
    batch = { action, folder, failure, ids: [], done: [] };
    waitingMoves.set(key, batch);
  }
  batch.ids.push(id);
  return new Promise<boolean>((resolve) => {
    batch.done.push(resolve);
    moveTimer ??= setTimeout(flushMoves, MOVE_BATCH_DELAY_MS);
  });
}

function flushMoves(): void {
  moveTimer = undefined;
  const batches = [...waitingMoves.values()];
  waitingMoves.clear();
  // One batch at a time: parallel ones would only open parallel connections to the same server.
  sending = sending.then(async () => {
    for (const batch of batches) {
      let moved = true;
      try {
        await api.moveMany(batch.ids, batch.action, batch.folder);
      } catch (e) {
        moved = false;
        showToast(batch.failure, message(e), true);
      }
      for (const id of batch.ids) {
        moving.delete(id);
        if (!moved) {
          delete arriving[id]; // refused: it never left, and the reload below brings it back
          if (popout) void selectMessage(id); // a message in its own window has no list to come back to
        }
        finished.push(id);
      }
      for (const resolve of batch.done) {
        resolve(moved);
        moveState.pending--;
      }
    }
    // Only when nothing else is waiting or on its way, so the list is not redrawn under the user's hands for every message.
    if (waitingMoves.size === 0 && moving.size === 0) {
      await reload();
      // The real rows are in the list now, so the stand-ins can go without the message blinking out for a moment.
      for (const id of finished.splice(0)) delete arriving[id];
    }
  });
}

export const archiveMessage = (id: string | null): Promise<boolean> => moveMessage(id, 'archive', 'Could not archive the message');
export const restoreMessage = (id: string | null): Promise<boolean> => moveMessage(id, 'restore', 'Could not restore the message');
export const trashMessage = (id: string | null): Promise<boolean> => moveMessage(id, 'trash', 'Could not delete the message');

/** Moves a message into one of its account's folders and says where it went. */
export async function moveMessageTo(id: string, folder: { id: string; name: string }): Promise<void> {
  if (await moveMessage(id, 'move', 'Could not move the message', folder.id)) showToast('Moved to ' + folder.name);
}

export const archiveSelected = (): Promise<boolean> => archiveMessage(mail.selectedId);
export const restoreSelected = (): Promise<boolean> => restoreMessage(mail.selectedId);
export const trashSelected = (): Promise<boolean> => trashMessage(mail.selectedId);

/** A new account was added on the server side: show its inbox. */
export async function accountAdded(account: Account): Promise<void> {
  await refreshAccounts();
  mail.account = account.id;
  mail.folder = 'inbox';
  clearSelection();
  await reload();
}

export async function removeAccount(id: string): Promise<void> {
  await api.removeAccount(id);
  delete sync.statuses[id];
  await refreshAccounts();
  clearSelection();
  await reload();
  if (mail.accounts.length === 0) {
    ui.screen = 'mail';
    ui.addAccount = true;
  }
}

export async function syncNow(): Promise<void> {
  try {
    await api.syncNow();
  } catch (e) {
    mail.error = message(e);
  }
}
