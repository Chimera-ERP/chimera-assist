# Chimera Assist

Status: Flutter Android test releases; physical-device acceptance remains pending. iOS host is preparation only.
Owner: Chimera mobile and ERP maintainers. Android ID: `ro.chimeraerp.assist`.
Business contract: [MDM-AST-001](https://github.com/Chimera-ERP/chimera/blob/main/docs/business-rules/master-data.md#assist-phone-discovery).
Current work and evidence remain in the ERP [handoff](https://github.com/Chimera-ERP/chimera/blob/main/docs/development/verification-checklist.md#assist-floating-workspace-and-per-caller-return--2026-11-07).


## Download and repository

This is the standalone **Chimera Assist** repository:
[Chimera-ERP/chimera-assist](https://github.com/Chimera-ERP/chimera-assist).
Android downloads: [latest release](https://github.com/Chimera-ERP/chimera-assist/releases/latest).
The application source was extracted with its history from ERP's former
`apps/chimera-assist` directory. ERP API/SQL and central Auth remain in their owning
repositories; they are not bundled into this Android repository.

The release APK uses a dedicated persistent signing identity. If you installed a
previous locally signed debug APK, uninstall that debug build once before the
first release APK; Android cannot update across different certificates. This
clears only the app's local session/settings. Subsequent release APKs retain the
same certificate and can update in place. Android package: `ro.chimeraerp.assist`.

## Flutter app and caller diagnostics

The welcome screen defaults to **Chimera Cloud** with a **Log in** button.
No ERP address is required. Auth opens in the system browser at
`https://auth.chimeraerp.ro`, which opens the public application at
`app.chimeraerp.ro` directly. Custom/enterprise organizations and their domains
are never listed in public Cloud login. Auth returns the
one-use authorization code directly to Assist.

**Enterprise / Self-hosted** reveals a server-address field. That explicitly
chosen ERP's browser configuration supplies its HTTPS Auth authority, which
Assist pins for discovery and token exchange. Users can switch back to Cloud.
Local credentials and configured enterprise SSO stay in the existing browser
login page. Assist has no password form, WebView or client secret.

The Cloud destination is fixed; the request is bound to the initiating state, nonce, S256 challenge
and an HttpOnly browser cookie. After returning, the app resolves that choice
once with its original verifier, then uses the standard tenant token endpoint.
The encrypted request owns all proofs; callback URLs cannot select a tenant or
an ERP. Selection expires after ten minutes and cannot change company once set.

Authorization Code + S256 PKCE, state and nonce bind the return to the initiating
app and tenant. The only registered callback is
`ro.chimeraerp.assist:/oauth2redirect`; it carries a one-use code, never a token.
The client ID is `<registered-ERP-origin>/native/assist`. Unknown ERP origins,
foreign tenants and configurable callback URLs are rejected by Auth. The app pins
the initiating HTTPS authority and tenant discovery/endpoints, checks the returned
organization claim and consumes its pending request once within ten minutes.
AppAuth validates the ID-token issuer/audience/time/nonce using the HTTPS token
response. Pending PKCE material survives Android activity/process recreation in
Keystore-encrypted storage; an interrupted token exchange requires a new login.

The Android AppAuth redirect receiver has an explicit translucent AppCompat theme.
It must not inherit the Flutter activity's platform theme: with the current AndroidX
dependencies that crashes before either a successful or rejected browser response
can reach the login handler. Version0.2.1 fixes that browser-return regression.

Sessions and pending requests use separate authenticated AES-GCM envelopes, with
backup disabled. Passwords, the address book, phone history and business responses
are not stored. Logout stops the helper and clears the local session and pending
login; the central browser session remains governed by Auth. The existing Auth
policy applies: access tokens last15 minutes. Assist alone receives rotating refresh
tokens bounded by the original eight-hour grant, without offline access or sliding
extension. Renewal is serialized and atomically saved in Keystore-encrypted storage;
logout or a changed login cannot be overwritten by an outstanding renewal. Replay
and current account/tenant/federation revocation are checked by Auth. Users upgrading
an old session must log in once to obtain renewal.
A rejected session stops the helper and requires browser login again; an existing
central browser session can satisfy that login. Permissions remain server checked.

Setup requests the system caller-ID role and permission to display over other
apps, plus Phone state to recognize ringing/active/held calls and their end. Only
aggregate Telecom state is read; no phone number or call log is read from that
permission. Optional Contacts permission lets Android deliver calls from numbers in the
phone's address book; the app never queries the address book. Notification
permission is requested on Android13+. Activation from the visible screen starts
a user-visible `specialUse` foreground service. A persistent notification provides
a stop action. There is no boot receiver, default-dialer replacement, call-log
permission, microphone access, accessibility service or call recording.

`CallScreeningService` enqueues the event while Telecom still holds its binding,
then immediately allows the call in a `finally` block, without waiting for network
I/O. The user-enabled foreground service requests system restart with
`START_STICKY`; Stop/logout revoke that choice. Reopening the visible app resumes
an enabled helper. A cold callback attempts foreground-service delivery while
bound; Android refusal is caught and reported with an actionable notification.
There is no background activity launch or permission bypass.

Separate canonical-caller contexts survive service recreation within the same
process, screen locking and activity navigation. Each remembers the selected
partner, action, list/detail page, scroll and expanded state. Returning from callerB
to callerA restoresA. Contexts expire after30 minutes without call activity;
ringing/active/held calls never expire mid-call. Aggregate Telecom idle starts the
inactivity interval, conservatively retaining held callers until all calls end. Late replies cannot
replace a newer call or a different session. Locking hides the overlay; unlocking
may restore an unexpired matching context. Process death clears private call data.
The Flutter home screen exposes the current result, so opening Assist during a
call does not require tapping the circle. Notifications contain operational text,
never numbers, names or financial data. The last callback time and result category
are retained locally for diagnosis; no phone history is persisted.

**Test the circle** exercises the real Android overlay without a lookup or call.
**Test with this number** uses the same normalization, lookup and overlay path as
an incoming call. Neither test proves Telecom/OEM callback delivery. The permission
checklist shows caller role, overlay, saved-number permission and notification
channel status independently. If a dialer suppresses overlays, the current context
remains accessible inside Assist and through its notification. A recognized partner displays the
Chimera logo bubble. Drag to reposition and tap to expand. Outside taps or the minimize chevron return
to the circle without losing context. There is no Close/long-press dismissal. More than
one partner requires a choice. Supplier actions read supplier invoices, recorded
payments and active supplied products. Client actions read location-scoped
invoices, permitted invoice/standalone payments and products appearing on issued
invoices. Amounts come from stored documents or existing supplier routines.
No payment, invoice, order or external message is created.

The rounded floating Flutter panel keeps permitted actions in its header. Actions
expand downward into lists with25 rows per page; selecting an invoice, payment or
product opens individual details inside the same window, with paginated invoice
lines and return to the previous list page/scroll. It never launches a full-screen
activity. Loading/error/retry/empty states retain the header. Manual number lookup is
available from the home screen. UI text is English/Romanian according to Android
locale. The logo is the existing Chimera artwork copied from Companion's
`design/companion-motion/chimera.png`, not a replacement brand asset.

## Boundaries and device acceptance

Android10+ (API29), target36. After force-stop or reboot, open Assist to resume the enabled helper. Ordinary
system service reclamation can restart the opted-in foreground service; OEM policy
may still prevent it. A refused background start is reported, never bypassed. Incoming calls only; hidden numbers and non-telephony
calls are not identified. Offline or unknown callers produce no bubble. One app
can hold the caller-ID role at a time. The bubble is not shown over the lock screen
and is hidden on screen-off. Logout, service stop, process death or30 minutes of
call inactivity remove its context. Another caller switches the displayed workspace;
it does not erase previous callers. Telecom aggregate state supplies the lifetime
boundary because CallScreeningService itself has no call-ended callback. Expanded panels have a bounded scroll area.
No identity or financial content appears in the notification or recent-app capture.

Google libphonenumber9.0.43 normalizes complete numbers to E.164 before lookup.
Explicit `+`/`00` international prefixes win; national numbers use the user-selected
country or, in automatic mode, network country, SIM country, then phone locale.
The chosen country is visible and editable. No country is silently forced to RO.
Roaming/multiple-SIM or missing country context can be ambiguous: select the
appropriate country or use an international number. Short/partial numbers,
extensions and malformed input fail explicitly; suffix/fuzzy matching is forbidden.
ERP supplier contacts already accept any international prefix, not only `+40`.
The legacy ERP endpoint still supports Romanian national input for compatibility;
this app sends canonical global numbers. There is no cross-company matching or peer-system data
exchange. Shared numbers return up to20 permitted partners with `has_more`.
Unassigned-location client documents are excluded, not treated as globally visible.
Phone identity is a navigation hint, never proof of the caller's identity.

Physical phone acceptance must cover the actual dialer/OEM and Android version,
saved/unsaved contacts, dual SIM, denied permissions, locked/unlocked screen,
background/process death, two rapid calls, lost network, permission/session
revocation and narrow/landscape/text-scale UI states. A debug APK/unit test is not
evidence that Android displayed the overlay during a real call. Physical-call and OEM acceptance remain required; release checks and emulator
trials do not replace them.

## Build and backend

Use Flutter3.44.3, Java17 and an Android SDK containing platform36:

```sh
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Set `JAVA_HOME`/`ANDROID_HOME` for your machine; do not commit `local.properties`,
keystores or credentials. Output: `build/app/outputs/flutter-apk/app-debug.apk`.
Release signing and publication use the dedicated GitHub Actions workflow below.
The shared Flutter UI is in `lib/`; Android services, AppAuth and Keystore storage
are in `android/app/`. Flutter receives session display metadata and scoped API
results, never tokens or PKCE proofs. Gradle9.1.0/AGP9.0.1 use Flutter3.44's
compatibility flags (`android.newDsl=false`, `android.builtInKotlin=false`) and
Kotlin2.3.20. Resource-enabled Robolectric API35 tests explicitly depend on Flutter
asset copying; this does not bypass the Android runtime or release checks.

The `ios/` host shares the UI and bundle ID but deliberately reports unsupported
native operations. It does not claim working browser login, caller-ID extensions
or Android-style overlays on iOS. Apple Call Directory/Live Caller ID require their
own native/server integration and acceptance. Full Xcode/signing are also required.
See [Apple's extension contract](https://developer.apple.com/documentation/identitylookup/getting-up-to-date-calling-and-blocking-information-for-your-app).

For synthetic browser UI inspection only:
`flutter run -d web-server --dart-define=ASSIST_PREVIEW=true`.
Preview fixtures require debug mode and never call a live ERP. Query states:
`welcome`, `setup`, `matched`, `empty`, `loading`, `error`; floating preview uses
`?overlay=1&state=panel&lang=ro` and `panel-empty`, `panel-error`, `panel-loading`. The release ignores this
preview flag. Browser inspection and Robolectric are not physical Android evidence.

ERP migration `sql/323_assist.sql` adds indexed phone normalization and read-only
domain functions; `sql/325_assist_details.sql` adds individual record reads. All
three core install paths include both. Apply through the
normal release migration process before using the app against an existing ERP.
Auth must run the existing standard OIDC provider with `AUTH_OIDC_ENABLED=true`,
`AUTH_BROWSER_LOGIN_ORIGIN=https://auth.chimeraerp.ro` and this ERP's exact origin
in `AUTH_BROWSER_CLIENT_ORIGINS`, bound to the active organization in `auth.login_hosts`.
Apply Auth-owned `sql/20261105_assist_browser_login.sql` after the OIDC migration,
then `sql/20261106_assist_cloud_login.sql`, `sql/20261107_assist_cloud_privacy.sql`
and `sql/20261107_assist_session_renewal.sql`; fresh Auth init includes them.
`AUTH_OIDC_WEB_LOGIN_ENABLED=false` may preserve the existing web flow during
native activation; ERP APIs must still trust the configured OIDC authority. Deploy Auth routes/provider changes with that migration.
The public `/auth/browser/native-config` and tenant `/auth/oidc/*` routes must reach
Auth at the central host. Missing configuration fails closed with an app error.
ERP must accept the tenant OIDC issuer as documented in
[Enterprise SSO](https://github.com/Chimera-ERP/chimera/blob/main/docs/integrations/enterprise-sso.md). Android uses
`net.openid:appauth:0.11.1` for the native browser flow. Phone lookup is POST, so phone numbers
do not enter ordinary query-string access logs; responses use `private, no-store`.

From the ERP root:

```sh
npm test -- --runTestsByPath src/routes/assist.test.ts src/routes/companion-updates.test.ts
node scripts/test-assist.mjs
npm run typecheck
npm run quality:sql
npm run quality:check
```

The SQL test clones local schema only, inserts synthetic fixtures, applies323
and325 twice to test reproducibility, and removes the disposable database.

Auth browser regression, from the Auth repository:

```sh
npm run build
npx --yes --package=node@22 node scripts/test-enterprise-oidc.mjs
npm run quality:check
```

The shared synthetic OIDC runner covers native password/enterprise flows, exact
redirects, mandatory PKCE, wrong verifier, replay, disabled runtime, tenant and
revoked-account rejection, refresh rotation/replay and wrong-client rejection. Android tests cover endpoint/state/expiry bounds and
launch the actual AppAuth redirect receiver with merged app resources on simulated
API35 for both success and error responses, checking forwarding to AppAuth;
real browser-to-app return, cancellation, process death and OEM browser behavior
still require an Android device/emulator trial.

Platform references: [Android caller identification](https://developer.android.com/reference/android/telecom/CallScreeningService),
[foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[special-use foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
Browser authentication follows [AppAuth Android](https://github.com/openid/AppAuth-Android)
and [RFC8252](https://www.rfc-editor.org/rfc/rfc8252.html).

## Automated Android releases

Like Companion, pushes and pull requests run verification. A new stable
`versionName` and increasing `versionCode` in `android/app/build.gradle.kts` on `main`
and the matching `pubspec.yaml` version build and publish a signed release after
Flutter analysis/widget tests, native unit/service tests, Android lint and package /
version / certificate checks. The workflow pins its actions, Java17, Android36
and Gradle wrapper. Commits without a new version do not replace a published APK.
Releases contain the APK, `assist-update.json`, `SHA256SUMS` and a GitHub build
attestation. Assist currently installs updates manually from Releases; an in-app
updater is not implemented.

Four encrypted repository secrets (`ASSIST_KEYSTORE_BASE64`,
`ASSIST_KEYSTORE_PASSWORD`, `ASSIST_KEY_ALIAS`, `ASSIST_KEY_PASSWORD`) restore the
persistent release key only for the main-branch release job. PRs cannot publish
or access it. Release builds fail without the key; there is no debug fallback.
Never commit, regenerate or rotate the key for an existing installation. The
expected certificate SHA256 is `23ff3ea173fdb9ca050af2099b922bacd2ddbb71a4194d4d875bf2ccdf79aad4`.

```sh
npm test
npm run quality:check
./gradlew :app:testDebugUnitTest :app:lintDebug
# With the four ASSIST signing environment values supplied securely:
flutter build apk --release
gh attestation verify chimera-assist-0.3.0.apk --repo Chimera-ERP/chimera-assist
```

An interrupted release remains a draft and must be reconciled before retrying.
Backend readiness and the physical-phone acceptance checklist above still apply.
