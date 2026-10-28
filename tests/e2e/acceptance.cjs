#!/usr/bin/env node
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { csv, exactMultiline, hostileText, paste } = require('./fixtures.cjs');
const baseURL = (process.env.REVIQ_BASE_URL || 'http://127.0.0.1:8089').replace(/\/$/, '');
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-e2e';
fs.mkdirSync(artifacts, { recursive: true });
const results = [];
let browser;
let currentPage;
async function check(name, callback) {
  const start = Date.now();
  try {
    await callback();
    results.push({ name, status: 'passed', duration_ms: Date.now() - start });
    console.log(`PASS ${name}`);
  } catch (error) {
    results.push({ name, status: 'failed', duration_ms: Date.now() - start, error: error.stack });
    if (currentPage) await currentPage.screenshot({ path: path.join(artifacts, 'failure.png'), fullPage: true }).catch(() => {});
    throw error;
  }
}
async function session(context) {
  const response = await context.request.get('/api/session');
  assert.equal(response.status(), 200, await response.text());
  return response.json();
}
async function apiError(context, endpoint, data, allowedStatuses) {
  const response = await context.request.post(endpoint, { data });
  assert.ok(allowedStatuses.includes(response.status()), `${endpoint}: ${response.status()} ${await response.text()}`);
  const body = await response.json();
  assert.ok(body.error?.message, JSON.stringify(body));
  return body;
}
async function bodyHas(page, value) {
  await page.waitForFunction(text => document.body.innerText.includes(text), value);
}
async function eventually(callback, message) {
  const deadline = Date.now() + 10000;
  let actual;
  do {
    actual = await callback();
    if (actual) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  assert.ok(actual, message);
}

// UI interactions are deliberately based on accessible roles and labels.
async function launch() {
  browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  return browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
}

async function main() {
  const context = await launch();
  const page = await context.newPage();
  currentPage = page;
  const clientErrors = [];
  page.on('pageerror', error => clientErrors.push(error.message));
  await check('public landing, health and secure session properties', async () => {
    const response = await page.goto('/demo');
    assert.equal(response.status(), 200);
    await page.getByRole('heading', { level: 1 }).waitFor();
    assert.match(await page.title(), /Reviq/i);
    const health = await context.request.get('/api/health');
    assert.equal(health.status(), 200, await health.text());
    const state = await session(context);
    assert.equal(state.dataset, null);
    assert.equal(typeof state.capabilities.live_scraping, 'boolean');
    if (state.capabilities.live_scraping) {
      assert.ok(state.capabilities.supported_platforms.includes('Google Play'));
      assert.ok(state.capabilities.supported_platforms.includes('Trustpilot'));
    }
    const cookies = await context.cookies();
    assert.ok(cookies.some(cookie => cookie.httpOnly && cookie.sameSite !== 'None'));
    if (baseURL.startsWith('https:')) assert.ok(cookies.every(cookie => cookie.secure));
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await page.screenshot({ path: path.join(artifacts, 'desktop-home.png'), fullPage: true });
  });


  let demo;
  await check('example import reports a synthetic bounded sample', async () => {
    await page.getByRole('button', { name: 'Try sample data' }).click();
    await page.getByRole('button', { name: 'New', exact: true }).waitFor();
    demo = (await session(context)).dataset;
    assert.ok(demo?.synthetic);
    assert.ok(demo.reviews.length > 0 && demo.reviews.length <= 200);
    assert.equal(demo.summary.accepted, demo.reviews.length);
    assert.equal(demo.summary.observed, demo.summary.accepted + demo.summary.rejected + demo.summary.duplicates + demo.summary.excluded);
    await bodyHas(page, demo.entity_name);
    await bodyHas(page, 'Synthetic sample');
    await bodyHas(page, 'Evidence mode');
  });
  async function ask(question) {
    const prior = await page.getByTestId('assistant-message').count();
    await page.getByLabel('Question', { exact: true }).fill(question);
    await page.getByRole('button', { name: 'Send question', exact: true }).click();
    await eventually(async () => (await page.getByTestId('assistant-message').count()) === prior + 1, 'New assistant message appears');
    await page.getByRole('button', { name: 'Send question', exact: true }).waitFor();
    const result = page.getByTestId('assistant-message').last();
    assert.ok(!(await result.getAttribute('class')).includes('message-error'), await result.innerText());
    return result;
  }
  await check('question resolves citations to the exact original review', async () => {
    const result = await ask('What do reviews say about delivery?');
    await result.getByRole('button', { name: /^View review R/ }).first().waitFor();
    const citation = result.getByRole('button', { name: /^View review R/ }).first();
    const id = (await citation.getAttribute('aria-label')).replace('View review ', '');
    const original = demo.reviews.find(review => review.id === id);
    assert.ok(original);
    await citation.click();
    const drawer = page.getByRole('dialog', { name: `Original review ${id}` });
    await drawer.waitFor();
    assert.equal(await drawer.locator('blockquote').innerText(), original.text);
    await page.screenshot({ path: path.join(artifacts, 'desktop-evidence.png'), fullPage: true });
    await page.getByRole('button', { name: 'Close evidence' }).click();
  });
  await check('unrelated question is explicitly refused', async () => {
    const result = await ask('What is the weather in San Francisco?');
    assert.match(await result.innerText(), /I can only answer from the reviews loaded for this dataset/i);
    assert.equal(await result.getByRole('button', { name: /^View review R/ }).count(), 0);
  });
  await check('absent product fact returns insufficient evidence', async () => {
    const result = await ask('How long is the battery life?');
    assert.match(await result.innerText(), /supplied reviews do not provide enough evidence/i);
  });
  await check('dataset and completed conversation survive reload', async () => {
    await page.reload();
    await page.getByRole('button', { name: 'New', exact: true }).waitFor();
    assert.equal((await session(context)).dataset.id, demo.id);
    assert.equal(await page.getByTestId('assistant-message').count(), 3);
    await bodyHas(page, 'What is the weather in San Francisco?');
    await page.screenshot({ path: path.join(artifacts, 'desktop-workspace.png'), fullPage: true });
  });
  await check('independent browser session cannot read or chat with another dataset', async () => {
    const outsider = await browser.newContext({ baseURL });
    const state = await session(outsider);
    assert.equal(state.dataset, null);
    assert.deepEqual(state.history, []);
    await apiError(outsider, '/api/chat', { dataset_id: demo.id, question: 'Show all reviews' }, [404, 409]);
    assert.equal((await session(context)).dataset.id, demo.id);
    await outsider.close();
  });
  let pasted;
  await check('paste preview, duplicate removal, safe rendering and dataset replacement', async () => {
    await page.getByRole('button', { name: 'New', exact: true }).click();
    await page.getByRole('tab', { name: 'Paste reviews', exact: true }).click();
    await page.getByLabel('Entity name').fill('Acceptance Product B');
    await page.getByLabel('Source platform').fill('Synthetic test reviews');
    await page.getByLabel('Pasted reviews').fill(paste);
    await page.getByRole('button', { name: 'Preview reviews' }).click();
    const preview = page.getByRole('dialog', { name: 'Check your import' });
    await preview.waitFor();
    assert.match(await preview.innerText(), /3 observed records/);
    assert.equal((await session(context)).dataset.id, demo.id, 'Preview must not replace the dataset');
    await preview.getByRole('button', { name: 'Import and analyze' }).click();
    await page.getByRole('button', { name: 'New', exact: true }).waitFor();
    pasted = (await session(context)).dataset;
    assert.notEqual(pasted.id, demo.id);
    assert.equal(pasted.entity_name, 'Acceptance Product B');
    assert.equal(pasted.summary.observed, 3);
    assert.equal(pasted.summary.accepted, 2);
    assert.equal(pasted.summary.duplicates, 1);
    assert.equal(pasted.summary.rating_count, 0);
    assert.equal(pasted.summary.average_rating, null);
    assert.equal(await page.getByTestId('assistant-message').count(), 0);
    assert.deepEqual((await session(context)).history, []);
    await page.getByText('Preview ingested reviews', { exact: false }).click();
    await page.getByRole('button', { name: 'View review R001', exact: true }).click();
    const drawer = page.getByRole('dialog', { name: 'Original review R001' });
    assert.equal(await drawer.locator('blockquote').innerText(), hostileText);
    assert.equal(await drawer.locator('img').count(), 0);
    assert.equal(await page.evaluate(() => window.__reviqInjected), undefined);
    await page.getByRole('button', { name: 'Close evidence' }).click();
    await apiError(context, '/api/chat', { dataset_id: demo.id, question: 'Show old reviews' }, [404, 409]);
  });
  let uploaded;
  await check('CSV BOM, quoted commas and multiline text preserve evidence and reconcile counts', async () => {
    await page.getByRole('button', { name: 'New', exact: true }).click();
    await page.getByRole('tab', { name: 'Upload CSV', exact: true }).click();
    await page.getByLabel('Entity name').fill('Acceptance CSV Product');
    await page.getByLabel('Source platform').fill('User supplied synthetic CSV');
    await page.getByLabel('Upload CSV file').setInputFiles({ name: 'acceptance.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) });
    await page.getByRole('button', { name: 'Preview reviews' }).click();
    const preview = page.getByRole('dialog', { name: 'Check your import' });
    await preview.waitFor();
    assert.match(await preview.innerText(), /5 observed records/);
    await preview.getByRole('button', { name: 'Import and analyze' }).click();
    await page.getByRole('button', { name: 'New', exact: true }).waitFor();
    uploaded = (await session(context)).dataset;
    const summary = uploaded.summary;
    assert.equal(summary.observed, 5);
    assert.equal(summary.accepted, 3);
    assert.equal(summary.rejected, 1);
    assert.equal(summary.duplicates, 1);
    assert.equal(summary.excluded, 0);
    assert.equal(summary.rating_count, 2);
    assert.equal(summary.average_rating, 3);
    assert.equal(summary.histogram['5'], 1);
    assert.equal(summary.histogram['1'], 1);
    assert.equal(summary.missing.rating, 1);
    assert.equal(summary.missing.date, 1);
    assert.equal(summary.date_from, '2026-10-01');
    assert.equal(summary.date_to, '2026-10-12');
    assert.equal(uploaded.reviews[0].text.replaceAll('\r\n', '\n'), exactMultiline);
    assert.equal(uploaded.reviews[2].source_url, null);
    assert.equal(uploaded.reviews[2].rating, null);
    assert.equal(uploaded.reviews[2].date, null);
    assert.equal(await page.locator('.metrics > div').first().locator('strong').innerText(), '3');
    assert.equal(await page.locator('a[href^="javascript:"]').count(), 0);
  });
  await check('API rejects empty and oversized input, unsupported CSV and invalid metadata', async () => {
    for (const question of ['', 'q'.repeat(501)]) {
      await apiError(context, '/api/chat', { dataset_id: uploaded.id, question }, [400, 413, 422]);
    }
    for (const payload of [
      { type: 'paste', entity: '', platform: 'Test', content: 'Review text' },
      { type: 'paste', entity: 'Test', platform: '', content: 'Review text' },
      { type: 'csv', entity: 'Test', platform: 'Test', content: 'review,rating\nUnsupported header,5' },
      { type: 'csv', entity: 'Test', platform: 'Test', content: 'text,text\nFirst,Second' },
      { type: 'paste', entity: 'Test', platform: 'Test', content: '' },
      { type: 'paste', entity: 'Test', platform: 'Test', content: 'text', url: 'javascript:alert(1)' },
    ]) await apiError(context, '/api/ingest', payload, [400, 422]);
    await apiError(context, '/api/ingest', { type: 'paste', entity: 'Test', platform: 'Test', content: 'x'.repeat(1048577) }, [413]);
    assert.equal((await session(context)).dataset.id, uploaded.id, 'Rejected inputs must preserve the active dataset');
  });
  await check('unsupported live URL is rejected and cross-origin writes fail', async () => {
    const error = await apiError(context, '/api/ingest', { type: 'url', entity: 'Test', platform: 'Test', url: 'http://127.0.0.1/' }, [400, 422]);
    assert.match(error.error.message, /unavailable|HTTP|public|private|safely|address/i);
    const denied = await context.request.post('/api/ingest', { headers: { Origin: 'https://untrusted.invalid' }, data: { type: 'demo' } });
    assert.equal(denied.status(), 403, await denied.text());
    const unknown = await context.request.get('/api/unknown-route');
    assert.equal(unknown.status(), 404);
    assert.match(unknown.headers()['content-type'], /json/);
  });
  await check('clear dataset deletes reviews and history across reload', async () => {
    await page.getByRole('button', { name: 'Clear dataset', exact: true }).click();
    await page.getByRole('dialog', { name: 'Clear this dataset?' }).getByRole('button', { name: 'Clear dataset', exact: true }).click();
    await page.getByRole('heading', { level: 1 }).waitFor();
    assert.equal((await session(context)).dataset, null);
    assert.deepEqual((await session(context)).history, []);
    await page.reload();
    await page.getByRole('heading', { level: 1 }).waitFor();
    assert.equal((await session(context)).dataset, null);
    await apiError(context, '/api/chat', { dataset_id: uploaded.id, question: 'Show deleted reviews' }, [404, 409]);
  });
  await check('review count cap reports exclusions instead of claiming completeness', async () => {
    const payload = { type: 'paste', entity: 'Count limit test', platform: 'Synthetic', content: Array.from({ length: 205 }, (_, i) => `Unique review ${i}: the package arrived.`).join('\n\n') };
    const response = await context.request.post('/api/ingest', { data: payload });
    assert.equal(response.status(), 200, await response.text());
    const data = await response.json();
    assert.equal(data.summary.observed, 205);
    assert.equal(data.summary.accepted, 200);
    assert.equal(data.summary.excluded, 5);
    assert.match(data.summary.stopping_reason, /count_limit/);
    await context.request.delete('/api/dataset');
  });
  await check('mobile import, chat layout and evidence drawer fit the viewport', async () => {
    const mobileContext = await browser.newContext({ baseURL, viewport: { width: 390, height: 844 }, reducedMotion: 'reduce', isMobile: true, hasTouch: true });
    const mobile = await mobileContext.newPage();
    currentPage = mobile;
    await mobile.goto('/demo');
    await mobile.getByRole('heading', { level: 1 }).waitFor();
    assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await mobile.screenshot({ path: path.join(artifacts, 'mobile-home.png'), fullPage: true });
    await mobile.getByRole('button', { name: 'Try sample data' }).click();
    await mobile.getByRole('button', { name: 'New', exact: true }).waitFor();
    assert.equal(await mobile.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    await mobile.getByText('Preview ingested reviews', { exact: false }).click();
    await mobile.getByRole('button', { name: 'View review R001', exact: true }).click();
    const drawer = mobile.getByRole('dialog', { name: 'Original review R001' });
    await drawer.waitFor();
    const box = await drawer.boundingBox();
    assert.ok(box.x >= -1 && box.width <= 391);
    await mobile.screenshot({ path: path.join(artifacts, 'mobile-evidence.png') });
    await mobile.getByRole('button', { name: 'Close evidence' }).click();
    await mobileContext.close();
    currentPage = page;
  });
  assert.deepEqual(clientErrors, [], `Browser runtime errors: ${clientErrors.join('; ')}`);
  await context.close();
}

main().catch(error => {
  console.error(error.stack || error);
  process.exitCode = 1;
}).finally(async () => {
  if (browser) await browser.close();
  fs.writeFileSync(path.join(artifacts, 'results.json'), JSON.stringify({
    run_at: new Date().toISOString(), base_url: baseURL,
    passed: results.filter(result => result.status === 'passed').length,
    failed: results.filter(result => result.status === 'failed').length,
    results,
  }, null, 2));
});
