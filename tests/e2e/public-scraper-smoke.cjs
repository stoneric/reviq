#!/usr/bin/env node
// Two real public imports and one AI question. Reports retain metadata only,
// never captured page HTML, customer review bodies, authors, or model answers.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const baseURL = process.env.REVIQ_BASE_URL || 'https://reviq.xyz';
const artifacts = process.env.REVIQ_E2E_ARTIFACTS || '/tmp/reviq-public-scraper';
const sourceList = [
  ['Spotify', 'https://play.google.com/store/apps/details?id=com.spotify.music'],
  ['WhatsApp', 'https://play.google.com/store/apps/details?id=com.whatsapp'],
];
const sources=process.env.REVIQ_SCRAPER_APPS==='whatsapp'?sourceList.slice(1):sourceList;
(async () => {
  const browser = await chromium.launch({executablePath:process.env.CHROMIUM_PATH||'/usr/bin/chromium',headless:true,args:['--no-sandbox']});
  const context = await browser.newContext({baseURL,viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  const page = await context.newPage();
  fs.mkdirSync(artifacts,{recursive:true});
  const result={run_at:new Date().toISOString(),base_url:baseURL,passed:false,imports:[],checks:[]};
  const errors=[];
  page.on('pageerror',error=>errors.push(error.name));
  let active;
  let chatCapture=Promise.resolve();
  let privateChatBody='';
  page.on('response',response=>{
    if(new URL(response.url()).pathname==='/api/chat'&&response.request().method()==='POST'){
      chatCapture=(async()=>{
        const body=await response.text();
        privateChatBody=body;
        result.chat_events=[];
        for(const block of body.split('\n\n')){
          const kind=block.match(/^event: (.+)$/m)?.[1];
          const raw=block.match(/^data: (.+)$/m)?.[1];
          if(!raw)continue;
          let data;try{data=JSON.parse(raw)}catch{continue}
          if(kind==='error')result.chat_events.push({event:kind,code:data.code||data.error?.code,message:data.message||data.error?.message});
          if(kind==='done')result.chat_events.push({event:kind,verified:data.verified,citation_count:data.citations?.length||0,outcome:data.answer?.includes('I can only answer from the reviews loaded for this dataset.')?'scope_refusal':data.answer?.includes('The supplied reviews do not provide enough evidence to answer that.')?'insufficient_evidence':'answer'});
        }
      })().catch(()=>{result.chat_events=[{event:'capture_unavailable'}]});
    }
  });
  try {
    const response=await page.goto('/demo');
    assert.equal(response.status(),200);
    await page.getByRole('heading',{level:1}).waitFor();
    const initial=await(await context.request.get('/api/session')).json();
    assert.equal(initial.capabilities.live_scraping,true);
    assert.deepEqual(initial.capabilities.supported_platforms,['Google Play']);
    assert.equal(initial.capabilities.ai_enabled,true);
    result.model=initial.capabilities.model;
    result.prompt_version=initial.capabilities.prompt_version;
    await page.getByRole('textbox',{name:'Review page URL',exact:true}).fill('https://example.com/reviews');
    let requests=0;
    const listener=req=>{if(req.method()==='POST'&&new URL(req.url()).pathname==='/api/ingest')requests++};
    page.on('request',listener);
    await page.getByRole('button',{name:'Ingest reviews',exact:true}).click();
    await page.getByRole('button',{name:'Upload CSV instead'}).waitFor();
    assert.equal(requests,0,'Unsupported URL should be rejected before a network request');
    page.off('request',listener);
    result.checks.push('unsupported platform rejected locally with CSV/paste recovery');
    for(const [name,url] of sources){
      if(active)await page.getByRole('button',{name:'New',exact:true}).click();
      await page.getByRole('textbox',{name:'Review page URL',exact:true}).fill(url);
      const started=Date.now();
      await page.getByRole('button',{name:'Ingest reviews',exact:true}).click();
      await page.getByRole('button',{name:'New',exact:true}).waitFor({timeout:25000});
      const state=await(await context.request.get('/api/session')).json();
      const data=state.dataset;
      assert.ok(data,'A dataset should be returned');
      if(active)assert.notEqual(data.id,active.id);
      assert.equal(data.mode,'scraped');
      assert.equal(data.synthetic,false);
      assert.equal(data.platform,'Google Play');
      assert.match(data.entity_name,new RegExp(name,'i'));
      assert.equal(data.source_url,`${url}&hl=en&gl=US`);
      assert.ok(data.captured_at&&Math.abs(Date.now()-Date.parse(data.captured_at))<120000,'Capture timestamp must reflect this fetch');
      assert.ok(data.reviews.length>0&&data.reviews.length<=200);
      assert.deepEqual(data.summary.pages,{attempted:1,fetched:1,parsed:1});
      assert.equal(data.summary.stopping_reason,'visible_page_sample');
      assert.equal(data.summary.scanned_total_known,false);
      assert.equal(data.summary.observed,data.summary.accepted+data.summary.rejected+data.summary.duplicates+data.summary.excluded);
      assert.equal(await page.getByText('Synthetic sample for exploring Reviq.',{exact:false}).count(),0);
      assert.deepEqual(state.history,[]);
      await page.getByText('Provenance & coverage',{exact:true}).click();
      for(const label of ['Pages attempted','Pages fetched','Pages parsed']){
        const value=page.getByText(label,{exact:true}).locator('..').locator('dd');
        assert.equal(await value.innerText(),'1');
      }
      await page.getByText('Preview ingested reviews',{exact:false}).click();
      const first=data.reviews[0];
      await page.getByRole('button',{name:`View review ${first.id}`,exact:true}).click();
      const drawer=page.getByRole('dialog',{name:`Original review ${first.id}`});
      assert.ok(await drawer.locator('blockquote').textContent()===first.text,'Evidence must match exact imported text');
      await page.getByRole('button',{name:'Close evidence'}).click();
      result.imports.push({entity:data.entity_name,source_url:data.source_url,captured_at:data.captured_at,mode:data.mode,review_count:data.reviews.length,observed:data.summary.observed,rejected:data.summary.rejected,duplicates:data.summary.duplicates,excluded:data.summary.excluded,pages:data.summary.pages,stopping_reason:data.summary.stopping_reason,scanned_total_known:data.summary.scanned_total_known,duration_ms:Date.now()-started});
      active=data;
    }
    result.checks.push(`${sources.length} fresh real Google Play imports`,'canonical source URL and fresh capture timestamp','bounded partial-page provenance','accurate accepted/rejected/duplicate/excluded counts','original evidence text matches without synthetic labels');
    if(sources.length>1)result.checks.push('dataset replacement clears prior context');
    await page.getByLabel('Question',{exact:true}).fill(process.env.REVIQ_SCRAPER_QUESTION||'Summarize the reviewers’ feedback in this sample using exact supporting excerpts and review citations.');
    const responseReady=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/chat'&&response.request().method()==='POST');
    await page.getByRole('button',{name:'Send question',exact:true}).click();
    const completedChatResponse=await responseReady;
    await completedChatResponse.finished();
    const answer=page.getByTestId('assistant-message').last();
    await answer.waitFor();
    await page.getByRole('button',{name:'Send question',exact:true}).waitFor({timeout:70000});
    await chatCapture;
    if((await answer.getAttribute('class')).includes('message-error'))result.assistant_error=await answer.locator('.message-content').innerText();
    const citations=answer.getByRole('button',{name:/^View review R/});
    assert.ok(await citations.count()>0,'Real imported data should produce a cited answer');
    const text=await answer.locator('.message-content').innerText();
    const quotes=[...text.matchAll(/["“]([^"”\n]+)["”]/g)].map(match=>match[1]);
    assert.ok(quotes.length>0,'Supported answer should use exact excerpts');
    assert.ok(quotes.every(quote=>active.reviews.some(review=>review.text.includes(quote))),'Quoted spans must exist in original imported reviews');
    const firstCitation=citations.first();
    const id=(await firstCitation.getAttribute('aria-label')).replace('View review ','');
    assert.ok(active.reviews.some(review=>review.id===id));
    await firstCitation.click();
    assert.ok(await page.getByRole('dialog',{name:`Original review ${id}`}).locator('blockquote').textContent()===active.reviews.find(review=>review.id===id).text,'AI citation should resolve original source text');
    await page.getByRole('button',{name:'Close evidence'}).click();
    await page.reload();
    await page.getByTestId('assistant-message').waitFor();
    const restored=await(await context.request.get('/api/session')).json();
    assert.equal(restored.dataset.id,active.id);
    assert.equal(restored.history.length,2);
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
    assert.deepEqual(errors,[]);
    result.checks.push('live AI question grounded in imported reviews','answer quote spans validated against original text','citations resolve exact evidence','dataset and completed answer persist after reload','no browser errors or overflow');
    result.validated_quote_count=quotes.length;
    result.passed=true;
    console.log(JSON.stringify(result,null,2));
  }catch(error){
    result.error={name:error.name,message:'A live scraper acceptance assertion failed; inspect the private local run output. No captured customer text is included in this report.'};
    await chatCapture;
    if(process.env.REVIQ_PRIVATE_DIAGNOSTICS==='1'){
      const directory=fs.realpathSync(artifacts),temporary=fs.realpathSync(os.tmpdir()),project=path.resolve(__dirname,'../..');
      if(directory.startsWith(temporary+path.sep)&&!directory.startsWith(project+path.sep)){
        const fd=fs.openSync(path.join(directory,'private-failure.json'),fs.constants.O_WRONLY|fs.constants.O_CREAT|fs.constants.O_TRUNC|fs.constants.O_NOFOLLOW,0o600);
        try{fs.writeFileSync(fd,JSON.stringify({dataset:active,sse:privateChatBody}))}finally{fs.closeSync(fd)}
      }else result.private_diagnostics_omitted=true;
    }
    console.error(JSON.stringify({error:error.stack,chat_events:result.chat_events},null,2));
    process.exitCode=1;
  }finally{
    await context.request.delete('/api/dataset').catch(()=>{});
    fs.writeFileSync(`${artifacts}/results.json`,JSON.stringify(result,null,2));
    await browser.close();
  }
})();
