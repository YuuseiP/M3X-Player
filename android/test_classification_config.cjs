const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { compileClassification } = require("./prepare-classification.cjs");
const raw = fs.readFileSync(path.join(__dirname, "../artist-classification/semantic/library-preview.json"));
const source = JSON.parse(raw);
const config = compileClassification(raw);
const entities = new Map(config.entities.map(item => [item.id, item]));
assert.equal(config.tracks.length, 4235);
assert.equal(config.entities.length, 461);
assert(config.entities.some(item => item.name === "三浦梓"));
for (let i = 0; i < source.tracks.length; i++) {
  assert.deepEqual(config.tracks[i].memberships, source.tracks[i].memberships.filter(id => entities.has(id)));
  assert.equal(config.tracks[i].path, source.tracks[i].path.replace(/\\/g, "/"));
}
assert(!JSON.stringify(config).includes("BaiduNetdiskDownload"), "Windows root must not be embedded");
const edits = { schema: 1, baseline: config.baseline, entities: {}, memberships: {} };
const first = config.entities[0];
edits.entities[first.id] = { kind: first.kind, name: "Saved name" };
edits.memberships[source.tracks[0].id] = [first.id];
const changed = compileClassification(raw, edits);
assert.equal(changed.entities[0].name, "Saved name");
assert.deepEqual(changed.tracks[0].memberships, [first.id]);
assert.equal(source.entities.find(item => item.id === first.id).name, first.name);
assert.throws(() => compileClassification(raw, { ...edits, baseline: "wrong" }), /baseline/);
for (const invalid of ["../escape.flac", "/absolute.flac", "C:\\absolute.flac"]) {
  const data = structuredClone(source); data.tracks[0].path = invalid;
  assert.throws(() => compileClassification(Buffer.from(JSON.stringify(data))), /path/);
}
const conflict = structuredClone(source);
conflict.tracks[1].artist = conflict.tracks[0].artist;
conflict.tracks[1].memberships = [];
assert(!Object.hasOwn(compileClassification(Buffer.from(JSON.stringify(conflict))).tags, conflict.tracks[0].artist));
console.log("PASS: all 4235 confirmed memberships, 461 entities, encyclopedia names, saved edits, portable paths, ambiguous tags and invalid inputs.");
