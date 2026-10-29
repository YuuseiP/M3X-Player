const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe", headless: true
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 }, hasTouch: true });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const track = { id: "touch-fixture", src: "native/touch-fixture", title: "Touch fixture", artist: "Artist",
      album: "Album", duration: 180, codec: "FLAC", bytes: 0, cover: "cover-placeholder.svg" };
    await page.addInitScript(() => {
      window.M3XNative = {
        bootstrap: () => JSON.stringify({ ui: {}, tools: {}, audio: {} }),
        command: () => JSON.stringify({ id: "", paused: true, duration: 180, position: 0 }),
        request: () => JSON.stringify({})
      };
    });
    await page.route("**/tracks.js", route => route.fulfill({
      contentType: "text/javascript",
      body: fs.readFileSync(path.join(__dirname, "../android/native-bridge.js"), "utf8") + "\nwindow.M3X_TRACKS=[];"
    }));
    await page.route("**/native-api/library", route => route.fulfill({ json: { tracks: [track], scan: {}, options: {} } }));
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => !!window.M3X_UI);
    await page.evaluate(() => {
      window.clicks = 0;
      document.querySelector('.m3x-nav [data-page="profile"]').addEventListener("click", () => clicks++);
    });
    const profile = page.locator('.m3x-nav [data-page="profile"]');
    await profile.tap();
    await page.waitForTimeout(400);
    assert.equal(await page.evaluate(() => clicks), 1, "A tap is dispatched once, without a delayed ghost click");
    assert(await page.locator('[data-panel="profile"]').isVisible());
    for (const mode of ["drag", "cancel", "multi"]) {
      await page.evaluate(mode => {
        const button = document.querySelector('.m3x-nav [data-page="profile"]');
        const touch = x => new Touch({ identifier: 1, target: button, clientX: x, clientY: 100 });
        const send = (type, touches, changed) => button.dispatchEvent(new TouchEvent(type, {
          bubbles: true, cancelable: true, touches, changedTouches: changed
        }));
        const start = touch(100);
        send("touchstart", mode === "multi" ? [start, new Touch({ identifier: 2, target: button })] : [start], [start]);
        if (mode === "drag") send("touchmove", [touch(125)], [touch(125)]);
        if (mode === "cancel") send("touchcancel", [], [start]);
        send("touchend", [], [start]);
      }, mode);
    }
    assert.equal(await page.evaluate(() => clicks), 1, "Scrolling, cancelled gestures and multi-touch are not clicks");
    await profile.focus();
    await page.keyboard.press("Enter");
    assert.equal(await page.evaluate(() => clicks), 2, "Keyboard activation still works");
    await page.locator('.m3x-nav [data-page="player"]').tap();
    await page.locator('[data-action="add-playlist"]').tap();
    await page.locator("#playlist-name").fill("Touch playlist");
    await page.locator('#playlist-form button[type="submit"]').tap();
    await page.locator("#playlist-dialog").waitFor({ state: "hidden" });
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")).playlists[0].name), "Touch playlist");
    assert.deepEqual(errors, []);
    console.log("PASS: native touch activates once; drag/cancel/multi-touch ignored; keyboard and form submission preserved.");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
