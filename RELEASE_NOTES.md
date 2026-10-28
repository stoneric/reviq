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
