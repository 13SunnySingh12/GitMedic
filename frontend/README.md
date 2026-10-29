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

| Path                  | Contents                                                                                                                                    |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/api/`            | `client.js` (fetch wrapper: CSRF header on writes, problem-details errors, 401 handling) and `hooks.js` (TanStack Query hooks per endpoint) |
| `src/components/`     | App shell and sub-navigation, status primitives (risk badge, sync status, per-widget query states), dialog, pagination, heatmap             |
| `src/pages/`          | One file per route in spec §3.1 (the dashboard is lazy-loaded with the charting library)                                                    |
| `src/utils/format.js` | Date, relative time, percentage and day formatting                                                                                          |
| `src/test/`           | MSW server, render helpers, page tests                                                                                                      |

## Visual direction (spec §4.1, TBD-12)

Layout and component shapes follow the Figma "Analytics Dashboard (Community)" reference (file
`8Fi8ZEy7pLEmuX0WWw24rz`, frames Main, Group Filter and Timeframe filter), translated to
GitMedic's own pages and data. Nothing from the template's sample content is shown.

- **Colour.** Three brand tones, defined once in `src/index.css`: mint `--color-mint` (#BEFFDB:
  primary buttons, Low risk, positive progress), periwinkle `--color-periwinkle` (#EEF0FF: page
  background, selected menu rows, chart tracks) and lavender `--color-lavender` (#E1E0FF: active
  navigation, card borders, predicted-label badges, Medium risk). Everything else is neutral: ink
  #151E23 for text, bars and High risk, and white cards. Dark mode (`prefers-color-scheme`) keeps
  the same three tones as accents on dark surfaces.
- **Type.** Inter (self-hosted from `@fontsource-variable/inter`, nothing fetched from third
  parties) on the reference scale: 24px bold titles, 16px semibold section labels, 14px body,
  12px captions, tabular figures for numbers.
- **Shape.** 230px sidebar card; 20px radius on cards and filter pills, 10px on buttons and nav
  items; 1px lavender borders and the reference's soft tile shadow (0 5px 20px, 5% black).
- **Status is never colour alone** (UX-01). Risk badges say "High risk · 72"; predictions are
  labelled "(predicted, 83%)"; sync status is text; error notices start with "!".
- **Motion** (`motion` for React, under one `MotionConfig reducedMotion="user"`): the active
  navigation pill slides between items, pages fade up 8px on arrival, filter menus and dialogs
  scale in, and chart bars and meters grow once on load. With `prefers-reduced-motion`,
  transforms are dropped and only opacity changes remain.
- **States.** Every data widget has its own loading skeleton, empty message and error with retry,
  so a failing heatmap never hides the risk score.
- **Small screens.** Below 960px the sidebar becomes a top card whose links wrap; below 640px
  filters stack, summary cards go two per row and tables become stacked label/value rows. No
  page-level horizontal scroll at 390px.

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
