# Reviq

Review intelligence with traceable evidence. Import a CSV, paste reviews or load the explicitly synthetic example; inspect coverage and data quality; ask questions; open citations to see the exact original review.

Live deployment: **https://reviq.xyz/** (also `/demo`). Design reference: https://revenue-iq-dashboard.lovable.app/. Design input: `Reviq_Implementation_Plan.pdf`.

## Architecture and scope

React + TypeScript + Vite is served by the existing Apache host. Apache proxies `/api/` to a Kotlin/Vert.x service bound to `127.0.0.1:8088`. The service uses the official OpenAI Java SDK Responses API, Apache Commons CSV and the O200K tokenizer. PostgreSQL stores immutable datasets, reviews, completed chat exchanges, anonymous sessions and atomic quota/cost reservations.

The user's PostgreSQL and Apache requirements override the plan's browser-only storage and container-hosting suggestions. This release remains public without login. An opaque, secure, HttpOnly, SameSite session cookie identifies a browser session; only its hash is stored. Database queries and composite foreign keys enforce session ownership. **Anonymous sessions are not authenticated tenants.** Data expires after 24 hours, with hourly cleanup. Clearing or replacing a dataset deletes its reviews and conversation.

The client sends dataset IDs, not editable dataset contents. The server retrieves authoritative session-owned records on each chat request; this replaces the browser-held signed-envelope design. No vector database, embeddings, RAG, tools or external browsing are used by the analyst. The bounded dataset is sent with each question and at most three prior user questions.

## Supported inputs and limits

- CSV: required `text`; optional `rating,title,date,author,source_url`. BOMs, commas in quoted fields and multiline cells are supported. Preview precedes import. Download the template in the app.
- Paste: blank-line-separated review blocks; missing structured metadata stays missing.
- Example: 18 **synthetic** Northstar Headphones reviews; never presented as scraped customer feedback.
- Public review URL imports: paste an HTTP/HTTPS review page URL. The importer accepts public sites, follows bounded validated redirects, and extracts identifiable review records from supported page markup. Dedicated parsers cover Google Play and Trustpilot; generic extraction covers schema.org JSON-LD and review microdata, plus common review widgets. Source identity, capture time, original text and sample coverage remain visible. One fetched review page is a limited sample, not a complete review history.
- URL acceptance does not guarantee access or readable review markup. Trustpilot's `https://www.trustpilot.com/review/allenbrothers.com` currently returns HTTP 403 from this host, including a normal browser probe. The app reports the block and preserves the prior dataset. An authorized data-access provider or review export is needed for that page. JavaScript-only, login-required, CAPTCHA-protected and unrecognized pages can also require CSV/paste; the app does not invent reviews from unrelated page text.
- 1 MiB raw input; at most 1,000 observed records, 200 admitted reviews and 24,000 dataset tokens. Complete reviews are omitted rather than truncated. Maximum question: 500 Unicode characters; total model input: 30,000 tokens including a framing allowance; output: 1,000 tokens.
- Observed = accepted + rejected + duplicate texts + excluded. Rating averages use valid supplied ratings only. Star ratings are not text sentiment. Source claims on imports are user-declared; findings describe the supplied sample, not all customers.

## Build and local run

Prerequisites: JDK 21, Node 24/npm and PostgreSQL. Gradle is supplied through the wrapper. Create a dedicated role/database and copy `.env.example` to `.env`; use a strong database password and keep `.env` private and untracked.

```sh
npm --prefix web ci
npm --prefix web run build
./gradlew --no-daemon test installDist

# For local HTTP, set COOKIE_SECURE=false and PUBLIC_ORIGIN=http://127.0.0.1:8088.
# For public HTTPS keep COOKIE_SECURE=true and PUBLIC_ORIGIN=https://reviq.xyz.
set -a
. ./.env
set +a
WEB_ROOT="$PWD/web/dist" build/install/reviq/bin/reviq
```

Open the configured origin. Development frontend: `npm --prefix web run dev` (Vite proxies API requests to 8088; set `PUBLIC_ORIGIN` to the Vite origin for browser writes). A multi-stage Dockerfile builds the same app and serves the frontend from the JVM; the installed host uses Apache instead. Docker is provided but has not been built on this host.

Configuration is listed in `.env.example`: PostgreSQL URL/user/password, bind host/port, public origin, secure cookie setting, OpenAI key/model, input/output cost bounds and daily budget. `WEB_ROOT` optionally serves compiled assets for local/container use. `RATE_LIMIT_MULTIPLIER` exists only for isolated test deployments and must stay unset in production.

With no provider key, the app explicitly shows **Evidence mode** and uses literal review matching/computed statistics. With a key, it performs real model analysis; provider failures are reported rather than disguised as successful AI answers. The deployed model is pinned to `gpt-4.1-2025-04-14`. Its configured standard cost bounds are $2.00/M input tokens and $8.00/M output tokens ([official model documentation](https://developers.openai.com/api/docs/models/gpt-4.1)); verify prices before changing models.

Optional `EXA_API_KEY` enables a fresh-content fallback after direct fetching is blocked or the page lacks recognizable review markup. It uses Exa's `/contents` endpoint with text extraction only, requests a fresh crawl, checks the per-URL result status, and still requires identifiable review records. Provider summaries are never used as customer review evidence. Source platform remains the original website; provenance identifies Exa when used. Exa has its own `EXA_DAILY_BUDGET_USD` (default $0.10), a 100-request daily ceiling, and a $0.002 reservation before each one-page request. Known costs reconcile afterward; uncertain usage keeps its reservation. The fallback shares the original import deadline and concurrency limit. The Exa integration requires a configured key and a successful live compatibility check before claiming support for a blocked page.

## API and safeguards

`GET /api/health`, `GET /api/session`, `GET /api/dataset`, `POST /api/ingest`, `POST /api/chat`, `DELETE /api/chat`, `DELETE /api/dataset`.

Chat uses fetch/SSE events `meta`, `delta`, `citations`, `done`, `error`. Streamed text is a draft. Final citation IDs must exist in the active dataset. Quoted excerpts must match their immediately cited original review. Case differences, whitespace runs and paired quotation-mark formatting (including model escapes) are accepted only when they resolve to an actual source substring with the same words and other punctuation; final text uses that original substring. Nested quotations and ordinary sentence punctuation are handled without accepting invented words. Invented/misattributed quotes cannot be finalized. Evidence always comes from original stored text. Valid IDs alone do not prove semantic support. Stop aborts the fetch and closes the upstream stream; interrupted or invalid answers are not saved. Dataset changes abort and invalidate old client stream events, and database writes cannot resurrect replaced datasets.

Default limits: 2 ingests/minute and 20/day; 5 chats/minute and 30/day per session and trusted source IP; one active chat per session. PostgreSQL transaction locks make reservations atomic across processes and restarts. IP quota keys contain hashes. The service trusts Apache's overwritten `X-Reviq-Client-IP` only on loopback connections.

Provider calls additionally reserve worst-case cost with a 50% margin before calling OpenAI, enforce 100 calls/day and $2/day globally, and reconcile known usage. Unknown usage retains the reservation. Database failure blocks billable calls. Provider retries are disabled. Configure independent provider-project spending limits and alerts separately; application limits do not configure the provider account.

React renders review/model text without raw HTML. HTTP/HTTPS source links require default ports and no embedded credentials. Live fetching validates public DNS addresses at the actual connector for every destination and redirect, rejects local/private/reserved addresses, and disables proxy routing. At most two fetch jobs run simultaneously. A 15-second deadline includes DNS, redirects, network and parsing; the decompressed HTML body is capped at 2 MiB, and oversized pages are rejected without partial ingestion. Only explicit review records are imported; arbitrary article or marketing text is not treated as customer reviews. No hidden pagination or login-required content is requested. Origin checks, secure cookies, request limits and Apache CSP/security headers protect the public surface. No key or database credential belongs in frontend assets, source control, exported logs or the web root.

## Host deployment and operations

The canonical host deployment is `ops/deploy.sh`. Run it after building and testing. It installs the JVM distribution under `/opt/reviq/releases/`, uses `/opt/reviq/current`, installs `reviq.service`, copies the private environment to `/etc/reviq/reviq.env`, and serves `web/dist` from `/var/www/reviq.xyz/web`. Existing site files and the virtual host are backed up under `/var/backups/reviq/<timestamp>`. Only the Reviq virtual host receives an include for `/etc/apache2/reviq-app.conf`; the current TLS certificate remains in use.

```sh
./ops/deploy.sh
sudo systemctl status reviq
sudo journalctl -u reviq --since '10 minutes ago'
curl --fail https://reviq.xyz/api/health
```

ISPConfig can regenerate virtual hosts: retain the Reviq include through its site's Apache directives if making later control-panel changes. Database backups should use `pg_dump`; service deployment does not constitute a database backup/restore test. For rollback, restore the prior frontend and virtual-host backup, point `/opt/reviq/current` to the prior release, restart Reviq, validate Apache configuration and reload Apache. Migrations must remain backward compatible.

## Validation and delivery status

The release was deployed and verified on 27–28 October 2026. No planned check is reported as passed.

Public-URL update: **71/71 backend tests passed** with no skips, including actual PostgreSQL and live Google Play checks plus synthetic Exa contract/budget/fallback coverage. **14/14 browser scenarios** and **28/28 URL-security cases** passed. A real Anker page imported five reviews, with exact evidence and direct-fetch provenance. The supplied Trustpilot URL still returned an upstream block and preserved the prior dataset. Exa has not been live-tested or enabled: a configured credential and successful source-compatibility check are still required. See `tests/backend-public-urls-2026-10-28.json` and the current public-URL section of `tests/e2e/README.md`.

Historical Google Play-only validation: **32/32 backend tests passed** (18 domain, 3 actual PostgreSQL integration, 11 scraper tests), including real Google Play captures of Spotify and WhatsApp with three visible reviews each. Tests cover strict URL/connection-address validation, redirects/blocking, decompressed body bounds, parsing, count reconciliation, deadlines and cancellation. The browser regression suite passed **14/14**, and the additional malformed-URL/SSRF boundary suite passed **22/22**, with failed imports preserving the prior dataset. The final public live-import walkthrough also passed on 28 October: both apps imported, cited AI excerpts matched the original reviews, and state survived reload. See `tests/e2e/public-scraper-2026-10-28.json`; earlier failed attempts are preserved with their fixes documented.

- Initial release: 17 Kotlin tests passed: parsing, bounds, complete-review token admission, summary reconciliation, literal evidence, safe links, session ownership, replacement/history behavior and actual PostgreSQL concurrency limits.
- 14 browser acceptance scenarios passed against a separate PostgreSQL test database in evidence mode; rerun successfully after enabling live scraping. See `tests/e2e/results-2026-10-27.json` and `tests/e2e/README.md` for the run and commands.
- `npm --prefix web run build` passed (TypeScript and Vite).
- **12/12 public HTTPS smoke checks passed** on the final release: actual Apache streaming, exact original citations, refusal, Stop after the first delta, no retention of the interrupted answer, reload persistence, session isolation and security headers/cookie properties. See `tests/e2e/public-smoke-2026-10-27.json`. Desktop/mobile layouts were visually reviewed without overflow or JavaScript errors.
- Final live-model evaluation: **21/21 passed** with `gpt-4.1-2025-04-14`, prompt `reviq-grounded-v5`. The three supported-complaint answers were also manually compared with the original reviews and contained supported excerpts. See `tests/evals/results-v5-gpt41-2026-10-27.json`; earlier failures and limitations are preserved in `tests/evals/README.md`. This is a bounded evaluation, not a guarantee for every future question.
- Live model checks are separate from evidence-mode tests. Run `python3 tests/evals/live_model.py --url http://127.0.0.1:8090` only against an isolated, configured AI test server. It makes billable calls and repeats seven scenarios three times. Reports preserve model and prompt versions, responses and failures.

For real database unit tests, point the environment at an isolated test database and run `RUN_DB_TESTS=true RATE_LIMIT_MULTIPLIER=1 ./gradlew --no-daemon test`. Never point test cleanup at customer data.

Source and release history are published at [stoneric/reviq](https://github.com/stoneric/reviq); see [RELEASE_NOTES.md](RELEASE_NOTES.md) for the implementation history. Recorded visible-session exports are in `ai-transcripts/`. Public exports omit all tool payloads, private context and credentials and are explicitly not complete unedited raw transcripts. Original local Codex records remain untouched. A Loom recording has not been completed.

Future authenticated `/app` work requires OIDC identity, verified workspace membership, a separate non-owner PostgreSQL runtime role, RLS, tenant-negative tests and retention/restore checks. No enterprise tenancy is implied by this public release.
