// Run against preview_server.py, using the real local library (no fixture songs).
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { chromium } = require("playwright");
const base = process.env.PREVIEW_URL || "http://127.0.0.1:18767";

(async () => {
  const browser = await chromium.launch({
    executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    headless: true, args: ["--mute-audio"]
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    const response = await page.request.get(base + "/api/classification");
    assert.equal(response.status(), 200);
    const data = await response.json();
    assert(data.tracks.length > 4000, "Full local library, not a ten-song demo");
    assert.equal(data.engine, "semantic_exact_lookup", "Preview uses saved semantic decisions");
    assert.equal(data.classification_summary.semantic_tag_count, 1352);
    const union = data.tracks.find(t => t.path.endsWith("003-UNION!!.flac"));
    const tsumugi = data.entities.find(e => e.kind === "character" && e.name === "白石紬");
    assert(union.memberships.character.includes(tsumugi.id), "Confirmed truncated role is completed");
    assert.equal(union.memberships.character.length, 17);
    const joined = "鈴木みのり (藤原 肇役)、椎名豪";
    assert(!data.entities.some(e => e.kind === "artist" && e.name === joined), "Joined artist category removed");
    const hajime = data.entities.find(e => e.kind === "character" && e.name === "藤原肇");
    const shiina = data.entities.find(e => e.kind === "artist" && e.name === "椎名豪");
    const duet = data.tracks.find(t => t.artist.includes(joined));
    assert(duet.memberships.character.includes(hajime.id) && duet.memberships.artist.includes(shiina.id));
    assert(duet.decisions.some(entry => entry.reason && entry.model), "Semantic provenance is visible");
    for (const [raw, expected] of [
      ["宮川 弾、原口沙輔", ["宮川弾", "原口沙輔"]],
      ["沼倉愛美 & 駒形友梨", ["沼倉愛美", "駒形友梨"]]
    ]) {
      const track = data.tracks.find(t => t.artist.includes(raw));
      assert(track, `Real joined credit exists: ${raw}`);
      for (const name of expected) {
        const individual = data.entities.find(e => e.kind === "artist" && e.name === name);
        assert(individual && track.memberships.artist.includes(individual.id), `${raw} includes ${name}`);
      }
    }
    assert(data.entities.some(e => e.kind === "artist" && e.name === "諭吉佳作/men"),
      "A genuine stage name containing / stays whole");
    const multi = data.tracks.find(t => t.available && t.format === "FLAC" && t.memberships.character.length > 1);
    assert(multi, "Real multi-character recording is available");
    const [first, second] = multi.memberships.character;
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector("#entities [data-entity]"));
    assert.equal(await page.locator("audio").count(), 1);
    assert(await page.locator("audio").evaluate(audio => audio.paused), "No autoplay");
    assert.equal(await page.locator("#kinds button").count(), 4, "No CV category");
    assert((await page.locator("#classification-method").textContent()).includes("模型语义分类"));
    const nameChanges = JSON.parse(fs.readFileSync(path.join(__dirname, "semantic", "encyclopedia-names.json"), "utf8")).mappings;
    for (const change of nameChanges) {
      assert(!data.entities.some(e => e.kind === change.kind && e.name === change.original),
        `Encyclopedia name replaces ${change.original}`);
      assert(data.entities.some(e => e.kind === change.kind && e.name === change.name),
        `Encyclopedia name is available: ${change.name}`);
    }
    const renamedRole = nameChanges.find(change => change.kind === "character");
    assert(renamedRole, "Encyclopedia character names were researched");
    await page.locator("#entity-search").fill(renamedRole.name);
    const renamedEntity = data.entities.find(e => e.kind === "character" && e.name === renamedRole.name);
    await page.locator(`#entities [data-entity="${renamedEntity.id}"]`).click();
    assert.equal(await page.locator("#selection-title").textContent(), renamedRole.name);
    await page.locator(".track details").first().locator("summary").click();
    assert(await page.locator(".track details").first().getByRole("link", { name: "百科来源" }).count() > 0);
    await page.locator('#kinds [data-kind="artist"]').click();
    await page.locator("#entity-search").fill("椎名豪");
    assert.equal(await page.locator("#entities [data-entity]").count(), 1);
    await page.locator(`#entities [data-entity="${shiina.id}"]`).click();
    assert.equal(await page.locator(".track").count(), shiina.track_ids.length);
    assert.equal(await page.locator(`[data-track="${duet.id}"]`).count(), 1);
    await page.locator(`[data-track="${duet.id}"] [data-entity="${hajime.id}"]`).click();
    assert.equal(await page.locator("#selection-title").textContent(), "藤原肇");
    await page.locator('#kinds [data-kind="character"]').click();
    await page.locator(`#entities [data-entity="${first}"]`).click();
    const entity = data.entities.find(e => e.id === first);
    const allButton = page.locator("#load-more");
    while (await allButton.isVisible()) await allButton.click();
    assert.equal(await page.locator(".track").count(), entity.track_ids.length, "Exact classified membership count");
    assert.equal(await page.locator(`[data-track="${multi.id}"]`).count(), 1, "No duplicate multi-character song");
    const row = page.locator(`[data-track="${multi.id}"]`);
    assert(await row.locator(".raw-credit").textContent().then(text => text.includes(multi.artist[0])));
    await row.locator(`[data-entity="${second}"]`).click();
    while (await allButton.isVisible()) await allButton.click();
    assert.equal(await page.locator(`[data-track="${multi.id}"]`).count(), 1, "Same song appears in second role");
    await page.locator(`[data-track="${multi.id}"] .track-title`).click();
    await page.waitForFunction(() => {
      const audio = document.querySelector("audio");
      return !audio.paused && audio.currentTime > 0.1 && audio.readyState >= 2;
    }, null, { timeout: 30000 });
    assert.equal(await page.locator("audio").evaluate(audio => audio.error), null);
    const range = await page.request.get(base + multi.src, { headers: { Range: "bytes=0-3" } });
    assert.equal(range.status(), 206);
    assert.equal((await range.body()).toString(), "fLaC");
    await page.locator("#seek").fill("45");
    await page.waitForFunction(() => document.querySelector("audio").currentTime >= 44.9);
    await page.locator("#play").click();
    assert(await page.locator("audio").evaluate(audio => audio.paused));
    const secondRoleQueue = data.tracks.filter(t => t.available && t.memberships.character.includes(second));
    const next = secondRoleQueue[secondRoleQueue.findIndex(t => t.id === multi.id) + 1];
    assert(next, "There is a following real track");
    await page.locator("#next").click();
    await page.waitForFunction(id => {
      const audio = document.querySelector("audio");
      return audio.currentSrc.endsWith(id) && !audio.paused && audio.currentTime > 0.1;
    }, next.id);
    await page.locator("#previous").click();
    await page.waitForFunction(id => {
      const audio = document.querySelector("audio");
      return audio.currentSrc.endsWith(id) && !audio.paused && audio.currentTime > 0.1;
    }, multi.id);
    await page.locator("#play").click();
    await page.locator("#show-original").uncheck();
    assert.equal(await page.locator(".raw-credit").count(), 0);
    await page.locator("#show-original").check();
    await page.locator("#track-search").fill("NO_MATCH_08721");
    assert(await page.locator("#empty").isVisible());
    await page.locator("#track-search").fill("");
    await page.locator('#kinds [data-kind="group"]').click();
    const selected = await page.locator("#entities [aria-pressed=true]").getAttribute("data-entity");
    const group = data.entities.find(e => e.id === selected);
    const solo = data.tracks.find(t => group.track_ids.includes(t.id) && !t.memberships.character.length);
    if (solo) {
      await page.locator("#track-search").fill(solo.title);
      const groupRow = page.locator(`[data-track="${solo.id}"]`);
      assert.equal(await groupRow.locator(".chip.character").count(), 0, "Group-only credit never invents members");
    }
    await page.locator("#all-tracks").click();
    await page.locator("#project").selectOption("Shiny Colors");
    const projectCount = data.tracks.filter(t => t.project === "Shiny Colors").length;
    assert((await page.locator("#selection-description").textContent()).includes(projectCount.toLocaleString("zh-CN")));
    await page.locator("#review-only").check();
    const reviewCount = data.tracks.filter(t => t.project === "Shiny Colors" && t.issues.length).length;
    assert((await page.locator("#result-count").textContent()).startsWith(reviewCount.toLocaleString("zh-CN")));
    await page.locator("#review-only").uncheck();
    await page.locator("#project").selectOption("");
    await page.locator('#kinds [data-kind="character"]').click();
    await page.locator("#entity-search").fill("星井美希");
    await page.locator("#entities [data-entity]").click();
    for (const width of [1440, 760, 390, 320]) {
      await page.setViewportSize({ width, height: width < 800 ? 844 : 1050 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `No overflow at ${width}px`);
      const player = await page.locator(".player").boundingBox();
      assert(player.x >= 0 && player.width <= width + 1);
    }
    const output = path.join(__dirname, "output", "preview");
    fs.mkdirSync(output, { recursive: true });
    await page.setViewportSize({ width: 1440, height: 1050 });
    await page.screenshot({ path: path.join(output, "desktop.png"), fullPage: false });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(output, "mobile.png"), fullPage: false });
    assert.deepEqual(errors, []);
    console.log(`PASS: ${data.tracks.length} real tracks; role cross-membership, group preservation, raw credits, filtering, real FLAC playback/seek, 320–1440px layouts.`);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
