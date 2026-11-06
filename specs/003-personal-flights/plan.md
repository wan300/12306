# Personal desktop flight prototype

## Scope

Use the owner's ordinary Ctrip and Qunar accounts in dedicated persistent Electron browser sessions. Add flight search, a booking-form preview, and low-frequency monitors with explicit flight, passenger names and a total CNY budget. Never export passenger identity numbers or cookies to the renderer or Python backend. Payment remains in the provider window.

## Architecture

- Vue flight workspace is available independently of railway authentication.
- Electron exposes a narrow IPC client for flight APIs. A random per-launch token protects the local FastAPI routes and a loopback-only browser bridge.
- FastAPI owns SQLite monitor records, price decisions, serialized attempts, restart recovery and structured errors.
- Provider adapters navigate the ordinary consumer pages. Read displayed prices and verify the complete booking form before any submission. Login or verification challenges pause work for the owner.
- Ambiguous submission, process restart during submission and missing order confirmation enter `unknown`; they never retry automatically.
- First support adult domestic one-way travel using saved passengers. Reject unsupported or ambiguous product/route/passenger/price conditions explicitly.

## Constitution Check

- Risk-driven tests: mock provider IO; test total-budget checks, invalid inputs, concurrency, pause, restart and unknown-order handling. Test page extraction against representative DOM and a read-only live account page. Build the Vue client and run Electron integration smoke checks.
- Errors: return stable codes and readable messages. No cookies, account tokens, certificate numbers, raw provider responses or exception strings in API responses/logs.
- Maintainability: keep browser integration, provider page logic, backend schemas/models/services/routes and Vue API/view separate. Existing train behavior remains isolated.
- Governance: no real order or payment in automated acceptance testing without a concrete trip and budget from the owner. This is a validation boundary, not a restriction on the user-configured automatic-order feature.

## Acceptance

1. Desktop-only flight workspace opens without a 12306 login.
2. Own-account login persists per provider; search returns real flight rows with explicitly untaxed indicative prices.
3. Booking preview verifies flight/date, passenger selection, paid extras and the displayed total.
4. Monitor interval is at least 60 seconds; no overlapping provider operations or duplicate submissions.
5. Automatic orders require configured flight and saved passengers, current verified total within budget, and a single durable submission attempt.
6. Unknown orders require manual reconciliation; no automatic retry after restart.

## Validation results — 2026-11-01

- Python unittest: 19 passed, including 4 existing railway login tests.
- Node tests: 21 passed. Covers representative live DOM, explicit grand-total nodes, eligibility tags outside price blocks, JSON/JSONP order responses, provider success codes, stale quote rejection, serialized commands, and loopback authentication.
- Vite production build and Electron JavaScript syntax checks passed.
- Electron startup, the independent `/flights` workspace, IPC-backed monitor persistence, and validation-error handling were exercised through the desktop UI. Synthetic paused monitor was removed after validation.
- Existing authenticated in-app-browser sessions were used for read-only Ctrip and Qunar query/offer/checkout checks. No credential export and no actual order submission or payment.
- Fresh Electron sessions encountered a Ctrip verification block and an empty Qunar result. Ctrip block detection and the visible recovery message were verified. Full live operation in the application's own persistent sessions still requires the owner's normal login; successful real order creation remains unverified.

## Continued account integration

- Reused the existing authenticated Ctrip browser context for search, expansion of all 22 products, selection of an ordinary direct-book economy offer, and the passenger checkout page. Invoked the repository's read-only page parser in that context. Ordinary total verified at CNY 590 (450 fare + 50 airport fee + 90 fuel); no order submitted.
- Fixed age/student eligibility exclusions, unsupported extra product-selection flows, and stale quote notices. The browser tool redacts contact input values, so parser-level contact verification remains false in this tool; the displayed contact was inspected visually. This is recorded separately from automatic verification.
- Both the ordinary Ctrip homepage and deep search link are blocked in the separate Electron session. The exact trigger has not been established. Existing browser APIs have no cookie-export capability; do not claim that credentials have been migrated or that desktop blocking is resolved.
- Added an owner-selected local Cookie JSON handoff into an isolated, nonpersistent Electron session. Renderer/backend receive only import counts. Validate domain, expiry, prefixes and size; preserve HttpOnly and SameSite. Reject session replacement once a submission has been attempted. Imported credentials never enter source/test fixtures/logs/database. Tests use synthetic cookies and mock all platform IO.
- `FLIGHT_READ_ONLY=1` blocks submission in both backend decision-making and the browser controller. Its desktop UI disables automatic-order selection. This is the active mode for continued live tests.
- Updated validation: backend 20 tests and browser/page/cookie tests 29 tests passed. Live cookie migration still awaits an owner-provided local export file.
