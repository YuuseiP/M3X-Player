const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

(async () => {
  const base = "http://127.0.0.1:18765";
  const data = await (await fetch(base + "/api/library")).json();
  const favoriteIds = data.tracks.slice(0, 160).map(track => track.id);
  assert.equal(favoriteIds.length, 160, "Use the active full library for pagination coverage");
  const seeded = {
    favorites: favoriteIds,
    playlists: [{ id: "saved-playlist", name: "已保存的歌单", trackIds: favoriteIds.slice(0, 2) }]
  };
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(value => {
      if (!localStorage.getItem("m3x-shiny-preview-v1")) localStorage.setItem("m3x-shiny-preview-v1", JSON.stringify(value));
    }, seeded);
    await page.goto(base);
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    const nav = name => page.locator(`.m3x-nav [data-page="${name}"]`);
    const profile = page.locator('[data-panel="profile"]');
    const favorites = page.locator('[data-panel="favorites"]');
    const playlists = page.locator('[data-panel="playlists"]');
    const detail = page.locator('[data-panel="playlist"]');
    const persisted = () => page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")));
    assert.equal(await page.locator('[data-filter="favorites"], [data-filter="playlists"]').count(), 0);
    assert.equal(await page.locator('[data-panel="library"] [data-action="new-playlist"]').count(), 0);
    await page.getByLabel("搜索曲库").fill("unrelated library search");
    await nav("profile").click();
    assert.equal(await profile.locator('[data-text="favoriteCount"]').textContent(), "160");
    assert.equal(await profile.locator('[data-text="playlistTotal"]').textContent(), "1");
    const shots = path.join(__dirname, "test-output");
    fs.mkdirSync(shots, { recursive: true });
    await page.emulateMedia({ colorScheme: "dark" });
    await page.locator(".m3x-screen").screenshot({ path: path.join(shots, "profile-collections.png") });
    for (const width of [320, 390, 1100]) {
      await page.setViewportSize({ width, height: 844 });
      const panelBounds = await profile.boundingBox();
      for (const action of ["open-favorites", "open-playlists"]) {
        const row = profile.locator(`[data-action="${action}"]`);
        const bounds = await row.boundingBox();
        assert.equal(bounds.width, panelBounds.width, "Collection entries occupy a full horizontal row");
        assert(bounds.height >= 44, "Touch targets stay accessible");
      }
      assert(await profile.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
    }
    await profile.locator('[data-action="open-favorites"]').click();
    assert(await favorites.isVisible());
    assert.equal(await nav("profile").getAttribute("aria-current"), "page");
    assert.equal(await favorites.locator("[data-track]").count(), 150, "Library search does not leak into favorites");
    await favorites.locator("[data-track]").first().click();
    assert.deepEqual((await persisted()).queue, favoriteIds, "Favorite queue includes songs beyond the rendered first page");
    await page.waitForFunction(() => !audio.paused);
    const source = await page.evaluate(() => audio.currentSrc);
    await nav("profile").click();
    await profile.locator('[data-action="open-favorites"]').click();
    assert.equal(await page.evaluate(() => audio.currentSrc), source);
    assert(await page.evaluate(() => !audio.paused), "Moving between collections does not interrupt playback");
    await favorites.getByLabel("收藏歌曲排序").selectOption("duration-desc");
    const durations = await favorites.locator("[data-track]").evaluateAll(buttons =>
      buttons.map(button => window.M3X_UI.tracks[Number(button.dataset.track)].duration));
    assert.deepEqual(durations, [...durations].sort((a, b) => b - a));
    await favorites.getByLabel("搜索收藏", { exact: true }).fill("no matching favorite");
    assert.equal(await favorites.locator("[data-track]").count(), 0);
    await favorites.getByLabel("返回我的").click();
    await profile.locator('[data-action="open-favorites"]').click();
    assert.equal(await favorites.getByLabel("搜索收藏", { exact: true }).inputValue(), "");
    await favorites.getByLabel("返回我的").click();
    await profile.locator('[data-action="open-playlists"]').click();
    assert(await playlists.isVisible());
    assert.equal(await nav("profile").getAttribute("aria-current"), "page");
    await playlists.locator("[data-playlist]").click();
    assert(await detail.isVisible());
    assert.equal(await detail.locator("[data-track]").count(), 2);
    assert.equal(await nav("profile").getAttribute("aria-current"), "page");
    await detail.getByLabel("返回歌单", { exact: true }).click();
    await playlists.getByLabel("新建歌单", { exact: true }).click();
    await page.getByLabel("歌单名称", { exact: true }).fill("新建的歌单");
    await page.locator('#playlist-form button[type="submit"]').click();
    await page.locator("#playlist-dialog").waitFor({ state: "hidden" });
    assert.equal(await playlists.locator("[data-playlist]").count(), 2);
    await playlists.locator('[data-playlist="saved-playlist"]').click();
    await detail.getByLabel("重命名歌单").click();
    await page.getByLabel("歌单名称", { exact: true }).fill("保留歌曲的歌单");
    await page.locator('#playlist-form button[type="submit"]').click();
    await page.locator("#playlist-dialog").waitFor({ state: "hidden" });
    assert.equal(await detail.locator('[data-text="playlistName"]').textContent(), "保留歌曲的歌单");
    assert.deepEqual((await persisted()).playlists[0].trackIds, seeded.playlists[0].trackIds);
    await page.evaluate(() => audio.pause());
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert.deepEqual((await persisted()).favorites, favoriteIds);
    assert.equal((await persisted()).playlists.length, 2);
    await nav("profile").click();
    await profile.locator('[data-action="open-playlists"]').click();
    assert.equal(await playlists.locator("[data-playlist]").count(), 2);
    await playlists.getByLabel("返回我的").click();
    await profile.locator('[data-action="open-library"]').click();
    assert.equal(await page.getByLabel("搜索曲库").inputValue(), "");
    assert(await page.locator('[data-panel="library"]').isVisible());

    const empty = await browser.newPage();
    empty.on("pageerror", error => errors.push(error.message));
    await empty.goto(base);
    await empty.waitForFunction(() => Boolean(window.M3X_UI));
    await empty.locator('.m3x-nav [data-page="profile"]').click();
    await empty.setViewportSize({ width: 390, height: 844 });
    await empty.emulateMedia({ colorScheme: "dark" });
    await empty.locator(".m3x-screen").screenshot({ path: path.join(shots, "profile-collections-empty.png") });
    await empty.locator('[data-action="open-favorites"]').click();
    assert.match(await empty.locator('[data-list="favorites"]').textContent(), /暂无收藏/);
    await empty.locator('[data-panel="favorites"] [data-page="profile"]').click();
    await empty.locator('[data-action="open-playlists"]').click();
    assert.match(await empty.locator("#profile-playlists").textContent(), /右上角/);
    assert.deepEqual(errors, []);
    console.log("PASS: horizontal profile rows, independent favorites search/sorting/full queue, empty states, playlist create/rename/back, active My navigation, playback continuity, existing data + reload and 320/390/1100px.");
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
