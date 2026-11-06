# Angular frontend

Interface of the Data Quality platform: sign-in, CSV upload, real-time pipeline tracking, and results in tabs (quality, rules, corrections, graph, questions).

Stack: Angular 21, standalone `OnPush` components, application **without zone.js** (signals), Vitest for tests, nginx for production.

## Run locally

```powershell
npm install
npm start
```

Application: `http://localhost:4200`. The backend is expected on `http://localhost:8081`; sign in with `admin / admin123` (the backend's development values).

## Commands

```powershell
npm start        # development server
npm run build    # production build (dist/frontend)
npm test         # unit tests (Vitest, no watch mode)
```

## Configuration

URLs are in `src/environments/`:

| File | Used by | Content |
| --- | --- | --- |
| `environment.ts` | `npm start` | `apiBase`, `airflowUiUrl`, `maxUploadBytes` |
| `environment.prod.ts` | `npm run build` and the Docker image | same keys, **fixed at build time**: adapt them to where the services are deployed (by default `http://localhost:8081/api` and `http://localhost:8080`) |

`maxUploadBytes` (500 MB) must stay in line with the backend's `MAX_UPLOAD_SIZE`.

## Docker

`Dockerfile`: production build (`npm ci` then `ng build`) served by nginx (`nginx.conf`: hashed files cached for a year, everything else sent back to `index.html` so deep links belong to the Angular router). `package-lock.json` must stay complete for `npm ci` on Linux: after updating dependencies, regenerate it in a Linux container rather than on Windows alone.

## Navigation

| URL | Screen |
| --- | --- |
| `/` | **Landing page**, public (no sign-in needed): presentation, features, how it works, preview, and a "Sign in" button ("Open the app" when already signed in) |
| `/login` | Sign-in (JWT kept in `sessionStorage`), with a link back to the landing page |
| `/datasets` | Opens the most recent dataset, or the welcome screen with "New analysis" when there is none |
| `/datasets/:id/versions/:versionId/overview` | Run status (steps, duration, errors, Retry), score and grade, dimensions, changes, global insights |
| `.../tables?table=orders` | List of tables + detail (columns and their role, frequent values, missing values, associations, insights) |
| `.../model` | Data model (zoom, pan, SVG export, relationships with coverage and cardinality) |
| `.../insights?table=orders` | Semantic analysis in collapsible groups + global recommendations |
| `.../compare?against=<versionId>` | Differences with an older version (score, rows, columns, missing values) |
| `.../rules` | The dataset's quality rules, result for the version, suggested rules to approve |
| `.../corrections` | Corrections of a table: found or written in plain language, counted preview, application to a copy |
| `.../graph` | Proposed graph model, AI names, build and delete in Neo4j |
| `.../ask` | Plain-language questions about the data, quality and how to use the application |
| `.../report` | Printable report of the version (PDF through print, or Markdown) |

Only `/` and `/login` are public. Every other route is behind `authGuard`, which sends a visitor who is not signed in to `/login?returnUrl=…` and brings them back after sign-in; `guestGuard` sends someone already signed in from `/login` to `/datasets`.

The URL holds the whole selection: a shared link or a refresh reopens the same dataset, version, tab and table.

Shortcuts: `Ctrl/⌘ K` or `/` opens the command palette (datasets, tables, tabs, actions), `N` opens "New analysis", `1` to `9` switch the version's tab. Shortcuts are ignored while typing or when a dialog is open.

## Notable screens

- **New analysis**: drop several CSVs (a file with the same name replaces the previous one, invalid files are reported), dataset name, **domain, goal and what one row represents**, and a free context (global or per table). An upload identical to an existing version offers to open it or rerun it.
- **Overview**: progress `Upload → Airflow → Tables x/y → Global → Done`, with failed tables and their reason, and Retry.
- **Rules**: approval of the rules proposed from the goal, creation of rules (format, order of two dates, thresholds…), "Check now".
- **Corrections**: a list of corrections is built from what the application finds or from a sentence; a counted preview updates on every change; applying creates a **new version**.
- **Graph**: findings (conflicts, isolated tables, uncertain links) are listed before the build; after the build, a query ready to paste in Neo4j Browser, the number of other graphs present, and "Delete this graph" (two clicks).
- **Ask**: example questions to click; every answer says where it comes from (graph, quality results, guide) and, for the graph, shows the query and the rows.
- Live alert (toast) when rules fail at the end of a run, wherever the user is.

## Structure

| Folder | Role |
| --- | --- |
| `src/app/core` | `ApiService` (all HTTP calls), `AuthService` + guard + interceptor (token, 401), `LiveEventsService` (SSE through `fetch`, then polling every 5 s), `WorkspaceStore` (shared state in signals), theme, layout, toasts, data models (`models.ts`) |
| `src/app/features` | one folder per screen: `landing` (public front page, written for non-technical visitors), `login`, `shell` (top bar, side panel, command palette), `datasets`, `version` (host of the tabs), `overview`, `tables`, `data-model`, `insights`, `compare`, `rules`, `corrections`, `graph`, `ask`, `report`, `upload` |
| `src/app/shared` | formatting (`format.ts`), reading of model answers (`insight.ts`), pipes, icons, keyboard shortcuts, common components (badges, empty state, modal, confirmation, tabs, score ring, skeleton, tooltip) |
| `src/styles.css` | design system: variables (colours, spacing, radii, shadows), dark theme, buttons, badges, cards, tables, forms |

Derived values are `computed()`, not method calls in templates. Silent refreshes (live events, polling) keep the data on screen until the new data arrives, so the page does not jump.

## Tests

`npm test` runs 57 tests: sign-in and API calls, reading of SSE events, formatting, quality grades and dimensions, report, rules, data model, table profile, file selection at upload, order of the graph's findings, small helpers of the Ask tab, the route guards and the landing page.

## Accessibility and responsive design

- Visible focus, labels on every field, toasts in an `aria-live` region, keyboard navigation in the tabs (arrows) and the table list.
- Light/dark theme following the system, remembered toggle.
- From 360 px to 1,920 px; below 1,024 px the dataset list becomes a drawer; wide tables scroll inside their card.
