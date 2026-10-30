(() => {
  "use strict";
  function parseLrc(source) {
    const offset = Number(source.match(/\[offset:\s*([+-]?\d+)\s*\]/i)?.[1] || 0) / 1000;
    const entries = [];
    const plain = [];
    for (const line of source.split(/\r?\n/)) {
      const stamps = [...line.matchAll(/\[(\d+):([0-5]?\d(?:\.\d+)?)\]/g)];
      const text = line.replace(/\[[^\]]*\]/g, "").trim();
      if (!stamps.length) { if (text) plain.push(text); continue; }
      for (const stamp of stamps) entries.push({ time: Math.max(0, Number(stamp[1]) * 60 + Number(stamp[2]) + offset), text });
    }
    entries.sort((a, b) => a.time - b.time);
    const lines = [];
    for (const entry of entries) {
      const last = lines[lines.length - 1];
      if (last && last.time === entry.time) last.text += entry.text ? (last.text ? "\n" : "") + entry.text : "";
      else lines.push({ ...entry });
    }
    return { lines, plain: plain.join("\n") };
  }
  function replayGain(tags, mode) {
    if (mode === "off") return { gain: 1, label: "已关闭" };
    let prefix = "replaygain_" + mode;
    const fallback = mode === "album" && tags[prefix + "_gain"] == null && tags.replaygain_track_gain != null;
    if (fallback) prefix = "replaygain_track";
    const db = Number.parseFloat(tags[prefix + "_gain"]);
    if (!Number.isFinite(db) || db < -60 || db > 30) return { gain: 1, label: "未检测到有效 ReplayGain 标签" };
    const peak = Number(tags[prefix + "_peak"]);
    const gain = Math.min(10 ** (db / 20), peak > 0 && Number.isFinite(peak) ? 1 / peak : 1);
    return { gain, label: `${fallback ? "使用单曲标签 · " : ""}${db > 0 ? "+" : ""}${db.toFixed(1)} dB` };
  }
  if (typeof document === "undefined") { module.exports = { parseLrc, replayGain }; return; }
  if (window.M3X_NATIVE) { window.M3X_TOOLS = { parseLrc, replayGain }; return; }

  function initialize() {
    const ui = window.M3X_UI;
    const audio = document.getElementById("audio");
    const root = document.getElementById("m3x-concept");
    const settingsKey = "m3x-player-tools-v1";
    let saved = {};
    try { saved = JSON.parse(localStorage.getItem(settingsKey)) || {}; } catch {}
    const rates = [.5, .75, 1, 1.25, 1.5, 2];
    const frequencies = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
    const presets = {
      flat: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
      vocal: [-2, -2, -1, 0, 1, 2, 3, 2, 0, -1],
      bass: [4, 4, 3, 1, 0, 0, 0, 0, -1, -1],
      bright: [-1, -1, 0, 0, 0, 1, 2, 3, 3, 2]
    };
    const config = {
      rate: rates.includes(saved.rate) ? saved.rate : 1,
      preservePitch: saved.preservePitch !== false, eq: saved.eq === true,
      bands: Array.isArray(saved.bands) && saved.bands.length === 10 ? saved.bands.map(value => Number.isFinite(value) ? Math.max(-12, Math.min(12, value)) : 0) : [...presets.flat],
      preset: Object.hasOwn(presets, saved.preset) ? saved.preset : "custom",
      replaygain: ["off", "track", "album"].includes(saved.replaygain) ? saved.replaygain : "off",
      lyricOffset: Number.isFinite(saved.lyricOffset) ? Math.max(-10, Math.min(10, saved.lyricOffset)) : 0,
      seconds: Number.isFinite(saved.seconds) ? Math.max(0, saved.seconds) : 0,
      counts: saved.counts && typeof saved.counts === "object" && !Array.isArray(saved.counts) ? Object.fromEntries(Object.entries(saved.counts).filter(([, value]) => Number.isInteger(value) && value >= 0)) : {}
    };
    let details = null;
    let requestId = 0;
    let lyrics = [];
    let activeLine = -1;
    let context, filters, gainNode, preamp;
    let currentPage = "library";
    let sleep = null;
    let stoppedForSleep = false;
    let previousTime = audio.currentTime;
    let previousClock = performance.now();
    let listened = 0, counted = false, lastSave = performance.now();
    const sleepDialog = document.getElementById("sleep-dialog");

    function save() {
      try { localStorage.setItem(settingsKey, JSON.stringify(config)); return true; }
      catch { ui.toast("无法保存播放偏好"); return false; }
    }
    function renderEq() {
      document.getElementById("eq-enabled").checked = config.eq;
      document.getElementById("eq-controls").hidden = !config.eq;
      document.getElementById("eq-preset").value = config.preset;
      document.getElementById("replaygain-mode").value = config.replaygain;
      document.querySelectorAll("[data-eq-band]").forEach(input => {
        const index = Number(input.dataset.eqBand);
        input.value = config.bands[index];
        input.previousElementSibling.querySelector("output").textContent = config.bands[index] + " dB";
      });
    }
    async function processAudio() {
      const result = replayGain(details?.replayGain || ui.currentTrack()?.replayGain || {}, config.replaygain);
      document.getElementById("replaygain-status").textContent = result.label;
      try {
        if (!context && (config.eq || config.replaygain !== "off")) {
          context = new AudioContext();
          const source = context.createMediaElementSource(audio);
          gainNode = context.createGain(); preamp = context.createGain();
          filters = frequencies.map(frequency => {
            const filter = context.createBiquadFilter();
            filter.type = "peaking"; filter.frequency.value = frequency; filter.Q.value = 1.4;
            return filter;
          });
          source.connect(gainNode); gainNode.connect(preamp);
          let previous = preamp;
          for (const filter of filters) { previous.connect(filter); previous = filter; }
          previous.connect(context.destination);
        }
        if (context) {
          gainNode.gain.setTargetAtTime(result.gain, context.currentTime, .015);
          // Leave headroom for positive EQ bands; this is not a hardware volume control.
          preamp.gain.setTargetAtTime(config.eq ? 10 ** (-Math.max(0, ...config.bands) / 20) : 1, context.currentTime, .015);
          filters.forEach((filter, index) => filter.gain.setTargetAtTime(config.eq ? config.bands[index] : 0, context.currentTime, .015));
          if (!audio.paused) await context.resume();
        }
      } catch {
        config.eq = false; config.replaygain = "off"; renderEq();
        if (gainNode) gainNode.gain.value = 1;
        if (preamp) preamp.gain.value = 1;
        filters?.forEach(filter => { filter.gain.value = 0; });
        save(); ui.toast("音效处理不可用，已恢复原始播放");
      }
    }
    const bands = document.getElementById("eq-bands");
    frequencies.forEach((frequency, index) => {
      const label = document.createElement("label");
      label.className = "m3x-eq-band";
      const heading = document.createElement("span");
      heading.append(document.createTextNode(frequency >= 1000 ? frequency / 1000 + "kHz" : frequency + "Hz"));
      const output = document.createElement("output"); heading.append(output);
      const input = document.createElement("input");
      input.type = "range"; input.min = -12; input.max = 12; input.step = .5; input.dataset.eqBand = index;
      input.setAttribute("aria-label", frequency + "Hz 增益");
      input.addEventListener("input", () => { config.bands[index] = Number(input.value); config.preset = "custom"; renderEq(); void processAudio(); });
      input.addEventListener("change", save);
      label.append(heading, input); bands.append(label);
    });
    document.getElementById("eq-enabled").addEventListener("change", event => { config.eq = event.target.checked; renderEq(); void processAudio(); save(); });
    document.getElementById("replaygain-mode").addEventListener("change", event => { config.replaygain = event.target.value; void processAudio(); save(); });
    document.getElementById("eq-preset").addEventListener("change", event => {
      if (presets[event.target.value]) { config.preset = event.target.value; config.bands = [...presets[config.preset]]; renderEq(); void processAudio(); save(); }
    });
    document.getElementById("eq-reset").addEventListener("click", () => { config.bands = [...presets.flat]; config.preset = "flat"; renderEq(); void processAudio(); save(); });
    document.getElementById("playback-rate").value = config.rate;
    document.getElementById("preserve-pitch").checked = config.preservePitch;
    function applyRate() { audio.defaultPlaybackRate = config.rate; audio.playbackRate = config.rate; audio.preservesPitch = config.preservePitch; }
    audio.addEventListener("loadedmetadata", applyRate);
    document.getElementById("playback-rate").addEventListener("change", event => { config.rate = Number(event.target.value); applyRate(); save(); ui.toast(config.rate + "× 播放"); });
    document.getElementById("preserve-pitch").addEventListener("change", event => { config.preservePitch = event.target.checked; applyRate(); save(); });
    applyRate(); renderEq();

    function renderLyrics(text) {
      const parsed = parseLrc(text || "");
      lyrics = parsed.lines; activeLine = -1;
      const container = document.getElementById("lyrics-lines"); container.replaceChildren();
      if (lyrics.length) {
        lyrics.forEach((line, index) => {
          const button = document.createElement("button"); button.type = "button"; button.className = "m3x-lyric-line";
          button.textContent = line.text || "♪"; button.dataset.lyricLine = index;
          button.addEventListener("click", () => { if (audio.readyState) audio.currentTime = Math.max(0, Math.min(audio.duration, line.time + config.lyricOffset)); });
          container.append(button);
        });
      } else {
        const content = document.createElement("p"); content.className = "m3x-plain-lyrics";
        content.textContent = parsed.plain || "暂无本地歌词"; container.append(content);
      }
      syncLyrics();
    }
    function syncLyrics() {
      let index = -1;
      for (let i = 0; i < lyrics.length && lyrics[i].time <= audio.currentTime - config.lyricOffset; i++) index = i;
      if (index === activeLine) return;
      activeLine = index;
      document.querySelectorAll("[data-lyric-line]").forEach((element, i) => {
        element.setAttribute("aria-current", String(i === index));
        if (i === index && currentPage === "lyrics") element.scrollIntoView({ block: "center", behavior: "auto" });
      });
    }
    const offset = document.getElementById("lyric-offset");
    const offsetLabel = document.getElementById("lyric-offset-label");
    offset.value = config.lyricOffset; offsetLabel.value = config.lyricOffset.toFixed(1) + " 秒";
    offset.addEventListener("input", () => { config.lyricOffset = Number(offset.value); offsetLabel.value = config.lyricOffset.toFixed(1) + " 秒"; syncLyrics(); });
    offset.addEventListener("change", save);
    async function trackChanged() {
      const id = ++requestId;
      const track = ui.currentTrack();
      listened = 0; counted = false; previousTime = audio.currentTime; previousClock = performance.now();
      details = null; renderLyrics("");
      if (navigator.mediaSession && window.MediaMetadata) {
        navigator.mediaSession.metadata = track ? new MediaMetadata({
          title: track.title, artist: track.artist, album: track.album,
          artwork: [{ src: new URL(track.cover, location.href).href }]
        }) : null;
      }
      if (!track) { void processAudio(); return; }
      try {
        const response = await fetch("/api/track?id=" + encodeURIComponent(track.id));
        if (!response.ok) throw new Error("Read failed");
        const data = await response.json();
        if (requestId !== id) return;
        details = data; renderLyrics(data.lyrics);
        if (track.classificationPreview) ui.updateTrack(track.id, data);
      } catch {
        if (requestId !== id) return;
        renderLyrics("");
      }
      void processAudio();
    }
    function renderStats() {
      document.getElementById("listening-minutes").textContent = Math.floor(config.seconds / 60);
      document.getElementById("listening-count").textContent = Object.values(config.counts).reduce((total, value) => total + value, 0);
      const ranking = document.getElementById("listening-ranking"); ranking.replaceChildren();
      const sorted = ui.tracks.filter(track => config.counts[track.id]).sort((a, b) => config.counts[b.id] - config.counts[a.id]).slice(0, 20);
      for (const track of sorted) {
        const button = document.createElement("button"); button.type = "button"; button.className = "m3x-sheet-action";
        const name = document.createElement("span"); name.textContent = track.title;
        const count = document.createElement("small"); count.textContent = config.counts[track.id] + " 次";
        button.append(name, count); button.addEventListener("click", () => ui.showTrack(track.id)); ranking.append(button);
      }
      if (!sorted.length) ranking.textContent = "暂无播放统计";
    }
    function renderSleep() {
      const message = sleep ? sleep.waiting ? "播完本曲后停止" : "剩余 " + Math.max(1, Math.ceil((sleep.deadline - Date.now()) / 60000)) + " 分钟" : "未开启";
      document.getElementById("sleep-summary").textContent = message;
      document.getElementById("sleep-status").textContent = message;
    }
    function cancelSleep(message) { sleep = null; renderSleep(); if (message) ui.toast(message); }
    function stopForSleep() { stoppedForSleep = true; cancelSleep(); audio.pause(); ui.toast("睡眠定时已结束"); }
    function checkSleep() {
      if (sleep && Date.now() >= sleep.deadline) {
        if (sleep.finishSong && !audio.paused && !audio.ended) { sleep.waiting = true; renderSleep(); }
        else stopForSleep();
      } else renderSleep();
    }
    ui.stopAtEnd = () => {
      listened = 0; counted = false;
      // A final timeupdate can expire the timer before the ended event arrives.
      if (stoppedForSleep) return true;
      if (sleep && Date.now() >= sleep.deadline) { stopForSleep(); return true; }
      return false;
    };
    ui.playCount = id => config.counts[id] || 0;
    root.addEventListener("click", event => {
      if (event.target.closest('[data-tool="sleep"]')) { renderSleep(); ui.openDialog(sleepDialog); }
    });
    document.getElementById("sleep-form").addEventListener("submit", event => {
      event.preventDefault();
      const minutes = Number(document.getElementById("sleep-minutes").value);
      if (!Number.isInteger(minutes) || minutes < 1 || minutes > 180) return;
      sleep = { deadline: Date.now() + minutes * 60000, finishSong: document.getElementById("sleep-finish-song").checked, waiting: false };
      renderSleep(); ui.closeDialog(sleepDialog); ui.toast("已设置 " + minutes + " 分钟定时");
    });
    document.getElementById("sleep-cancel").addEventListener("click", () => { cancelSleep("已关闭睡眠定时"); ui.closeDialog(sleepDialog); });
    window.addEventListener("m3x-manual-track", () => { if (sleep?.finishSong) cancelSleep("已切歌，整曲定时已取消"); });
    audio.addEventListener("pause", () => {
      if (sleep?.finishSong && !audio.ended) cancelSleep("已暂停，整曲定时已取消");
      save();
      if (navigator.mediaSession) navigator.mediaSession.playbackState = "paused";
    });
    audio.addEventListener("play", () => {
      stoppedForSleep = false;
      previousTime = audio.currentTime; previousClock = performance.now();
      if (context) void context.resume().catch(() => ui.toast("请重新点击播放以启用音效"));
      if (navigator.mediaSession) navigator.mediaSession.playbackState = "playing";
    });
    audio.addEventListener("seeking", () => { previousTime = audio.currentTime; previousClock = performance.now(); });
    audio.addEventListener("timeupdate", () => {
      const now = performance.now(), elapsed = (now - previousClock) / 1000;
      const advanced = audio.currentTime - previousTime;
      if (!audio.paused && !audio.seeking && elapsed > 0 && elapsed < 3 && advanced > 0 && advanced <= elapsed * audio.playbackRate + .75) {
        config.seconds += elapsed; listened += elapsed;
        const track = ui.currentTrack();
        if (track && !counted && listened >= Math.min(30, track.duration / 2)) {
          config.counts[track.id] = (config.counts[track.id] || 0) + 1; counted = true; save();
        }
      }
      previousTime = audio.currentTime; previousClock = now;
      if (now - lastSave > 10000) { save(); lastSave = now; }
      syncLyrics(); checkSleep();
      if (currentPage === "statistics") renderStats();
    });
    window.addEventListener("m3x-track", () => { void trackChanged(); });
    window.addEventListener("m3x-render", event => {
      currentPage = event.detail.page;
      if (currentPage === "statistics") renderStats();
      if (currentPage === "lyrics") {
        activeLine = -1; syncLyrics();
      }
    });
    document.addEventListener("keydown", event => {
      if (event.code !== "Space" || event.repeat || event.altKey || event.ctrlKey || event.metaKey ||
          event.target.closest("input, textarea, select, button, [contenteditable], dialog")) return;
      event.preventDefault(); if (audio.paused) void ui.play(); else audio.pause();
    });
    if (navigator.mediaSession) {
      const handlers = {
        play: () => void ui.play(), pause: () => audio.pause(), previoustrack: ui.previous, nexttrack: ui.next,
        seekto: event => { if (audio.readyState && Number.isFinite(event.seekTime)) audio.currentTime = Math.max(0, Math.min(audio.duration, event.seekTime)); },
        seekbackward: event => { if (audio.readyState) audio.currentTime = Math.max(0, audio.currentTime - (event.seekOffset || 10)); },
        seekforward: event => { if (audio.readyState) audio.currentTime = Math.min(audio.duration, audio.currentTime + (event.seekOffset || 10)); }
      };
      for (const [action, handler] of Object.entries(handlers)) {
        try { navigator.mediaSession.setActionHandler(action, handler); } catch { /* Some browsers implement only basic actions. */ }
      }
    }
    setInterval(checkSleep, 1000);
    document.addEventListener("visibilitychange", checkSleep);
    window.addEventListener("pagehide", save);
    renderStats(); renderSleep(); void trackChanged();
  }
  if (window.M3X_UI) initialize();
  else window.addEventListener("m3x-ready", initialize, { once: true });
})();
