const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

(async () => {
  const base = "http://127.0.0.1:18765";
  const initial = await (await fetch(base + "/api/classification")).json();
  const source = initial.config;
  assert.equal(source.tracks.length, 4235);
  const baselinePath = path.join(__dirname, "../artist-classification/semantic/library-preview.json");
  const baseline = fs.readFileSync(baselinePath);
  const scannedLibrary = fs.readFileSync(path.join(__dirname, ".library.json"));
  await fetch(base + "/api/classification", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ schema: 1, baseline: initial.edits.baseline, entities: {}, memberships: {} })
  });
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1100, height: 850 }, acceptDownloads: true });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const screen = page.locator(".m3x-screen");
    async function open() {
      await page.goto(base);
      await page.waitForFunction(() => Boolean(window.M3X_UI));
      assert.equal(await page.evaluate(() => window.M3X_UI.tracks.length), 4235, "Saved classification is the initial music library");
      await page.locator('[data-panel="library"] [data-page="create-library"]').click();
      await page.locator("#classification-workspace").waitFor({ state: "visible" });
    }
    await open();
    assert((await page.locator("#classification-summary").textContent()).includes("4,235"));
    assert.equal(await page.locator('[data-classification-kind="character"]').textContent(), "角色 189");
    assert.equal(await page.locator('[data-classification-kind="group"]').textContent(), "组合 84");
    assert.equal(await page.locator('[data-classification-kind="artist"]').textContent(), "歌手 188");
    assert.equal(await page.getByRole("button", { name: /待检查/ }).count(), 0);
    assert.equal(await page.locator("#classification-save").textContent(), "已应用到曲库");
    assert(await page.locator("#classification-save").isDisabled());
    assert.equal(await page.locator("audio").count(), 1);
    assert.equal(await page.locator("[data-classification-entity]").count(), 50);
    const mark = await page.locator(".classification-entity-mark").first().boundingBox();
    assert.equal(mark.width, 38);
    assert.equal(mark.height, 38);
    await page.getByRole("button", { name: "加载更多（50 / 189）" }).click();
    assert.equal(await page.locator("[data-classification-entity]").count(), 100);

    await page.locator("#classification-search").fill("三浦あずさ");
    assert.equal(await page.locator("[data-classification-entity]").count(), 1, "Encyclopedia original names remain searchable");
    const row = page.locator("[data-classification-entity]").first();
    const entityId = await row.getAttribute("data-classification-entity");
    await row.click();
    assert(!(await page.getByRole("textbox", { name: "分类显示名称" }).isVisible()), "Browsing does not start in an editing form");
    await page.locator(".classification-edit-details > summary").click();
    assert.equal(await page.getByRole("textbox", { name: "分类显示名称" }).inputValue(), "三浦梓");
    assert((await page.locator(".classification-editor").textContent()).includes("百科记录"));
    await page.getByRole("textbox", { name: "分类显示名称" }).fill("三浦梓（预览测试）");
    await page.getByLabel("分类类型", { exact: true }).selectOption("group");
    await page.getByRole("button", { name: "确认名称与类型" }).click();
    assert((await page.locator("#classification-status").textContent()).includes("未保存"));
    assert.equal(await page.evaluate(id => window.M3X_UI.tracks.flatMap(track => track.credits || []).find(item => item.id === id).name, entityId), "三浦梓", "Uncommitted edits do not change the active library");
    const firstTrack = page.locator("[data-classification-track]").first();
    const trackId = await firstTrack.getAttribute("data-classification-track");
    await firstTrack.getByRole("button", { name: /^调整归属/ }).click();
    const creditSearch = page.locator("#classification-credit-search");
    await creditSearch.fill("三浦梓（预览测试）");
    await page.locator(`#classification-credit-list input[value="${entityId}"]`).uncheck();
    const singer = source.entities.find(entity => entity.kind === "artist");
    await creditSearch.fill(singer.name);
    await page.locator(`#classification-credit-list input[value="${singer.id}"]`).check();
    assert.equal(await page.locator("#classification-credit-search").inputValue(), singer.name);
    await page.locator("#classification-song-apply").click();
    await page.locator("#classification-song-dialog").waitFor({ state: "hidden" });
    await page.route("**/api/classification", route => route.request().method() === "POST" ? route.fulfill({
      status: 400, contentType: "application/json", body: JSON.stringify({ error: "保存失败测试" })
    }) : route.continue());
    await page.locator("#classification-save").click();
    await page.waitForFunction(() => document.getElementById("toast").textContent === "保存失败测试");
    assert((await page.locator("#classification-status").textContent()).includes("未保存"));
    assert.equal(await page.evaluate(id => window.M3X_UI.tracks.flatMap(track => track.credits || []).find(item => item.id === id).name, entityId), "三浦梓");
    await page.unroute("**/api/classification");
    await page.locator("#classification-save").click();
    await page.locator('[data-panel="library"]').waitFor({ state: "visible" });
    await page.waitForFunction(() => document.getElementById("classification-status").textContent.startsWith("当前分类已应用"));
    await page.locator('[data-filter="groups"]').click();
    assert.equal(await page.locator(`#library-groups [data-group="${entityId}"] strong`).textContent(), "三浦梓（预览测试）", "Save applies the new name and type to the normal music library");
    await page.locator(`#library-groups [data-group="${entityId}"]`).click();
    assert.equal(await page.locator(`[data-list="library"] [data-track]`).count(), source.tracks.filter(track => track.memberships.includes(entityId)).length - 1, "Updated memberships affect normal browsing");
    const stored = await (await fetch(base + "/api/classification")).json();
    assert.deepEqual(stored.edits.entities[entityId], { name: "三浦梓（预览测试）", kind: "group" });
    assert(stored.edits.memberships[trackId].includes(singer.id));
    assert(!stored.edits.memberships[trackId].includes(entityId));
    const originalTrack = source.tracks.find(track => track.id === trackId);
    for (const id of originalTrack.memberships.filter(id => !["character", "group", "artist"].includes(source.entities.find(entity => entity.id === id).kind))) {
      assert(stored.edits.memberships[trackId].includes(id), "Read-only memberships survive editing");
    }
    await open();
    await page.locator('[data-panel="create-library"] [data-page="library"]').click();
    await page.locator('[data-filter="groups"]').click();
    assert.equal(await page.locator(`#library-groups [data-group="${entityId}"] strong`).textContent(), "三浦梓（预览测试）", "Main library retains saved classification after refresh");
    await page.locator('[data-panel="library"] [data-page="create-library"]').click();
    await page.locator('[data-classification-kind="group"]').click();
    await page.locator("#classification-search").fill("三浦梓（预览测试）");
    assert.equal(await page.locator("[data-classification-entity]").count(), 1, "Saved draft survives reload");

    const downloadPromise = page.waitForEvent("download");
    await page.locator(".classification-backup > summary").click();
    await page.locator("#classification-export").click();
    const download = await downloadPromise;
    const stream = await download.createReadStream();
    const chunks = []; for await (const chunk of stream) chunks.push(chunk);
    const exported = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    assert.equal(exported.tracks.length, 4235);
    assert.equal(exported.entities.find(entity => entity.id === entityId).name, "三浦梓（预览测试）");
    assert.equal(exported.web_preview_only, true);
    assert.equal(exported.folder, "D:\\BaiduNetdiskDownload\\THE_IDOLMASTER");

    page.once("dialog", dialog => dialog.accept());
    await page.locator("#classification-reset").click();
    await page.locator("#classification-save").click();
    await page.locator('[data-panel="library"]').waitFor({ state: "visible" });
    await page.locator('[data-panel="library"] [data-page="create-library"]').click();
    await page.locator(".classification-backup > summary").click();
    await page.locator("#classification-search").fill("三浦梓");
    await page.locator('[data-classification-kind="character"]').click();
    await page.locator("#classification-search").fill("三浦梓");
    await page.locator("[data-classification-entity]").first().click();
    await page.locator(".classification-edit-details > summary").click();
    await page.getByRole("textbox", { name: "分类显示名称" }).fill("不保存的调整");
    await page.getByRole("button", { name: "确认名称与类型" }).click();
    await page.locator("#classification-undo").click();
    assert(await page.locator("#classification-save").isDisabled(), "Undo restores the last saved configuration without writing");
    for (const width of [1100, 390, 320]) {
      await page.setViewportSize({ width, height: width === 320 ? 640 : 800 });
      await page.locator('[data-classification-kind="tracks"]').click();
      assert(await screen.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
      const bounds = await page.locator("#classification-results").boundingBox();
      assert(bounds.height >= 110, "Results remain usable on a small screen");
      assert(await page.locator("#classification-save").isVisible());
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
      await page.locator("[data-classification-track]").first().getByRole("button", { name: /^调整归属/ }).click();
      await page.locator("#classification-credit-search").fill("三浦梓");
      assert.equal(await page.locator("#classification-credit-list input").count(), 1);
      await page.locator('[data-close="classification-song-dialog"]').click();
      await page.locator("#classification-song-dialog").waitFor({ state: "hidden" });
    }
    const shots = path.join(__dirname, "test-output");
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('[data-classification-kind="character"]').click();
    await screen.screenshot({ path: path.join(shots, "classification-phone.png") });
    await page.locator("#classification-search").fill("三浦梓");
    await page.locator("[data-classification-entity]").first().click();
    await screen.screenshot({ path: path.join(shots, "classification-detail.png") });
    const song = page.locator("[data-classification-track]").first();
    const playId = await song.getAttribute("data-classification-track");
    const relative = source.tracks.find(track => track.id === playId).path;
    const audioFile = path.join(source.folder, relative);
    const before = fs.statSync(audioFile, { bigint: true });
    await song.getByRole("button", { name: /^试听/ }).click();
    await page.waitForFunction(() => !audio.paused && audio.readyState >= 2 && audio.duration > 0, null, { timeout: 45000 });
    await page.waitForFunction(() => window.M3X_UI.currentTrack().sampleRate > 0, null, { timeout: 45000 });
    assert((await page.evaluate(() => audio.currentSrc)).includes("/classification/audio/" + playId));
    assert.equal(await page.evaluate(() => window.M3X_UI.tracks.length), 4235);
    await page.evaluate(() => { audio.currentTime = 30; });
    await page.waitForFunction(() => audio.currentTime >= 30 && !audio.seeking);
    await page.emulateMedia({ colorScheme: "dark" });
    await screen.screenshot({ path: path.join(shots, "classification-playing-dark.png") });
    await page.evaluate(() => audio.pause());
    await page.emulateMedia({ colorScheme: "light" });
    await page.locator('.m3x-nav [data-page="library"]').click();
    await page.locator('[data-filter="characters"]').click();
    await screen.screenshot({ path: path.join(shots, "classification-library.png") });
    const after = fs.statSync(audioFile, { bigint: true });
    assert.equal(after.size, before.size);
    assert.equal(after.mtimeNs, before.mtimeNs);
    assert.deepEqual(fs.readFileSync(baselinePath), baseline, "Frozen classification is unchanged");
    assert.deepEqual(fs.readFileSync(path.join(__dirname, ".library.json")), scannedLibrary, "Original scan library is unchanged");
    assert.deepEqual(errors, []);
    console.log("PASS: Confirmed classification (no pending work), 4235-track applied library, name/type/membership save + refresh, failed save isolation, undo/export/reset, original files preserved, shared-audio playback/seek, responsive 320/390/1100.");
  } finally {
    await fetch(base + "/api/classification", {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(initial.edits)
    });
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
