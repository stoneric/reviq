#!/usr/bin/env node
// No model calls. Runs against an isolated high-quota server by default.
// Captured reviews and author names stay in memory; reports contain metadata only.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const baseURL = process.env.REVIQ_BASE_URL || 'http://127.0.0.1:8089';
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-public-review-urls';
const hostedSmoke=process.env.REVIQ_HOSTED_SMOKE==='1';
const expectedTrustpilotProvider=process.env.REVIQ_EXPECT_RETRIEVAL_PROVIDER?.trim().toLowerCase();
const expectedGenericProvider=process.env.REVIQ_EXPECT_GENERIC_PROVIDER?.trim().toLowerCase();
const trustpilotURL='https://www.trustpilot.com/review/allenbrothers.com';
const scenarios = [{name:'Trustpilot Allen Brothers',url:trustpilotURL,platform:'Trustpilot',expectedProvider:expectedTrustpilotProvider,requireSuccess:process.env.REVIQ_REQUIRE_TRUSTPILOT==='1'||!!expectedTrustpilotProvider}];
if(hostedSmoke)assert.ok(process.env.REVIQ_GENERIC_REVIEW_URL,'Hosted smoke requires a generic source to establish the prior dataset without a seed import');
if(process.env.REVIQ_GENERIC_REVIEW_URL){
 const generic={name:'Generic public review page',url:process.env.REVIQ_GENERIC_REVIEW_URL,expectedProvider:expectedGenericProvider,requireSuccess:hostedSmoke||process.env.REVIQ_REQUIRE_GENERIC==='1'||!!expectedGenericProvider};
 if(hostedSmoke)scenarios.unshift(generic);else scenarios.push(generic);
}
const jsonResponse=async response=>{assert.match(response.headers()['content-type']||'',/json/);return response.json()};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({baseURL,viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage();
 const result={run_at:new Date().toISOString(),base_url:baseURL,hosted_smoke:hostedSmoke,passed:false,model_calls:0,url_import_requests:0,seed_imports:0,successful_imports:0,source_outcomes:[],checks:[]};
 const runtimeErrors=[];
 page.on('pageerror',error=>runtimeErrors.push(error.name));
 page.on('request',request=>{if(request.method()!=='POST')return;const path=new URL(request.url()).pathname;if(path==='/api/chat')result.model_calls++;if(path==='/api/ingest')result.url_import_requests++});
 fs.mkdirSync(artifacts,{recursive:true});
 try{
  await page.goto('/demo');
  await page.getByRole('heading',{level:1}).waitFor();
  const initial=await jsonResponse(await context.request.get('/api/session'));
  assert.equal(initial.capabilities.live_scraping,true);
  assert.ok(initial.capabilities.supported_platforms.includes('Trustpilot'));
  assert.ok(initial.capabilities.supported_platforms.includes('Google Play'));
  assert.equal(initial.dataset,null,'The smoke must start in a fresh browser session');
  assert.equal(await page.getByRole('textbox',{name:'Review page URL',exact:true}).getAttribute('placeholder'),trustpilotURL);
  assert.match(await page.locator('#url-import-help').innerText(),/another site with identifiable reviews/);
  let attempts=0;
  const countImport=request=>{if(request.method()==='POST'&&new URL(request.url()).pathname==='/api/ingest')attempts++};
  page.on('request',countImport);
  for(const invalid of ['file:///etc/hosts','ftp://example.com/reviews','https://user:password@example.com/reviews','http://example.com:8080/reviews']){
   await page.getByRole('textbox',{name:'Review page URL',exact:true}).fill(invalid);
   await page.getByRole('button',{name:'Ingest reviews',exact:true}).click();
   await page.getByRole('button',{name:'Upload CSV instead'}).waitFor();
   assert.equal(attempts,0,'Invalid scheme, credentials and custom ports must be rejected locally');
  }
  page.off('request',countImport);
  result.checks.push('HTTP/HTTPS public URL guidance and Trustpilot example','unsupported scheme, credentials and custom ports rejected locally');
  let active=null;
  if(!hostedSmoke){
   const seeded=await context.request.post('/api/ingest',{data:{type:'paste',entity:'Browser import baseline',platform:'Synthetic test',content:'This synthetic review must remain available when a source cannot be imported.'}});
   result.seed_imports++;
   assert.equal(seeded.status(),200);
   active=await jsonResponse(seeded);
   await page.reload();
   await page.getByRole('button',{name:'New',exact:true}).waitFor();
  }
  for(const scenario of scenarios){
   if(active)await page.getByRole('button',{name:'New',exact:true}).click();
   await page.getByRole('textbox',{name:'Review page URL',exact:true}).fill(scenario.url);
   const responseReady=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/ingest'&&response.request().method()==='POST',{timeout:25000});
   const started=Date.now();
   await page.getByRole('button',{name:'Ingest reviews',exact:true}).click();
   const response=await responseReady;
   const data=await jsonResponse(response);
   const outcome={name:scenario.name,requested_url:scenario.url,status:response.status(),duration_ms:Date.now()-started};
   assert.notEqual(data.error?.code,'unsupported_url','Public review platforms must not be rejected by a site whitelist');
   if(response.ok()){
    await page.getByRole('button',{name:'New',exact:true}).waitFor();
    assert.equal(data.mode,'scraped');assert.equal(data.synthetic,false);
    assert.notEqual(data.id,active?.id);
    assert.ok(data.reviews.length>0&&data.reviews.length<=200);
    if(scenario.platform)assert.equal(data.platform,scenario.platform);
    const provider=data.retrieval_provider||'direct';
    if(scenario.expectedProvider)assert.equal(provider,scenario.expectedProvider,'Retrieval provider must match the requested expectation');
    assert.ok(data.entity_name&&data.platform&&data.source_url);
    assert.equal(new Set(data.reviews.map(review=>review.id)).size,data.reviews.length);
    for(const review of data.reviews){
     assert.ok(typeof review.text==='string'&&review.text.trim().length>0);
     assert.ok(review.rating===null||(Number.isInteger(review.rating)&&review.rating>=1&&review.rating<=5));
     if(review.date!==null){assert.match(review.date,/^\d{4}-\d{2}-\d{2}$/);assert.equal(new Date(review.date).toISOString().slice(0,10),review.date)}
     if(review.source_url){
      const link=new URL(review.source_url);
      assert.ok(['http:','https:'].includes(link.protocol)&&!link.username&&!link.password&&!link.port,'Per-review source links must use safe default-port web URLs');
      if(data.platform==='anker.com')assert.equal(review.source_url,data.source_url,'Anker review source must refer to the imported product page');
     }
    }
    assert.ok(Math.abs(Date.now()-Date.parse(data.captured_at))<120000);
    const dates=data.reviews.map(review=>review.date).filter(Boolean).sort();
    assert.equal(data.summary.missing.date,data.reviews.length-dates.length);
    assert.equal(data.summary.date_from,dates[0]||null);
    assert.equal(data.summary.date_to,dates.at(-1)||null);
    assert.equal(data.summary.observed,data.summary.accepted+data.summary.rejected+data.summary.duplicates+data.summary.excluded);
    for(const field of ['attempted','fetched','parsed'])assert.equal(data.summary.pages[field],1,`Review-page ${field} count`);
    assert.equal(data.summary.scanned_total_known,false);
    assert.notEqual(data.summary.stopping_reason,'source_exhausted');
    const state=await jsonResponse(await context.request.get('/api/session'));
    assert.equal(state.dataset.id,data.id);assert.equal(state.history.length,0);
    await page.getByText('Provenance & coverage',{exact:true}).click();
    const providerLabel=page.getByText('Retrieved via',{exact:true}).locator('..').locator('dd');
    assert.equal(await providerLabel.innerText(),provider==='exa'?'Exa':'Direct page fetch');
    const review=data.reviews[0];
    await page.getByText('Preview ingested reviews',{exact:false}).click();
    await page.getByRole('button',{name:`View review ${review.id}`,exact:true}).click();
    const drawer=page.getByRole('dialog',{name:`Original review ${review.id}`});
    assert.ok(await drawer.locator('blockquote').textContent()===review.text,'The evidence drawer must show the exact original review');
    await page.getByRole('button',{name:'Close evidence'}).click();
    active=data;result.successful_imports++;
    Object.assign(outcome,{outcome:'imported',platform:data.platform,retrieval_provider:provider,entity:data.entity_name,source_url:data.source_url,captured_at:data.captured_at,review_count:data.reviews.length,dated_review_count:dates.length,date_range:{from:data.summary.date_from,to:data.summary.date_to},pages:data.summary.pages,stopping_reason:data.summary.stopping_reason});
   }else{
    assert.ok([413,422,503,504].includes(response.status()),`Unexpected source failure HTTP${response.status()}`);
    assert.match(data.error?.code||'',/^scrape_/);
    await page.getByRole('button',{name:'Upload CSV instead'}).waitFor();
    await page.getByRole('button',{name:'Paste reviews instead'}).waitFor();
    assert.equal((await jsonResponse(await context.request.get('/api/session'))).dataset?.id||null,active?.id||null,'Failed import must preserve the active dataset');
    if(active){
     const back=page.getByRole('button',{name:/Back to/});await back.click();
     await page.getByRole('button',{name:'New',exact:true}).waitFor();
     assert.equal(await page.getByRole('heading',{name:active.entity_name,exact:true}).count(),1);
    }
    Object.assign(outcome,{outcome:'source_unavailable',error_code:data.error.code,prior_dataset_preserved:!!active,csv_paste_recovery_visible:true});
   }
   result.source_outcomes.push(outcome);
   assert.ok(!scenario.requireSuccess||outcome.outcome==='imported',`${scenario.name} did not yield importable reviews`);
  }
  await page.reload();
  await page.getByRole('button',{name:'New',exact:true}).waitFor();
  assert.equal((await jsonResponse(await context.request.get('/api/session'))).dataset.id,active.id);
  assert.deepEqual(runtimeErrors,[]);assert.equal(result.model_calls,0);
  assert.equal(result.url_import_requests,scenarios.length);
  if(hostedSmoke){assert.equal(result.seed_imports,0);assert.equal(result.url_import_requests,2);result.checks.push('exactly two URL imports with no seed, generic source first and Trustpilot second')}
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  result.checks.push('public review URLs reach the server rather than a client site whitelist','successful imports show exact evidence and safe per-review source links, or honest source failure preserves data','CSV/paste recovery for unavailable sources','reload preserves the current dataset','no model calls or browser runtime errors');
  if(result.successful_imports>0)result.checks.push('retrieval provider shown separately from source platform in provenance','review dates reconcile with summary coverage and date range');
  result.passed=true;
  console.log(JSON.stringify(result,null,2));
 }catch(error){
  result.error={name:error.name,message:error.message};console.error(error.stack);process.exitCode=1;
 }finally{
  await context.request.delete('/api/dataset').catch(()=>{});
  fs.writeFileSync(`${artifacts}/results.json`,JSON.stringify(result,null,2));
  await browser.close();
 }
})();
