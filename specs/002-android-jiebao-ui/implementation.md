# Core Android ticket UI implementation

Scope: home, ticket results, station selection and date selection. The connected
official 5.9.6.8 app is the visual reference. Locally extracted 5.9.4.5 resources
are reused selectively after comparison; ads and external services are excluded.

The implementation keeps Compose and the existing PythonBridge query/task
contracts. Shared presentation components, local station data and date/filter
rules are separated from MainActivity. Selected assets and source hashes are
recorded in android/origin-assets.json. Query history contains only routes and
station names, never accounts or passenger records.

## Constitution Check before implementation

- Risk-driven tests: test sale-window boundaries, station parsing/search, seat
  availability and result filtering. Build the actual edited worktree, install
  without clearing app data, compare all four pages on the connected phone.
- Error contract: retain bridge result handling, use readable fallback messages
  without exception details, preserve cancellation and restore loading state.
- Maintainability: Android UI changes only; keep login, scheduling, storage
  schema, task lifecycle and payment implementation compatible.
- Governance: no ads, unsupported candidate waitlist submission, fabricated train
  branding or invented ticket prices. Finish with a second constitution check
  and evidence from the freshly built APK.

## Acceptance

- Fullscreen station/calendar pages, system and toolbar back behavior.
- Real route history and clear action; query values survive results/back.
- Six visible dates at normal font scale, valid 15-day window including today.
- Compact train cards with unbroken train codes/times, actual seat types,
  truthful inventory labels, working filters and task-selection actions.
- Blue status bar, coherent white/gray surfaces, original asset states.
- No source-only verification: build, install and inspect the four live pages.

## Verification on 2026-11-02

- Device: physical Huawei ADA_AL00U, Android API 31, 1224 x 2776, density 540.
  All final comparisons use this phone, not the connected x86_64 virtual device.
- Official reference: com.MobileTicket 5.9.6.8. Project package:
  com.railhelper.ticket.debug. Standard screenshots use Beijing to Shanghai,
  2026-11-02, departure-time sorting, font scale 1.0.
- `:app:assembleDebug :app:testDebugUnitTest` passed; 10 tests, zero failures,
  errors or skipped cases. `git diff --check` passed.
- Installed with `adb install -r`, preserving application data. The installed
  base APK was pulled back and matched the build output SHA-256:
  `3CD14805B500A748624B33E9403A17CEE0B10EAE7F5DA6C66BD34C3A8394E49F`.
- Verified all 13 imported asset hashes against `android/origin-assets.json`.
- Four pages were inspected against fresh official screenshots. Adjusted status
  bars, typography, card density, date-strip spacing, calendar row geometry,
  station tiles, filter layout and the complete A-Z index. Results show four
  complete cards on the default phone configuration.
- Real-phone interactions passed: hot/recent station selection; `bj` and
  `beijing` rank Beijing first; `VNP` finds Beijing South; Z index jumps to the Z
  group; route swap; clear history and recreate history after a successful query.
- The sale window accepted November 16 and rejected November 17 (today is
  November 2). Changing the results date to November 3 queries again, clears
  train selection and updates the date shown on the home page.
- Combining normal trains, hard seats and available-only returned T109 with one
  hard seat. Clearing draft filters then cancelling preserved the applied
  filter; clearing and confirming restored 54 trains. Duration sorting placed
  G25 (4h18) before G35 (4h27). Expanding and selecting G25 updated the selection
  count without creating or running a task.
- Rotation preserved the active date, result list and sort. Font scale 1.3 was
  inspected on all four pages; truncated dates and filter labels
  found during this test were fixed and rechecked. The original system font
  scale (1.0) and rotation settings (0/0) were restored.
- No fatal AndroidRuntime entry was found for the final installed app process
  during the checked session. No purchase, payment or task execution was tested.

Evidence directory (outside the source tree):
`C:/Users/15917/.codex/visualizations/2026/11/01/01a1964c-774e-7720-bc0a-fe48578dc56d/12306-verification/`.
The self-contained `12306-ui-verification.html` contains the four official/final
comparisons. `final-*.png` are final screenshots; supporting interaction captures
have descriptive names. The full APK is also available as `installed-final.apk`.

## Constitution Check after implementation

- Testing: changed date, parsing, filtering and safe-error behavior has isolated
  JVM coverage; the compiled APK and actual navigation were checked on hardware.
- Error handling: query failures use safe readable messages; no credentials,
  passenger records or raw bridge errors were added to UI history or reports.
- Maintainability: Compose presentation and pure rules are separated from the
  activity; PythonBridge, authentication, scheduling and task storage contracts
  remain in their existing modules. UI state survives configuration changes.
- Governance: only core ticket UI assets were imported, with source versions and
  hashes recorded. Ads and external service entries are excluded. Unsupported
  price, train-brand, transfer and waitlist features are not presented as working
  controls. Station account favourites/location and calendar sale reminders are
  outside this implementation; local recent stations and the valid purchase
  window are provided. Official inventory can differ between captures because
  queries occurred at different times and use different existing data paths.
