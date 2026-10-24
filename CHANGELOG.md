# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.57.0] — 2026-10-23

### Added

- `onelo.affiliates.capture(url, consent)` captures an Affiliate referral from the link that opened your app
  (#929). It reads `?ref=<code>` or `?onelo_click=<id>`. Without consent nothing
  is stored on the device. With consent the click is kept and tied to the user
  who signs in, so their purchase is attributed whichever checkout opens.

## [1.56.0] — 2026-10-07

### Fixed

- Features are bound to the identity they were resolved for (#737). At launch the stored user is the Features identity from the first frame (their own persisted snapshot, never the anonymous one), and the restored user is resolved instead of "anonymous with the user's token" (which also saved that user's features under the anonymous key). An answer that arrives after a sign-in, sign-out or user switch is dropped. A signed-in user whose token is refused even after a refresh keeps their snapshot instead of receiving the anonymous one, over `/resolve`, `/poll` and the event stream. A user with no saved snapshot starts empty instead of seeing the previous user's features. `/poll` sends a non-zero `since_version` only for a snapshot the server confirmed for the current user.

### Changed

- `features.ready()` / `isReady` mean "the server confirmed the snapshot for the CURRENT identity". They reset on every identity change, and a network failure no longer counts as ready (`ready()` returns at its timeout). Await `ready()` once before showing gated UI, at launch and after each sign-in.

## [1.55.0] — 2026-10-03

### Added

- Marketing Consent for your own screens (#818). `OneloAuth.signupMarketingConsent` (`OneloSignupMarketingConsent`: `asked`, `text`, `language`) tells a custom sign-up screen whether to show the optional checkbox and with what text, from `/api/sdk/config` asked in the device language. `signUp(email, password, marketingConsent = false)` sends the consent (and its language) only when ticked. `OneloWaitlist.join(email, slug, consent)` takes `OneloWaitlistConsent(marketing, custom)`.

## [1.54.0] — 2026-10-03

### Added

- Email Verification (#819). `OneloUser` carries `emailVerified` and `accessReason` (`"email_unverified"` when the app is withheld for it), decoded from the server and kept in the stored session. When the parent App requires Email Verification, `isAllowedIn()` stays false until the user opens the link; the hosted flow shows "check your inbox", resends and hands the session back by itself. Apps with their own screen get `resendVerificationEmail(redirectTo)` (returns `OneloVerificationResend`) and `confirmEmail(token)` (refreshes the signed-in session afterwards); new `OneloError.Code` values `VERIFICATION_RATE_LIMITED`, `EMAIL_VERIFICATION_OFF`, `INVALID_VERIFICATION_TOKEN`, `INVALID_VERIFY_REDIRECT`. `revalidateEntitlement()` refreshes `allowedIn`, `emailVerified` and `accessReason` together.

## [1.53.0] — 2026-10-03

### Added

- A signed-in user's feedback report names a verified reporter (#820). With an Onelo Auth session, `feedback.open()` sends the user's access token (`Authorization: Bearer`) on `/api/sdk/feedback/initiate`; Onelo stores the report with that user and `reporter_verified=true`, so no `userId` is needed. The token reaches the feedback Activity in-process, never as an Intent extra. A refused token is refreshed and retried once; refused again, the form still opens without it. Signed out, the report stays anonymous; an explicit `userId` without a session is still an unverified label. The public `OneloFeedback(config, features, securityHeaders)` constructor is unchanged.

## [1.52.0] — 2026-10-01

### Added

- The hosted flow is bound to a PKCE challenge on `/api/sdk/flow/init` (and on the legacy `/auth/initiate`) (#541). The verifier is kept in EncryptedSharedPreferences for the 15-minute magic-link lifetime. Pairing therefore works: a magic link from the hosted sign-in page, opened on another device, shows a code screen, and the sign-in completes in the app. Every return (in the WebView, or through `Onelo.handleRedirect`, also after the process was killed) exchanges the code with that verifier. Sign-out and a successful exchange clear it.
- A `registerLauncher(activity, onResult, onError)` overload. `onError` receives `CANCELLED` when the person closes the sign-in, or `SERVER` with the backend's reason when the exchange is refused. The two-argument overload still reports both as `onResult(null)`.

### Changed

- A refused code exchange reports the backend's reason ("code_verifier required", "PKCE verification failed") instead of "Hosted callback failed". There is one attempt, no retry.
- Custom-UI `sendMagicLink()` stays unpaired: it sends an ordinary link that must be opened on the same device. Its NOTE comment is corrected.

## [1.51.1] — 2026-09-29

### Changed

- SDK chrome is painted from the app's Branding (#681). New `OneloBrandTokens` / `OneloBrand` (from `brand_tokens` on `/api/sdk/config` and the store / portal / upgrade initiate responses): the auth, consent, portal and feedback Activities take their ground, progress bar colour, fail-safe ✕ and WebView skeletons from it. Before config → neutral, never Onelo orange.
- The Gradle `version` (was 1.49.0) and `SDK_VERSION` (was 1.50.0) agree again.

### Fixed

- An expired hosted auth link now keeps `OneloAuthActivity` open with a retry action when re-resolving fails or the resolver is temporarily unavailable (#638).

## [1.51.0] — 2026-09-29

### Changed

- SDK chrome is painted from the app's Branding (#681). New `OneloBrandTokens` / `OneloBrand` (from `brand_tokens` on `/api/sdk/config` and the store / portal / upgrade initiate responses): the auth, consent, portal and feedback Activities take their ground, progress bar colour, fail-safe ✕ and WebView skeletons from it. Before config → neutral, never Onelo orange.

## [1.50.0] — 2026-09-29

### Fixed

- After a purchase or a plan switch in `OneloAuthActivity`'s store or `OneloCustomerPortalActivity` (including `openUpgrade`), the app shows the new plan at once instead of waiting for the Event Stream (#659). Every successful hand-back forces `features.refresh(force = true)` and `auth.revalidateEntitlement()`; a `plan_switch_pending` switch is re-checked after 1, 2, 4, 8 and 15 s until the features change. The portal Activity now returns a code-less switch as `EXTRA_STORE_DONE` with the store's reason. One refresher (`PlanChange.kt`), wired by `Onelo`.
- The entitlement now follows a plan change live (#635): `Onelo` listens for the Event Stream's per-user `paywall.access_changed` frame and calls `auth.revalidateEntitlement()`.

## [1.49.0] — 2026-09-28

### Fixed

- A store whose link died (#619) and reports it over the postMessage bridge (`{type:'onelo:cancel', reason:'expired'}`) now reloads a fresh hosted URL in place, as the `?error=token_expired` deep link already did, instead of closing the activity.
## [1.48.0] — 2026-09-28

### Added

- The feedback screen (`OneloFeedbackActivity`) now keeps the reporter when the form was left open longer than its 30-minute reporter token (#622). On submit the hosted form asks the SDK for a fresh token (`onelo:renew_request`, kind `feedback`); the SDK calls `/api/sdk/feedback/initiate` again with the same options and hands the new form URL back, and the report is sent with its reporter. Before, such a report was stored without a reporter and nobody was told.

## [1.47.0] — 2026-09-28

### Added

- `customerPortal.openCustomerPortal(auth, launcher, intent = PortalIntent.CHANGE_PLAN)` opens the portal straight into the plan picker, the same as `@onelo/js` (#606). The hosted portal asks the backend where the change goes.

### Changed

- `onelo.openUpgrade(plan)` opens the upgrade in the SDK's `OneloCustomerPortalActivity` instead of the system browser (#606). The store's hand-back (a purchase, a plan switch, or "Return to app" on a spent link) closes it, and the entitlement is re-read.
- A closed portal re-reads the entitlement before the launcher callback fires, so `auth.hasActiveAccess` reflects a plan bought or changed there (#606).
- The portal Activity closes on `<scheme>://callback?error=…` (a spent store link) instead of loading the scheme URL (#606).

### Fixed

- One decoder for the store's postMessage hand-back (#627). The store / auth Activity and the portal Activity forward `onelo:code`, `onelo:done` and `onelo:cancel` to `classifyHandBackMessage`. The auth Activity listened for the retired `onelo:cancelled`, and the portal Activity ignored `onelo:done` and `onelo:cancel`.

## [1.46.0] — 2026-09-28

### Changed

- One decoder for every `<scheme>://callback` hand-back (#615): `OneloAuthActivity` and `OneloCustomerPortalActivity` classify `?code=`, `?source=store&event=…`, `?source=portal[&event=…]` and `?error=` through the same function. `event=plan_switch_pending` (a paid switch still being updated) is a success like any other plan switch.

## [1.45.0] — 2026-09-28

### Added

- `OneloCustomerPortalActivity` renews an expired portal token instead of showing "Link expired" to a signed-in user (#613 / #614). The page relay forwards `onelo:renew_request` through the `OneloAndroid` bridge (`renewPortal`); the SDK mints a fresh portal with the current session and evaluates the reply in the page. A 401 from `portal-initiate` now throws `NOT_AUTHENTICATED`.

## [1.44.0] — 2026-09-28

### Fixed

- After a month↔year plan switch or a scheduled downgrade, the store (`OneloAuthActivity`) and the Customer Portal (`OneloCustomerPortalActivity`) close by themselves as a success (#599). The hosted store reports these code-less finishes as `<scheme>://callback?source=store&event=plan_switched|switch_scheduled`. The store finishes with `RESULT_OK` and `registerLauncher` hands back the current session instead of `null`; the portal reports `dismissed = false` instead of trying to load the unknown scheme. The new plan arrives over the Event Stream.

## [1.43.0] — 2026-09-26

### Changed

- A refused session refresh now says what happened (#548). `refreshSession()` throws `OneloError` `SESSION_ENDED` ("Your session ended — please sign in again") for `session_compromised`, `session_expired` and `session_invalid`, `USER_REVOKED` ("suspended", sets `isUserRevoked`) only for `banned`, and the new `NO_ACTIVE_PLAN` for `no_plan_available`. It used to return `null`, and a ban did not set `isUserRevoked`. `getSession()` still returns `null` for every ended session and never throws for it.
- Uncaught exceptions captured by Monitor carry `meta.origin` (`onelo_sdk` or `app`).
- A transient refresh failure — offline, a lost response, `429`, or a `5xx` including the new `503 refresh_unavailable` — no longer signs the user out. The session is kept, `refreshSession()` throws `SERVER_ERROR` (or the network error), and the refresh is retried after 5 s, 10 s, 20 s, 40 s, then every 60 s.

## [1.42.0] — 2026-09-25

### Changed

- Features calls now prove the signed-in user with the Onelo access token (#519). Resolve, poll, batch-ping, module-token and the Event Stream send `Authorization: Bearer <access token>`, read fresh for every call and every reconnect. A refused token (`401 invalid_user_token`) is refreshed and retried once; if it is refused again, that call is made without a user and a warning is logged. A refused module-token request fails as not authenticated instead.
- A refused token triggers at most one session refresh until a token is accepted again or the user changes, so a server that keeps refusing cannot cause a refresh loop.
- `userIdHash` is no longer sent. `identify()` and its `userIdHash` argument still compile but are deprecated: the signed-in Onelo user is used automatically.
- `OneloError.Code.SECURE_MODE_REQUIRED` and `INVALID_USER_HASH` are deprecated and never raised: the server no longer returns them. `OneloAuth.refreshSession()` is now single-flight, so overlapping refreshes share one request.
