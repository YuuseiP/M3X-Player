'use strict';
// Runs against disposable screenshots. Never writes into a real capture batch.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {chromium} = require('playwright');
const root = __dirname;
const run = 'appearance-test';
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function eventually(check) {
  for (let i = 0; i < 80; i++) {
    try { if (await check()) return; } catch {}
    await delay(100);
  }
  throw new Error('Timed out waiting for saved draft');
}
async function main() {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'm3x-review-browser-'));
  let server, browser;
  try {
    const fixture = spawn(process.env.PYTHON || 'python', ['-c', 'import sys;sys.path.insert(0,sys.argv[1]);from test_appearance_review import fixture;fixture(sys.argv[2])', root, temp]);
    let fixtureError = '';
    fixture.stderr.on('data', chunk => fixtureError += chunk);
    assert.equal(await new Promise(resolve => fixture.on('exit', resolve)), 0, fixtureError);
    server = spawn(process.env.PYTHON || 'python', ['-u', path.join(root, 'appearance-review.py'), '--captures', temp, '--port', '0'], {env:{...process.env, PYTHONUTF8:'1'}});
    let serviceOutput = '';
    server.stdout.on('data', chunk => serviceOutput += chunk);
    server.stderr.on('data', chunk => serviceOutput += chunk);
    await eventually(() => /http:\/\/127\.0\.0\.1:\d+\//.test(serviceOutput));
    const url = serviceOutput.match(/http:\/\/127\.0\.0\.1:\d+\//)[0];
    browser = await chromium.launch({executablePath:process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', headless:true});
    const context = await browser.newContext({viewport:{width:1440,height:1000}, acceptDownloads:true});
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    page.on('console', message => {if(message.type() === 'error' && /Content Security Policy|Refused to/.test(message.text())) errors.push(message.text());});
    const draftPath = path.join(temp,run,'review','draft.json');
    const readDraft = async () => JSON.parse(await fs.readFile(draftPath,'utf8'));
    await page.goto(url);
    await page.waitForFunction(() => document.querySelectorAll('.card').length === 2);
    await page.locator('[data-theme="dark"]').click();
    assert.equal(await page.locator('.card').count(),1);
    await page.locator('[data-theme="all"]').click();
    await page.locator('#global-details summary').click();
    await page.locator('#global-note').fill('Overall: reduce excess whitespace');
    await eventually(async () => (await readDraft()).globalNote === 'Overall: reduce excess whitespace');
    await page.locator('#card-light-player').click();
    await page.waitForFunction(() => document.querySelector('#canvas').getContext('2d').getImageData(0,0,1,1).data[3] === 255);
    async function draw(kind, start, end) {
      await page.locator(`[data-tool="${kind}"]`).click();
      const bounds = await page.locator('#canvas').boundingBox();
      await page.mouse.move(bounds.x+bounds.width*start[0],bounds.y+bounds.height*start[1]);
      await page.mouse.down();
      if(end) await page.mouse.move(bounds.x+bounds.width*end[0],bounds.y+bounds.height*end[1],{steps:8});
      await page.mouse.up();
    }
    await draw('rect',[.1,.2],[.6,.45]);
    await page.locator('#annotation-text').fill('Rectangle: reduce the cover margin');
    await page.locator('#page-note').fill('Keep the cover centered');
    await page.locator('#editor-save').click();
    await eventually(async () => (await readDraft()).pages['light-player']?.annotations[0]?.text === 'Rectangle: reduce the cover margin');
    let saved = await readDraft();
    assert.ok(Math.abs(saved.pages['light-player'].annotations[0].points[1][0]-.6)<.01);
    assert.ok(saved.pages['light-player'].annotatedFile);
    assert.ok((await fs.readFile(path.join(temp,run,'review','AGENT_DRAFT.md'),'utf8')).includes('reduce the cover margin'));
    await fs.access(path.join(temp,run,'review','annotated',saved.pages['light-player'].annotatedFile));
    await page.locator('#undo').click();
    assert.equal(await page.locator('.annotation-row').count(),0);
    await page.locator('#redo').click();
    assert.equal(await page.locator('.annotation-row').count(),1);
    assert.equal(await page.locator('#annotation-text').inputValue(),'Rectangle: reduce the cover margin');
    await page.locator('#zoom').selectOption('1.5');
    await page.locator('#stage').evaluate(element=>{element.scrollTop=0;element.scrollLeft=0;});
    await draw('arrow',[.03,.04],[.2,.12]);
    await page.locator('#annotation-text').fill('Arrow at 150 percent');
    await page.locator('#editor-save').click();
    await eventually(async () => (await readDraft()).pages['light-player']?.annotations.length===2);
    saved = await readDraft();
    assert.ok(Math.abs(saved.pages['light-player'].annotations[1].points[1][0]-.2)<.01);
    await page.locator('#zoom').selectOption('fit');
    await draw('pin',[.7,.5]);
    await draw('pen',[.2,.65],[.5,.7]);
    await page.locator('#editor-save').click();
    await eventually(async () => (await readDraft()).pages['light-player']?.annotations.length===4);
    await page.locator('#close-editor').click();
    await page.reload();
    await page.locator('#card-light-player').click();
    assert.equal(await page.locator('.annotation-row').count(),4);
    assert.equal(await page.locator('#page-note').inputValue(),'Keep the cover centered');
    await page.locator('#close-editor').click();
    await page.locator('#only-marked').click();
    assert.equal(await page.locator('.card').count(),1);
    await page.locator('#only-marked').click();
    await page.locator('#search').fill('dark-player');
    assert.equal(await page.locator('.card').count(),1);
    await page.locator('#search').fill('');
    // Two simultaneous editors: the later writer must receive a conflict, not overwrite.
    const secondContext = await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true});
    const second = await secondContext.newPage();
    await second.goto(url);
    await second.waitForFunction(() => document.querySelectorAll('.card').length===2);
    await page.locator('#global-details summary').click();
    await page.locator('#global-note').fill('Newest disk note');
    await page.locator('#save').click();
    await eventually(async () => (await readDraft()).globalNote==='Newest disk note');
    await second.locator('#global-details summary').click();
    await second.locator('#global-note').fill('Conflicting note must not overwrite');
    await second.locator('#save').click();
    await second.locator('#reload-draft').waitFor({state:'visible'});
    assert.equal((await readDraft()).globalNote,'Newest disk note');
    const backup = second.waitForEvent('download');
    await second.locator('#reload-draft').click();
    assert.ok((await backup).suggestedFilename().endsWith('-draft-backup.json'));
    await second.waitForFunction(() => document.querySelector('#global-note').value==='Newest disk note');
    assert.equal(await second.locator('#error').isHidden(),true);
    await secondContext.close();
    // Failed saves retain an emergency backup, then recover after connectivity returns.
    await page.route('**/api/draft?*',route=>route.abort('connectionfailed'));
    await page.locator('#global-note').fill('Recovered after disconnected save');
    await page.locator('#save').click();
    await page.locator('#error').waitFor({state:'visible'});
    assert.equal((await readDraft()).globalNote,'Newest disk note');
    assert.ok(await page.evaluate(run=>localStorage.getItem('m3x-appearance-draft:'+run),run));
    await page.unroute('**/api/draft?*');
    await page.reload();
    await eventually(async () => (await readDraft()).globalNote==='Recovered after disconnected save');
    await page.waitForFunction(() => document.querySelectorAll('.card').length===2);
    await page.locator('#error').waitFor({state:'hidden'});
    assert.deepEqual(errors,[]);
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
    await page.locator('#card-dark-player').click();
    await page.locator('#page-note').fill('Mobile note');
    await page.locator('#editor-save').click();
    await eventually(async () => (await readDraft()).pages['dark-player']?.note==='Mobile note');
    console.log('PASS: filters, four drawing tools, zoom coordinates, undo/redo, disk PNG and summary, autosave, reload, conflicts, offline recovery, mobile layout.');
  } finally {
    if(browser) await browser.close();
    if(server && server.exitCode===null) {
      const ended = new Promise(resolve=>server.once('exit',resolve));
      server.kill(); await ended;
    }
    if(path.dirname(temp)===os.tmpdir() && path.basename(temp).startsWith('m3x-review-browser-')) await fs.rm(temp,{recursive:true,force:true});
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
