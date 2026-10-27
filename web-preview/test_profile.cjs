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
    const screen = page.locator(".m3x-screen");
    const profile = screen.locator('[data-panel="profile"]');
    const settings = screen.locator('[data-panel="settings"]');
    const mine = screen.locator('.m3x-nav [data-page="profile"]');
    const settingsLink = profile.locator('[data-page="settings"]');
    assert.deepEqual(await screen.locator(".m3x-nav button").allTextContents(), ["曲库", "播放", "我的"]);
    assert.equal(await screen.locator('.m3x-nav [data-action="output"]').count(), 0);
    await mine.click();
    assert(await profile.isVisible());
    assert(await screen.locator(".m3x-mini").isVisible());
    assert.equal(await profile.locator('[data-text="favoriteCount"]').textContent(), "0");
    assert.equal(await profile.locator('[data-text="historyCount"]').textContent(), "0 首");
    assert.equal(await profile.locator('[data-list="recent"] [data-track]').count(), 0, "History is initially empty");
    assert.equal(await profile.locator("#nickname").inputValue(), "本地听众");
    assert.equal(await page.locator("#profile-form").count(), 0, "No duplicate name form");
    const originalNameBounds = await profile.locator("#nickname").boundingBox();
    await profile.locator('[data-action="edit-profile"]').click();
    await profile.locator("#nickname").fill("  羽那与音乐  ");
    assert.deepEqual(await profile.locator("#nickname").boundingBox(), originalNameBounds, "Edits in place");
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")).nickname), "羽那与音乐", "Autosaves while typing");
    await profile.locator("#nickname").press("Enter");
    assert.equal(await profile.locator("#nickname").inputValue(), "羽那与音乐");
    await profile.locator('[data-action="open-library"]').click();
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 10);
    await screen.locator('[data-list="library"] [data-track="5"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentTime > 0.1);
    await screen.locator('[data-action="favorite"]').click();
    await mine.click();
    assert.equal(await profile.locator('[data-text="favoriteCount"]').textContent(), "1");
    assert.equal(await profile.locator('[data-text="historyCount"]').textContent(), "1 首");
    assert.equal(await profile.locator('[data-list="recent"] [data-track="5"]').count(), 1);
    await profile.locator('[data-action="open-favorites"]').click();
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 1);
    await screen.locator(".m3x-search").fill("no result");
    await mine.click();
    await profile.locator('[data-action="open-favorites"]').click();
    assert.equal(await screen.locator(".m3x-search").inputValue(), "");
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 1);
    await mine.click();
    await page.screenshot({ path: path.join(shots, "profile.png"), fullPage: true });
    await settingsLink.click();
    assert(await settings.isVisible());
    assert.equal(await mine.getAttribute("aria-current"), "page");
    const before = await page.evaluate(() => audio.currentTime);
    const source = await page.evaluate(() => audio.currentSrc);
    await settings.locator('[data-setting="theme"]').selectOption("dark");
    assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme), "dark");
    await page.emulateMedia({ colorScheme: "light" });
    assert.equal(await page.evaluate(() => getComputedStyle(document.querySelector("#m3x-concept")).colorScheme), "dark");
    await settings.locator('[data-setting="theme"]').selectOption("light");
    assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme), "light");
    await settings.locator('[data-setting="layout"]').selectOption("catalog");
    assert.equal(await screen.getAttribute("data-layout"), "catalog");
    assert.equal(await settings.locator('[data-setting="layout"]').inputValue(), "catalog");
    await settings.locator('[data-setting="startPage"]').selectOption("profile");
    assert.equal(await settings.locator("#settings-volume").count(), 0);
    assert.equal(await page.evaluate(() => audio.volume), 1, "Only system volume is used");
    assert.equal(await page.evaluate(() => audio.currentSrc), source);
    assert(await page.evaluate(() => !audio.paused && audio.currentTime > 0));
    assert(await page.evaluate(() => audio.currentTime) > before, "Playback continues through profile/settings");
    await page.screenshot({ path: path.join(shots, "settings.png"), fullPage: true });
    await settings.locator('[data-setting="rememberPosition"]').uncheck();
    await page.evaluate(() => { audio.currentTime = 60; audio.pause(); });
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert(await profile.isVisible(), "Configured start page is used");
    assert(await page.evaluate(() => audio.paused));
    assert.equal(await profile.locator("#nickname").inputValue(), "羽那与音乐");
    assert.equal(await profile.locator('[data-text="favoriteCount"]').textContent(), "1");
    assert.equal(await profile.locator('[data-list="recent"] [data-track="5"]').count(), 1);
    await page.waitForFunction(() => audio.readyState >= 1);
    assert.equal(await page.evaluate(() => audio.currentTime), 0, "Disabled position memory starts at zero");
    assert.equal(await page.evaluate(() => audio.volume), 1);
    await settingsLink.click();
    assert(!(await settings.locator('[data-setting="rememberPosition"]').isChecked()));
    await settings.locator('[data-setting="rememberPosition"]').check();
    await settings.locator('[data-setting="startPage"]').selectOption("player");
    await screen.locator('.m3x-nav [data-page="player"]').click();
    await screen.locator('.m3x-transport [data-action="play"]').click();
    await page.waitForFunction(() => !audio.paused);
    await screen.locator("#seek").fill("45");
    await screen.locator('.m3x-transport [data-action="play"]').click();
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert(await screen.locator('[data-panel="player"]').isVisible());
    await page.waitForFunction(() => audio.readyState >= 1 && audio.currentTime >= 44.5);
    assert(await page.evaluate(() => audio.paused), "Remembering position does not autoplay");
    await mine.click();
    await settingsLink.click();
    await settings.locator('[data-setting="startPage"]').selectOption("library");
    await settings.locator('[data-setting="theme"]').selectOption("system");
    await page.emulateMedia({ colorScheme: "dark" });
    assert.equal(await settings.locator('[data-setting="theme"]').inputValue(), "system");
    assert.equal(await screen.evaluate(element => getComputedStyle(element).backgroundColor), "rgb(25, 26, 24)");
    await page.emulateMedia({ colorScheme: "light" });
    assert.equal(await screen.evaluate(element => getComputedStyle(element).backgroundColor), "rgb(245, 242, 235)");

    for (const width of [1100, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      for (const layout of ["album", "catalog"]) {
        await mine.click();
        await settingsLink.click();
        await settings.locator('[data-setting="layout"]').selectOption(layout);
        await mine.click();
        for (const panel of [profile, settings]) {
          if (panel === settings) await settingsLink.click();
          assert(await panel.isVisible());
          assert(await screen.locator(".m3x-mini").isVisible());
          assert(await panel.evaluate(element => element.scrollWidth <= element.clientWidth + 1), "Panel fits at " + width);
          assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
          const content = await screen.locator(".m3x-content").boundingBox();
          const mini = await screen.locator(".m3x-mini").boundingBox();
          assert(content.y + content.height <= mini.y + 1, "Mini player is below content");
        }
      }
    }
    await page.setViewportSize({ width: 1100, height: 850 });
    await settings.locator('[data-setting="theme"]').selectOption("dark");
    await settings.locator('[data-setting="layout"]').selectOption("album");
    await settings.locator('[data-page="profile"]').click();
    await page.screenshot({ path: path.join(shots, "profile-dark.png"), fullPage: true });
    assert.deepEqual(errors, [], "No runtime errors");
    console.log("PASS: inline autosaved nickname, real history, favorites, theme/layout, no app volume, startup/position memory, persistence, uninterrupted playback, 320/390/1100px.");
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
