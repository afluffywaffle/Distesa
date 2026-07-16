# Distesa — security notes

Distesa is a GeckoView browser for Supernote e-ink devices. Browsers are broad attack
surfaces; this file records where responsibility sits and how credentials should be
handled. Scope today: **basic browsing only** — no credential storage is implemented.

## Shared responsibility: engine vs. app

**GeckoView (the engine) owns and patches** the memory-safety-critical surface — HTML/CSS/JS
parsers, JIT, media/image/font decoders, sandboxed content processes, site isolation — plus
TLS/cert validation (NSS), same-origin policy, mixed-content blocking, HSTS, CORS, and the
network stack. It also provides opt-in Enhanced Tracking Protection and Safe Browsing
(`ContentBlocking.SafeBrowsing`).

> ⚠️ **#1 practice: keep GeckoView on a current release.** Mozilla ships security releases
> ~every 4 weeks and the fixes are public — a pinned old GeckoView is a list of known,
> weaponizable CVEs. This is a packaging discipline the app owns, not something the engine
> does for you automatically.

**Distesa (the app) owns** everything the engine only delegates: credential storage,
permission policy, extension-install approval, certificate-error UI, and the Android app
surface (`allowBackup`, signing, exported components, deep links). This is where a minimal
browser realistically gets burned.

## Credentials strategy

Ranked by least risk taken on. **Current stance: store nothing.**

### On Supernote, autofill is unconfigured (measured 2026-07-15, Manta)
- `settings get secure autofill_service` → **empty** (no provider selected).
- The autofill **framework is present** (AOSP Android 11), but there's **no Google Play
  Services**, so no default Google Password Manager.
- One provider is registered on-device: `org.mozilla.fenix.autofill.AutofillService`
  (Firefox/Fennec F-Droid is installed) — but not selected.
- **Conclusion:** native autofill *works only if the user installs AND selects a provider*
  (Bitwarden's app registers an `AutofillService`; so does Fenix). No zero-config path.

### Option A — WebExtension password manager (preferred direction for Distesa)
Users bring their own manager (e.g. Bitwarden) as a WebExtension — Distesa stores no secret.
GeckoView supports WebExtensions (already used for uBlock).
- **Gap to close:** a manager extension needs its **`browserAction`/popup** to unlock the
  vault and pick a credential. Distesa's chrome is minimal with no browser-action surface —
  we must expose a way to invoke extension popups before this is usable.
- The vault then lives in the extension's storage inside the Gecko profile → depends on
  profile-at-rest protection (see app-surface: `allowBackup=false` + disk encryption).

### Option B — Android Autofill Framework
GeckoView builds the autofill virtual structure, so a selected system provider can fill.
Viable, but requires the user to install + select a provider (no GMS default here). Don't
break the autofill structure GeckoView provides.

### Option C — Passkeys / WebAuthn (modern target)
Gecko supports WebAuthn; Android has Credential Manager. Moves the secret to hardware-backed
credentials — no shared password to phish. Design toward this even if not first.

### If we ever implement built-in "save password" (do it the Fenix way, never naively)
GeckoView gives plumbing via `Autocomplete.StorageDelegate` (`onLoginSave`/`onLoginFetch`,
`Autocomplete.LoginEntry`) — the app implements the vault:
- Master key in **Android Keystore** (hardware-backed, non-exportable).
- Encrypt at rest (SQLCipher, or Jetpack Security `EncryptedSharedPreferences`/Tink).
  **Never plaintext / plain SharedPreferences.**
- Gate access behind device lock / `BiometricPrompt`.
- **Autofill only on an exact origin match** — cross-origin/look-alike fill is the classic
  autofill-phishing bug (same class as the `endsWith()` ETP-allowlist bug already fixed).

> Note: cookies/session tokens ARE credentials and are already written to the profile dir,
> so profile-at-rest protection matters even with no password manager.

## App-surface hardening checklist (currently deferred — track before any credential work)
- [ ] **`AutoApprovePromptDelegate`** auto-grants add-on install + permission prompts —
      test spike; must not ship. An add-on/permission grant is high-privilege.
- [ ] **WebExtension install approval** — require explicit user consent; don't auto-approve.
- [ ] **`allowBackup=true`** — lets `adb backup` extract app data dirs (incl. any future
      credential DB / cookies). Set `allowBackup=false`.
- [ ] **Debug keystore + no minify** on release — sign release properly; enable R8/minify.
- [ ] **Certificate-error UI** — `onLoadError` SECURITY category / `SslError`: show a real
      interstitial; never silently allow a cert override (MITM risk).
- [ ] **Exported components** — audit `exported` flags; keep internal activities unexported.
- [ ] **ETP look-alike host matching** — label-boundary match only (fixed once; keep the
      invariant if the allowlist logic changes).

_Last updated 2026-07-15. Device findings from the Manta (serial SN100C10008955)._
