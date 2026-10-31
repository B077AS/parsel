// Dates in the message list. Java sends UTC epoch milliseconds; the page formats them in the user's locale and time zone.

const DAY = 24 * 60 * 60 * 1000;

function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export type DayGroup = 'Today' | 'Yesterday' | 'Earlier this week' | 'Older';

export function dayGroup(ms: number, now = Date.now()): DayGroup {
  const days = Math.round((startOfDay(now) - startOfDay(ms)) / DAY);
  if (days <= 0) return 'Today';
  if (days === 1) return 'Yesterday';
  if (days < 7) return 'Earlier this week';
  return 'Older';
}

/** The short form for the list: a time today, a weekday this week, a date after that. */
export function listTime(ms: number, now = Date.now()): string {
  const group = dayGroup(ms, now);
  if (group === 'Today') return new Date(ms).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  if (group === 'Yesterday' || group === 'Earlier this week') {
    return new Date(ms).toLocaleDateString(undefined, { weekday: 'short' });
  }
  return new Date(ms).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

/** The long form for the reader header. */
export function fullTime(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function fileSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
