const dateFmt = new Intl.DateTimeFormat(undefined, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
});
const dateTimeFmt = new Intl.DateTimeFormat(undefined, {
  year: 'numeric',
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});
const relFmt = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

/** A calendar date; pass `timeZone` when the date belongs to a server period (e.g. report weeks). */
export const date = (iso, timeZone) => {
  if (!iso) return 'n/a';
  const fmt = timeZone
    ? new Intl.DateTimeFormat(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        timeZone,
      })
    : dateFmt;
  return fmt.format(new Date(iso));
};
export const dateTime = (iso) => (iso ? dateTimeFmt.format(new Date(iso)) : 'n/a');

/** "3 hours ago"; the exact time belongs in a title attribute next to it. */
export function relative(iso, now = Date.now()) {
  if (!iso) return 'never';
  const seconds = Math.round((new Date(iso).getTime() - now) / 1000);
  const steps = [
    ['year', 31536000],
    ['month', 2592000],
    ['week', 604800],
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
  ];
  for (const [unit, size] of steps) {
    if (Math.abs(seconds) >= size) return relFmt.format(Math.round(seconds / size), unit);
  }
  return 'just now';
}

export const percent = (x) => (x == null ? 'n/a' : `${Math.round(x * 100)}%`);
export const days = (x) => (x == null ? 'n/a' : `${x < 10 ? x.toFixed(1) : Math.round(x)} days`);
export const signed = (n) => (n > 0 ? `+${n}` : `${n}`);

export const LEVEL_TEXT = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };

export const SYNC_TEXT = {
  PENDING: 'Queued',
  RUNNING: 'Syncing',
  DONE: 'Synced',
  FAILED: 'Sync failed',
  RATE_LIMITED: 'Paused (rate limit)',
};
