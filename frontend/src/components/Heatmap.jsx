const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

function level(count, max) {
  if (!count) return 0;
  return Math.min(4, Math.ceil((count / max) * 4));
}

/** Monday-aligned list of every day from `from` to `to` (ISO dates, inclusive). */
export function calendarDays(from, to) {
  const out = [];
  const start = new Date(`${from}T00:00:00Z`);
  const end = new Date(`${to}T00:00:00Z`);
  const lead = (start.getUTCDay() + 6) % 7; // days before `from` in its Monday-started week
  for (let i = 0; i < lead; i++) out.push(null);
  for (let d = start; d <= end; d = new Date(d.getTime() + 86400000)) {
    out.push(d.toISOString().slice(0, 10));
  }
  return out;
}

/** FR-009 calendar grid. Each cell has an accessible "<date>: <n> commits" label (§4.5). */
export function Heatmap({ data }) {
  const counts = new Map(data.days.map((d) => [d.date, d.count]));
  const max = Math.max(1, ...data.days.map((d) => d.count));
  const cells = calendarDays(data.from, data.to);
  return (
    <figure style={{ margin: 0 }} className="stack">
      <figcaption className="muted small">
        {data.total} commits from {data.from} to {data.to} ({data.timezone})
      </figcaption>
      <div
        className="heatmap-scroll"
        tabIndex={0}
        role="region"
        aria-label="Commit calendar (scrolls horizontally)"
      >
        <div className="heatmap">
          {cells.map((day, i) =>
            day === null ? (
              <span key={`pad-${i}`} aria-hidden="true" />
            ) : (
              <span
                key={day}
                role="img"
                aria-label={`${day}: ${counts.get(day) ?? 0} commits`}
                title={`${day}: ${counts.get(day) ?? 0} commits`}
                className={`heat heat-${level(counts.get(day) ?? 0, max)}`}
              />
            ),
          )}
        </div>
      </div>
      <div className="legend" aria-hidden="true">
        Less <span className="heat" /> <span className="heat heat-1" />{' '}
        <span className="heat heat-2" />
        <span className="heat heat-3" /> <span className="heat heat-4" /> More
      </div>
    </figure>
  );
}

export { WEEKDAYS };
