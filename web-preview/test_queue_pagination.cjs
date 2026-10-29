const assert = require("node:assert/strict");
const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const tracks = Array.from({ length: 400 }, (_, index) => ({
      id: "fixture-" + index, title: "Track " + index, artist: "Artist", album: "Album", folder: "",
      duration: index === 0 ? 0 : 180, codec: "FLAC", bytes: 0, cover: "cover-placeholder.svg", src: "unused"
    }));
    await page.route("**/api/library", route => route.fulfill({ json: { tracks, options: {}, scan: { running: false } } }));
    await page.addInitScript(ids => localStorage.setItem("m3x-shiny-preview-v1", JSON.stringify({ trackId: ids[320], queue: ids })), tracks.map(track => track.id));
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert.equal(await page.locator('[data-list="library"] [data-track="0"] .m3x-track-details').textContent(), "—");
    assert.equal(await page.locator('[data-list="queue"] [data-track]').count(), 0, "Hidden lists are not built on startup");
    await page.locator('.m3x-nav [data-page="player"]').dispatchEvent("mousedown", { clientX: 200, clientY: 800 });
    assert.equal(await page.locator(".m3x-ripple").count(), 0, "Press feedback does not allocate overlay nodes");
    await page.locator('.m3x-nav [data-page="player"]').click();
    assert.equal(await page.locator('[data-list="upcoming"] [data-track]').count(), 0, "Album layout does not build its invisible upcoming list");
    await page.locator(".m3x-output").click();
    await page.evaluate(() => { document.getElementById("output-dialog").getAnimations = undefined; });
    await page.locator('[data-action="close-output"]').click();
    assert(await page.locator("#output-dialog").isVisible(), "Legacy WebView waits for the close animation");
    await page.locator("#output-dialog").waitFor({ state: "hidden" });
    await page.locator('.m3x-transport [data-page="queue"]').click();
    assert.equal(await page.locator('[data-list="queue"] [aria-current="true"]').count(), 0);
    await page.locator('[data-action="locate-current"]').click();
    assert.equal(await page.locator('[data-list="queue"] [aria-current="true"]').getAttribute("data-track"), "320");
    assert(await page.locator('[data-list="queue"] [aria-current="true"]').isVisible());
    const bounds = await page.locator('[data-list="queue"] [aria-current="true"]').boundingBox();
    const list = await page.locator('[data-list="queue"]').boundingBox();
    assert(bounds.y >= list.y && bounds.y + bounds.height <= list.y + list.height + 1);
    assert.equal(await page.evaluate(() => M3X_UI.back()), true);
    assert(await page.locator('[data-panel="player"]').isVisible());
    console.log("PASS: unknown duration marker, locating song 321 beyond first 150, scrolling into view and queue back navigation.");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
