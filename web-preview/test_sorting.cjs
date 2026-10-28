const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
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
    const open = async () => {
      await page.goto("http://127.0.0.1:18765/");
      await page.waitForFunction(() => Boolean(window.M3X_UI));
    };
    await open();
    const groupSort = page.getByLabel("分类排序", { exact: true });
    const songSort = page.getByLabel("歌曲排序", { exact: true });
    const names = () => page.locator("#library-groups strong").allTextContents();
    const compareName = (a, b) => a.localeCompare(b, "zh-Hans", { numeric: true });
    await songSort.selectOption("artist");
    for (const filter of ["characters", "groups", "artists", "albums"]) {
      await page.locator(`[data-filter="${filter}"]`).click();
      assert(await groupSort.isVisible());
      assert(!await songSort.isVisible(), "Song ordering is not offered on category directories");
      assert.deepEqual(await groupSort.locator("option").allTextContents(), ["名称升序", "名称降序", "歌曲最多"]);
      await groupSort.selectOption("name");
      const ascending = await names();
      assert(ascending.length > 0);
      assert.deepEqual(ascending, [...ascending].sort(compareName));
      await groupSort.selectOption("name-desc");
      assert.deepEqual(await names(), [...ascending].reverse(), `${filter}: descending category names take effect`);
      await groupSort.selectOption("count");
      const counts = await page.locator("#library-groups small").allTextContents();
      const values = counts.map(text => Number.parseInt(text));
      assert.deepEqual(values, [...values].sort((a, b) => b - a), `${filter}: most songs first takes effect`);
      await page.locator("#library-groups [data-group]").first().click();
      assert(await songSort.isVisible());
      assert(!await groupSort.isVisible());
      assert.equal(await songSort.inputValue(), "artist", "Category ordering preserves the chosen song order");
      await songSort.selectOption("title");
      const titles = await page.locator('[data-list="library"] [data-track]').evaluateAll(buttons =>
        buttons.map(button => window.M3X_UI.tracks[Number(button.dataset.track)].title));
      assert.deepEqual(titles, [...titles].sort(compareName));
      await page.locator("#group-back").click();
      assert.equal(await groupSort.inputValue(), "count", "Song ordering preserves category ordering");
      assert(await groupSort.isVisible());
      await page.locator('[data-filter="all"]').click();
      await songSort.selectOption("artist");
    }
    await page.reload();
    await page.waitForFunction(() => Boolean(window.M3X_UI));
    assert.equal(await songSort.inputValue(), "artist");
    await page.locator('[data-filter="artists"]').click();
    assert.equal(await groupSort.inputValue(), "count", "Category ordering survives refresh independently");
    for (const width of [320, 390, 1100]) {
      await page.setViewportSize({ width, height: width === 320 ? 640 : 844 });
      assert(await groupSort.isVisible());
      assert(!await songSort.isVisible());
      assert(await page.locator(".m3x-screen").evaluate(element => element.scrollWidth <= element.clientWidth + 1));
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await groupSort.selectOption("name");
    await page.emulateMedia({ colorScheme: "dark" });
    const shots = path.join(__dirname, "test-output");
    fs.mkdirSync(shots, { recursive: true });
    await page.locator(".m3x-screen").screenshot({ path: path.join(shots, "classification-sort.png") });
    assert.deepEqual(errors, []);
    console.log("PASS: separate category/song ordering, four category views, name ascending/descending and song counts, independent settings + reload, 320/390/1100px.");
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
