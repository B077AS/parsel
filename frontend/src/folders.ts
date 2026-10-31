import { reactive } from 'vue';
import { api, type Folder } from './api';
import { ALL, mail, moveMessageTo } from './mail';
import { demo, showToast } from './ui';

// The "Move to" / "New folder" dialog. One dialog serves the reader's button, the message list's context menu and the sidebar's "+":
// it lists the folders of one account, can move a message into one of them, and can make a new folder (and then move the message into it).

export const folderDialog = reactive({
  open: false,
  /** what it shows first: the folder list (to move a message) or the form for a new folder */
  mode: 'move' as 'move' | 'create',
  account: '',
  /** the message being moved, or null when the dialog only makes a folder */
  messageId: null as string | null,
  /** the folder the message is in now, if known (the Starred view mixes folders, so it is unknown there) */
  current: '',
  folders: [] as Folder[],
  loading: false,
});

function accountOfMessage(id: string): string | null {
  return mail.messages.find((m) => m.id === id)?.account ?? (mail.detail?.summary.id === id ? mail.detail.summary.account : null);
}

async function loadFolders(): Promise<void> {
  folderDialog.loading = true;
  try {
    // The Starred view is not a folder anyone can move mail into.
    folderDialog.folders = (await api.folders(folderDialog.account)).filter((f) => f.id !== 'starred');
  } catch (e) {
    folderDialog.open = false;
    showToast('Could not list the folders', e instanceof Error ? e.message : String(e), true);
  } finally {
    folderDialog.loading = false;
  }
}

/** Asks where to move a message. */
export async function openMoveTo(id: string | null): Promise<void> {
  if (!id || demo) return;
  const account = accountOfMessage(id);
  if (!account) return;
  Object.assign(folderDialog, { open: true, mode: 'move', account, messageId: id, current: mail.folder === 'starred' ? '' : mail.folder, folders: [] });
  await loadFolders();
}

/** Opens the form for a new folder in the account on screen. Not offered when all accounts are shown, because it would not know which one. */
export async function openNewFolder(): Promise<void> {
  if (demo || mail.account === ALL || mail.account === '') return;
  Object.assign(folderDialog, { open: true, mode: 'create', account: mail.account, messageId: null, current: '', folders: [] });
  await loadFolders();
}

/** Moves the message the dialog was opened for into this folder. */
export async function chooseFolder(folder: Folder): Promise<void> {
  const id = folderDialog.messageId;
  folderDialog.open = false;
  if (id) await moveMessageTo(id, folder);
}

/**
 * Makes the folder, inside another one or at the top level. A failure is thrown so the dialog can show it under the name; on success
 * the dialog closes, and the message it was opened for (if any) goes into the new folder.
 */
export async function createFolderAndMove(name: string, parent: string | null): Promise<void> {
  const folder = await api.createFolder(folderDialog.account, name, parent);
  if (mail.account === folderDialog.account) mail.folders = await api.folders(mail.account);
  const id = folderDialog.messageId;
  folderDialog.open = false;
  if (id) await moveMessageTo(id, folder);
  else showToast('Folder created', folder.name);
}
