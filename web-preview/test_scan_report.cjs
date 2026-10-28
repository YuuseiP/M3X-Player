const assert = require("node:assert/strict");
const fs = require("node:fs");
const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 320, height: 580 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const scan = { running: false, lastScan: Date.now(), failed: 25, cueFailed: 1, unsupported: 1,
      errors: Array.from({ length: 27 }, (_, i) => ({
        kind: i < 25 ? "audio" : i === 25 ? "cue" : "unsupported",
        path: `file:///music/very-long-folder/${i}-<img src=x onerror="window.injected=true">.flac`,
        message: "完整错误原因"
      })) };
    await page.route("**/api/library", async route => {
      const response = await route.fetch();
      await route.fulfill({ json: { ...await response.json(), scan } });
    });
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    await page.locator('.m3x-nav [data-page="profile"]').click();
    await page.locator('[data-panel="profile"] [data-page="settings"]').click();
    assert.equal(await page.locator("#scan-errors li").count(), 27);
    await page.locator("#scan-report summary").click();
    assert.match(await page.locator("#scan-report-summary").textContent(), /音频失败 25 · CUE 警告 1 · 不支持 1/);
    assert.equal(await page.locator("#scan-errors img").count(), 0);
    assert.equal(await page.evaluate(() => Boolean(window.injected)), false);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const downloading = page.waitForEvent("download");
    await page.locator('[data-action="scan-report-export"]').click();
    const download = await downloading;
    assert.equal(download.suggestedFilename(), "澄-扫描报告.json");
    assert.deepEqual(JSON.parse(fs.readFileSync(await download.path(), "utf8")), scan);
    assert.deepEqual(errors, []);
    console.log("Scan report: full 27 errors, separated counts, safe text, mobile layout and export passed");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
