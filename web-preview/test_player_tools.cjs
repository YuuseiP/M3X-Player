const assert = require("node:assert/strict");
const { parseLrc, replayGain } = require("./player-tools.js");

assert.deepEqual(parseLrc("[ti:Title]\n[offset:-500]\n[00:01.50][00:04]原文\n[00:01.50]翻译\n[02:03.125]末句").lines, [
  { time: 1, text: "原文\n翻译" }, { time: 3.5, text: "原文" }, { time: 122.625, text: "末句" }
]);
assert.deepEqual(parseLrc("[ar:Artist]\n普通歌词\n第二行"), { lines: [], plain: "普通歌词\n第二行" });
assert.equal(parseLrc("[offset:-1000]\n[00:00.5]开始").lines[0].time, 0);
assert.deepEqual(parseLrc(""), { lines: [], plain: "" });
assert.equal(replayGain({}, "off").gain, 1);
assert.equal(replayGain({}, "track").gain, 1);
assert.equal(replayGain({ replaygain_track_gain: "invalid" }, "track").gain, 1);
assert(Math.abs(replayGain({ replaygain_track_gain: "-6 dB" }, "track").gain - 10 ** (-6 / 20)) < 1e-9);
assert.equal(replayGain({ replaygain_track_gain: "+6 dB", replaygain_track_peak: "0.8" }, "track").gain, 1.25);
assert.equal(replayGain({ replaygain_track_gain: "+6 dB" }, "track").gain, 1, "No unbounded boost without a peak tag");
assert(replayGain({ replaygain_track_gain: "-4 dB" }, "album").label.includes("使用单曲标签"));
assert.equal(replayGain({ replaygain_track_gain: "-4 dB", replaygain_album_gain: "-8 dB" }, "album").gain, 10 ** (-8 / 20));
console.log("PASS: LRC timestamps, translation grouping, offsets/plain text; ReplayGain modes, fallback, peak limiting and invalid tags.");
