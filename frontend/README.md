# GitMedic frontend

React 19 + Vite, JavaScript. Talks only to the Spring backend through the Vite proxy
(`/api`, `/oauth2`, `/login/oauth2` → `http://localhost:8080`). Setup and the other services are
described in the [root README](../README.md).

```bash
npm ci
npm run dev          # http://localhost:5173
npm run lint
npm run format:check
npm test -- --run    # Vitest + Testing Library + MSW, with axe checks
npm run build
```

## Structure

| Path | Contents |
|---|---|
| `src/api/` | `client.js` (fetch wrapper: CSRF header on writes, problem-details errors, 401 handling) and `hooks.js` (TanStack Query hooks per endpoint) |
| `src/components/` | App shell and sub-navigation, status primitives (risk badge, sync status, per-widget query states), dialog, pagination, heatmap |
| `src/pages/` | One file per route in spec §3.1 (the dashboard is lazy-loaded with the charting library) |
| `src/utils/format.js` | Date, relative time, percentage and day formatting |
| `src/test/` | MSW server, render helpers, page tests |

## Visual direction (spec §4.1, TBD-12)

GitMedic is a diagnostic tool, so the interface is calm, clinical and data-dense: it should read
like an instrument panel, not a marketing page.

- **Colour.** Cool neutral greys for surfaces and text, one teal accent (`--accent`) for links,
  primary actions and chart marks. Green, amber and red are reserved for the Low/Medium/High risk
  levels and error/warning notices, so status colour always means status. Light and dark themes
  follow the system setting (`prefers-color-scheme`); both meet WCAG AA contrast (checked with axe).
- **Type.** The platform's system UI font at a 15px base (set in `rem`), tabular figures for
  every number so columns line up. No web fonts: nothing is fetched from third parties.
- **Shape.** 6px radius on controls and panels; status badges are full pills. Panels use 1px
  borders, no shadows.
- **Status is never colour alone** (UX-01). Risk badges say "High risk · 72"; predictions are
  labelled "(predicted, 83%)"; sync status is text.
- **Motion.** Only feedback: a 160ms press scale on buttons and a 200ms fade/scale when a dialog
  opens. Nothing animates on frequent actions. With `prefers-reduced-motion`, transforms are
  removed and the skeleton pulse stops.
- **States.** Every data widget has its own loading skeleton (sized like its content), empty
  message and error with retry, so a failing heatmap never hides the risk score.
- **Small screens.** At 640px and below, tables become stacked label/value rows; the heatmap
  scrolls inside its own focusable region. No page-level horizontal scroll at 360px.

## Accessibility notes

- Skip link, landmarks, labelled filters and dialogs (native `<dialog>`: focus trap and Escape
  come from the browser).
- Charts are hidden from assistive technology and duplicated as visually hidden tables;
  heatmap cells carry "date: n commits" labels.
- Live regions announce sync, training and report-generation status.
- External GitHub links open in a new tab with `rel="noopener noreferrer"` and say so to screen
  readers.
- No `dangerouslySetInnerHTML` (enforced by ESLint); Markdown is rendered with `react-markdown`,
  which never renders raw HTML.
