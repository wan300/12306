# Android login simplification — 2026-11-05

## Official behavior checked

- Current official login page: https://kyfw.12306.cn/otn/resources/login.html
- Current login script: https://kyfw.12306.cn/otn/resources/js/login_new_v20260207.js
- Official password recovery link: https://kyfw.12306.cn/otn/view/find_my_password.html

The website offers account/password login and QR login. Its SMS branch still submits
username and encrypted password alongside `checkMode=0` and `randCode`. SMS is an
identity-verification step, not a verified passwordless login method. The account
password is therefore retained; no unsupported phone/code-only endpoint is invented.
Recovery offers phone, email and face routes. Following the user's in-app requirement,
the app embeds the official recovery page in a dedicated WebView and selects the phone
tab. It does not attach credentials or verification codes to navigation.

## Changes

- White, uncluttered two-step form with filled inputs, one primary action, and
  remember-account/password controls behind Login settings.
- SMS selected before slider whenever the official response allows SMS. Slider-only
  requirements are respected. Failure to load an optional slider no longer blocks SMS.
- SMS fields replace the account form during verification; resend countdown,
  numeric code input, disabled incomplete actions, and a return-to-account action.
- A failed verification returns to account entry because the bridge has invalidated
  its challenge. Password, verification token and SMS code remain transient Compose
  state, rather than saved-instance state. Existing opt-in Keystore storage remains.
- Forgot password button below Login opens the verified official recovery URL inside
  the app, with a visible official domain, return and reload actions. The original
  form is adapted for a narrow screen; its official validation and submission remain
  unchanged. No native JavaScript bridge or field-reading script is attached.
- Recovery navigation accepts only HTTPS 12306 hosts. File/content access and mixed
  content are disabled, TLS failures cancel loading, and errors offer retry.
- Re-login is available for an existing account without first clearing its valid session.

## Validation

- 39 Python tests and 19 Android JVM tests pass; debug APK assembles offline.
- Huawei ADA_AL00U: overwritten installation succeeded and original session remained.
  Real preflight using the saved credentials selected SMS verification by default;
  visible fields are document suffix and SMS code, with disabled empty actions.
  Subsequently, the user authorized receiving/filling the login code. A fresh 12306
  login SMS was entered directly from the phone notification without printing its
  value; login succeeded and enabled the real waitlist test. No password reset was submitted.
- The subsequent in-app version rendered the real official phone recovery form on
  the Huawei. Focusing the phone field opened the keyboard. Returning to login retained
  the account/password fields. No recovery code was sent and no password was changed.
- Final APK SHA-256: 656d36f4284c92d9ab873cd529b37a083c7113647ea25fd31c5b07a84524568b.
- Local company attribution now comes from `brand_name=wan300`; startup copyright
  uses 2026. Huawei startup capture confirms the replacement. The embedded official
  authentication page keeps its 12306 source/domain identity.
- Waitlist acceptance is recorded separately in `price-waitlist-implementation.md`.
