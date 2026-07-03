# AGENTS.md

## What this project is

This is a **self-hosted revival of Omnivore**, the open-source read-it-later
app. Upstream (`omnivore-app/omnivore`) was **archived by its maintainers**
(acquired by ElevenLabs, service shut down); this fork (`zaiemv/omnivore`) is
being kept alive for **single-user, self-hosted** use only.

Target deployment: a small LAN host (Mac/Linux box), everything running via
**`docker compose up`** from `self-hosting/docker-compose/`, reachable
remotely only through a **Cloudflare Tunnel + Cloudflare Access** (no public
ports, no public signup flow needed). There is exactly one user. Anything
that assumes multi-tenant SaaS operation (billing, cloud analytics, cloud
support chat, managed push-notification fleets) is out of scope and should be
made inert, not deleted outright, unless it blocks startup or build.

## Repo map — what matters for self-hosting vs. dead weight

This is a Yarn 1 classic workspaces + Lerna monorepo. Root `package.json`
declares `"workspaces": ["packages/*"]`.

**Matters (touch freely, this is the self-hosted product):**
- `packages/api` — GraphQL/REST backend (Express + Apollo). Also doubles as
  `queue-processor` and `export-processor` entrypoints (same package,
  different `start_*` scripts).
- `packages/web` — Next.js frontend.
- `packages/content-fetch` — Puppeteer/Firefox-based article content fetcher
  service (the thing that actually downloads and parses saved pages).
- `packages/puppeteer-parse` — shared parsing library used by content-fetch.
- `packages/content-handler`, `packages/readabilityjs`, `packages/db`,
  `packages/utils`, `packages/liqe` — shared libs the api/content-fetch build
  depends on (see `self-hosting/Dockerfile`, which builds exactly this set).
- `packages/text-to-speech`, `packages/thumbnail-handler`, `packages/pdf-handler`,
  `packages/rss-handler`, `packages/rule-handler`, `packages/import-handler`,
  `packages/export-handler`, `packages/discover`, `packages/queue-manager`,
  `packages/integration-handler`, `packages/inbound-email-handler`,
  `packages/local-mail-watcher`, `packages/imap-mail-watcher` — feature
  workers used by the self-hosting compose stack or its optional mail add-ons.
  Keep these building; don't assume unused.
- `imageproxy/` — the `sh-image-proxy` service in docker-compose (Go, separate
  from the `packages/` JS workspace).
- `self-hosting/` — the deploy path. `docker-compose/docker-compose.yml` +
  `.env.example` is the primary target; `podman-compose/` and `helm/` are
  secondary/lower-priority. `GUIDE.md` is the source of truth for how a human
  is expected to run this.
- `android/Omnivore/` — the Android client. See dedicated section below.

**Dead weight for this fork (do not spend effort here unless explicitly asked):**
- `apple/` — iOS/macOS client. Not a target platform for this project.
- `packages/appreader`, `packages/cypress` — appreader's relevance is unclear
  and low-value; Cypress e2e is heavyweight for a single-user instance — leave
  as-is, don't invest in fixing if broken.
- `ml/`, `pkg/` — check before touching; likely cloud/extension-store-oriented
  and not required for the Docker self-host path.
- Anything Firebase-Cloud-Messaging, GCP-App-Engine, Vercel, Intercom,
  PostHog-analytics, or Sentry-specific — see "no new cloud dependencies" rule
  below. These exist in the code but are not part of the self-hosting
  `.env.example` and must not be required for the app to start or build.

## Build / test commands (from actual `package.json` scripts — verify before running)

Package manager: **Yarn 1 (classic) workspaces**, orchestrated by **Lerna
7.x** (`npmClient: yarn` in `lerna.json`). Root scripts:

```
yarn install               # workspace install (do NOT run in this session per Jules brief unless asked)
yarn lint                  # lerna run --parallel lint
yarn build                 # lerna run build
yarn test                  # lerna run --stream test
yarn gql-typegen           # graphql-codegen (schema/codegen.yml)
```

Node version: **`.node-version` pins 22.12.0**; the self-hosting Docker
builder image is `node:22.12-alpine`, so treat **22.12.x as the real
target**. Note: root `package.json` still has a stale `"volta": {"node":
"18.16.1", "yarn": "1.22.19"}` block left over from an older Node line —
this is a leftover, not the intended version; don't "fix" the app to run on
18 to match it.

Per-package (from each `packages/*/package.json`):

| Package | build | test | lint | typecheck |
|---|---|---|---|---|
| `api` | `tsc` | `nyc mocha -r ts-node/register --config mocha-config.json` | `eslint src --ext ts,js,tsx,jsx` | `tsc --noEmit` |
| `web` | `next build` | (no test script; `test:typecheck` only) | `next lint` | `tsc --noEmit` |
| `content-fetch` | `tsc` | `yarn mocha -r ts-node/register --config mocha-config.json` | `eslint src --ext ts,js,tsx,jsx` | `tsc --noEmit` |
| `puppeteer-parse` | `tsc` | `yarn mocha -r ts-node/register --config mocha-config.json` | `eslint src --ext ts,js,tsx,jsx` | `tsc --noEmit` |
| `db` | (`migrate`, `generate` via plop) | — | — | `tsc --noEmit` |

Most other `packages/*` follow the same `build`/`lint`/`test:typecheck`
pattern via `tsc` + `eslint` + `mocha` — check that package's own
`package.json` before assuming a script exists; not all packages have a
`test` script (many only have `test:typecheck`).

`api`'s `dev`/`start` split matters: `server.ts` (API), `queue-processor.ts`,
and `export-processor.ts` are three separate entrypoints built from the same
package and run as separate containers/processes in production and in the
self-hosting compose stack.

## Conventions

- **TypeScript strict mode is on** repo-wide (root `tsconfig.json`:
  `"strict": true`, target `es2020`, module `commonjs`). Per-package
  `tsconfig.json` files `extend` the root — don't loosen strictness in a leaf
  package to make something compile.
- ESLint: `eslint:recommended` + `@typescript-eslint/recommended` +
  `@typescript-eslint/recommended-requiring-type-checking` +
  `plugin:prettier/recommended` (see root `.eslintrc`). Notable house rule:
  **no semicolons** (`"semi": [2, "never"]`), matching `.prettierrc`
  (`"semi": false, "singleQuote": true`). Run the package's `lint` script,
  don't hand-format.
- Commit style: no enforced convention detected (no commitlint config found);
  keep messages short and descriptive.

## Android app (`android/Omnivore/`)

- Gradle **8.7** (`gradle/wrapper/gradle-wrapper.properties`), Kotlin +
  Jetpack Compose, Hilt DI, Room, Retrofit, Apollo (GraphQL codegen).
- `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`. JVM target 17.
- Build: `./gradlew assembleDebug` (debug variant does not require the
  release keystore — release signing needs `app/external/keystore.properties`
  + `app/external/omnivore-prod.keystore`, which won't exist in this
  environment; don't attempt release builds).
- **Blocker for self-hosting: the server URL is hardcoded at compile time**,
  not user-configurable. `app/build.gradle.kts` bakes
  `OMNIVORE_API_URL`/`OMNIVORE_WEB_URL` into `BuildConfig` per build type
  (debug → `api-demo.omnivore.work`/`demo.omnivore.work`, release →
  `api-prod.omnivore.work`/`omnivore.work`), and
  `app/src/main/java/app/omnivore/omnivore/utils/Constants.kt` reads
  `BuildConfig.OMNIVORE_API_URL` directly into `Constants.apiURL`, used by
  the network layer (`RESTNetworker`, `Networker`). There is no in-app
  settings screen to change server URL today — this must be added (see work
  items below).
- Also bundled: `pspdfkit` (commercial PDF SDK, requires a license key —
  don't assume it "just works" without one; PDF viewing may need to
  degrade gracefully without a license), `posthog` and `intercom` (cloud
  analytics/support — see cloud-dependency rule below), and a Google OAuth
  `OMNIVORE_GAUTH_SERVER_CLIENT_ID` scoped to Omnivore's own Google Cloud
  project (won't work for a self-hosted instance's own auth unless replaced
  or bypassed for self-host builds).
- No Firebase/`google-services.json` in the Android module — nothing to
  strip there.

## Self-hosting deploy path (do not break this)

`self-hosting/docker-compose/docker-compose.yml` is the contract. It defines
11 services: `postgres` (ankane/pgvector), `migrate` (one-shot, also seeds a
`demo@omnivore.work` demo user), `api`, `queue-processor`, `web`,
`image-proxy`, `content-fetch` (runs Firefox inside the container — Chrome
was reported to freeze in Docker), `redis`, `minio` + `createbuckets`
(self-hosted S3-compatible storage), and `mail-watch-server` (newsletter
email ingestion). `self-hosting/GUIDE.md` documents the full setup including
optional IMAP watcher / docker-mailserver / SES+SNS / Zapier paths for email,
and an Nginx reverse-proxy config at `self-hosting/nginx/nginx.conf`. A
`podman-compose/` variant and a `helm/` chart also exist as secondary
targets — don't regress them if a change is trivial to carry over, but don't
block on them either.

Compose images referenced (`ghcr.io/omnivore-app/sh-*`) are prebuilt; if a
change requires rebuilding, use `self-hosting/Dockerfile` /
`self-hosting/build-and-push-images.sh` as the reference build path, or the
`self-build` variant mentioned in `GUIDE.md`.

## Known self-hosting blockers found during inventory

- **`packages/api/src/utils/sendNotification.ts`** calls
  `initializeApp({ credential: applicationDefault() })` from
  `firebase-admin/app` **at module import time, unconditionally**. On a
  self-hosted box with no `GOOGLE_APPLICATION_CREDENTIALS` / no GCP metadata
  server, this can throw or hang at process startup depending on the
  `firebase-admin` version's ADC resolution behavior, and it is *not* gated
  by any self-hosting env var — no Firebase variables appear in
  `self-hosting/docker-compose/.env.example` at all. This needs to become
  lazy/optional so a missing Firebase credential doesn't take down `api`.
- **PostHog analytics and Intercom** are wired fairly deeply into both `api`
  (`packages/api/src/utils/intercom.ts`, `packages/api/src/util.ts`, many
  resolvers call `analytics.capture(...)`) and `web`
  (`packages/web/lib/analytics.ts`, `_document.tsx`, several settings pages).
  `intercom.ts` is already reasonably well-gated (`env.dev.isLocal` short-
  circuits to `null`) — model any new gating on that pattern. Analytics calls
  elsewhere assume a no-op/safe client when unconfigured; verify that holds
  rather than assuming it.
- **~88 files** across `packages/web` (mostly `pages/settings/*`,
  `components/templates/*`, landing pages) contain hardcoded
  `omnivore.app`/`omnivore.work` strings — mostly marketing/support links,
  privacy policy, "get help" URLs. Low priority (cosmetic, not functional
  blockers) but worth sweeping if doing a broader self-host cleanup pass.
- **Android hardcoded server URL** — see Android section above; this is the
  one Android blocker that actually breaks the "point the app at my own
  server" use case.
- **`pspdfkit`** in both `web` (`packages/web/package.json` has an
  `upgrade-pspdfkit` script copying `node_modules/pspdfkit/dist` into
  `public/`) and Android is a commercial SDK. Confirm whether a valid license
  is available for self-host use; if not, PDF viewing needs a fallback path
  that doesn't hard-fail the build/runtime.
- Storage (GCS) is already self-host-aware: `GCS_USE_LOCAL_HOST=true` +
  Minio env vars in `.env.example` route `GcsStorageClient` at a local Minio
  S3-compatible endpoint — this one is **not** a blocker, just noted so it
  isn't "fixed" a second time.

## Fork staleness

`zaiemv/omnivore` `main` is **exactly even** with
`upstream/omnivore-app/omnivore` `main` — same HEAD commit
(`3372a2e5012b734b0320a322156382052a7a6a07`, 2026-03-26, "Remove port
exposing for Redis service (#4642)"). Upstream is archived, so this will not
drift further; no rebase/merge work is needed before starting.

## Rules for Jules

1. **Never touch git history.** No rebase, no force-push, no amending
   existing commits. Linear, additive commits only.
2. **Small, focused commits.** One logical change per commit; don't bundle
   unrelated fixes.
3. **Don't upgrade major framework versions** (Next.js, Node, TypeScript,
   Express/Apollo, Gradle/AGP/Kotlin, etc.) **unless required to get
   something building**. If a major bump is unavoidable, call it out clearly
   in the commit message and keep it isolated from other changes.
4. **`docker compose up` from `self-hosting/docker-compose/` must remain the
   deploy path.** Any change to `api`, `web`, `content-fetch`,
   `image-proxy`, `queue-processor`, or their Dockerfiles must keep this
   working end to end — don't introduce a service, port, or env var the
   compose stack doesn't know about without updating
   `self-hosting/docker-compose/docker-compose.yml` and `.env.example` (and
   `GUIDE.md` if user-facing) in the same change.
5. **Android app must build with `./gradlew assembleDebug`**, and must gain
   (or keep, once added) a **user-configurable server URL** — not a
   compile-time constant. Don't reintroduce a hardcoded
   `api-prod.omnivore.work`/`api-demo.omnivore.work` dependency once this is
   fixed.
6. **No new cloud service dependencies.** Don't add anything that requires a
   paid/cloud account to start up. For existing cloud integrations (Firebase
   Cloud Messaging, Intercom, PostHog, Sentry, pspdfkit licensing) that block
   startup or build when unconfigured: **make them optional/no-op**, gated
   behind an env var check (follow the existing `intercom.ts` pattern of
   `env.dev.isLocal` / falsy-token → `null` client), rather than removing the
   feature outright.
