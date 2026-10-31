import { mdiAlertOctagonOutline, mdiArchiveOutline, mdiDeleteOutline, mdiFileEditOutline, mdiFolderOutline, mdiInbox, mdiSendOutline, mdiStarOutline } from '@mdi/js';

// The icon of a folder, by its id: the standard folders have their own, the account's own folders (ids like "f12") share one.
const ICONS: Record<string, string> = {
  inbox: mdiInbox,
  starred: mdiStarOutline,
  sent: mdiSendOutline,
  drafts: mdiFileEditOutline,
  archive: mdiArchiveOutline,
  spam: mdiAlertOctagonOutline,
  trash: mdiDeleteOutline,
};

export function folderIcon(id: string): string {
  return ICONS[id] ?? mdiFolderOutline;
}
