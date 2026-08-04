# Section browser extension

Manifest V3 extension for Chrome / Edge / Brave / Arc / Opera. Scans
prompts entered on consumer AI sites against your operator-deployed
[Section gateway](../../services/gateway/) and rewrites or blocks them
before they leave the tab.

Install + operate guide: [`docs/operations/browser-extension-install.md`](../../docs/operations/browser-extension-install.md).

## Supported sites

| Site | Domain | Submit hook | Page-world fetch hook |
|---|---|---|---|
| ChatGPT | `chatgpt.com`, `chat.openai.com` | PASS | PASS |
| Claude | `claude.ai` | PASS | PASS |
| Gemini | `gemini.google.com` | PARTIAL[^gemini] | PASS |
| Copilot | `copilot.microsoft.com` | PASS | PASS |
| Perplexity | `perplexity.ai`, `www.perplexity.ai` | PASS | PASS |
| Mistral | `chat.mistral.ai` | PASS | PASS |

[^gemini]: Gemini's React-controlled composer swallows DOM keyboard
events before our capture-phase handler runs; the page-world fetch
hook is the canonical interception path for that site.

Selectors are tracked per site; if a vendor refactors its composer DOM,
we update in a patch release.

## Install

### Distribution

The release pipeline emits a cosign-signed `.crx` and `.zip` per tag.
Operators install via Chrome Enterprise policy
(`ExtensionInstallForcelist`) or sideload the artefact from a GitHub
release.

### Sideload (developer / self-host)

1. Run `npm install` then `npm run build` in this directory.
2. Open `chrome://extensions`, enable Developer mode.
3. Click "Load unpacked", point at `dist/`.

The extension defaults to `https://localhost:8000` for the gateway URL.
Open the popup and either:
- paste a gateway API key, or
- click "Sign in with OIDC" (operators configure the issuer URL via
  enterprise policy or the popup's settings).

### Self-host signed `.crx`

For enterprises that prefer their own update server:

```bash
npm run build:crx     # produces dist-zip/section-browser-extension-<ver>.crx
```

Drop the `.crx` and an `updates.xml` manifest on an internal HTTPS host.
Push the extension ID via `ExtensionInstallForcelist` policy (Jamf /
Intune / Workspace).

The `key.pem` generated on first run pins the extension's ID — store it
in a secure secret manager. Re-using the same key across rebuilds keeps
the install in place across updates.

## Threat model

See [`docs/threat-model.md`](../../docs/threat-model.md) for the full
picture. Headlines:

- The extension only sends prompts to the operator-configured gateway URL.
  `connect-src` in the manifest constrains the set of hosts; if you need
  a non-default host, self-build with `manifest.json#content_security_policy`
  updated.
- Secrets (API key, OIDC tokens) live in `chrome.storage.local` and are
  **never** synced across the user's Google Account. Settings (gateway
  URL, per-site toggles) live in `chrome.storage.sync`.
- The background worker fires a `__section_heartbeat__` scan every 5
  minutes. SIEM dashboards can alert on heartbeat gaps to detect agents
  taken offline.
- The signed `.crx` artifact uses `cosign keyless` + SLSA-3 provenance
  attestations published with every release.

## Screenshots

The popup is a 380px-wide React panel on the Section ivory canvas
(`#FAFAF7`) with the indigo accent (`#4F46E5`). It shows, from top to
bottom:

- A header pill (Connected / Offline) with the gateway URL.
- The gateway URL input + Save button.
- An auth panel: API-key field, OR a "Sign in with OIDC" button that
  shows the device-code on click.
- A per-site list of six toggles (all on by default).
- A scrolling list of the last 10 decisions (allow / mask N tokens /
  block) with the site and timestamp.
- A footer link to `<gateway>/admin/events` for the full audit log.

## Dev workflow

```bash
npm install
npm run dev          # vite watch build into dist/
npm run typecheck
npm run lint
npm test             # vitest unit tests
npm run e2e          # Playwright against e2e/fixtures/*.html
npm run build
npm run build:zip    # Web Store artifact
npm run build:crx    # self-host artifact (signs with ./key.pem)
```

Configuration:

| Env var | Default | Purpose |
|---|---|---|
| `SECTION_CRX_KEY` | `./key.pem` | Path to RSA key for `.crx` signing. |

## License

Apache-2.0. See [`../../LICENSE`](../../LICENSE).
