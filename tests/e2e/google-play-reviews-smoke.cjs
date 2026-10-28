#!/usr/bin/env node
// One URL import, no seed and no chat. Review text and authors stay in memory.
const { chromium }=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const baseURL=process.env.REVIQ_BASE_URL||'http://127.0.0.1:8089';
const artifacts=process.env.REVIQ_E2E_ARTIFACTS||'/tmp/reviq-google-play-reviews';
const sourceURL=process.env.REVIQ_GOOGLE_PLAY_URL||'https://play.google.com/store/apps/details?id=com.onepagecrm';
const parsedSource=new URL(sourceURL);
assert.ok(parsedSource.protocol==='https:'&&parsedSource.hostname==='play.google.com'&&parsedSource.pathname==='/store/apps/details'&&!parsedSource.username&&!parsedSource.password&&!parsedSource.port,'Source must be a Google Play app URL');
const packageId=parsedSource.searchParams.get('id');
assert.ok(packageId,'Google Play source must identify an app');
const onePageCRM=packageId==='com.onepagecrm';
const canonicalSourceURL=`https://play.google.com/store/apps/details?id=${encodeURIComponent(packageId)}&hl=en&gl=US`;
const expectedEntity=process.env.REVIQ_EXPECT_ENTITY||(onePageCRM?'OnePageCRM':'');
assert.ok(expectedEntity,'Set REVIQ_EXPECT_ENTITY when overriding the app URL');
const expectedAccepted=process.env.REVIQ_EXPECT_REVIEW_COUNT?Number(process.env.REVIQ_EXPECT_REVIEW_COUNT):(onePageCRM?83:null);
const expectedObserved=Number(process.env.REVIQ_EXPECT_OBSERVED_COUNT||(onePageCRM?83:200));
const expectedBatches=Number(process.env.REVIQ_EXPECT_REVIEW_BATCHES||(onePageCRM?1:2));
const expectedPages=Number(process.env.REVIQ_EXPECT_PAGES||(onePageCRM?2:3));
const expectedStop=process.env.REVIQ_EXPECT_STOPPING_REASON||(onePageCRM?'google_play_source_exhausted':'google_play_target_reached');
for(const number of [expectedAccepted,expectedObserved,expectedBatches,expectedPages].filter(value=>value!==null))assert.ok(Number.isInteger(number)&&number>0,'Expected counts must be positive integers');
const readJSON=async response=>{assert.match(response.headers()['content-type']||'',/json/);return response.json()};
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
 const context=await browser.newContext({baseURL,viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 const page=await context.newPage();
 const result={run_at:new Date().toISOString(),base_url:baseURL,passed:false,url_import_requests:0,model_calls:0,seed_imports:0,expected_requested_reviews:200,checks:[]};
 const runtimeErrors=[];
 let requestBody=null;
 page.on('pageerror',error=>runtimeErrors.push(error.name));
 page.on('request',request=>{
  if(request.method()!=='POST')return;
  const path=new URL(request.url()).pathname;
  if(path==='/api/chat')result.model_calls++;
  if(path==='/api/ingest'){result.url_import_requests++;requestBody=request.postDataJSON()}
 });
 fs.mkdirSync(artifacts,{recursive:true});
 const session=async()=>readJSON(await context.request.get('/api/session'));
 const provenanceValue=label=>page.getByText(label,{exact:true}).locator('..').locator('dd');
 async function openEvidence(review){
  await page.getByRole('button',{name:`View review ${review.id}`,exact:true}).click();
  const drawer=page.getByRole('dialog',{name:`Original review ${review.id}`});
  assert.ok(await drawer.locator('blockquote').textContent()===review.text,'Evidence must match the full original imported text');
  await page.getByRole('button',{name:'Close evidence',exact:true}).click();
 }
 try{
  await page.goto('/demo');await page.getByRole('heading',{level:1}).waitFor();
  const initial=await session();assert.equal(initial.dataset,null);assert.equal(initial.capabilities.live_scraping,true);
  assert.equal(initial.capabilities.google_play_max_reviews,200);assert.equal(initial.capabilities.google_play_batch_size,100);
  assert.match(await page.locator('#url-import-help').innerText(),/Google Play.*up to 200 reviews in batches of 100/);
  await page.getByRole('textbox',{name:'Review page URL',exact:true}).fill(sourceURL);
  const cache=page.getByRole('checkbox',{name:'Use cached snapshot (may be outdated)',exact:true});
  if(await cache.count())assert.equal(await cache.isChecked(),false);
  const responseReady=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/ingest'&&response.request().method()==='POST',{timeout:25000});
  const started=Date.now();
  await page.getByRole('button',{name:'Ingest reviews',exact:true}).click();
  const response=await responseReady;
  const data=await readJSON(response);
  result.status=response.status();result.duration_ms=Date.now()-started;
  if(!response.ok())result.error_code=data.error?.code||null;
  assert.equal(response.status(),200,'Google Play import must succeed');
  await page.getByRole('button',{name:'New',exact:true}).waitFor();
  assert.equal(requestBody.type,'url');assert.equal(requestBody.cache_only,false);
  assert.equal(data.mode,'scraped');assert.equal(data.synthetic,false);assert.equal(data.platform,'Google Play');
  assert.equal(data.retrieval_provider,'direct');assert.equal(data.retrieval_freshness,'live');
  assert.ok(data.entity_name.replace(/\s+/g,'').toLowerCase().includes(expectedEntity.replace(/\s+/g,'').toLowerCase()),'App name must match the expected source entity');
  assert.equal(data.source_url,canonicalSourceURL);
  assert.ok(Math.abs(Date.now()-Date.parse(data.captured_at))<120000,'Capture time must reflect this retrieval');
  assert.ok(Math.abs(Date.now()-Date.parse(data.retrieved_at))<120000,'Retrieval time must be current');
  const {reviews,summary:s}=data;
  if(expectedAccepted!==null)assert.equal(reviews.length,expectedAccepted,'Accepted count must match the observed acceptance target');
  assert.ok(reviews.length>3&&reviews.length<=200,'Google Play must provide a bounded sample beyond the old three-card preview');
  assert.equal(s.accepted,reviews.length);assert.equal(s.observed,expectedObserved);
  assert.equal(s.observed,s.accepted+s.rejected+s.duplicates+s.excluded);
  assert.ok(s.token_count<=24000,'Normalization token bound must remain in force');
  assert.equal(new Set(reviews.map(review=>review.id)).size,reviews.length);
  assert.equal(new Set(reviews.map(review=>review.text.normalize('NFKC').replace(/[ \t\n\x0B\f\r]+/g,' ').trim())).size,reviews.length,'Duplicate normalized text must not be counted twice');
  for(const [index,review] of reviews.entries()){
   assert.equal(review.id,'R'+String(index+1).padStart(3,'0'));
   assert.ok(typeof review.text==='string'&&review.text.trim().length>0);
   assert.ok(review.rating===null||(Number.isInteger(review.rating)&&review.rating>=1&&review.rating<=5));
   if(review.date!==null){assert.match(review.date,/^\d{4}-\d{2}-\d{2}$/);assert.equal(new Date(review.date).toISOString().slice(0,10),review.date)}
   if(review.source_url){const link=new URL(review.source_url);assert.equal(link.protocol,'https:');assert.equal(link.hostname,'play.google.com');assert.ok(!link.username&&!link.password&&!link.port);assert.equal(link.searchParams.get('id'),packageId)}
  }
  const ratings=reviews.map(review=>review.rating).filter(rating=>rating!==null);
  const dates=reviews.map(review=>review.date).filter(Boolean).sort();
  for(let rating=1;rating<=5;rating++)assert.equal(s.histogram[String(rating)],ratings.filter(value=>value===rating).length);
  assert.equal(s.rating_count,ratings.length);assert.equal(s.missing.rating,reviews.length-ratings.length);
  assert.equal(s.missing.date,reviews.length-dates.length);assert.equal(s.date_from,dates[0]||null);assert.equal(s.date_to,dates.at(-1)||null);
  assert.equal(s.pages.requested_reviews,200);assert.equal(s.pages.batch_size,100);assert.equal(s.pages.review_batches,expectedBatches);
  for(const field of ['attempted','fetched','parsed'])assert.equal(s.pages[field],expectedPages,`Page ${field} count includes metadata and review batches`);
  assert.equal(s.pages.parsed,s.pages.review_batches+1);
  assert.equal(s.stopping_reason,expectedStop);assert.equal(s.scanned_total_known,false);
  const warnings=[...(s.warnings||[]),...(data.warnings||[])].join(' ');
  assert.match(warnings,/200/);assert.match(warnings,/newest/i);assert.match(warnings,/\b(?:en|English)\b/i);assert.match(warnings,/\bUS\b/);
  assert.equal(await page.locator('.metrics > div').first().locator('strong').innerText(),String(reviews.length));
  assert.equal(await page.getByText('Synthetic sample for exploring Reviq.',{exact:false}).count(),0);
  assert.equal(await page.getByTestId('cached-snapshot-notice').count(),0);
  await page.getByText('Provenance & coverage',{exact:true}).click();
  for(const [label,key] of [['Pages attempted','attempted'],['Pages fetched','fetched'],['Pages parsed','parsed'],['Requested reviews','requested_reviews'],['Review batches','review_batches'],['Batch size','batch_size']])assert.equal(await provenanceValue(label).innerText(),String(s.pages[key]));
  assert.equal(await provenanceValue('Retrieved via').innerText(),'Direct page fetch');
  assert.match(await page.locator('.detail-section').first().innerText(),/Google Play reviews were fetched in batches/);
  await page.getByText('Preview ingested reviews',{exact:false}).click();
  const evidenceIndices=[0,Math.floor(reviews.length/2),reviews.length-1];
  for(const index of evidenceIndices)await openEvidence(reviews[index]);
  const last=reviews.at(-1);
  await page.getByRole('textbox',{name:'Filter ingested reviews',exact:true}).fill(last.id);
  assert.equal(await page.getByRole('button',{name:`View review ${last.id}`,exact:true}).count(),1);
  const beforeReload=await session();assert.equal(beforeReload.dataset.id,data.id);assert.equal(beforeReload.history.length,0);
  await page.reload();await page.getByRole('button',{name:'New',exact:true}).waitFor();
  const restored=await session();assert.equal(restored.dataset.id,data.id);assert.equal(restored.dataset.content_hash,data.content_hash);assert.equal(restored.dataset.reviews.length,reviews.length);assert.deepEqual(restored.dataset.summary.pages,s.pages);assert.equal(restored.history.length,0);
  await page.setViewportSize({width:390,height:844});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.getByText('Preview ingested reviews',{exact:false}).click();
  await page.getByRole('textbox',{name:'Filter ingested reviews',exact:true}).fill(last.id);
  await openEvidence(last);
  assert.deepEqual(runtimeErrors,[]);assert.equal(result.url_import_requests,1);assert.equal(result.model_calls,0);
  Object.assign(result,{entity:data.entity_name,platform:data.platform,source_url:data.source_url,retrieval_provider:data.retrieval_provider,retrieval_freshness:data.retrieval_freshness,captured_at:data.captured_at,retrieved_at:data.retrieved_at,requested_reviews:s.pages.requested_reviews,batch_size:s.pages.batch_size,review_count:reviews.length,accepted:s.accepted,observed:s.observed,rejected:s.rejected,duplicates:s.duplicates,excluded:s.excluded,token_count:s.token_count,dated_review_count:dates.length,pages:s.pages,stopping_reason:s.stopping_reason,scanned_total_known:s.scanned_total_known,evidence_review_ids:evidenceIndices.map(index=>reviews[index].id)});
  result.checks=['one fresh Google Play URL import with target200 and batch size100, no chat','actual accepted count and normalization limits reconcile with observed records','rating/date statistics and duplicate removal match the captured sample','metadata page and review batches match API and visible provenance','stop reason applies to the selected locale/order, not all ratings','first, middle and final review evidence matches original text','final review remains searchable and accessible after reload and on mobile','dataset, page counts and empty history persist after reload'];
  result.passed=true;console.log(JSON.stringify(result,null,2));
 }catch(error){result.error={name:error.name,message:error.message};console.error(error.stack);process.exitCode=1}
 finally{await context.request.delete('/api/dataset').catch(()=>{});fs.writeFileSync(`${artifacts}/results.json`,JSON.stringify(result,null,2));await browser.close()}
})();
