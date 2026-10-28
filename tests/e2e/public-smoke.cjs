#!/usr/bin/env node
// Small public deployment smoke: one synthetic import, two answers and a stopped third answer.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const baseURL = process.env.REVIQ_BASE_URL || 'https://reviq.xyz';
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-public-smoke';
fs.mkdirSync(artifacts, { recursive: true });
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  const context = await browser.newContext({ baseURL, viewport: { width:1440, height:1000 }, reducedMotion:'reduce' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  let result = { base_url:baseURL, run_at:new Date().toISOString(), passed:false };
  try {
    const response = await page.goto('/demo');
    assert.equal(response.status(), 200);
    assert.equal(response.headers()['x-content-type-options'], 'nosniff');
    assert.ok(response.headers()['content-security-policy']);
    await page.getByRole('heading', { level:1 }).waitFor();
    await page.screenshot({path:path.join(artifacts, 'public-home.png'), fullPage:true});
    const health = await context.request.get('/api/health');
    assert.equal(health.status(), 200);
    await page.getByRole('button', {name:'Try sample data'}).click();
    await page.getByRole('button', {name:'New', exact:true}).waitFor();
    const state = await (await context.request.get('/api/session')).json();
    assert.ok(state.dataset.synthetic);
    assert.ok((await context.cookies()).some(cookie => cookie.httpOnly && cookie.secure && cookie.sameSite === 'Strict'));
    const ask = async question => {
      const prior = await page.getByTestId('assistant-message').count();
      await page.getByLabel('Question', {exact:true}).fill(question);
      await page.getByRole('button', {name:'Send question', exact:true}).click();
      await page.getByTestId('assistant-message').nth(prior).waitFor();
      await page.getByRole('button', {name:'Send question', exact:true}).waitFor({timeout:70000});
      return page.getByTestId('assistant-message').last();
    };
    const answer = await ask('What do the reviews say about delivery?');
    const cite = answer.getByRole('button', {name:/^View review R/}).first();
    await cite.waitFor();
    const id = (await cite.getAttribute('aria-label')).replace('View review ', '');
    await cite.click();
    const drawer = page.getByRole('dialog', {name:`Original review ${id}`});
    assert.equal(await drawer.locator('blockquote').innerText(), state.dataset.reviews.find(review => review.id === id).text);
    await page.screenshot({path:path.join(artifacts,'public-evidence.png'), fullPage:true});
    await page.getByRole('button', {name:'Close evidence'}).click();
    const refusal = await ask('What is the weather in San Francisco?');
    assert.match(await refusal.innerText(), /I can only answer from the reviews loaded for this dataset/i);
    await page.getByLabel('Question', {exact:true}).fill('Summarize the recurring complaints and praise in this review sample with detailed supporting citations.');
    await page.getByRole('button', {name:'Send question', exact:true}).click();
    await page.getByRole('button', {name:'Stop response'}).waitFor({timeout:70000});
    await page.waitForFunction(() => {
      const last = [...document.querySelectorAll('[data-testid=assistant-message]')].at(-1);
      return !!last?.querySelector('.message-content')?.textContent && !last.querySelector('.thinking') && !!document.querySelector('[aria-label="Stop response"]');
    }, null, {timeout:70000});
    await page.getByRole('button', {name:'Stop response'}).click();
    await page.getByRole('button', {name:'Send question', exact:true}).waitFor();
    assert.match(await page.getByTestId('assistant-message').last().innerText(), /Response stopped/);
    await page.reload();
    await page.getByTestId('assistant-message').nth(1).waitFor();
    assert.equal(await page.getByTestId('assistant-message').count(), 2, 'Stopped exchange must not be retained');
    const outsider = await browser.newContext({baseURL});
    await outsider.request.get('/api/session');
    const forbidden = await outsider.request.post('/api/chat', {data:{dataset_id:state.dataset.id,question:'Read the other session reviews'}});
    assert.equal(forbidden.status(),404);
    await outsider.close();
    const unknown = await context.request.get('/api/missing-endpoint');
    assert.equal(unknown.status(),404);
    assert.match(unknown.headers()['content-type'],/json/);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    assert.deepEqual(errors, []);
    await page.screenshot({path:path.join(artifacts,'public-workspace.png'), fullPage:true});
    result = {...result, passed:true, model:state.capabilities.model, mode:state.capabilities.chat_mode, checks:['HTTPS public entry','security headers','Secure HttpOnly SameSite=Strict session cookie','synthetic example import','streamed answer through Apache','original citation text','off-topic refusal','Stop after first delta cancels and does not persist incomplete exchange','PostgreSQL reload persistence','wrong-session chat denied','unknown API returns JSON404','no browser runtime errors']};
    console.log(JSON.stringify(result, null, 2));
  } catch (error) {
    result.error = error.stack;
    await page.screenshot({path:path.join(artifacts, 'failure.png'), fullPage:true}).catch(() => {});
    throw error;
  } finally {
    await context.request.delete('/api/dataset').catch(() => {});
    fs.writeFileSync(path.join(artifacts,'results.json'),JSON.stringify(result,null,2));
    await browser.close();
  }
})().catch(error => { console.error(error.stack || error); process.exitCode=1; });
