# Browser acceptance tests

`acceptance.cjs` exercises the actual UI and backend using isolated browser contexts and synthetic review text. It requires the `playwright` package and Chromium; the application's runtime has no browser dependency.

```sh
# Install separately from application production dependencies if needed:
npm install --prefix /tmp/reviq-browser-tests playwright@1.63.0
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  CHROMIUM_PATH=/usr/bin/chromium \
  node tests/e2e/acceptance.cjs
```

Run against an isolated test database/server with generous test quotas. The full suite intentionally sends more requests than public production limits permit. Never loosen the public deployment's limits to run the suite. A production smoke check should use one dataset and at most three questions.

Results and screenshots are written to `/tmp/reviq-e2e` by default; override with `REVIQ_E2E_ARTIFACTS`. These artifacts contain only synthetic fixtures, and are not application files.

Coverage: landing and mobile layout; demo import; supplied-sample summary; original citation text; off-topic refusal and insufficient evidence; persistence after reload; CSV BOM, quoted commas and multiline text; duplicates, invalid optional fields and rejected empty text; dataset replacement and deletion; session ownership and stale dataset IDs; byte/question bounds; safe rendering and source links; unsafe URL rejection and cross-origin rejection.

A pass in evidence-search mode does **not** constitute a live-model evaluation. Live OpenAI grounding, injection resistance, usage accounting, provider failure cancellation, and streamed-draft semantic accuracy must be evaluated separately with the configured model.

## Explicit Exa cached retrieval

On 2026-10-28 at 02:05 UTC, the isolated Exa-enabled build passed the focused cached-import browser flow, all **14/14** browser regressions in evidence mode, and **31/31** URL/cache security cases. The security additions reject a nonboolean cache option, cached mode on CSV, and a cached request targeting loopback before provider retrieval. Reports: [`exa-cache-browser-2026-10-28.json`](exa-cache-browser-2026-10-28.json), [`results-exa-cache-2026-10-28.json`](results-exa-cache-2026-10-28.json), and [`scraper-security-exa-cache-2026-10-28.json`](scraper-security-exa-cache-2026-10-28.json).

Fresh retrieval of the supplied Trustpilot Allen Brothers URL returned `422 exa_fresh_blocked` and preserved the active dataset. Explicit cached retrieval then imported **one review** through Exa. The browser verified exact evidence, the visible cached/unknown-age warning, `captured_at: null`, a separate current `retrieved_at`, persistence, and mobile layout. This is a successful cached import, **not a successful fresh Trustpilot fetch**. The focused test made two URL requests and zero chat requests; customer review text and authors stayed in memory.

The deployed HTTPS check passed on **2026-10-28 at 02:11 UTC**. Explicit cached retrieval imported one Allen Brothers review via Exa; the subsequent fresh request returned `422 exa_fresh_blocked` and preserved that cached dataset. Unknown capture date, separate retrieval time, visible warning, exact evidence, reload persistence, and mobile layout all passed. This run used exactly two URL imports, no synthetic seed, and no chat requests. See [`exa-cache-hosted-2026-10-28.json`](exa-cache-hosted-2026-10-28.json).

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  node tests/e2e/exa-cache-smoke.cjs
```

`exa-cache-smoke.cjs` requires Exa to be configured and can incur provider usage. Its default isolated run creates one synthetic baseline, checks fresh-failure preservation, then imports the explicitly selected cached snapshot. For a coordinated public check, set `REVIQ_HOSTED_SMOKE=1` and the HTTPS base URL: it imports the cached snapshot first, then checks that a fresh attempt fails without replacing it, using exactly two URL imports and no seed. It verifies the checkbox starts unchecked and resets on URL changes, then deletes only its own session dataset. Reports default to `/tmp/reviq-exa-cache-smoke`; override with `REVIQ_E2E_ARTIFACTS`.

## Historical direct-fetch public URL verification

The current frontend accepts HTTP/HTTPS review URLs on their default ports without a platform, path, or query whitelist. Embedded credentials and custom ports remain rejected; the backend checks public network destinations and redirects. A valid public URL does not guarantee that a site exposes accessible, identifiable reviews.

On 2026-10-28, before Exa was enabled, the isolated PostgreSQL/server run passed:

- **14/14** full browser acceptance scenarios: [`results-public-urls-2026-10-28.json`](results-public-urls-2026-10-28.json).
- **28/28** URL security cases, including forbidden schemes, credentials, custom ports, localhost aliases, IPv4/IPv6 private, loopback, link-local, unspecified, and reserved destinations: [`scraper-security-public-urls-2026-10-28.json`](scraper-security-public-urls-2026-10-28.json). Every rejected request preserved the active dataset. The current suite has **31** cases, including the three cache-mode checks documented above.
- The public review URL browser flow, with **zero model calls**: [`review-url-browser-2026-10-28.json`](review-url-browser-2026-10-28.json). The supplied Trustpilot Allen Brothers URL reached the server, but the source blocked the fetch. The app reported `scrape_blocked`, retained the prior dataset, and offered CSV/paste recovery. The Anker product page successfully supplied five reviews, with exact evidence, safe per-review source links, capture/provenance metadata, and reload persistence. The final provider-disabled build also verifies `retrieval_provider=direct` against the visible **Direct page fetch** provenance row.

The Trustpilot outcome is a verified recovery path, **not a successful live Trustpilot import**. Neither these tests nor the UI claim that every review site can be scraped. Reports contain only source metadata and counts; customer review bodies and author names are not saved as public test artifacts.

The deployed direct-fetch HTTPS smoke passed on **2026-10-28 at 00:46 UTC**: exactly two URL import requests, no seed import, and no chat requests. Anker supplied five dated reviews through direct retrieval; the API and visible provenance agreed, and the dates reconciled with the summary. The subsequent Trustpilot request returned `422 scrape_blocked`, preserved the Anker dataset through reload, and displayed CSV/paste alternatives. Exa was disabled for that historical run; the later configured Exa/cache result is documented above. See [`review-url-hosted-2026-10-28.json`](review-url-hosted-2026-10-28.json).

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  node tests/e2e/scraper-security.cjs

NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  REVIQ_GENERIC_REVIEW_URL=https://www.anker.com/products/a2663 \
  REVIQ_REQUIRE_GENERIC=1 \
  node tests/e2e/public-review-url-smoke.cjs
```

`public-review-url-smoke.cjs` always checks the supplied Trustpilot URL and optionally a generic source. `REVIQ_REQUIRE_TRUSTPILOT=1` or `REVIQ_REQUIRE_GENERIC=1` makes actual ingestion success mandatory for that source; otherwise an explicit source-unavailable response must preserve data and show recovery controls. It seeds one synthetic baseline dataset, so run it on an isolated high-quota server unless coordinating production quota windows. The default artifact directory is `/tmp/reviq-public-review-urls`.

For a coordinated hosted smoke within the two-import limit, set `REVIQ_HOSTED_SMOKE=1`. This requires a generic URL, imports it successfully first, then tries Trustpilot with no synthetic seed. If Trustpilot is unavailable, the generic dataset must remain intact. The script asserts exactly two URL import requests, no chat requests, and deletes only its own session dataset afterward. Wait for any other imports sharing the public IP quota to clear before running it.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=https://reviq.xyz \
  REVIQ_HOSTED_SMOKE=1 \
  REVIQ_GENERIC_REVIEW_URL=https://www.anker.com/products/a2663 \
  REVIQ_EXPECT_GENERIC_PROVIDER=direct \
  node tests/e2e/public-review-url-smoke.cjs
```

The following command conditionally requires **fresh** Trustpilot retrieval through Exa. It is intended for a future run when source access is available and currently fails for the supplied URL with `exa_fresh_blocked`; configuring Exa alone does not make that source accessible. It never selects cached retrieval. Use `exa-cache-smoke.cjs` above for the verified explicit-cache workflow.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  REVIQ_REQUIRE_TRUSTPILOT=1 \
  REVIQ_EXPECT_RETRIEVAL_PROVIDER=exa \
  node tests/e2e/public-review-url-smoke.cjs
```

`REVIQ_EXPECT_RETRIEVAL_PROVIDER` applies to Trustpilot; optional `REVIQ_EXPECT_GENERIC_PROVIDER` applies to the generic URL. Either provider expectation also requires a successful import. The test checks the API field and the visible **Retrieved via** provenance row while the source platform stays Trustpilot (or the generic source). Missing provider fields on older datasets are interpreted as direct retrieval. With no provider-expectation flags, the default provider-disabled/recovery suite remains valid. These flags do not configure Exa or supply credentials; a configured Exa fetch can incur provider usage even though this test makes zero chat requests.

## Historical initial release verification

Historical initial result: 2026-10-27 23:26 UTC, local PostgreSQL test database, evidence-search mode, 14 scenarios passed / 0 failed. The recorded JSON report is [`results-2026-10-27.json`](results-2026-10-27.json). New runs write `/tmp/reviq-e2e/results.json`. Live OpenAI evaluation is a separate report.

For the deployed Apache host, `public-smoke.cjs` uses one synthetic import and three chat requests (the third is stopped after its first text delta). It checks secure session cookies and headers, original citation text, scope refusal, completed history after reload, cancellation without retaining the partial exchange, session isolation, and JSON API 404s. It deletes only its own test dataset when finished.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=https://reviq.xyz \
  node tests/e2e/public-smoke.cjs
```

This smoke requires AI chat to be enabled and uses the public quotas and a small amount of the configured model budget. Its results default to `/tmp/reviq-public-smoke/results.json`.

Public smoke passed on 2026-10-27 at 23:39 UTC against `https://reviq.xyz`, using `gpt-4.1-2025-04-14`. All 12 recorded checks passed, including stopping an active response after its first delta and verifying the partial exchange was absent after reload. See [`public-smoke-2026-10-27.json`](public-smoke-2026-10-27.json).

## Historical Google Play-only release verification

After enabling Google Play imports, the full browser suite was rerun against the isolated test instance: **14 passed, 0 failed**. See [`results-scraping-2026-10-27.json`](results-scraping-2026-10-27.json).

The Google Play-only release tested 22 malformed or unsupported URLs, including other platforms, fragments, and alternate app paths. Those platform/path restrictions were removed by the public-URL release; the current `scraper-security.cjs` checks 31 URL/cache safety cases. It also verifies rejected imports preserve the current dataset. All 22 historical cases passed; see [`scraper-security-2026-10-27.json`](scraper-security-2026-10-27.json). These are API boundary checks; backend parser/transport unit tests cover redirects, body bounds, deadlines and connection-target validation.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=http://127.0.0.1:8089 \
  node tests/e2e/scraper-security.cjs
```

`public-scraper-smoke.cjs` uses exactly two real Google Play imports (Spotify and WhatsApp), then one model question. It verifies fresh capture timestamps, canonical source URLs, bounded-page provenance, original evidence, quote support, dataset replacement, and persistence. Live review bodies, author names, model answers and downloaded HTML are never written to its public report or committed as fixtures. They remain in memory by default. Explicit `REVIQ_PRIVATE_DIAGNOSTICS=1` may save a failure-only diagnostic file with mode0600, restricted to an operating-system temporary directory outside this checkout. Reports contain metadata and counts only. The test deletes only its own session dataset afterward.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=https://reviq.xyz \
  node tests/e2e/public-scraper-smoke.cjs
```

The deployed live scraper flow passed on **2026-10-28 at 00:10 UTC**: Spotify and WhatsApp each supplied three visible reviews from one fetched/parsed page, followed by one verified AI answer with three supporting review IDs and six source-matching quote spans. Reload persistence, exact evidence, source provenance, and unsupported-platform recovery passed. See [`public-scraper-2026-10-28.json`](public-scraper-2026-10-28.json).

Earlier live attempts are retained as metadata-only failure reports (`public-scraper-attempt1/2/3-2026-10-27.json`). They correctly withheld answers that failed quote verification. Private diagnostics identified nested quotation delimiters, model-escaped inner quotes, and quotation-presentation differences. The validator now restores exact source wording only after source-backed matching, with regressions that continue to reject changed words, wrong sources, and uncited extra text. The final test used the original question; it did not bypass validation or change the success criteria. Private captured review text and draft responses remain outside this repository.
