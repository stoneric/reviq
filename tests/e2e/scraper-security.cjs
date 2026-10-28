#!/usr/bin/env node
// Run against the isolated rate100 test server, never against public quotas.
const { request } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const baseURL = process.env.REVIQ_BASE_URL || 'http://127.0.0.1:8089';
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-scraper-security';
const attacks = [
  ['empty', ''], ['http', 'http://play.google.com/store/apps/details?id=com.spotify.music'],
  ['loopback', 'https://127.0.0.1/store/apps/details?id=com.spotify.music'],
  ['ipv6 loopback', 'https://[::1]/store/apps/details?id=com.spotify.music'],
  ['private address', 'https://10.0.0.1/store/apps/details?id=com.spotify.music'],
  ['userinfo', 'https://private@play.google.com/store/apps/details?id=com.spotify.music'],
  ['host suffix', 'https://play.google.com.untrusted.invalid/store/apps/details?id=com.spotify.music'],
  ['host prefix', 'https://untrusted-play.google.com/store/apps/details?id=com.spotify.music'],
  ['alternate port', 'https://play.google.com:8443/store/apps/details?id=com.spotify.music'],
  ['fragment', 'https://play.google.com/store/apps/details?id=com.spotify.music#reviews'],
  ['encoded path', 'https://play.google.com/store/apps/%64etails?id=com.spotify.music'],
  ['extra path', 'https://play.google.com/store/apps/details/other?id=com.spotify.music'],
  ['dot path', 'https://play.google.com/store/apps/../apps/details?id=com.spotify.music'],
  ['missing package', 'https://play.google.com/store/apps/details?hl=en'],
  ['duplicate package', 'https://play.google.com/store/apps/details?id=com.spotify.music&id=com.whatsapp'],
  ['unknown query', 'https://play.google.com/store/apps/details?id=com.spotify.music&url=https://127.0.0.1'],
  ['invalid package', 'https://play.google.com/store/apps/details?id=invalid'],
  ['package delimiter', 'https://play.google.com/store/apps/details?id=com.spotify.music%26target%3Dprivate'],
  ['path in package', 'https://play.google.com/store/apps/details?id=com.spotify/music'],
  ['non-web protocol', 'file:///etc/hosts'],
  ['malformed escape', 'https://play.google.com/store/apps/details?id=%ZZ'],
  ['other platform', 'https://www.trustpilot.com/review/example.com'],
];
(async () => {
  const context = await request.newContext({baseURL});
  const results = [];
  fs.mkdirSync(artifacts,{recursive:true});
  try {
    const initial = await (await context.get('/api/session')).json();
    assert.equal(initial.capabilities.live_scraping,true);
    const seeded = await context.post('/api/ingest',{data:{type:'paste',entity:'URL rejection test',platform:'Synthetic',content:'This synthetic review must survive rejected URL imports.'}});
    assert.equal(seeded.status(),200);
    const dataset = await seeded.json();
    for (const [name,url] of attacks) {
      const started = Date.now();
      const response = await context.post('/api/ingest',{data:{type:'url',url}});
      const value = await response.json();
      assert.equal(response.status(),400,`${name}: expected400, got${response.status()}`);
      assert.equal(value.error?.code,'unsupported_url',name);
      results.push({name,passed:true,status:response.status(),duration_ms:Date.now()-started});
    }
    const final = await (await context.get('/api/session')).json();
    assert.equal(final.dataset.id,dataset.id,'Rejected URLs preserve the active dataset');
    console.log(JSON.stringify({passed:results.length,failed:0,active_dataset_preserved:true}));
  } finally {
    await context.delete('/api/dataset').catch(()=>{});
    fs.writeFileSync(`${artifacts}/results.json`,JSON.stringify({run_at:new Date().toISOString(),base_url:baseURL,results},null,2));
    await context.dispose();
  }
})().catch(error=>{console.error(error.stack);process.exitCode=1});
