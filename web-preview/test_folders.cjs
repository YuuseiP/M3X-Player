const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");

(async () => {
  const base = "http://127.0.0.1:18765";
  const data = await (await fetch(base + "/api/library")).json();
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const ready = async target => {
      await target.goto(base);
      await target.waitForFunction(() => Boolean(window.M3X_UI));
      await target.locator('[data-filter="folders"]').click();
    };
    await ready(page);
    const rootFolders = [...new Set(data.tracks.map(track => (track.folder || "").replace(/\\/g, "/").split("/")[0]).filter(name => name && name !== "."))];
    assert.deepEqual((await page.locator("#library-groups strong").allTextContents()).sort(), rootFolders.sort());
    assert(!await page.locator("#group-back").isVisible());
    const shots = path.join(__dirname, "test-output");
    fs.mkdirSync(shots, { recursive: true });
    await page.emulateMedia({ colorScheme: "dark" });
    await page.locator(".m3x-screen").screenshot({ path: path.join(shots, "folders-root.png") });
    const firstFolder = await page.locator("#library-groups [data-group]").first().getAttribute("data-group");
    await page.locator("#library-groups [data-group]").first().click();
    assert.equal(await page.locator("#folder-location").textContent(), firstFolder);
    const children = await page.locator("#library-groups [data-group]").evaluateAll(items => items.map(item => item.dataset.group));
    assert(children.every(key => key.startsWith(firstFolder + "/") && key.split("/").length === 2), "Only immediate children are listed");
    await page.locator(".m3x-screen").screenshot({ path: path.join(shots, "folders-child.png") });

    const fixture = await browser.newPage({ viewport: { width: 390, height: 844 } });
    fixture.on("pageerror", error => errors.push(error.message));
    const folders = [".", "A", "A", "A/Live", "A/Live/Disc 1", "AB/Live", "日本語\\子"];
    const tracks = data.tracks.slice(0, folders.length).map((track, index) => ({
      ...track, folder: folders[index], title: index === 4 ? "Nested song" : "Song " + index
    }));
    await fixture.route("**/api/library", route => route.fulfill({
      contentType: "application/json", body: JSON.stringify({ ...data, tracks })
    }));
    await ready(fixture);
    const keys = () => fixture.locator("#library-groups [data-group]").evaluateAll(items => items.map(item => item.dataset.group));
    const songs = () => fixture.locator('[data-list="library"] [data-track]').evaluateAll(items => items.map(item => Number(item.dataset.track)));
    const enter = key => fixture.locator("#library-groups [data-group]").filter({
      has: fixture.getByText(key.split("/").pop(), { exact: true })
    }).click();
    assert.deepEqual((await keys()).sort(), ["A", "AB", "日本語"].sort());
    assert.deepEqual(await songs(), [0], "Root songs remain visible alongside folders");
    await enter("A");
    assert.deepEqual(await keys(), ["A/Live"], "A does not include its sibling AB");
    assert.deepEqual(await songs(), [1, 2], "Only songs directly in A are listed");
    assert(await fixture.getByLabel("分类排序", { exact: true }).isVisible());
    assert(await fixture.getByLabel("歌曲排序", { exact: true }).isVisible());
    for (const width of [320, 390, 1100]) {
      await fixture.setViewportSize({ width, height: width === 320 ? 640 : 844 });
      assert(await fixture.locator(".m3x-screen").evaluate(element => element.scrollWidth <= element.clientWidth + 1));
    }
    await fixture.locator('[data-list="library"] [data-track="1"]').click();
    await fixture.locator('.m3x-nav [data-page="library"]').click();
    const saved = await fixture.evaluate(() => JSON.parse(localStorage.getItem("m3x-shiny-preview-v1")));
    assert.deepEqual(saved.queue, [tracks[1].id, tracks[2].id], "Playback queue follows this level, not descendants");
    await fixture.evaluate(() => audio.pause());
    await fixture.getByLabel("搜索曲库").fill("Nested song");
    assert.deepEqual(await keys(), ["A/Live"], "Search keeps matching descendants reachable without flattening");
    assert.deepEqual(await songs(), []);
    await enter("A/Live");
    assert.deepEqual(await keys(), ["A/Live/Disc 1"]);
    await fixture.getByLabel("搜索曲库").fill("");
    assert.deepEqual(await songs(), [3]);
    await enter("A/Live/Disc 1");
    assert.deepEqual(await songs(), [4]);
    assert(!await fixture.locator("#library-groups").isVisible());
    assert(await fixture.getByLabel("歌曲排序", { exact: true }).isVisible());
    assert(!await fixture.getByLabel("分类排序", { exact: true }).isVisible());
    for (const current of ["A / Live", "A", data.folder.split(/[\\/]/).pop()]) {
      await fixture.locator("#group-back").click();
      assert.equal(await fixture.locator("#folder-location").textContent(), current);
    }
    assert(!await fixture.locator("#group-back").isVisible());
    await enter("日本語");
    assert.deepEqual(await keys(), ["日本語/子"], "Windows separators and Unicode folder names work");
    await enter("日本語/子");
    assert.deepEqual(await songs(), [6]);
    await fixture.locator('[data-filter="artists"]').click();
    assert(!await fixture.locator("#folder-location").isVisible());
    assert(!await fixture.locator("#group-back").isVisible());
    assert.deepEqual(errors, []);
    console.log("PASS: real-library folder hierarchy, mixed root/child songs, parent navigation, scoped search, sibling boundaries, Unicode/Windows paths, direct-song queue, sorting and 320/390/1100px.");
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
