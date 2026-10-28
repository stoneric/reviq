#!/usr/bin/env node
// Explicit cached retrieval only. No chat requests; real review text remains in memory.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const baseURL=process.env.REVIQ_BASE_URL||'http://127.0.0.1:8089';
const artifacts=process.env.REVIQ_E2E_ARTIFACTS||'/tmp/reviq-exa-cache-smoke';
const hosted=process.env.REVIQ_HOSTED_SMOKE==='1';
const sourceURL='https://www.trustpilot.com/review/allenbrothers.com';
const checkboxName='Use cached snapshot (may be outdated)';
const readJSON=async response=>{assert.match(response.headers()['content-type']||'',/json/);return response.json()};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({baseURL,viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage();
 const result={run_at:new Date().toISOString(),base_url:baseURL,hosted_smoke:hosted,passed:false,model_calls:0,seed_imports:0,url_import_requests:0,outcomes:[],checks:[]};
 const runtimeErrors=[];
 const requestModes=[];
 let active=null;
 page.on('pageerror',error=>runtimeErrors.push(error.name));
 page.on('request',request=>{
  if(request.method()!=='POST')return;
  const path=new URL(request.url()).pathname;
  if(path==='/api/chat')result.model_calls++;
  if(path==='/api/ingest'){result.url_import_requests++;requestModes.push(request.postDataJSON().cache_only)}
 });
 fs.mkdirSync(artifacts,{recursive:true});
 const session=async()=>readJSON(await context.request.get('/api/session'));
 const provenanceValue=label=>page.getByText(label,{exact:true}).locator('..').locator('dd');
 async function openImport(){
  if(active)await page.getByRole('button',{name:'New',exact:true}).click();
  const url=page.getByRole('textbox',{name:'Review page URL',exact:true});
  await url.fill(sourceURL);
  const checkbox=page.getByRole('checkbox',{name:checkboxName,exact:true});
  assert.equal(await checkbox.isChecked(),false,'Cached retrieval must start unchecked');
  return {url,checkbox};
 }
 async function importURL(cached){
  const {url,checkbox}=await openImport();
  if(cached){
   await checkbox.check();
   await page.getByRole('button',{name:'Import cached snapshot',exact:true}).waitFor();
   await url.fill('');await url.fill(sourceURL);
   assert.equal(await checkbox.isChecked(),false,'Changing the URL must reset cache consent');
   await checkbox.check();
  }
  const responseReady=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/ingest'&&response.request().method()==='POST',{timeout:25000});
  const started=Date.now();
  await page.getByRole('button',{name:cached?'Import cached snapshot':'Ingest reviews',exact:true}).click();
  const response=await responseReady;
  return {response,data:await readJSON(response),duration:Date.now()-started};
 }
 async function verifyFreshFailure(){
  const {response,data,duration}=await importURL(false);
  const outcome={mode:'fresh',status:response.status(),duration_ms:duration,error_code:data.error?.code||null};
  result.outcomes.push(outcome);
  assert.equal(response.status(),422,'The known blocked source must report a source failure');
  assert.equal(data.error?.code,'exa_fresh_blocked','The upstream block must be reported as Exa fresh retrieval failure');
  assert.equal(requestModes.at(-1),false,'Fresh import must send cache_only:false');
  assert.equal((await session()).dataset?.id||null,active?.id||null,'Fresh failure must preserve the prior dataset');
  await page.getByRole('button',{name:'Upload CSV instead',exact:true}).waitFor();
  await page.getByRole('button',{name:'Paste reviews instead',exact:true}).waitFor();
  assert.equal(await page.getByRole('checkbox',{name:checkboxName,exact:true}).isChecked(),false,'Fresh failure must not enable cached retrieval automatically');
  if(active){await page.getByRole('button',{name:/Back to/}).click();await page.getByRole('button',{name:'New',exact:true}).waitFor()}
  Object.assign(outcome,{outcome:'source_blocked',prior_dataset_preserved:!!active,cached_fallback_not_automatic:true});
 }
 async function verifyCachedSuccess(){
  const {response,data,duration}=await importURL(true);
  const outcome={mode:'cached',status:response.status(),duration_ms:duration,error_code:data.error?.code||null};
  result.outcomes.push(outcome);
  assert.equal(response.status(),200,'Explicit cached retrieval must succeed');
  await page.getByRole('button',{name:'New',exact:true}).waitFor();
  assert.equal(requestModes.at(-1),true,'Cached import must send cache_only:true');
  assert.equal(data.mode,'scraped');assert.equal(data.synthetic,false);
  assert.equal(data.platform,'Trustpilot');assert.equal(data.source_url,sourceURL);
  assert.equal(data.retrieval_provider,'exa');assert.equal(data.retrieval_freshness,'cached');
  assert.equal(data.captured_at,null,'An unknown snapshot date must not be replaced with retrieval time');
  assert.ok(Math.abs(Date.now()-Date.parse(data.retrieved_at))<120000,'Retrieval time must describe this import');
  assert.ok(data.reviews.length>0&&data.reviews.length<=200);
  assert.notEqual(data.id,active?.id);
  const warnings=[...(data.warnings||[]),...(data.summary.warnings||[])].join(' ');
  assert.match(warnings,/cach/i);assert.match(warnings,/unknown|not known/i);
  assert.equal(data.summary.accepted,data.reviews.length);
  assert.equal(data.summary.observed,data.summary.accepted+data.summary.rejected+data.summary.duplicates+data.summary.excluded);
  const notice=page.getByTestId('cached-snapshot-notice');
  assert.equal(await notice.isVisible(),true,'The cached notice must be visible without opening provenance');
  assert.match(await notice.innerText(),/cached snapshot.*capture date unknown/i);
  assert.equal(await notice.evaluate(element=>!!element.closest('details')),false);
  await page.getByText('Provenance & coverage',{exact:true}).click();
  assert.equal(await provenanceValue('Retrieved via').innerText(),'Exa');
  assert.equal(await provenanceValue('Freshness').innerText(),'Cached snapshot — capture date unknown');
  assert.equal(await provenanceValue('Captured').innerText(),'Unknown');
  assert.notEqual(await provenanceValue('Retrieved').innerText(),'Not supplied');
  const review=data.reviews[0];
  await page.getByText('Preview ingested reviews',{exact:false}).click();
  await page.getByRole('button',{name:`View review ${review.id}`,exact:true}).click();
  const drawer=page.getByRole('dialog',{name:`Original review ${review.id}`});
  assert.ok(await drawer.locator('blockquote').textContent()===review.text,'Evidence must match the exact imported review');
  await page.getByRole('button',{name:'Close evidence',exact:true}).click();
  active=data;
  Object.assign(outcome,{outcome:'imported',platform:data.platform,entity:data.entity_name,source_url:data.source_url,retrieval_provider:data.retrieval_provider,retrieval_freshness:data.retrieval_freshness,retrieved_at:data.retrieved_at,captured_at:data.captured_at,review_count:data.reviews.length,pages:data.summary.pages,stopping_reason:data.summary.stopping_reason});
 }
 try{
  await page.goto('/demo');await page.getByRole('heading',{level:1}).waitFor();
  const initial=await session();
  assert.equal(initial.dataset,null);assert.equal(initial.capabilities.exa_enabled,true,'This test requires Exa to be configured');
  const checkbox=page.getByRole('checkbox',{name:checkboxName,exact:true});
  assert.equal(await checkbox.isChecked(),false);
  assert.equal(await page.getByText('Uses an existing copy; its capture date may be unknown.',{exact:true}).isVisible(),true);
  if(!hosted){
   const seeded=await context.request.post('/api/ingest',{data:{type:'paste',entity:'Cached retrieval baseline',platform:'Synthetic test',content:'This synthetic baseline must survive an unsuccessful request for a fresh review page.'}});
   result.seed_imports++;
   assert.equal(seeded.status(),200);active=await readJSON(seeded);
   await page.reload();await page.getByRole('button',{name:'New',exact:true}).waitFor();
   await verifyFreshFailure();await verifyCachedSuccess();
  }else{await verifyCachedSuccess();await verifyFreshFailure()}
  await page.reload();await page.getByRole('button',{name:'New',exact:true}).waitFor();
  const restored=await session();
  assert.equal(restored.dataset.id,active.id);assert.equal(restored.dataset.retrieval_freshness,'cached');
  assert.equal(restored.dataset.captured_at,null);assert.equal(restored.dataset.retrieved_at,active.retrieved_at);
  assert.equal(restored.history.length,0);
  assert.equal(await page.getByTestId('cached-snapshot-notice').isVisible(),true);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.getByTestId('cached-snapshot-notice').isVisible(),true);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  assert.deepEqual(runtimeErrors,[]);assert.equal(result.model_calls,0);assert.equal(result.url_import_requests,2);
  if(hosted)assert.equal(result.seed_imports,0);
  result.checks=['cached import is an explicit unchecked choice and resets on URL changes','fresh blocked import preserves prior data without automatic cache fallback','explicit Exa cached import returns real reviews with exact evidence','visible cached warning is separate from collapsed provenance','unknown capture date is never replaced by retrieval time','cached freshness and timestamps persist after reload','mobile warning visible with no horizontal overflow','exactly two URL imports and zero chat requests'];
  result.passed=true;
  console.log(JSON.stringify(result,null,2));
 }catch(error){result.error={name:error.name,message:error.message};console.error(error.stack);process.exitCode=1}
 finally{
  await context.request.delete('/api/dataset').catch(()=>{});
  fs.writeFileSync(`${artifacts}/results.json`,JSON.stringify(result,null,2));
  await browser.close();
 }
})();
