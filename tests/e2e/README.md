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

Coverage: landing and mobile layout; demo import; supplied-sample summary; original citation text; off-topic refusal and insufficient evidence; persistence after reload; CSV BOM, quoted commas and multiline text; duplicates, invalid optional fields and rejected empty text; dataset replacement and deletion; session ownership and stale dataset IDs; byte/question bounds; safe rendering and source links; unavailable live scraping and cross-origin rejection.

A pass in evidence-search mode does **not** constitute a live-model evaluation. Live OpenAI grounding, injection resistance, usage accounting, provider failure cancellation, and streamed-draft semantic accuracy must be evaluated separately with the configured model.

Last executed result: 2026-10-27 23:26 UTC, local PostgreSQL test database, evidence-search mode, 14 scenarios passed / 0 failed. The recorded JSON report is [`results-2026-10-27.json`](results-2026-10-27.json). New runs write `/tmp/reviq-e2e/results.json`. Live OpenAI evaluation is a separate report.

For the deployed Apache host, `public-smoke.cjs` uses one synthetic import and three chat requests (the third is stopped after its first text delta). It checks secure session cookies and headers, original citation text, scope refusal, completed history after reload, cancellation without retaining the partial exchange, session isolation, and JSON API 404s. It deletes only its own test dataset when finished.

```sh
NODE_PATH=/tmp/reviq-browser-tests/node_modules \
  REVIQ_BASE_URL=https://reviq.xyz \
  node tests/e2e/public-smoke.cjs
```

This smoke requires AI chat to be enabled and uses the public quotas and a small amount of the configured model budget. Its results default to `/tmp/reviq-public-smoke/results.json`.

Public smoke passed on 2026-10-27 at 23:39 UTC against `https://reviq.xyz`, using `gpt-4.1-2025-04-14`. All 12 recorded checks passed, including stopping an active response after its first delta and verifying the partial exchange was absent after reload. See [`public-smoke-2026-10-27.json`](public-smoke-2026-10-27.json).

## Live scraper acceptance

After enabling Google Play imports, the full browser suite was rerun against the isolated test instance: **14 passed, 0 failed**. See [`results-scraping-2026-10-27.json`](results-scraping-2026-10-27.json).

`scraper-security.cjs` tests the HTTP URL-validation boundary against 22 malformed, private-address, non-HTTPS, credential-bearing, alternate-host/path/port, fragment, duplicate-parameter, and invalid-app-ID inputs. It also verifies rejected imports preserve the current dataset. All 22 cases passed; see [`scraper-security-2026-10-27.json`](scraper-security-2026-10-27.json). These are API boundary checks; backend parser/transport unit tests cover redirects, body bounds, deadlines and connection-target validation.

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
