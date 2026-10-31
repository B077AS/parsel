import { call } from './jolt';

// One typed function per handler registered in Handlers.java and App.java. If you rename a handler there, change it here.
// The interfaces mirror the records in com.parsel.mail.Mail and com.parsel.account.

export interface AppInfo {
  os: string;
  java: string;
}

export interface Account {
  id: string;
  name: string;
  email: string;
}

export interface Folder {
  id: string;
  name: string;
  unread: number;
  /** how many folders it sits inside (0 for a top-level one); the sidebar indents by it */
  depth: number;
}

export interface Attachment {
  name: string;
  bytes: number;
}

export interface Summary {
  id: string;
  account: string;
  fromName: string;
  fromAddress: string;
  subject: string;
  snippet: string;
  epochMillis: number;
  unread: boolean;
  starred: boolean;
  thread: number;
  hasAttachments: boolean;
  important: boolean;
}

export interface Detail {
  summary: Summary;
  to: string[];
  bodyHtml: string;
  hasRemoteImages: boolean;
  attachments: Attachment[];
}

/** A message to send. The recipient lists hold plain addresses; replyTo is the id of the message being answered. */
export interface Draft {
  account: string;
  to: string[];
  cc: string[];
  bcc: string[];
  subject: string;
  /** the message as HTML; Java cleans it and writes the plain-text version itself */
  html: string;
  replyTo: string | null;
  /** ids from `pickAttachments`; Java reads the files when the message is sent */
  attachments: string[];
}

/** A file the user picked to attach. The page only sees its name and size, Java keeps the path behind `id`. */
export interface PickedFile {
  id: string;
  name: string;
  bytes: number;
}

/** A starting point for the composer: Java works out recipients, subject and the quoted original. */
export interface Prefill {
  account: string;
  to: string[];
  cc: string[];
  subject: string;
  /** the starting text as HTML, with the original quoted */
  html: string;
  replyTo: string | null;
}

/** Everything about a message being written, as it moves to a window of its own. */
export interface DraftState {
  account: string;
  to: string;
  cc: string;
  bcc: string;
  showCc: boolean;
  subject: string;
  html: string;
  bodyTouched: boolean;
  replyTo: string | null;
  attachments: PickedFile[];
  /** what the fields held when the composer first opened, so "has it been changed" still means the same */
  initial: string;
}

export type ReplyMode = 'reply' | 'replyAll' | 'forward';

export interface SendResult {
  /** set when the mail went out but something around it failed (no copy in Sent) */
  warning?: string | null;
}

export type Security = 'SSL' | 'STARTTLS' | 'NONE';

/** What the Add account form edits: the address, the login and the two servers. The password travels separately. */
export interface AccountForm {
  name: string;
  email: string;
  username: string;
  imapHost: string;
  imapPort: number;
  imapSecurity: Security;
  smtpHost: string;
  smtpPort: number;
  smtpSecurity: Security;
}

export interface Detected {
  /** domain, database or guess */
  source: 'domain' | 'database' | 'guess';
  imapHost: string;
  imapPort: number;
  imapSecurity: Security;
  smtpHost: string;
  smtpPort: number;
  smtpSecurity: Security;
  username: string;
}

/** The outcome of logging in. The error fields are sentences for the user, null when that part worked. */
export interface TestResult {
  imapOk: boolean;
  imapError: string | null;
  smtpOk: boolean;
  smtpError: string | null;
  folders: number;
}

export type SyncState = 'IDLE' | 'SYNCING' | 'ERROR' | 'LOCKED';

export interface SyncStatus {
  account: string;
  state: SyncState;
  lastSync: number | null;
  error: string | null;
}

export interface VaultStatus {
  /** where passwords live: the operating system's keystore, or Parsel's own encrypted file */
  mode: 'os' | 'vault';
  state: 'ready' | 'new' | 'locked';
  /** why the OS keystore is not used (only with mode vault) */
  reason?: string;
}

export type Preference = 'system' | 'light' | 'dark';
export type MoveAction = 'archive' | 'trash' | 'restore' | 'move';
export type Density = 'comfortable' | 'compact';

export interface ThemeRequest {
  preference: Preference;
  accent: string;
  scheme: 'light' | 'dark';
  /** Window background as #rrggbb; Java uses it for the title bar and the web view background. */
  background: string;
  /** Window text colour as #rrggbb; Java uses it for the title bar text. */
  text: string;
  /** The accent and the colour on it as #rrggbb; Java draws the window and taskbar icon with them. */
  accentColor: string;
  onAccent: string;
}

export const api = {
  info: () => call<AppInfo>('app.info'),
  /** Opens a link in the system browser (or mail program). Only http, https and mailto; rejects with a sentence otherwise. */
  openExternal: (url: string) => call<{ ok: boolean }>('app.openExternal', { url }),

  accounts: () => call<Account[]>('mail.accounts'),
  folders: (account: string) => call<Folder[]>('mail.folders', { account }),
  list: (account: string, folder: string) => call<Summary[]>('mail.list', { account, folder }),
  get: (id: string) => call<Detail>('mail.get', { id }),
  /** Resolves when the server has kept the mark; rejects with a sentence if it cannot (some servers refuse custom flags). */
  setImportant: (id: string, important: boolean) => call<{ ok: boolean }>('mail.setImportant', { id, important }),
  setStarred: (id: string, starred: boolean) => call<{ ok: boolean }>('mail.setStarred', { id, starred }),
  /** Moves the message to the account's Archive folder. Resolves when the server has done it; rejects with a sentence if it cannot. */
  /** Moves a message to another folder of its account: a standard folder's id ("inbox", "archive", ...) or one of the account's own ("f12"). */
  moveTo: (id: string, folder: string) => call<{ ok: boolean }>('mail.moveTo', { id, folder }),
  /** Creates a folder (inside `parent`, or at the top level) and returns it. Rejects with a sentence if the name is taken or not usable. */
  createFolder: (account: string, name: string, parent: string | null) => call<Folder>('mail.createFolder', { account, name, parent }),
  archive: (id: string) => call<{ ok: boolean }>('mail.archive', { id }),
  /** Moves the message back to the inbox. Same contract as `archive`. */
  restore: (id: string) => call<{ ok: boolean }>('mail.restore', { id }),
  /**
   * Moves several messages at once (one connection to the server for all of them): `action` is archive, trash, restore or move
   * (to `folder`). Same contract as `archive`; if only some could not be moved, the others still are and the error says how many.
   */
  moveMany: (ids: string[], action: MoveAction, folder?: string) => call<{ ok: boolean }>('mail.moveMany', { ids, action, folder }),
  /** Moves the message to the account's Trash folder (made if the server has none). Same contract as `archive`. */
  trash: (id: string) => call<{ ok: boolean }>('mail.trash', { id }),
  markRead: (id: string, read: boolean) => call<{ ok: boolean }>('mail.markRead', { id, read }),
  send: (draft: Draft) => call<SendResult>('mail.send', { ...draft }),
  /** Opens the system's save dialog and downloads the attachment (its position in `Detail.attachments`) to the chosen file. */
  saveAttachment: (id: string, index: number) => call<{ saved: boolean; name: string }>('mail.saveAttachment', { id, index }),
  /** Opens the system's file dialog; resolves to the files chosen (none if cancelled). */
  pickAttachments: () => call<PickedFile[]>('compose.attach'),
  reply: (id: string, mode: ReplyMode) => call<Prefill>('mail.reply', { id, mode }),
  /** Same as `reply`, but with Reply-To and Cc read from the server (slow: opens a connection). */
  replyRecipients: (id: string, mode: ReplyMode) => call<Prefill>('mail.replyRecipients', { id, mode }),

  /** Tells Java the page has its content on screen, so the (initially hidden) window can be shown. */
  windowReady: () => call<{ ok: boolean }>('window.ready'),
  /** Leaves a message being written with Java so another window can pick it up; returns the token that window is opened with. */
  stashDraft: (draft: DraftState) => call<{ token: string }>('compose.stash', { draft }),
  /** The message left by `stashDraft`, for the window that was opened on it. It stays with Java until sent, discarded or returned. */
  peekDraft: (token: string) => call<DraftState>('compose.peek', { token }),
  /** Keeps Java's copy of the message current while it is written in a window of its own. */
  updateDraft: (token: string, draft: DraftState) => call<{ ok: boolean }>('compose.update', { token, draft }),
  /** The message was sent or thrown away: nothing is to be returned when its window closes. */
  discardDraft: (token: string) => call<{ ok: boolean }>('compose.discard', { token }),
  /** Takes a message back from Java (once): what a window that was closed gives back, or what a failed pop-out left. */
  takeDraft: (token: string) => call<DraftState>('compose.take', { token }),
  /** Closes this window (a message opened in its own window). */
  closeWindow: () => call<{ ok: boolean }>('window.close'),

  detect: (email: string) => call<Detected>('accounts.detect', { email }),
  testAccount: (form: AccountForm, password: string) => call<TestResult>('accounts.test', { ...form, password }),
  addAccount: (form: AccountForm, password: string) => call<Account>('accounts.add', { ...form, password }),
  removeAccount: (id: string) => call<{ ok: boolean }>('accounts.remove', { id }),

  syncNow: () => call<{ ok: boolean }>('sync.now'),
  syncStatus: () => call<SyncStatus[]>('sync.status'),

  vaultStatus: () => call<VaultStatus>('vault.status'),
  createVault: (password: string) => call<VaultStatus>('vault.create', { password }),
  unlockVault: (password: string) => call<VaultStatus>('vault.unlock', { password }),

  /** Restyles the native window (title bar, background) and remembers the theme choice. */
  setTheme: (theme: ThemeRequest) => call<{ ok: boolean }>('window.setTheme', { ...theme }),
  /** The plain text on the system clipboard (the web view does not let the page read it itself). */
  readClipboardText: () => call<{ text: string }>('clipboard.readText').then((r) => r.text),
  setListWidth: (width: number) => call<{ ok: boolean }>('settings.setListWidth', { width }),
  setDensity: (density: Density) => call<{ ok: boolean }>('settings.setDensity', { density }),
  /** Close button hides the window to a tray icon (true) or quits (false). Rejects when the desktop has no tray. */
  /** Minutes between background syncs (0: only when asked). */
  setRefreshMinutes: (minutes: number) => call<{ ok: boolean }>('settings.setRefreshMinutes', { minutes }),
  setNotifyNewMail: (enabled: boolean) => call<{ ok: boolean }>('settings.setNotifyNewMail', { enabled }),
  setCloseToTray: (enabled: boolean) => call<{ ok: boolean }>('settings.setCloseToTray', { enabled }),
};
