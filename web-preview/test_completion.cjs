const assert = require("node:assert/strict");
const path = require("node:path");
const fs = require("node:fs");
const { chromium } = require("playwright");
const base = "http://127.0.0.1:18765";
const shots = path.join(__dirname, "test-output");
fs.mkdirSync(shots, { recursive: true });

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 760 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    // Isolated lyric/ReplayGain fixtures; no song files or saved catalog are changed.
    await page.route("**/api/track?*", async route => {
      const response = await route.fetch();
      const track = await response.json();
      await route.fulfill({ json: { ...track, lyrics: "[00:00]第一句\n[00:05]第二句\n[00:05]翻译\n[00:12]第三句",
        replayGain: { replaygain_track_gain: "-6 dB", replaygain_track_peak: "0.8" } } });
    });
    await page.addInitScript(() => {
      window.testOffset = 0;
      const now = Date.now;
      Date.now = () => now() + window.testOffset;
      const NativeContext = window.AudioContext;
      window.testFilters = []; window.testGains = [];
      window.AudioContext = class extends NativeContext {
        constructor(...args) { super(...args); window.testContext = this; }
        createBiquadFilter() { const node = super.createBiquadFilter(); window.testFilters.push(node); return node; }
        createGain() { const node = super.createGain(); window.testGains.push(node); return node; }
      };
    });
    await page.goto(base);
    await page.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    const screen = page.locator(".m3x-screen");
    const nav = name => screen.locator(`.m3x-nav [data-page="${name}"]`);
    const library = screen.locator('[data-list="library"]');
    const queue = screen.locator('[data-list="queue"]');
    const settings = async () => {
      await nav("profile").click();
      await screen.locator('[data-panel="profile"] [data-page="settings"]').click();
    };
    const menu = async index => {
      await nav("library").click();
      await screen.locator('[data-filter="all"]').click();
      await screen.locator(`[data-list="library"] [data-menu-track="${index}"]`).click();
    };
    for (const filter of ["albums", "artists"]) {
      await screen.locator(`[data-filter="${filter}"]`).click();
      assert(await screen.locator("#library-groups").isVisible());
      assert(await screen.locator("[data-group]").count() > 0);
      await screen.locator("[data-group]").first().click();
      assert(await library.isVisible());
      assert(await library.locator("[data-track]").count() > 0);
      await screen.locator("#group-back").click();
    }
    await screen.locator('[data-filter="folders"]').click();
    assert(await library.isVisible(), "Root-level fixture songs appear directly, without a synthetic folder");
    assert.equal(await library.locator("[data-track]").count(), 10);
    assert(!await screen.locator("#group-back").isVisible());
    await screen.locator('[data-filter="all"]').click();
    await screen.locator('[data-panel="library"] [data-setting="sort"]').selectOption("duration-desc");
    const durations = await library.locator("[data-track]").evaluateAll(buttons => buttons.map(button => window.M3X_TRACKS[Number(button.dataset.track)].duration));
    assert.deepEqual(durations, [...durations].sort((a, b) => b - a));
    await screen.locator('[data-panel="library"] [data-setting="sort"]').selectOption("original");
    await library.locator('[data-track="0"]').click();
    await page.waitForFunction(() => !audio.paused && audio.currentTime > .1);
    await menu(7);
    await screen.locator('[data-action="play-next"]').click();
    await nav("player").click();
    await screen.locator('.m3x-transport [data-page="queue"]').click();
    assert.deepEqual((await queue.locator("[data-track]").evaluateAll(items => items.map(item => Number(item.dataset.track)))).slice(0, 3), [0, 7, 1]);
    await screen.locator('[data-action="locate-current"]').click();
    await queue.locator('[data-remove-queue]').nth(1).click();
    assert.equal(await queue.locator("[data-track]").count(), 9);
    const source = await page.evaluate(() => audio.currentSrc);
    await queue.locator("[data-remove-queue]").first().click();
    await page.waitForFunction(previous => audio.currentSrc !== previous && !audio.paused, source);
    await screen.locator('[data-action="clear-queue"]').click();
    assert.equal(await queue.locator("[data-track]").count(), 0);
    assert(await page.evaluate(() => audio.paused));
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    assert.equal(await queue.locator("[data-track]").count(), 0, "Empty queue persists");

    await menu(5);
    await screen.locator('[data-action="menu-add-playlist"]').click();
    await screen.locator("#playlist-name").fill("歌曲菜单创建");
    await screen.locator('#playlist-form button[type="submit"]').click();
    await screen.locator("#playlist-dialog").waitFor({ state: "hidden" });
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")).playlists[0].trackIds[0]), await page.evaluate(() => M3X_TRACKS[5].id));
    await menu(5);
    await screen.locator('[data-action="song-info"]').click();
    await page.waitForFunction(() => document.getElementById("song-info").textContent.includes("文件路径"));
    assert((await screen.locator("#song-info").textContent()).includes("96kHz"));
    await page.keyboard.press("Escape");
    await screen.locator('[data-panel="library"] .m3x-search').fill("Reflection");
    await library.locator('[data-track="5"]').click();
    await page.waitForFunction(() => !audio.paused && audio.readyState >= 2);
    await screen.locator('[data-action="mode"]').click();
    await screen.locator('[data-action="mode"]').click();
    await menu(5);
    await screen.locator('[data-action="hide-song"]').click();
    await screen.locator("#song-menu-dialog").waitFor({ state: "hidden" });
    assert.equal(await queue.locator("[data-track]").count(), 0, "Hiding the only queued song must not expand the queue");
    await page.evaluate(() => { audio.currentTime = audio.duration - .2; });
    await page.waitForFunction(() => audio.ended && audio.paused);
    await screen.locator('[data-panel="library"] .m3x-search').fill("");
    assert.equal(await library.locator("[data-track]").count(), 9);
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    assert.equal(await library.locator("[data-track]").count(), 9, "Hidden preference persists");
    await settings();
    await screen.locator('[data-action="hidden-songs"]').click();
    await screen.locator("#hidden-list [data-restore]").click();
    await page.keyboard.press("Escape");
    await screen.locator('[data-setting="filenameTitle"]').check();
    await screen.locator('[data-setting="showCovers"]').uncheck();
    await nav("library").click();
    assert.equal(await library.locator("[data-track]").count(), 10);
    assert.equal(await library.locator(".m3x-track-title").first().textContent(), "01.flac");
    assert(!(await library.locator("img").first().isVisible()));
    await settings();
    await screen.locator('[data-setting="filenameTitle"]').uncheck();
    await screen.locator('[data-setting="showCovers"]').check();
    await screen.locator("#playback-rate").selectOption("1.5");
    await screen.locator("#preserve-pitch").uncheck();
    await nav("library").click();
    await library.locator('[data-track="0"]').click();
    await page.waitForFunction(() => audio.readyState >= 2 && !audio.paused);
    assert.equal(await page.evaluate(() => audio.playbackRate), 1.5);
    assert.equal(await page.evaluate(() => audio.preservesPitch), false);
    await screen.locator('[data-action="next"]').click();
    await page.waitForFunction(() => audio.readyState >= 2 && audio.currentSrc.endsWith("02.flac"));
    assert.equal(await page.evaluate(() => audio.playbackRate), 1.5, "Speed survives track loading");
    assert((await page.evaluate(() => navigator.mediaSession.metadata.artist)).length > 0);
    await screen.locator(".m3x-cover-open").click();
    await page.waitForFunction(() => document.querySelectorAll("[data-lyric-line]").length === 3);
    await screen.locator('[data-lyric-line="1"]').click();
    await page.waitForFunction(() => audio.currentTime >= 5 && audio.currentTime < 8);
    assert.equal(await screen.locator('[data-lyric-line="1"]').getAttribute("aria-current"), "true");
    await screen.locator("#lyric-offset").fill("2");
    await screen.locator('[data-lyric-line="2"]').click();
    await page.waitForFunction(() => audio.currentTime >= 14 && audio.currentTime < 17);
    await page.screenshot({ path: path.join(shots, "lyrics.png") });
    await nav("player").click();
    await screen.locator(".m3x-output").click();
    await screen.locator('[data-sheet-tab="processing"]').click();
    await screen.locator("#eq-enabled").check();
    await screen.locator("#eq-preset").selectOption("bass");
    await screen.locator("#replaygain-mode").selectOption("album");
    await page.waitForFunction(() => testFilters.length === 10 && testContext.state === "running" && testFilters[0].gain.value > 3.9);
    await page.waitForFunction(() => Math.abs(testGains[0].gain.value - 10 ** (-6 / 20)) < .001);
    assert((await screen.locator("#replaygain-status").textContent()).includes("使用单曲标签"));
    assert.equal(await page.evaluate(() => audio.volume), 1);
    await screen.locator('[data-eq-band="0"]').fill("-2.5");
    await page.waitForFunction(() => Math.abs(testFilters[0].gain.value + 2.5) < .01);
    assert.equal(await screen.locator("#eq-preset").inputValue(), "custom");
    await page.screenshot({ path: path.join(shots, "equalizer.png") });
    await screen.locator("#eq-reset").click();
    await screen.locator("#eq-enabled").uncheck();
    await screen.locator("#replaygain-mode").selectOption("off");
    await page.waitForFunction(() => testFilters.every(filter => Math.abs(filter.gain.value) < .01) && Math.abs(testGains[0].gain.value - 1) < .001);
    await page.keyboard.press("Escape");
    await screen.locator(".m3x-title").click();
    await page.keyboard.press("Space");
    assert(await page.evaluate(() => audio.paused));
    await page.keyboard.press("Space");
    await page.waitForFunction(() => !audio.paused);

    await settings();
    await screen.locator('[data-tool="sleep"]').click();
    await screen.locator("#sleep-minutes").fill("1");
    await screen.locator('#sleep-form button[type="submit"]').click();
    await screen.locator("#sleep-dialog").waitFor({ state: "hidden" });
    await page.evaluate(() => { window.testOffset += 61000; });
    await page.waitForFunction(() => audio.paused && document.getElementById("sleep-summary").textContent === "未开启");
    await nav("player").click();
    await screen.locator('.m3x-transport [data-action="play"]').click();
    await settings();
    await screen.locator('[data-tool="sleep"]').click();
    await screen.locator("#sleep-finish-song").check();
    await screen.locator('#sleep-form button[type="submit"]').click();
    await screen.locator("#sleep-dialog").waitFor({ state: "hidden" });
    await page.evaluate(() => { window.testOffset += 61000; });
    await page.waitForFunction(() => document.getElementById("sleep-summary").textContent === "播完本曲后停止");
    const endingSource = await page.evaluate(() => audio.currentSrc);
    await page.evaluate(() => { audio.currentTime = audio.duration - .3; });
    await page.waitForFunction(() => audio.paused && document.getElementById("sleep-summary").textContent === "未开启");
    assert.equal(await page.evaluate(() => audio.currentSrc), endingSource, "Finish-song timer suppresses automatic advance");
    await screen.locator('[data-tool="sleep"]').click();
    await screen.locator('#sleep-form button[type="submit"]').click();
    await screen.locator("#sleep-dialog").waitFor({ state: "hidden" });
    await nav("player").click();
    await screen.locator('[data-action="next"]').click();
    assert.equal(await screen.locator("#sleep-summary").textContent(), "未开启", "Manual skip cancels finish-song timer");

    await settings();
    await screen.locator('[data-action="scan-options"]').click();
    await screen.locator("#skip-short").check();
    await screen.locator("#excluded-folders").fill("../outside");
    await screen.locator('#scan-options-form button[type="submit"]').click();
    await screen.locator("#scan-options-error").waitFor({ state: "visible" });
    await screen.locator("#skip-short").uncheck();
    await screen.locator("#excluded-folders").fill("");
    let scans = 0;
    const countScan = request => { if (request.url().endsWith("/api/scan") && request.method() === "POST") scans++; };
    page.on("request", countScan);
    await screen.locator('#scan-options-form button[type="submit"]').click();
    await screen.locator("#scan-options-dialog").waitFor({ state: "hidden" });
    assert.equal(scans, 0, "Saving filters must not start scanning");
    assert.equal((await (await page.request.get(base + "/api/library")).json()).scan.running, false);
    page.off("request", countScan);
    await page.keyboard.press("Escape");
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    await settings();
    assert.equal(await screen.locator("#playback-rate").inputValue(), "1.5");
    assert(!(await screen.locator("#preserve-pitch").isChecked()));
    assert.equal(await screen.locator("#lyric-offset").inputValue(), "2");
    for (const width of [320, 390, 1100]) {
      await page.setViewportSize({ width, height: 640 });
      for (const panel of ["library", "player", "profile"]) {
        await nav(panel).click();
        assert(await screen.evaluate(element => element.scrollWidth <= element.clientWidth + 1));
      }
    }
    assert.deepEqual(errors, []);

    // A real four-second PCM stream checks listening counts in a fresh browser context.
    const stats = await browser.newPage();
    const pcm = Buffer.alloc(44 + 44100 * 2 * 4);
    pcm.write("RIFF", 0); pcm.writeUInt32LE(pcm.length - 8, 4); pcm.write("WAVEfmt ", 8);
    pcm.writeUInt32LE(16, 16); pcm.writeUInt16LE(1, 20); pcm.writeUInt16LE(1, 22);
    pcm.writeUInt32LE(44100, 24); pcm.writeUInt32LE(88200, 28);
    pcm.writeUInt16LE(2, 32); pcm.writeUInt16LE(16, 34); pcm.write("data", 36); pcm.writeUInt32LE(pcm.length - 44, 40);
    await stats.route("**/api/library", async route => {
      const data = await (await route.fetch()).json();
      data.tracks = [{ ...data.tracks[0], id: "isolated-statistics-fixture", title: "测试音频", src: "statistics-test.wav", duration: 4 }];
      await route.fulfill({ json: data });
    });
    await stats.route("**/statistics-test.wav", route => route.fulfill({ contentType: "audio/wav", body: pcm }));
    await stats.goto(base);
    await stats.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    await stats.locator('[data-list="library"] [data-track]').click();
    await stats.waitForFunction(() => Number(document.getElementById("listening-count").textContent) > 0 ||
      JSON.parse(localStorage.getItem("m3x-player-tools-v1") || "{}").counts?.["isolated-statistics-fixture"] === 1);
    await stats.evaluate(() => audio.pause());
    await stats.locator('.m3x-nav [data-page="profile"]').click();
    await stats.locator('[data-panel="profile"] [data-page="statistics"]').click();
    assert.equal(await stats.locator("#listening-count").textContent(), "1");
    await stats.reload();
    await stats.waitForFunction(() => Boolean(window.M3X_UI?.stopAtEnd));
    assert.equal(await stats.locator("#listening-count").textContent(), "1", "Listening counts persist");
    console.log("PASS: grouping/sorting, queue editing/clear, non-current playlist addition, info/hide/restore, filename/covers, rate/pitch persistence, LRC seek/offset, real EQ/ReplayGain graph, keyboard/Media Session, sleep expiry/finish/cancel, scan validation, real-stream statistics, responsive layouts.");
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
