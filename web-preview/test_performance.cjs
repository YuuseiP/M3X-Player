const assert = require("node:assert/strict");
const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const tracks = Array.from({ length: 4200 }, (_, index) => ({
      id: "fixture-" + index, title: "Track " + index, artist: "Artist", album: "Album",
      folder: "", duration: 180, codec: "FLAC", bytes: 0, cover: "cover-placeholder.svg", src: "unused"
    }));
    await page.route("**/api/library", route => route.fulfill({ json: { tracks, options: {}, scan: { running: false } } }));
    await page.addInitScript(ids => {
      window.M3X_NATIVE = true;
      window.nativeCalls = [];
      window.nativeCommand = name => { nativeCalls.push(name); return {}; };
      localStorage.setItem("m3x-shiny-preview-v1", JSON.stringify({ trackId: ids[320], queue: ids }));
    }, tracks.map(track => track.id));
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => !!window.M3X_UI);
    assert.equal(await page.locator('[data-list="library"] [data-track]').count(), 40);
    await page.evaluate(() => { window.firstRow = document.querySelector('[data-list="library"]').firstChild; });
    for (const section of ["profile", "player", "library", "profile", "library"]) {
      await page.locator(`.m3x-nav [data-page="${section}"]`).click();
    }
    assert(await page.evaluate(() => firstRow === document.querySelector('[data-list="library"]').firstChild), "Unchanged lists retain rows and scroll state");
    assert.deepEqual(await page.evaluate(() => nativeCalls), [], "Navigation does not save or rebuild the native queue");
    assert.equal(await page.locator('[data-list="upcoming"] [data-track]').count(), 0);
    await page.locator('[data-more-list="library"]').click();
    assert.equal(await page.locator('[data-list="library"] [data-track]').count(), 80);
    await page.locator('.m3x-nav [data-page="player"]').click();
    await page.locator(".m3x-output").click();
    assert.equal(await page.locator("#output-dialog .m3x-sheet").evaluate(element => getComputedStyle(element).animationName), "m3x-sheet-in");
    await page.locator('[data-action="close-output"]').click();
    await page.locator("#output-dialog").waitFor({ state: "hidden" });
    await page.locator('[data-action="add-playlist"]').click();
    await page.evaluate(() => {
      const dialog = document.getElementById("playlist-dialog");
      M3X_UI.closeDialog(dialog);
      setTimeout(() => M3X_UI.openDialog(dialog), 30);
    });
    await page.waitForTimeout(220);
    assert(await page.locator("#playlist-dialog").isVisible(), "A stale close timer cannot close a reopened sheet");
    await page.keyboard.press("Escape");
    await page.locator("#playlist-dialog").waitFor({ state: "hidden" });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.locator('[data-action="add-playlist"]').click();
    assert.equal(await page.locator("#playlist-dialog .m3x-sheet").evaluate(element => getComputedStyle(element).animationName), "none");
    await page.locator("#playlist-dialog [data-close]").click();
    assert(!(await page.locator("#playlist-dialog").isVisible()));
    assert.deepEqual(errors, []);
    console.log("PASS: 4200-track native pagination, stable rows, no navigation saves, hidden lists, sheet animations/reopen/cancel and reduced motion.");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
