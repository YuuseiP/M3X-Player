const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const shots = path.join(__dirname, "test-output");
require("node:fs").mkdirSync(shots, { recursive: true });

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true,
    args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 850 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert((await page.title()).includes("M3X Player"), "Connected to the M3X preview");
    const screen = page.locator(".m3x-screen");
    async function setLayout(layout) {
      await screen.locator('.m3x-nav [data-page="profile"]').click();
      await screen.locator('[data-panel="profile"] [data-page="settings"]').click();
      await screen.locator('[data-setting="layout"]').selectOption(layout);
    }
    assert.equal(await page.locator('[class*="preview-"], [data-layout-choice], #volume').count(), 0, "No preview-only interface");
    assert(!(await screen.locator("#playback-status").isVisible()), "No persistent implementation notice");
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 10);
    assert.equal(await page.locator("audio").count(), 1);
    assert.equal(await page.evaluate(() => audio.paused), true, "No autoplay on open");
    const tracks = await page.evaluate(() => window.M3X_TRACKS);
    assert.equal(tracks.length, 10);
    assert.equal(new Set(tracks.map(track => track.title)).size, 10);
    for (let index = 0; index < tracks.length; index++) {
      const track = tracks[index];
      const response = await page.request.get("http://127.0.0.1:18765/" + track.src, { headers: { Range: "bytes=0-3" } });
      assert.equal(response.status(), 206);
      assert.equal((await response.body()).toString(), "fLaC");
      assert.equal(response.headers()["content-range"], `bytes 0-3/${track.bytes}`);
      await screen.locator('.m3x-nav [data-page="library"]').click();
      await screen.locator(`[data-list="library"] [data-track="${index}"]`).click();
      await page.waitForFunction(() => !audio.paused && audio.currentTime > 0.08 && audio.readyState >= 2);
      assert.equal(await page.evaluate(() => audio.error), null, "Decodes track " + index);
      const duration = await page.evaluate(() => audio.duration);
      assert(Math.abs(duration - track.duration) < 1, "Actual duration matches source metadata");
      assert.equal(await screen.locator(".m3x-title").textContent(), track.title);
      assert.equal(await screen.locator('.m3x-mini [data-text="singer"]').textContent(), track.artist);
      assert.equal(await screen.locator(".m3x-mini-cover").getAttribute("src"), track.cover);
      assert(await screen.locator(".m3x-cover").evaluate(image => image.complete && image.naturalWidth > 0));
      console.log("Decoded:", track.title);
    }
    const badRange = await page.request.get("http://127.0.0.1:18765/media/01.flac", { headers: { Range: "bytes=99999999999-" } });
    assert.equal(badRange.status(), 416);
    const suffix = await page.request.get("http://127.0.0.1:18765/media/01.flac", { headers: { Range: "bytes=-8" } });
    assert.equal(suffix.status(), 206);
    assert.equal((await suffix.body()).length, 8);

    await screen.locator('.m3x-transport [data-action="play"]').click();
    assert(await page.evaluate(() => audio.paused));
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/01.flac") && !audio.paused);
    await screen.locator('[data-action="previous"]').click();
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/10.flac") && !audio.paused);
    await screen.locator('.m3x-transport [data-action="play"]').click();
    await screen.locator('.m3x-transport [data-action="play"]').click();
    await page.waitForFunction(() => !audio.paused);
    await screen.locator("#seek").fill("90");
    await page.waitForFunction(() => audio.currentTime >= 89.5 && audio.currentTime < 100);
    await screen.locator('[data-action="previous"]').click();
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/09.flac") && !audio.paused);
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/10.flac") && !audio.paused);

    await screen.locator(".m3x-output").click();
    assert(await screen.locator("#output-dialog").isVisible());
    assert(await screen.locator('[data-panel="player"]').isVisible());
    const before = await page.evaluate(() => audio.currentTime);
    await screen.locator('[data-sheet-tab="processing"]').click();
    assert(await screen.locator('[data-preference="eq"]').isEnabled());
    assert(await screen.locator('[data-preference="replaygain"]').isEnabled());
    await page.waitForTimeout(200);
    assert(await page.evaluate(() => !audio.paused && audio.currentTime > 0));
    assert(await page.evaluate(() => audio.currentTime) >= before);
    await page.keyboard.press("Shift+Tab");
    assert(await page.evaluate(() => document.querySelector("#output-dialog").contains(document.activeElement)));
    await page.keyboard.press("Escape");
    assert(!(await screen.locator("#output-dialog").isVisible()));
    assert(await screen.locator(".m3x-output").evaluate(button => document.activeElement === button));
    // Closing before the first animation frame must not leave a modal blocking the page.
    await screen.evaluate(element => {
      for (const selector of [".m3x-output", '[data-action="close-output"]']) {
        element.querySelector(selector).dispatchEvent(new MouseEvent("click", { bubbles: true, detail: 1 }));
      }
    });
    await screen.locator("#output-dialog").waitFor({ state: "hidden" });

    await screen.locator('[data-action="favorite"]').click();
    await screen.locator('.m3x-nav [data-page="library"]').click();
    await screen.locator('[data-filter="favorites"]').click();
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 1);
    await screen.locator('[data-filter="all"]').click();
    await screen.locator(".m3x-search").fill("ノクチル");
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 1);
    await screen.locator(".m3x-search").fill("nothing matches");
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 0);
    await screen.locator(".m3x-search").fill("");
    await setLayout("catalog");
    assert(await page.evaluate(() => !audio.paused));
    assert.equal(await screen.getAttribute("data-layout"), "catalog");
    await screen.locator('.m3x-nav [data-page="library"]').click();
    await screen.screenshot({ path: path.join(shots, "catalog.png") });

    // Real media end events exercise repeat-one and sequential advance.
    await screen.locator('.m3x-nav [data-page="player"]').click();
    await screen.locator('[data-action="mode"]').click();
    const previousSource = await page.evaluate(() => audio.currentSrc);
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(source => audio.currentSrc !== source && !audio.paused && Number.isFinite(audio.duration) && audio.readyState >= 2, previousSource);
    await screen.locator('[data-action="mode"]').click();
    const repeatSource = await page.evaluate(() => audio.currentSrc);
    await page.evaluate(() => { audio.currentTime = audio.duration - 0.3; });
    await page.waitForFunction(() => audio.currentTime < 2 && !audio.paused);
    assert.equal(await page.evaluate(() => audio.currentSrc), repeatSource);
    await screen.locator('[data-action="mode"]').click();
    await screen.locator('.m3x-nav [data-page="library"]').click();
    await screen.locator('[data-list="library"] [data-track="0"]').click();
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/01.flac") && audio.readyState >= 2);
    await page.evaluate(() => { audio.currentTime = audio.duration - 0.3; });
    await page.waitForFunction(() => audio.currentSrc.endsWith("/media/02.flac") && !audio.paused);

    await page.evaluate(() => audio.pause());
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert(await page.evaluate(() => audio.paused), "Reload does not autoplay");
    await screen.locator('[data-filter="favorites"]').click();
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 1, "Favorites persist");
    await screen.locator('[data-filter="all"]').click();
    for (const width of [1100, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      for (const layout of ["album", "catalog"]) {
        await setLayout(layout);
        for (const panel of ["library", "player", "queue"]) {
          await screen.locator('.m3x-nav [data-page="player"]').click();
          if (panel === "library") await screen.locator('.m3x-nav [data-page="library"]').click();
          if (panel === "queue") await screen.locator('.m3x-transport [data-page="queue"]').click();
          assert(await screen.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
          assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
        }
        await screen.locator('.m3x-nav [data-page="player"]').click();
        await screen.locator(".m3x-output").click();
        const bounds = await screen.locator("#output-dialog .m3x-sheet").boundingBox();
        const screenBounds = await screen.boundingBox();
        assert(bounds.x >= screenBounds.x - 1 && bounds.x + bounds.width <= screenBounds.x + screenBounds.width + 1);
        assert(bounds.height < screenBounds.height);
        await screen.locator('[data-action="close-output"]').click();
        await screen.locator("#output-dialog").waitFor({ state: "hidden" });
      }
    }
    await page.setViewportSize({ width: 1100, height: 850 });
    await setLayout("album");
    await screen.locator('.m3x-nav [data-page="library"]').click();
    await page.screenshot({ path: path.join(shots, "preview.png"), fullPage: true });
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    await screen.locator('.m3x-nav [data-page="player"]').click();
    await screen.locator(".m3x-output").click();
    assert.equal(await screen.locator("#output-dialog .m3x-sheet").evaluate(element => getComputedStyle(element).transform), "none");
    await page.screenshot({ path: path.join(shots, "preview-dark.png"), fullPage: true });
    await page.keyboard.press("Escape");
    await page.emulateMedia({ colorScheme: "light" });
    await page.setViewportSize({ width: 360, height: 640 });
    await page.screenshot({ path: path.join(shots, "player-clean.png"), fullPage: true });
    await screen.locator('.m3x-nav [data-page="library"]').click();
    await page.screenshot({ path: path.join(shots, "library-clean.png"), fullPage: true });
    assert(await screen.evaluate(element => element.clientHeight === innerHeight && element.clientWidth === innerWidth), "Phone UI fills the viewport");
    assert(!(await screen.locator("#playback-status").isVisible()), "Normal playback stays free of notices");
    await page.evaluate(() => audio.dispatchEvent(new Event("error")));
    assert(await screen.locator("#playback-status").isVisible(), "Real playback feedback remains visible");
    assert.equal(await screen.locator("#playback-status").textContent(), "无法读取音频，请尝试下一首");
    assert.deepEqual(errors, []);
    console.log("PASS: ten real FLAC tracks, HTTP ranges, native playback/seek/end/repeat/shuffle, search/favorites, cover/artist, sheets/focus, shared audio, persistence, responsive layouts.");
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
