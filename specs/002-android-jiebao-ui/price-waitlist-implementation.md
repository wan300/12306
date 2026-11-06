# Ticket prices and waitlist pages

## Latest acceptance — 2026-11-05 19:10 (Asia/Shanghai)

- Quiet-carriage prompts are based on the official query flag, with cancel/continue
  behavior and optional remember-choice. Official and project dialogs were compared
  on Huawei; the final dialog width and typography now track the native layout.
- Added functional new-train seat/date/hour settings. A real order was submitted
  once at about 19:00: 2026-11-06 G531, Beijing South to Shanghai Hongqiao, second
  class, one adult, deadline six hours before departure, standing declined, new
  trains accepted for Nov 6 from 06:00 to 07:00 with second class. Pending amount
  CNY 498, masked order EQ****6000, pay deadline Nov 5 19:20. No payment/cancellation.
- At 19:05, official Android Orders > Pending payment independently showed the same
  train/date/passenger/amount/deadline and expanded new-train conditions, including
  second class and 06:00–07:00. The earlier 17:45 order was already expired; official
  showed no pending orders at 18:22 before this new test.
- Final app was rebuilt and installed with data preserved. At 19:09–19:10 it queried
  the same order without resubmitting, showing actual fares, date/weekday, passenger
  fare badge, countdown and foldable new-train conditions. The response omits the
  submission-only seat field; its fallback now follows official `hb_add_time_temp`
  / `getSeatList(obj.needs)`. Final live read confirmed second class and 06:00–07:00.
- Reused official clock and route PNGs, plus existing passenger/delete/navigation
  assets. Android asset manifest has 17 source-tracked entries. New-train switches
  are compact; its single-date sheet shows the full slider and confirmation button.
  A final presentation-only change uses circular slider handles and a thin track.
- 39 Python and 19 JVM tests pass. Local final APK SHA-256:
  `656d36f4284c92d9ab873cd529b37a083c7113647ea25fd31c5b07a84524568b`.
- Login, in-app official password recovery, and wan300 attribution changes are
  included; see `login-sms-polish.md`. Personal order screenshots were deleted after
  comparison. No credentials, codes, raw passenger IDs or session tokens are in reports.
- Evidence report: C:/Users/15917/.codex/visualizations/2026/11/01/01a1964c-774e-7720-bc0a-fe48578dc56d/12306-waitlist-polish/verification.html
- Not tested: payment/fulfillment, multiple passengers/dates or all seat types.

## Latest acceptance — 2026-11-05 17:57 (Asia/Shanghai)

The historical blocking notes below are retained as a work log. The non-JSON
failure is now resolved and one real waitlist submission reached pending payment.

- Real test: 2026-11-06 G531, Beijing South to Shanghai Hongqiao, second class,
  one adult passenger. Deadline: six hours before departure; standing declined.
  Exactly one confirmation was submitted. Actual pending-payment amount: CNY 498;
  masked order EQ****0830; payment deadline shown as 2026-11-05 18:05.
- At 17:54, official Android 5.9.6.8, Orders > Pending payment, independently
  showed the matching G531/date/seat/passenger count/amount and six-hour deadline.
  Its Unfinished screen still offered payment. No payment or cancellation was
  attempted. Private official-order screenshots were removed after inspection.
- Final APK was installed on Huawei ADA_AL00U with `install --no-streaming -r`.
  Login was preserved. At 17:57, restarting the final app and querying order
  status returned the same actual pending order and amount, without resubmission.
  Local and installed APK SHA-256 both equal
  `4e38500f0ad3ab9234f2a86ce1bd4fd63ef70123125b3fc0236bd0eded12bf91`.
- Root causes fixed: query queue uses `afterNate/queryQueue`; the old
  `afterNateOrder/queryQueue` redirects to a generic HTML error. Preserve the
  opaque query secret exactly when form-encoding `secretList`. Preparation no
  longer polls the submission-scoped queue first. Explicit face-check rejection
  still blocks; an absent face flag follows the official login-flag logic.
  Passport redirects are classified as login required. Pending-order details
  come from read-only `afterNatePay/payOrderInit`, never from a payment operation.
- Confirmation UI now has the blue gradient, rounded cards, capacity notice,
  functional additional-train entry, official passenger-add asset, standing
  option and snapping deadline wheel. The actual six-hour wheel selection and
  passenger selection were used in the successful live flow. Final build also
  reduces train-card spacing and fully expands the passenger sheet so its
  confirmation button is visible. These last two presentation adjustments were
  built/tested and installed, but were not reopened on a new draft after the
  pending order was created; no extra order was created for a screenshot.
- Validation: 28 Python tests, 17 JVM tests; assembleDebug and
  testDebugUnitTest successful; git diff --check clean. All 15 asset-manifest
  entries match their recorded hashes; source version 5.9.4.5, comparison version
  5.9.6.8. No new assets were extracted in this follow-up.
- Remaining differences: quiet-car labels/reminder and accepting newly added
  trains are not implemented. The project pending page displays verified status,
  amount and payment deadline but is visually simpler than the official detail
  page. Official success-rate estimates are not fabricated. This was one real
  adult/second-class order, not acceptance of every seat type or verification flow.
- Current evidence report:
  C:/Users/15917/.codex/visualizations/2026/11/01/01a1964c-774e-7720-bc0a-fe48578dc56d/12306-recheck-20261105/waitlist-fixed-verification.html

Requested continuation: real seat prices, waitlist-related Android pages,
selective official asset reuse, build/install and page comparison on the Huawei
phone. Existing query/task and login behavior must remain usable.

## Constitution Check before implementation

- Add isolated tests for upstream price decoding, currency units, malformed and
  absent fields, waitlist eligibility/capacity, selection identity and limits.
  Network behavior is mocked in tests. Verify live read-only quotes on the phone.
- Keep public failures readable and structured; never persist or print session
  cookies, passenger identities or opaque query tokens in evidence artifacts.
- Extend the shared query service and Android bridge/models rather than calling
  upstream services from Compose. Separate waitlist presentation/rules from the
  main activity. Preserve existing scheduling and order contracts.
- Inspect the installed official app and current official website scripts/rules
  before implementing behavior. Missing inventory alone never establishes
  waitlist eligibility. No fabricated prices, success rates or submitted orders.
- User subsequently authorized real waitlist submission testing, explicitly
  excluding payment. Implement real submission/status APIs, retain authentication
  and identity gates, and never call payment APIs. The user later explicitly
  delegated route/date/seat/passenger selection and login, then requested phone
  or WeChat login rather than QR login. The phone/password path was used; the
  user entered credentials and verification directly and confirmed login.

## Primary evidence

- Official app: com.MobileTicket 5.9.6.8 on Huawei ADA_AL00U (API 31).
- Current official query script:
  https://kyfw.12306.cn/otn/resources/merged/queryLeftTicket_end_js.js?scriptVersion=1.95055
  It maps response fields 16/17 to station sequence numbers, 35 to seat types,
  37/38 to waitlist train flag/capacity restrictions, and 39 to current seat
  price/inventory segments. The price endpoint uses actual train number, station
  sequence numbers, seat types and travel date.
- Rules: https://mobile.12306.cn/otsmobile/h5/otsbussiness/houbuRules/houbuRules.html
  and https://kyfw.12306.cn/otn/gonggao/alternate.html
- Live anonymous price checks on 2026-11-02: Beijing South to Shanghai Hongqiao,
  G531 second/first/business 498/836/1744 yuan; G547 553/929/1995 yuan;
  G1 598/1005/1995 yuan. Both query payload and explicit price endpoint agree.
  Values are verification evidence, never hardcoded application data.

## Implementation and acceptance

- Display actual quotes in result cards, a seat-price toggle and expanded seat
  details. Missing quotes get an explicit unavailable/loading/retry state.
- Use server waitlist flags, capacity restrictions and supported seat types;
  preserve the independent task-selection action.
- Implement waitlist selection, a clearable/removable list, and review pages
  aligned with the observed official flow. Persist only minimal draft data.
- Rebuild, install without clearing app data, test on the physical phone and
  produce new side-by-side evidence for each added page.
- Complete the post-implementation Constitution Check with concrete evidence and
  any actual upstream or authentication limitations.

## Implemented behavior

- Decode actual price/inventory segments and query seat prices for the exact
  train, route segment and travel date. Store money in integer fen. Results can
  switch between inventory and prices, expand seat details and refresh quotes.
  Preferred-first, special-class and sleeper aliases retain their actual codes.
- Persist only the local train/seat draft. Eligibility uses current server flags,
  restrictions, inventory and departure time; preparation rechecks the selection.
- Add the waitlist list, confirmation, passenger/deadline selectors and current
  order status screens. Reuse two additional official H5 image assets; all 15
  reused assets have a recorded source and verified SHA-256.
- Implement authenticated preparation, confirmHB submission and queryQueue
  status retrieval. Existing pending orders, identity checks, human verification,
  mismatched drafts and ambiguous responses block submission. A timeout never
  silently retries a submission. The implementation contains no payment call.
- Mobile login now goes directly to phone/username/password and official SMS or
  human verification. No QR login entry remains in the mobile UI. An existing
  bridge QR session-lifetime defect was separately fixed and regression-tested;
  that compatibility code is not the mobile login flow.

## Constitution Check after implementation — 2026-11-02

- Risk-driven coverage: 25 Python tests and 17 JVM tests passed. External request
  behavior is mocked. Cases cover price units, missing/malformed values, segment
  binding, limits, actual seat codes, private passenger handles, identity gates,
  changed drafts, submission wire format, duplicate protection, timeout and
  pending/empty/unknown order states. Mock success is not live-order acceptance.
- Error/privacy boundaries: unavailable or non-JSON upstream responses produce
  a readable stable failure. Passwords, verification codes, cookies and passenger
  identity data are absent from the evidence report and saved screenshots.
  Passenger details stay in the authenticated in-memory preparation session;
  UI handles and masked display data are used across the bridge.
- Maintainability: query/pricing and waitlist protocol behavior remain shared
  Python services. The Android bridge owns session/timeout integration; Kotlin
  rules, state and Compose screens are separate. No unrelated task scheduling
  refactor or payment implementation was added.
- Build: assembleDebug and testDebugUnitTest succeeded. The final APK was
  installed with adb install -r on Huawei ADA_AL00U (API 31, 1224 x 2776), preserving
  the user's login. APK SHA-256:
  570959051D0D70A2AA7C2B696660C4473D18632679617C5936CB81321E4A1C3D.
- Device evidence: official 5.9.6.8 and the project were compared for inline seat
  prices and the waitlist list. Expanded-price refresh, selected-seat state,
  confirmation unavailable state and order-status unavailable state were also
  exercised on the Huawei. A 1.3 font-scale list check kept primary actions
  visible; font scale was restored to 1.0. The final build has not had a new
  rotation acceptance run. The device disconnected again after the 02:47 capture.

## Acceptance still blocked / known differences

- At 02:35 on 2026-11-02, the official app's waitlist Next action explicitly
  reported system maintenance from 01:00 to 05:00. The project's authenticated
  preparation/status requests also received an unavailable response. No live
  confirmHB was reached; no order was created and no payment was attempted.
  Real passenger/deadline data, real submission and the resulting pending-payment
  page still require physical-device acceptance after the service resumes.
- On G531, public queries reported business 5 and standing 1, while the native
  app displayed business 4 and no standing inventory. Seat fares agreed. The app
  preserves actual returned inventory rather than overriding it to match a
  screenshot. The inventory/source discrepancy remains unresolved.
- Current styles align the core flow, but native quiet-car/vehicle tags and the
  quiet-car reminder are not implemented. Actual official confirmation and
  pending-payment screens could not be entered during maintenance, so their
  visual acceptance remains pending. The official rules link remains available.
- The report explicitly separates observed device behavior, mocked protocol
  coverage and pending live acceptance. Evidence is stored at:
  C:/Users/15917/.codex/visualizations/2026/11/01/01a1964c-774e-7720-bc0a-fe48578dc56d/12306-price-waitlist/12306-price-waitlist-verification.html

## Remaining acceptance steps

1. After service recovery, reconnect the Huawei and reuse the existing phone
   login; requery current choices rather than relying on an expired local draft.
2. Compare official passenger selection, deadline and confirmation screens with
   the project, then complete any remaining layout or response-shape adjustments.
3. Submit one explicitly reviewed eligible selection under the user's existing
   authorization, verify the actual account order and pending-payment state, and
   stop without entering payment. If verification is required, use the official
   interactive flow rather than bypassing it.

## Physical-device recheck — 2026-11-05

## Follow-up after user login

- User completed phone login. Queried November 5 and November 6 successfully.
  November 6 G531, G547 and G1 starting fares were 498, 553 and 598 yuan;
  official results showed the same starting fares. Selected G531 second class.
- Official app entered the real confirmation page and deadline picker normally.
  The current failure is not explained by the historical maintenance window.
- Project preparation failed before passenger retrieval. A rebuilt diagnostic
  version, installed successfully with --no-streaming -r, reproduced:
  `afterNateOrder/queryQueue`, HTTP 200, non-JSON response. The precise reason for
  the unexpected response remains unresolved. No live confirmHB was reached.
- Added only endpoint/status/format diagnostics, without raw response bodies,
  cookies, passenger data or credentials. All 25 Python tests passed; debug build
  succeeded. No UI behavior was changed in this recheck.
- Official confirmation differs in its blue gradient, capacity banner, additional
  train area, compact passenger card and wheel-style deadline picker. These are
  newly observed gaps, not completed changes. Acceptance remains incomplete.
- No order submitted and no payment attempted. Phone remains in project results.

Installed diagnostic APK SHA-256: 7d7ce734e5176a8c3a814cc14c8ef217a84007148a1522262f293c0309129303
