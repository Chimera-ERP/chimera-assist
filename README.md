# Chimera Assist

Status: Android test releases; physical-device acceptance remains pending.
Owner: Chimera mobile and ERP maintainers. Android ID: `ro.chimeraerp.assist`.
Business contract: [MDM-AST-001](https://github.com/Chimera-ERP/chimera/blob/main/docs/business-rules/master-data.md#assist-phone-discovery).
Current work and evidence remain in the ERP [handoff](https://github.com/Chimera-ERP/chimera/blob/main/docs/development/verification-checklist.md#android-identity-and-chimera-assist--2026-11-05).


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

## First version

The welcome screen defaults to **Chimera Cloud** with a **Log in** button.
No ERP address is required. Auth opens in the system browser at
`https://auth.chimeraerp.ro`; its company chooser lists active registered,
allowlisted ERP hosts and starts the existing tenant login. Auth returns the
one-use authorization code directly to Assist.

**Enterprise / Self-hosted** reveals a server-address field. That explicitly
chosen ERP's browser configuration supplies its HTTPS Auth authority, which
Assist pins for discovery and token exchange. Users can switch back to Cloud.
Local credentials and configured enterprise SSO stay in the existing browser
login page. Assist has no password form, WebView or client secret.

Cloud tenant selection is bound to the initiating state, nonce, S256 challenge
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

Sessions and pending requests use separate authenticated AES-GCM envelopes, with
backup disabled. Passwords, the address book, phone history and business responses
are not stored. Logout stops the helper and clears the local session and pending
login; the central browser session remains governed by Auth. The existing Auth
policy applies: access tokens last 15 minutes, without refresh/offline grants.
A rejected session stops the helper and requires browser login again; an existing
central browser session can satisfy that login. Permissions remain server checked.

Setup requests the system caller-ID role and permission to display over other
apps. Optional Contacts permission lets Android deliver calls from numbers in the
phone's address book; the app never queries the address book. Notification
permission is requested on Android13+. Activation from the visible screen starts
a user-visible `specialUse` foreground service. A persistent notification provides
a stop action. There is no boot receiver, default-dialer replacement, call-log
permission, microphone access, accessibility service or call recording.

`CallScreeningService` immediately allows an incoming call, without waiting for
network I/O. The already activated service performs an exact, authenticated ERP
lookup. It clears earlier call context before every lookup; late replies cannot
replace a newer call or a different session. A recognized partner displays the
Chimera logo bubble. Drag to reposition and tap to expand; long press or Close dismisses it. More than
one partner requires a choice. Supplier actions read supplier invoices, recorded
payments and active supplied products. Client actions read location-scoped
invoices, permitted invoice/standalone payments and products appearing on issued
invoices. Amounts come from stored documents or existing supplier routines.
No payment, invoice, order or external message is created.

The panel offers the existing permitted actions; tapping one opens native lists
with25 rows per page, loading/error/retry/empty states. Manual number lookup is
available from the home screen. UI text is English/Romanian according to Android
locale. The logo is the existing Chimera artwork copied from Companion's
`design/companion-motion/chimera.png`, not a replacement brand asset.

## Boundaries and device acceptance

Android10+ (API29), target36. Enable the helper again after force-stop, reboot or
OS process termination. It intentionally does not attempt a restricted background
foreground-service launch. Incoming calls only; hidden numbers and non-telephony
calls are not identified. Offline or unknown callers produce no bubble. One app
can hold the caller-ID role at a time. The bubble is not shown over the lock screen
and is removed on screen-off, dismissal, a new call, logout, service stop or after
five minutes. Telecom screening does not expose a call-ended event; this version
uses explicit dismissal/expiry instead. Expanded panels have a bounded scroll area.
No identity or financial content appears in the notification or recent-app capture.

Numbers in national format are interpreted as Romanian; other countries require
an international prefix. There is no cross-company matching or peer-system data
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

Use Java17 and an Android SDK containing platform36:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Set `JAVA_HOME`/`ANDROID_HOME` for your machine; do not commit `local.properties`,
keystores or credentials. Output: `app/build/outputs/apk/debug/app-debug.apk`.
Release signing and publication use the dedicated GitHub Actions workflow below. Gradle wrapper9.1.0 is reused from
Companion; Android Gradle Plugin9.0.1 supplies built-in Kotlin support. No Flutter
or hardware adapter dependency is included.

ERP migration `sql/323_assist.sql` adds indexed phone normalization and read-only
domain functions; all three core install paths include it. Apply through the
normal release migration process before using the app against an existing ERP.
Auth must run the existing standard OIDC provider with `AUTH_OIDC_ENABLED=true`,
`AUTH_BROWSER_LOGIN_ORIGIN=https://auth.chimeraerp.ro` and this ERP's exact origin
in `AUTH_BROWSER_CLIENT_ORIGINS`, bound to the active organization in `auth.login_hosts`.
Apply Auth-owned `sql/20261105_assist_browser_login.sql` after the OIDC migration,
then `sql/20261106_assist_cloud_login.sql`; fresh Auth init includes both.
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
twice to test reproducibility, and removes the disposable database.

Auth browser regression, from the Auth repository:

```sh
npm run build
npx --yes --package=node@22 node scripts/test-enterprise-oidc.mjs
npm run quality:check
```

The shared synthetic OIDC runner covers native password/enterprise flows, exact
redirects, mandatory PKCE, wrong verifier, replay, disabled runtime, tenant and
revoked-account rejection. Android unit tests cover endpoint/state/expiry bounds;
real browser-to-app return, cancellation, process death and OEM browser behavior
still require an Android device/emulator trial.

Platform references: [Android caller identification](https://developer.android.com/reference/android/telecom/CallScreeningService),
[foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[special-use foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use).
Browser authentication follows [AppAuth Android](https://github.com/openid/AppAuth-Android)
and [RFC8252](https://www.rfc-editor.org/rfc/rfc8252.html).

## Automated Android releases

Like Companion, pushes and pull requests run verification. A new stable
`versionName` and increasing `versionCode` in `app/build.gradle.kts` on `main`
build and publish a signed release after unit tests, Android lint and package /
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
./gradlew :app:assembleRelease
gh attestation verify chimera-assist-0.1.2.apk --repo Chimera-ERP/chimera-assist
```

An interrupted release remains a draft and must be reconciled before retrying.
Backend readiness and the physical-phone acceptance checklist above still apply.
