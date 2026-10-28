# Reviq 1.3.1 — 200-review Google Play limit

Release date: 28 October 2026. Live application: https://reviq.xyz/.

Fresh Google Play imports now request up to **200 review records in batches of 100**, increased from 100 records in batches of 50. The interface reads the collection settings from the API, and provenance shows both the requested limit and batch size. Earlier datasets retain the settings recorded when they were imported; re-import a URL to use the new limit.

The existing maximum of 200 admitted reviews and 24,000 dataset tokens remains in force. The collector stops on source exhaustion, the record target or its page limit. Duplicate text and invalid or over-budget records remain explicitly counted, so the number accepted can be lower than the number fetched. The 15-second deadline, five-batch cap, cumulative 2 MiB response limit and atomic failure behavior are unchanged.

## Implementation and validation

- Centralized the target and batch size for the collector, API capabilities and dataset provenance, then updated the interface and active browser checks.
- Expanded tests for two 100-record batches, source exhaustion after one batch, overlapping source IDs, remaining-target requests, oversized responses, and admission of R200 into the analysis input.
- All **108 backend tests passed with no skips**, including actual PostgreSQL and live Google Play checks. The frontend build passed.
- The live backend check collected **200 Spotify records in two batches**, accepting **197** after removing three repeated texts. OnePageCRM still supplied **83** available written reviews in this English/US selection, now in one batch; all 83 were accepted.

The two focused isolated browser checks passed with those exact counts. They verified API settings, visible batch size and requested count, source evidence through the final accepted review, reload and mobile layout. Each flow used one URL import and no model calls; broader browser/security results from v1.3.0 remain historical.

The final hosted checks passed at **02:53 UTC** with exactly two imports and zero chat requests: OnePageCRM 83 fetched/83 accepted, Spotify 200 fetched/197 accepted. Both reported target 200 and batch size 100; exact evidence, reload and mobile access passed. Reports: `tests/e2e/google-play-200-onepagecrm-hosted-2026-10-28.json` and `tests/e2e/google-play-200-spotify-hosted-2026-10-28.json`.

---

# Reviq 1.3.0 — Google Play review pagination

Release date: 28 October 2026. Live application: https://reviq.xyz/.

## What changed

Fresh Google Play URL imports now request up to **100 review records**, newest first, using the public English/US review feed. Previously the importer only extracted the three preview reviews displayed on an app page. It now retrieves additional records in batches of at most 50 and shows the requested count, review-batch count and stopping reason.

For the requested `https://play.google.com/store/apps/details?id=com.onepagecrm`, Google Play returned **83 written reviews** in two batches (50 + 33), then no continuation. Reviq accepted all 83. This is the available sample for that selection; it does not assert that the app has only 83 ratings or reviews worldwide. A separate Spotify check retrieved 100 records and accepted 99 after removing one repeated text.

## Implementation history

1. Probed the public website's current review request and verified that a 100-record request and a 50 + 33 paginated request return the same 83 OnePageCRM review IDs. All 83 texts were unique after normalization. The direct request uses no Exa credits or model calls.
2. Implemented a fixed-host review client and strict response parser. The app name requires canonical and SoftwareApplication metadata matching the requested package before reviews are fetched. Original review text is preserved, developer replies are excluded, timestamps become UTC dates, and continuation values are treated only as opaque request data. The [maintainer's request implementation](https://github.com/JoMingyu/google-play-scraper/blob/master/google_play_scraper/constants/request.py) provided the protocol reference; the live response verified it.
3. Added collection limits and atomic failure handling: up to five review batches, a cumulative 2 MiB review-response limit and the existing 15-second deadline. Repeated source IDs are ignored; text deduplication and the 24,000-token admission bound still apply. A failed or stalled later page preserves the user's prior dataset instead of silently replacing it with the three-card preview. An empty terminal page is a valid end to pagination.
4. Updated import guidance and provenance for paginated Google Play samples. Other-site and explicit cached-snapshot imports retain their existing behavior. Real review bodies, author names and raw responses remain outside committed artifacts.

## Validation

All **106 backend tests passed with no skips**, including actual PostgreSQL checks, real OnePageCRM and Spotify paging, UTF-8 response framing, exact text, reply exclusion, source validation, cursor handling, byte limits, cancellation, exhaustion and failed later pages. The frontend build passed. All **14 browser regression scenarios** and **31 URL/cache security cases** passed.

The isolated OnePageCRM browser flow imported **83/83 records**, with no duplicates, rejected records or exclusions and 7,103 dataset tokens. It verified three fetched/parsed responses (one metadata page and two review batches), exact evidence from the first/middle/final reviews, access to the final review after reload and on mobile, and zero chat requests.

The deployed HTTPS walkthrough passed at **02:36 UTC** with exactly one URL import and zero chat requests: 100 requested, 83 observed and accepted, three fetched/parsed responses and two review batches. Exact source evidence, final-review access, reload and mobile layout passed. See `tests/e2e/google-play-paging-hosted-2026-10-28.json`.

The consumer Google Play review endpoint is not a stable official API and may change. Requests target 100 records; source availability, validation, duplicate-text removal and token limits determine the number admitted. The app reports those counts rather than filling missing reviews.

---

# Reviq 1.2.0 — Exa retrieval and explicit cached snapshots

Release date: 28 October 2026. Live application: https://reviq.xyz/.

## What changed

Exa is configured privately as Reviq's optional retrieval provider. URL imports try direct public access, then request fresh content through Exa if needed. Fresh retrieval still fails for the supplied Allen Brothers Trustpilot page: Exa reports an upstream HTTP 403.

An explicit **Use cached snapshot (may be outdated)** option now allows importing Exa's existing copy. The tested Allen Brothers snapshot contains one review. It is not a current or complete review history. Cached datasets show a prominent warning, `retrieval_freshness: cached`, `captured_at: null`, and a separate retrieval timestamp. The choice defaults off and resets when the URL changes. Fresh failures never automatically switch to cached data.

## Implementation history

1. Verified the supplied credential and probed the real Exa Contents API. Fresh retrieval failed; cache-only retrieval succeeded. The provider supplies no original crawl timestamp.
2. Added strict parsing for provider-extracted Trustpilot HTML using review-rating, title, body and date boundaries. Unrelated company recommendations, marketing text, replies and footer content are excluded. Original decoded review text is retained; ambiguous author text stays missing. Dates are explicitly labeled as dates of experience.
3. Added explicit cache selection across the API and interface, with distinct freshness checks and provenance. Source URL validation, the 15-second deadline, size/review limits and separate Exa budget remain enforced.
4. Tested the actual cached response against its original text and verified the browser import, source evidence, warnings, timestamps and persistence. Credentials and real review bodies are excluded from committed test artifacts.

## Validation

All **82 backend tests passed**, including PostgreSQL budget concurrency, fresh/cache response contracts, explicit cache selection, source matching, parser boundaries and existing live Google Play checks. All **14 browser regression scenarios** and **31 URL/cache security cases** passed. The frontend build passed. The private real-response parser replay matched the complete 427-character review exactly. The focused browser flow used the actual Exa service and verified one cached review, a failed fresh request that preserved prior data, visible freshness warnings, reload and mobile layout.

The deployed HTTPS check passed at **02:11 UTC**: exactly two URL imports and zero chat requests. Explicit cached retrieval succeeded; the subsequent fresh request failed with `exa_fresh_blocked` and preserved the cached dataset. Exact evidence, the visible unknown-age notice, timestamps, reload and mobile layout passed. See `tests/e2e/exa-cache-hosted-2026-10-28.json`.

Fresh Trustpilot scraping remains unavailable through the current providers. Cached retrieval is a separate, clearly labeled user choice; other sources can also fail when neither accessible review markup nor a usable cached copy is available.

---

# Reviq 1.1.0 — public review URL imports

Release date: 28 October 2026. Live application: https://reviq.xyz/.

## What changed

URL imports now accept public HTTP and HTTPS review pages across domains. Trustpilot and Google Play have dedicated parsers; other pages can expose reviews through schema.org JSON-LD, microdata, WooCommerce, Judge.me or Amazon-style review cards. Original review text, source URLs, capture times and explicit sample limits carry through to the existing PostgreSQL workspace and cited analysis.

The URL field no longer restricts users to Google Play app links. It accepts ordinary query parameters and section anchors, follows up to three validated redirects, and records the final source URL. Every destination is checked for public network addresses at connection time. The existing concurrency, deadline, body-size and review/token limits remain enforced. Failed imports preserve the prior dataset.

## Implementation history

1. Generalized URL validation and fetching while preserving the tested Google Play adapter.
2. Added explicit review-record extraction for Trustpilot, schema.org and common review widgets. Unrelated article text and ambiguous multi-product review groups are not imported as customer reviews.
3. Generalized product/business metadata, source platform, source links and provenance warnings.
4. Updated the URL form, help text and recovery flow; added parser, redirect, DNS and browser regression coverage.
5. Prepared an optional Exa Contents fallback for blocked or unreadable pages. It requests fresh text only, validates source identity and per-URL status, records provider provenance, and shares the original import deadline. A separate atomic PostgreSQL budget defaults to $0.10/day with at most 100 calls/day. Credentials are not configured and live Exa compatibility has not been verified; the adapter is inactive until enabled with a server-side key.

## Validation

The final build passed **71/71 backend tests** with no skips, including actual PostgreSQL concurrency/budget tests, real Google Play captures, arbitrary-URL/redirect/DNS checks, parser fixtures, and Exa contract/fallback checks. Exa tests use synthetic provider responses and make no live Exa calls. The frontend build passed.

The isolated browser suite passed **14/14** scenarios and the expanded URL-security suite passed **28/28** cases. A real Anker product page supplied five reviews with exact source text, source links, fresh capture metadata, provider provenance and reload persistence. The supplied Trustpilot page exercised a blocked-source recovery path and preserved the prior dataset; it was not counted as a successful import. These checks made no model calls. Dated reports are in `tests/`.

The final public HTTPS walkthrough also passed with exactly two URL imports: Anker succeeded, then the blocked Trustpilot request preserved that dataset. Exact evidence, dates, direct-fetch provenance and reload were verified. See `tests/e2e/review-url-hosted-2026-10-28.json`.

## Source-access limitation

The requested `https://www.trustpilot.com/review/allenbrothers.com` page returned HTTP 403 from this server in both direct HTTP and ordinary Chromium checks. The Trustpilot parser is implemented, but this URL is not claimed as a successful live import. It needs an authorized data-access provider or an export. No proxy service or provider credential is configured by this release.

A public URL is accepted for an import attempt; that does not make every website fetchable or its markup recognizable. One review page is sampled. JavaScript-only pages, access challenges and login-required content may need CSV or pasted reviews. Arbitrary page prose is never substituted for review evidence.

---

# Reviq 1.0.0 — review intelligence with traceable evidence

Implementation history: 27–28 October 2026. Live application: https://reviq.xyz/. Source: https://github.com/stoneric/reviq.

## What this release delivers

Reviq turns a bounded review sample into an inspectable dataset and a grounded question-and-answer workspace. Visitors can import reviews, check coverage and missing fields, ask questions, and open citations to the original source text. The application is public and requires no login.

The interface follows the supplied ReviewLens reference: a white-and-green import screen, review summary beside chat, responsive mobile layout, and an evidence drawer. It carries Reviq branding and uses the actual backend rather than simulated analysis.

## Implementation history

1. **Read the plan and inspected the host.** Started from the supplied `Reviq_Implementation_Plan.pdf` and a repository containing only a README. Reviewed the reference design, existing Apache virtual host and local PostgreSQL service. Kept Kotlin/Vert.x and React/TypeScript from the plan; used the requested Apache host and local PostgreSQL instead of the plan's initial container-host/browser-only-storage proposal.
2. **Built ingestion and data quality reporting.** Implemented CSV parsing with quoted commas, multiline fields and BOM support; blank-line-separated pasted reviews; and an explicitly synthetic example. Added import preview, stable review IDs, conservative duplicate-text removal, complete-review admission limits, rating distribution, valid-date coverage, missing fields, count reconciliation and provenance.
3. **Added isolated server persistence.** Created a dedicated PostgreSQL role/database and tables for anonymous sessions, datasets, reviews, completed conversations, rate counters and cost reservations. Immutable server-owned datasets replace editable browser-held envelopes. Replacement and deletion clear old reviews and conversation; records expire after 24 hours. These sessions are not authenticated customer tenants.
4. **Integrated real AI analysis.** Loaded the user-authorized API credential into private server configuration. Added the official OpenAI Java SDK Responses integration, streamed draft answers, Stop/cancellation, exact original evidence, scoped refusals, input/output token limits and conservative cost controls. No key is shipped in frontend assets or source control.
5. **Iterated from measured failures.** Initial live evaluations exposed grouped citation parsing, inconsistent refusal wording, and confusion between missing evidence and another-platform requests. Manual review then caught an altered defect description and incorrect attribution that valid citation IDs alone could not detect. Preserved those failed evaluation reports, strengthened the server-owned prompt, removed previous assistant prose from evidence context, and added runtime verification that quoted excerpts occur in their immediately cited original review. The final analysis configuration uses `gpt-4.1-2025-04-14` and prompt `reviq-grounded-v5`.
6. **Deployed and verified the hosted app.** Apache serves the built frontend and proxies `/api/` to a dedicated systemd service on loopback. Existing TLS remains in use. Added HTTPS redirection, security headers, private configuration permissions, atomic quotas, deadline/cancellation handling and deployment backups. Verified public streaming, original evidence, refusal, persistence, session isolation and Stop through the real HTTPS host.
7. **Enabled live Google Play imports.** After the initial release used CSV/paste because the first platform probes failed, a fresh spike found readable Google Play app pages. Added one bounded public-page adapter with strict URL validation, public-destination DNS checks at connection time, redirect refusal, a body-size limit and a total deadline. The UI now accepts supported app URLs and reports capture time, attempted/fetched/parsed pages and the visible-page coverage boundary. Blocked, missing or changed pages return actionable errors; they never replay the synthetic fixture as a scrape. Testing real imported reviews also exposed embedded quotation marks that the initial excerpt validator rejected; added nested-quotation parsing, handling for model-escaped inner quotes, and regression coverage while preserving exact-source validation.
8. **Prepared GitHub delivery.** Consolidated active deployment tooling under `ops/`, archived an unused host-deployment draft, documented setup and operations, retained test/evaluation history, and prepared sanitized recorded-session exports. Public transcript exports omit tool payloads and private context and are clearly labeled as filtered; original local session logs are untouched.

## Live scraping scope

Supported URLs have this form:

```text
https://play.google.com/store/apps/details?id=com.spotify.music
```

The importer requests the public English/US app page and extracts the review cards that page actually contains. This is usually a small sample. It does not claim to fetch every review, fetch logged-in content, or paginate through hidden review endpoints. Ratings and date coverage describe only the accepted sample, not the store's global aggregate rating. App identity and platform are derived from the fetched source; capture time is recorded for each successful import.

Trustpilot and other platforms remain unsupported. CSV and paste remain available for broader exports. A platform layout change or blocking response can interrupt live imports without affecting saved review inspection.

## Validation

The initial hosted implementation passed 17 Kotlin tests, 14 deterministic browser scenarios, 21 final live-model checks (seven cases repeated three times), and 12 public HTTPS smoke checks. Earlier unsuccessful model evaluations remain in `tests/evals/` rather than being relabeled as successes. The final cited complaint excerpts were manually compared with their source reviews.

The live-scraping build passed **32/32 backend tests** (18 domain, 3 PostgreSQL integration and 11 scraper tests), **14/14 browser regression scenarios**, and **22/22 malformed-URL/SSRF boundary checks**. Actual Spotify and WhatsApp app-page imports each returned three visible reviews with capture times and page counts. The final public walkthrough on 28 October passed live imports, cited AI excerpts, original evidence and reload persistence. Earlier failed attempts are retained alongside the successful report. Exact results and dated reports are recorded in the repository README and `tests/`. A passing citation/quotation check proves source identity and excerpt matching; it is not a universal semantic guarantee for every future question.

## Operational boundaries

- Import limits: 1 MiB CSV/paste, at most 1,000 observed records, 200 accepted reviews and 24,000 dataset tokens. Complete reviews are excluded instead of silently truncated.
- Analysis limits: 500-character questions, 30,000 input tokens and 1,000 output tokens; one active chat per session.
- Default quotas: 2 imports/minute and 20/day, 5 chats/minute and 30/day per session and trusted source IP; 100 provider calls/day and a $2/day application ceiling, with reservations before provider calls.
- Anonymous sessions expire after 24 hours. OIDC login, workspaces, verified tenant membership, tenant RLS, billing and scheduled crawling are outside this release.
- The Dockerfile and CI workflow are supplied; a container build, database restore drill and Loom recording have not been completed on this host.

See [README.md](README.md) for setup, deployment, test commands and remaining limitations.
