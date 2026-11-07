const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
  const output = path.join(__dirname, 'results');
  fs.mkdirSync(output, {recursive:true});
  const target = path.join(__dirname, 'INFERNUM.html');
  const hasActual = fs.existsSync(target);
  const html = hasActual ? fs.readFileSync(target, 'utf8') : '<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#120608;color:#eee;font:18px sans-serif;display:grid;place-items:center;min-height:100dvh}</style><main>INFERNUM QA infrastructure smoke test. Actual artwork not yet provided.</main>';
  const browser = await chromium.launch({headless:true});
  const report = {mode:hasActual?'actual-html':'infrastructure-only',screens:[],errors:[]};
  for(const [w,h] of [[320,568],[360,640],[393,852],[412,915]]){
    const page=await browser.newPage({viewport:{width:w,height:h},deviceScaleFactor:1,isMobile:true,hasTouch:true});
    page.on('pageerror', e=>report.errors.push(String(e)));
    await page.setContent(html,{waitUntil:'load'});
    await page.screenshot({path:path.join(output,`screen-${w}x${h}.png`),fullPage:true});
    report.screens.push({width:w,height:h,scrollWidth:await page.evaluate(()=>document.documentElement.scrollWidth)});
    await page.close();
  }
  fs.writeFileSync(path.join(output,'report.json'),JSON.stringify(report,null,2));
  await browser.close();
  if(report.errors.length)process.exitCode=1;
})().catch(e=>{console.error(e);process.exitCode=1});
