#!/usr/bin/env node
// Run against the isolated rate100 test server, never against public quotas.
const { request } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const baseURL = process.env.REVIQ_BASE_URL || 'http://127.0.0.1:8089';
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-scraper-security';
const attacks = [
  ['empty', ''], ['scheme-relative', '//example.com/reviews'],
  ['file protocol', 'file:///etc/hosts'], ['FTP protocol', 'ftp://example.com/reviews'],
  ['data protocol', 'data:text/html,review'], ['script protocol', 'javascript:alert(1)'],
  ['HTTPS loopback', 'https://127.0.0.1/reviews'], ['HTTP loopback', 'http://127.0.0.1/reviews'],
  ['localhost name', 'http://localhost/reviews'], ['localhost trailing dot', 'http://localhost./reviews'],
  ['short loopback', 'http://127.1/reviews'], ['numeric loopback', 'http://2130706433/reviews'],
  ['IPv6 loopback', 'https://[::1]/reviews'], ['IPv4-mapped loopback', 'https://[::ffff:127.0.0.1]/reviews'],
  ['IPv6 private', 'https://[fc00::1]/reviews'], ['IPv6 link local', 'https://[fe80::1]/reviews'],
  ['IPv4 private 10', 'https://10.0.0.1/reviews'], ['IPv4 private 172', 'https://172.16.0.1/reviews'],
  ['IPv4 private 192', 'https://192.168.1.1/reviews'], ['IPv4 link local', 'https://169.254.1.1/reviews'],
  ['IPv4 unspecified', 'http://0.0.0.0/reviews'], ['IPv4 documentation', 'https://192.0.2.1/reviews'],
  ['embedded credentials', 'https://user:password@example.com/reviews'],
  ['HTTP custom port', 'http://example.com:8080/reviews'], ['HTTPS custom port', 'https://example.com:8443/reviews'],
  ['HTTP wrong default port', 'http://example.com:443/reviews'], ['HTTPS wrong default port', 'https://example.com:80/reviews'],
  ['malformed escape', 'https://example.com/%ZZ'],
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
      assert.ok([400,422].includes(response.status()),`${name}: expected400/422, got${response.status()}`);
      assert.ok(['unsupported_url','scrape_fetch_failure','scrape_unsafe_destination'].includes(value.error?.code),`${name}: unexpected rejection code${value.error?.code}`);
      assert.equal((await(await context.get('/api/session')).json()).dataset.id,dataset.id,`${name}: rejected import must preserve active dataset`);
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
