import { motion } from 'motion/react';

/** The axis top: 4 × a round step (1, 2, 2.5 or 5 × 10ⁿ, at least 1), so all 5 ticks are round. */
export function niceMax(max) {
  const raw = Math.max(max, 1) / 4;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 2.5, 5, 10].find((m) => m * pow >= raw) * pow;
  return 4 * Math.max(1, step);
}

/**
 * Figma "Activity" chart: rounded bars on full-height tracks with a 0-to-max axis.
 * `rows` is [{ label, value, title }]. The visible chart is hidden from assistive technology;
 * the table below it carries the same numbers.
 */
export function ActivityBars({ rows, caption }) {
  const top = niceMax(Math.max(0, ...rows.map((r) => r.value)));
  const ticks = [4, 3, 2, 1, 0].map((i) => Math.round((top * i) / 4));
  return (
    <>
      <div className="bars" aria-hidden="true">
        <div className="bars-axis">
          {ticks.map((t) => (
            <span key={t}>{t}</span>
          ))}
        </div>
        <div className="bars-plot">
          {rows.map((r, i) => (
            <div className="bar-col" key={r.label}>
              <div className="bar-track">
                <span className="bar-tip">{r.title}</span>
                <motion.span
                  className="bar"
                  style={{ height: `${(r.value / top) * 100}%` }}
                  initial={{ scaleY: 0 }}
                  animate={{ scaleY: 1 }}
                  transition={{ duration: 0.45, delay: Math.min(i * 0.02, 0.2) }}
                />
              </div>
              <span className="bar-label">{r.label}</span>
            </div>
          ))}
        </div>
      </div>
      <table className="visually-hidden">
        <caption>{caption}</caption>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <th scope="row">{r.label}</th>
              <td>{r.value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

/**
 * Figma "Topics" progress rows. `rows` is [{ name, value (0–1), text, note, good }];
 * `text` is the visible number, so the bar itself is decorative.
 */
export function Meters({ rows }) {
  return (
    <ul className="meters">
      {rows.map((r) => (
        <li className="meter" key={r.name}>
          <span className="meter-name">{r.name}</span>
          <span className="meter-track" aria-hidden="true">
            <motion.span
              className={`meter-fill${r.good ? ' meter-fill-good' : ''}`}
              style={{
                display: 'block',
                width: `${Math.min(1, Math.max(0, r.value ?? 0)) * 100}%`,
              }}
              initial={{ scaleX: 0 }}
              animate={{ scaleX: 1 }}
              transition={{ duration: 0.5 }}
            />
          </span>
          <span className="meter-value">
            {r.text} {r.note && <span className="muted">{r.note}</span>}
          </span>
        </li>
      ))}
    </ul>
  );
}
