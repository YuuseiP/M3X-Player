const assert = require("node:assert/strict");
const path = require("node:path");
const { chromium } = require("playwright");
const shots = path.join(__dirname, "test-output");
require("node:fs").mkdirSync(shots, { recursive: true });

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  let restoreFolder;
  const base = "http://127.0.0.1:18765";
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 760 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.goto("http://127.0.0.1:18765/");
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    const screen = page.locator(".m3x-screen");
    const nav = name => screen.locator(`.m3x-nav [data-page="${name}"]`);
    const playlistDialog = page.locator("#playlist-dialog");
    const create = async name => {
      await playlistDialog.locator("#playlist-name").fill(name);
      await playlistDialog.locator('button[type="submit"]').click();
      await playlistDialog.waitFor({ state: "hidden" });
    };
    await nav("player").click();
    assert(await page.evaluate(() => audio.paused));
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentTime > .05 && audio.currentSrc.endsWith("02.flac"));
    await screen.locator('[data-action="mode"]').click();
    assert.equal(await screen.locator('[data-action="mode"] svg').count(), 1);
    assert.equal(await screen.locator('[data-action="mode"]').textContent(), "");
    assert.equal(await screen.locator("#toast").textContent(), "随机播放");
    await screen.locator('[data-action="mode"]').click();
    assert.equal(await screen.locator("#toast").textContent(), "单曲循环");
    await screen.locator('[data-action="mode"]').click();
    await page.waitForTimeout(2500);
    assert(!(await screen.locator("#toast").isVisible()));
    await screen.locator('[data-action="add-playlist"]').click();
    await create("夜间聆听");
    await screen.locator('[data-action="add-playlist"]').click();
    assert(await playlistDialog.locator("[data-add-to-playlist]").isDisabled(), "Current song is not duplicated");
    await page.keyboard.press("Escape");
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentSrc.endsWith("03.flac"));
    await screen.locator('[data-action="add-playlist"]').click();
    await playlistDialog.locator("[data-add-to-playlist]").click();
    await playlistDialog.waitFor({ state: "hidden" });
    await nav("library").click();
    await screen.locator('[data-filter="playlists"]').click();
    assert.equal(await page.locator("#library-playlists [data-playlist]").count(), 1);
    await page.locator("#library-playlists [data-playlist]").click();
    assert.equal(await screen.locator('[data-list="playlist"] [data-track]').count(), 2);
    await screen.locator('[data-action="rename-playlist"]').click();
    await create("夜航");
    assert.equal(await screen.locator('[data-text="playlistName"]').textContent(), "夜航");
    await screen.locator('[data-action="play-playlist"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentSrc.endsWith("02.flac"));
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentSrc.endsWith("03.flac"));
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentSrc.endsWith("02.flac"));
    await nav("library").click();
    await page.locator("#library-playlists [data-playlist]").click();
    await screen.locator('[data-remove-track]').last().click();
    assert.equal(await screen.locator('[data-list="playlist"] [data-track]').count(), 1);
    await screen.locator('[data-action="back-playlists"]').click();
    await screen.locator('[data-action="new-playlist"]').click();
    await create("通勤");
    assert.equal(await page.locator("#library-playlists [data-playlist]").count(), 2);
    await page.screenshot({ path: path.join(shots, "playlists.png"), fullPage: true });

    await nav("profile").click();
    const nickname = screen.locator("#nickname");
    const bounds = await nickname.boundingBox();
    await screen.locator('[data-action="edit-profile"]').click();
    await nickname.fill("澄的听众");
    assert.deepEqual(await nickname.boundingBox(), bounds);
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")).nickname), "澄的听众");
    await nickname.fill("");
    await nickname.blur();
    assert.equal(await nickname.inputValue(), "澄的听众", "Empty names retain last valid value");
    const fixture = await page.evaluate(() => {
      const canvas = document.createElement("canvas"); canvas.width = 1200; canvas.height = 800;
      const ctx = canvas.getContext("2d"); ctx.fillStyle = "#ff0000"; ctx.fillRect(0, 0, 600, 800);
      ctx.fillStyle = "#0000ff"; ctx.fillRect(600, 0, 600, 800);
      return canvas.toDataURL("image/png").split(",")[1];
    });
    const upload = () => screen.locator("#avatar-file").setInputFiles({ name: "avatar.png", mimeType: "image/png", buffer: Buffer.from(fixture, "base64") });
    await upload();
    const avatarDialog = page.locator("#avatar-dialog");
    await avatarDialog.waitFor({ state: "visible" });
    await screen.locator("#crop-size").fill("2");
    const crop = screen.locator("#crop-stage");
    const cropBounds = await crop.boundingBox();
    await page.mouse.move(cropBounds.x + cropBounds.width * .7, cropBounds.y + cropBounds.height / 2);
    await page.mouse.down();
    await page.mouse.move(cropBounds.x + cropBounds.width * .1, cropBounds.y + cropBounds.height / 2, { steps: 8 });
    await page.mouse.up();
    await crop.focus();
    await crop.press("ArrowRight");
    await page.screenshot({ path: path.join(shots, "avatar-crop.png"), fullPage: true });
    await screen.locator("#save-avatar").click();
    await avatarDialog.waitFor({ state: "hidden" });
    const savedAvatar = await page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")).avatar);
    assert(savedAvatar.startsWith("data:image/jpeg;base64,"));
    assert(Buffer.from(savedAvatar.split(",")[1], "base64").length < 80 * 1024, "Avatar compressed");
    const pixels = await page.evaluate(async data => {
      const image = new Image(); image.src = data; await image.decode();
      const canvas = document.createElement("canvas"); canvas.width = canvas.height = 1;
      const ctx = canvas.getContext("2d"); ctx.drawImage(image, 0, 0, 1, 1);
      return { width: image.width, height: image.height, pixel: [...ctx.getImageData(0, 0, 1, 1).data] };
    }, savedAvatar);
    assert.equal(pixels.width, pixels.height);
    assert(pixels.width <= 384);
    assert(pixels.pixel[2] > 200 && pixels.pixel[0] < 50, "Crop selects blue region, not the default center");
    assert.equal(await screen.locator(".m3x-avatar").evaluate(element => getComputedStyle(element).borderRadius), "50%");
    await upload();
    await avatarDialog.waitFor({ state: "visible" });
    await page.keyboard.press("Escape");
    assert.equal(await screen.locator("#avatar-image").getAttribute("src"), savedAvatar, "Cancel leaves avatar unchanged");
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    await nav("profile").click();
    assert.equal(await nickname.inputValue(), "澄的听众");
    assert.equal(await screen.locator("#avatar-image").getAttribute("src"), savedAvatar);
    assert.equal(await screen.locator('[data-text="playlistTotal"]').textContent(), "2");
    await screen.locator('[data-page="settings"]').click();
    assert.equal(await screen.locator('#settings-volume, [aria-label="音量"]').count(), 0);
    await screen.locator('[data-action="choose-folder"]').click();
    const folderDialog = screen.locator("#folder-dialog");
    await page.waitForFunction(() => !document.getElementById("select-folder").disabled);
    const originalFolder = await folderDialog.locator("#folder-path").inputValue();
    restoreFolder = originalFolder;
    await folderDialog.locator("#folder-path").fill("not-an-absolute-path");
    await folderDialog.locator('button[type="submit"]').click();
    await page.waitForFunction(() => !document.getElementById("folder-error").hidden);
    assert(await folderDialog.locator("#select-folder").isDisabled());
    await folderDialog.locator("#folder-path").fill(originalFolder);
    await folderDialog.locator('button[type="submit"]').click();
    await page.waitForFunction(() => !document.getElementById("select-folder").disabled);
    await page.keyboard.press("Escape");
    const [scanResponse] = await Promise.all([
      page.waitForResponse(response => response.url().endsWith("/api/scan") && response.request().method() === "POST"),
      screen.locator('[data-panel="settings"] [data-action="scan"]').click()
    ]);
    await scanResponse.finished();
    await page.waitForFunction(() => !document.querySelector('[data-panel="settings"] [data-action="scan"]').disabled &&
      document.querySelector('[data-text="scanStatus"]').textContent.includes("已扫描"), null, { timeout: 30000 });
    assert.equal((await page.request.get("http://127.0.0.1:18765/api/library")).status(), 200);
    const data = await (await page.request.get("http://127.0.0.1:18765/api/library")).json();
    assert.equal(data.folder, originalFolder, "Scanning keeps fixed directory");
    assert.equal(data.tracks.length, 10);
    // Only generated local fixtures enter this scan; user music is never edited.
    const fs = require("node:fs");
    const fixtures = path.join(shots, "scan-fixtures");
    fs.mkdirSync(path.join(fixtures, "nested"), { recursive: true });
    fs.mkdirSync(path.join(shots, "empty-library"), { recursive: true });
    const wav = (name, rate, bits) => {
      const bytes = rate * bits / 8;
      const buffer = Buffer.alloc(44 + bytes);
      buffer.write("RIFF", 0); buffer.writeUInt32LE(36 + bytes, 4); buffer.write("WAVEfmt ", 8);
      buffer.writeUInt32LE(16, 16); buffer.writeUInt16LE(1, 20); buffer.writeUInt16LE(1, 22);
      buffer.writeUInt32LE(rate, 24); buffer.writeUInt32LE(rate * bits / 8, 28);
      buffer.writeUInt16LE(bits / 8, 32); buffer.writeUInt16LE(bits, 34); buffer.write("data", 36); buffer.writeUInt32LE(bytes, 40);
      fs.writeFileSync(name, buffer);
    };
    wav(path.join(fixtures, "16bit.wav"), 44100, 16);
    wav(path.join(fixtures, "nested", "24bit.wav"), 48000, 24);
    const choose = async (folder, expectedCount) => {
      await screen.locator('[data-action="choose-folder"]').click();
      await page.waitForFunction(() => !document.getElementById("select-folder").disabled);
      await folderDialog.locator("#folder-path").fill(folder);
      await folderDialog.locator('button[type="submit"]').click();
      await page.waitForFunction(() => !document.getElementById("select-folder").disabled);
      await folderDialog.locator("#select-folder").click();
      await folderDialog.waitFor({ state: "hidden" });
      assert.equal((await (await page.request.get(base + "/api/library")).json()).scan.running, false, "Choosing a directory must not start scanning");
      assert((await screen.locator("#scan-directories").textContent()).includes(folder), "Selected directory is listed below the add button");
      await screen.locator('[data-panel="settings"] [data-action="scan"]').click();
      await page.waitForFunction(count => document.querySelector('[data-text="scanStatus"]').textContent.includes("已扫描") &&
        window.M3X_TRACKS.length === count, expectedCount, { timeout: 30000 });
    };
    await choose(fixtures, 2);
    await nav("library").click();
    await screen.locator('[data-filter="all"]').click();
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 2);
    const labels = await screen.locator('[data-list="library"] .m3x-track-artist').allTextContents();
    assert(labels.some(label => label.includes("16bit · 44.1kHz")));
    assert(labels.some(label => label.includes("24bit · 48kHz")));
    await screen.locator('[data-list="library"] [data-track]').first().click();
    await page.waitForFunction(() => !audio.paused && audio.readyState >= 2);
    assert(await page.evaluate(() => audio.currentSrc.includes("/library/audio/")), "Scanned files really play");
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert.equal(await screen.locator('[data-list="library"] [data-track]').count(), 2, "Scanned library persists across reload");
    await nav("profile").click();
    await screen.locator('[data-panel="profile"] [data-page="settings"]').click();
    await choose(path.join(shots, "empty-library"), 0);
    await nav("player").click();
    assert(await screen.locator('[data-action="next"]').isDisabled());
    assert.equal(await screen.locator(".m3x-title").textContent(), "暂无歌曲");
    await nav("profile").click();
    await screen.locator('[data-panel="profile"] [data-page="settings"]').click();
    await choose(originalFolder, 10);
    assert.equal(await page.evaluate(() => window.M3X_TRACKS.length), 10);
    await page.waitForTimeout(2500);
    for (const width of [320, 390]) {
      await page.setViewportSize({ width, height: 640 });
      for (const layout of ["album", "catalog"]) {
        await nav("profile").click();
        await screen.locator('[data-panel="profile"] [data-page="settings"]').click();
        await screen.locator('[data-setting="layout"]').selectOption(layout);
        await nav("library").click();
        await screen.locator('[data-filter="all"]').click();
        const row = screen.locator('[data-list="library"] [data-track]').first();
        assert((await row.boundingBox()).height <= 70, "Compact rows");
        assert((await row.locator(".m3x-track-artist").textContent()).includes("24bit · 96kHz"));
        for (const panel of ["library", "player", "profile"]) {
          await nav(panel).click();
          assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
          if (panel === "player") {
            const next = await screen.locator('[data-action="next"]').boundingBox();
            const content = await screen.locator(".m3x-content").boundingBox();
            assert(next.y + next.height <= content.y + content.height + 1, "Transport visible without scrolling");
            if (layout === "catalog") {
              const upcoming = screen.locator('[data-list="upcoming"] [data-track]').first();
              assert.equal(await upcoming.evaluate(element => getComputedStyle(element).display), "flex");
              assert((await upcoming.boundingBox()).height <= 70, "Upcoming queue uses the same compact song rows");
            }
          }
          await page.screenshot({ path: path.join(shots, `${panel}-${layout}-${width}.png`), fullPage: true });
        }
      }
    }
    await nav("player").click();
    await screen.locator('[data-list="upcoming"] [data-track]').first().click();
    await page.waitForFunction(() => !audio.paused && audio.currentSrc.endsWith("02.flac"));
    assert.equal(await screen.locator('[data-list="queue"] [data-track]').count(), 10, "Upcoming selection preserves full queue");
    assert.deepEqual(errors, []);
    console.log("PASS: paused next autoplays, mode icons/toasts, playlist create/add/dedupe/rename/remove/queue/persistence, inline nickname, avatar crop/drag/compress/cancel/persistence, fixed-folder UI, real scan, compact layouts.");
  } finally {
    if (restoreFolder) {
      const request = await browser.newContext();
      await request.request.post(base + "/api/folder", { data: { folder: restoreFolder } });
      await request.request.post(base + "/api/scan", { data: {} });
      for (let attempt = 0; attempt < 60; attempt++) {
        const data = await (await request.request.get(base + "/api/library")).json();
        if (!data.scan.running) break;
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      await request.close();
    }
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
