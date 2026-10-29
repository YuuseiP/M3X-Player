(() => {
  "use strict";
  function initialize() {
    const ui = window.M3X_UI, root = document.getElementById("m3x-concept"), audio = document.getElementById("audio");
    let state = nativeCommand("poll"), config = state.settings || {}, lines = [], active = -1, detailRequest = 0, page = "library", lastNotice = "";
    const $ = id => document.getElementById(id);
    const classification = document.createElement("p");
    classification.id = "builtin-classification"; classification.className = "m3x-caption";
    const directories = document.getElementById("scan-directories");
    directories.parentNode.insertBefore(classification, directories.nextSibling);
    function classificationSummary() {
      classification.textContent = `内置分类 · 已匹配 ${ui.library()?.classification?.matched || 0} 首 · 其余保留原始歌手`;
    }
    classificationSummary();
    const configure = update => { const result = nativeCommand("configure", update); if (!result.error) config = result.settings; return result; };
    function setting(label, control) {
      const row = document.createElement("label"); row.className = "m3x-setting";
      const text = document.createElement("span"); text.textContent = label; row.append(text, control); return row;
    }
    function button(label, action) {
      const button = document.createElement("button"); button.type = "button"; button.className = "m3x-setting m3x-setting-button";
      button.textContent = label; button.addEventListener("click", action); return button;
    }
    function check(label, key, parent) {
      const input = document.createElement("input"); input.type = "checkbox"; input.checked = !!config[key];
      input.addEventListener("change", () => configure({ [key]: input.checked })); parent.append(setting(label, input));
    }
    function theme() {
      const saved = JSON.parse(localStorage.getItem("m3x-shiny-preview-v1") || "{}");
      document.documentElement.dataset.theme = saved.theme === "dark" || (saved.theme !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches) ? "dark" : "light";
    }
    theme();
    const frequencies = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];
    const presets = { flat: [0,0,0,0,0,0,0,0,0,0], vocal: [-2,-2,-1,0,1,2,3,2,0,-1], bass: [4,4,3,1,0,0,0,0,-1,-1], bright: [-1,-1,0,0,0,1,2,3,3,2] };
    $("eq-enabled").checked = !!config.eq; $("eq-controls").hidden = !config.eq;
    $("eq-enabled").addEventListener("change", event => { configure({ eq: event.target.checked }); $("eq-controls").hidden = !event.target.checked; });
    function bands() {
      $("eq-bands").replaceChildren();
      frequencies.forEach((frequency, index) => {
        const label = document.createElement("label"); label.className = "m3x-eq-band";
        const heading = document.createElement("span"); heading.textContent = frequency >= 1000 ? frequency / 1000 + "kHz" : frequency + "Hz";
        const value = document.createElement("output"); value.textContent = (config.bands?.[index] || 0) + " dB"; heading.append(value);
        const slider = document.createElement("input"); slider.type = "range"; slider.min = -12; slider.max = 12; slider.step = .5; slider.value = config.bands?.[index] || 0;
        slider.setAttribute("aria-label", frequency + "Hz 增益");
        slider.addEventListener("change", () => {
          const values = [...(config.bands || presets.flat)]; values[index] = Number(slider.value);
          configure({ bands: values, peq: null, preset: "custom" }); value.textContent = slider.value + " dB"; $("eq-preset").value = "custom";
        });
        label.append(heading, slider); $("eq-bands").append(label);
      });
    }
    bands();
    $("eq-preset").value = config.preset || "flat";
    $("eq-preset").addEventListener("change", event => { if (presets[event.target.value]) { configure({ bands: presets[event.target.value], preset: event.target.value, peq: null }); bands(); } });
    $("eq-reset").addEventListener("click", () => { configure({ bands: presets.flat, peq: null, preset: "flat" }); bands(); });
    $("replaygain-mode").value = config.replaygain || "off";
    $("replaygain-mode").addEventListener("change", event => configure({ replaygain: event.target.value }));
    $("playback-rate").replaceChildren();
    [.25, .5, .75, .8, .9, .95, 1, 1.05, 1.1, 1.25, 1.5, 1.75, 2, 2.5, 3, 4].forEach(rate => $("playback-rate").append(new Option(rate + "×", rate)));
    $("playback-rate").value = String(config.rate || 1);
    $("playback-rate").addEventListener("change", event => configure({ rate: Number(event.target.value) }));
    $("preserve-pitch").checked = config.preservePitch !== false;
    $("preserve-pitch").addEventListener("change", event => configure({ preservePitch: event.target.checked }));
    const processing = document.getElementById("processing-panel");
    check("混响", "reverb", processing); check("动态压限", "compressor", processing); check("音乐厅", "hall", processing);
    check("倒放 · PCM16 缓存", "reverse", processing);
    check("播放／暂停淡入淡出", "fades", processing);
    check("专辑感知淡变", "albumTransitions", processing);
    processing.append(button("参数均衡器 · 32 段", () => openPeq()));
    processing.append(button("导入 AutoEQ / APO 参数", () => nativeCommand("peq-import")));
    processing.append(button("导出参数均衡器", () => nativeCommand("peq-export")));
    processing.append(button("系统均衡器", () => nativeCommand("system-eq")));
    const outputRate = document.createElement("select");
    [0, 44100, 48000, 88200, 96000, 176400, 192000].forEach(rate => outputRate.append(new Option(rate ? rate / 1000 + "kHz" : "自动", rate)));
    outputRate.value = config.sampleRate || 0;
    outputRate.addEventListener("change", () => configure({ sampleRate: Number(outputRate.value) }));
    processing.append(setting("引擎提交采样率", outputRate));
    function sheet(title) {
      const dialog = document.createElement("dialog"); dialog.className = "m3x-sheet-overlay";
      dialog.setAttribute("aria-label", title);
      const scrim = document.createElement("div"); scrim.className = "m3x-sheet-scrim";
      const content = document.createElement("section"); content.className = "m3x-sheet";
      const header = document.createElement("header"); header.className = "m3x-sheet-header";
      const name = document.createElement("h2"); name.textContent = title;
      const close = document.createElement("button"); close.className = "m3x-icon"; close.textContent = "×";
      close.addEventListener("click", () => ui.closeDialog(dialog, true)); header.append(name, close); content.append(header);
      dialog.append(scrim, content); root.querySelector(".m3x-screen").append(dialog);
      dialog.addEventListener("click", event => { if (event.target === scrim) ui.closeDialog(dialog, true); });
      // Dynamic native sheets need the same Android 7.1 dialog fallback as static sheets.
      if (!dialog.showModal) {
        Object.defineProperty(dialog, "open", { get() { return this.hasAttribute("open"); } });
        dialog.showModal = () => dialog.setAttribute("open", "");
        dialog.close = () => { dialog.removeAttribute("open"); dialog.dispatchEvent(new Event("close")); };
      }
      dialog.addEventListener("close", () => dialog.remove());
      ui.openDialog(dialog); return content;
    }
    function openPeq() {
      const content = sheet("参数均衡器");
      let values = config.peq ? JSON.parse(JSON.stringify(config.peq)) : Array.from({ length: 32 }, (_, i) => ({ type: "PK", frequency: Math.round(20 * Math.pow(1000, i / 31)), gain: 0, q: 1.4, enabled: true }));
      const editor = document.createElement("div");
      values.forEach((band, index) => {
        const row = document.createElement("div"); row.className = "m3x-peq-row";
        const count = document.createElement("span"); count.textContent = index + 1;
        const enabled = document.createElement("input"); enabled.type = "checkbox"; enabled.checked = band.enabled !== false;
        enabled.setAttribute("aria-label", `启用第 ${index + 1} 段`); enabled.addEventListener("change", () => { band.enabled = enabled.checked; });
        const type = document.createElement("select");
        ["PK", "LS", "HS", "LP", "HP"].forEach(value => type.append(new Option(value, value))); type.value = band.type;
        type.addEventListener("change", () => { band.type = type.value; });
        row.append(count, enabled, type);
        for (const [key, label, min, max, step] of [["frequency", "Hz", 10, 48000, 1], ["gain", "dB", -18, 18, .1], ["q", "Q", .1, 18, .1]]) {
          const input = document.createElement("input"); input.type = "number"; input.value = band[key]; input.min = min; input.max = max; input.step = step; input.setAttribute("aria-label", `${index + 1} ${label}`);
          input.addEventListener("change", () => { band[key] = Number(input.value); }); row.append(input);
        }
        editor.append(row);
      });
      content.append(editor, button("应用并保存", () => { const result = configure({ peq: values, eq: true, preset: "custom" }); if (!result.error) { $("eq-enabled").checked = true; $("eq-controls").hidden = false; ui.closeDialog(content.closest("dialog"), true); } }));
    }
    const settings = root.querySelector(".m3x-settings-body");
    settings.append(button("导出歌单与个人资料备份", () => nativeCommand("backup-export")));
    settings.append(button("从备份恢复", () => nativeCommand("backup-import")));
    settings.append(button("清理封面与解码缓存", () => nativeCommand("clear-cache")));
    settings.append(button("开源许可", () => nativeCommand("licenses")));
    function networkBrowser(operation, initial = {}) {
      const content = sheet(operation === "autoeq" ? "AutoEQ 耳机搜索" : operation === "discover" ? "局域网音乐" : "WebDAV 音乐");
      const form = document.createElement("form"), input = document.createElement("input");
      input.type = operation === "autoeq" ? "search" : "url"; input.placeholder = operation === "autoeq" ? "耳机型号" : "https://服务器/音乐/";
      input.setAttribute("aria-label", operation === "autoeq" ? "耳机型号" : "服务器地址"); input.required = true; input.value = initial.url || "";
      const user = document.createElement("input"), password = document.createElement("input");
      user.placeholder = "用户名（可选）"; password.placeholder = "密码"; password.type = "password";
      user.setAttribute("aria-label", "WebDAV 用户名"); password.setAttribute("aria-label", "WebDAV 密码");
      form.append(input);
      if (operation === "webdav") form.append(user, password);
      const submit = document.createElement("button"); submit.type = "submit"; submit.className = "m3x-text-button"; submit.textContent = "读取"; form.append(submit);
      const status = document.createElement("p"); status.className = "m3x-caption";
      const results = document.createElement("div");
      let last = { operation, ...initial }, history = [], requested = false;
      const back = button("返回上一层", () => { const previous = history.pop(); if (previous) request(previous, false); }); back.hidden = true;
      function request(data, remember = true) {
        if (remember && requested) history.push(last);
        requested = true; back.hidden = !history.length;
        status.textContent = "正在读取"; last = data; nativeCommand("network", data);
        delete data.password; delete data.username;
      }
      if (operation !== "discover" && operation !== "dlna") content.append(form);
      content.append(back, status, results);
      form.addEventListener("submit", event => { event.preventDefault(); request(operation === "autoeq" ? { operation, query: input.value } : { operation, url: input.value, username: user.value, password: password.value }); password.value = ""; });
      const receive = event => {
        if (!content.closest("dialog")) return;
        const data = event.detail; status.textContent = data.error || data.message || (data.peq ? "已应用耳机参数" : "");
        results.replaceChildren();
        for (const item of data.entries || []) results.append(button((item.folder ? "▸ " : "") + item.title, () => {
          if (item.profile) request({ operation: "autoeq", profile: item.profile });
          else if (item.folder) request({ operation: last.operation === "discover" || item.service ? "dlna" : "webdav", ...item });
          else request({ operation: "play", ...item });
        }));
        if (data.entries && !data.entries.length) status.textContent = "没有找到匹配内容";
        if (last.operation === "dlna" && Number(data.total) > (last.start || 0) + (data.entries?.length || 0))
          results.append(button("下一页", () => request({ ...last, start: (last.start || 0) + data.entries.length })));
      };
      window.addEventListener("m3x-native-network", receive);
      content.closest("dialog").addEventListener("close", () => window.removeEventListener("m3x-native-network", receive));
      if (operation === "discover" || operation === "dlna") request(last);
    }
    settings.append(button("WebDAV 网络音乐", () => networkBrowser("webdav")));
    settings.append(button("发现 DLNA 音乐服务器", () => networkBrowser("discover")));
    processing.append(button("AutoEQ 耳机搜索", () => networkBrowser("autoeq")));
    const songMenu = $("song-menu-dialog").querySelector(".m3x-sheet");
    for (const [label, lyrics] of [["用外部应用编辑标签", false], ["用外部应用编辑歌词", true]]) {
      songMenu.append(button(label, () => nativeCommand("external-edit", { id: $("song-menu-dialog").dataset.trackId, lyrics })));
    }
    songMenu.append(button("导出歌曲片段", () => {
      const track = ui.tracks.find(track => track.id === $("song-menu-dialog").dataset.trackId) || ui.currentTrack(); if (!track) return;
      const content = sheet("导出 PCM WAV 片段");
      const start = document.createElement("input"), end = document.createElement("input");
      start.type = end.type = "number"; start.min = end.min = 0; start.step = end.step = .1; start.value = 0; end.value = track.duration;
      content.append(setting("起点（秒）", start), setting("终点（秒）", end), button("选择保存位置", () => {
        if (Number(start.value) < 0 || Number(end.value) <= Number(start.value) || Number(end.value) > track.duration) { ui.toast("请填写歌曲范围内的起止时间"); return; }
        nativeCommand("clip-export", { id: track.id, start: Number(start.value), end: Number(end.value) });
      }));
    }));
    songMenu.append(button("从网址导入歌词", () => {
      const track = ui.tracks.find(track => track.id === $("song-menu-dialog").dataset.trackId) || ui.currentTrack(); if (!track) return;
      const content = sheet("导入 LRC 歌词"), input = document.createElement("input");
      input.type = "url"; input.placeholder = "https://…/歌词.lrc"; input.setAttribute("aria-label", "歌词地址");
      content.append(input, button("读取并保存", () => nativeCommand("network", { operation: "lyrics", id: track.id, url: input.value })));
    }));
    function lyrics() {
      const request = ++detailRequest, track = ui.currentTrack();
      if (!track) return;
      fetch("/api/track?id=" + encodeURIComponent(track.id)).then(response => response.json()).then(details => {
        if (request !== detailRequest) return;
        const parsed = M3X_TOOLS.parseLrc(details.lyrics || ""); lines = parsed.lines; active = -1; $("lyrics-lines").replaceChildren();
        if (!lines.length) { const p = document.createElement("p"); p.className = "m3x-plain-lyrics"; p.textContent = parsed.plain || "暂无本地歌词"; $("lyrics-lines").append(p); }
        lines.forEach((line, index) => {
          const button = document.createElement("button"); button.className = "m3x-lyric-line"; button.textContent = line.text || "♪";
          button.addEventListener("click", () => { audio.currentTime = line.time + Number($("lyric-offset").value); }); $("lyrics-lines").append(button);
        });
        $("replaygain-status").textContent = M3X_TOOLS.replayGain(details.replayGain || {}, config.replaygain || "off").label;
      }).catch(() => {});
    }
    $("lyric-offset").value = config.lyricOffset || 0;
    $("lyric-offset").addEventListener("change", event => { configure({ lyricOffset: Number(event.target.value) }); $("lyric-offset-label").value = event.target.value + " 秒"; });
    window.addEventListener("m3x-track", lyrics);
    window.addEventListener("m3x-native-network", event => {
      if (event.detail.message || event.detail.error) ui.toast(event.detail.error || event.detail.message);
      if (event.detail.message === "歌词已保存") lyrics();
    });
    window.addEventListener("m3x-render", event => { page = event.detail.page; theme(); classificationSummary(); });
    window.addEventListener("m3x-native-state", event => {
      state = event.detail;
      config = state.settings || config;
      $("eq-enabled").checked = !!config.eq; $("eq-controls").hidden = !config.eq;
      $("eq-bands").hidden = Array.isArray(config.peq) && config.peq.length > 0;
      if (config.peq) $("eq-preset").value = "custom";
      const signal = document.querySelector('#signal-panel .m3x-signal');
      signal.children[1].querySelector("dd").textContent = state.outputRate ? "AudioTrack · " + state.outputRate / 1000 + "kHz · " + (state.outputEncoding === 4 ? "Float32" : state.outputEncoding === 2 ? "PCM16" : "PCM") : "原生 ExoPlayer";
      signal.children[2].querySelector("dd").textContent = state.route;
      const message = state.sleepWaiting ? "播完本曲后停止" : state.sleepRemaining > 0 ? "剩余 " + Math.ceil(state.sleepRemaining / 60000) + " 分钟" : "未开启";
      $("sleep-summary").textContent = message; $("sleep-status").textContent = message;
      const notice = state.conversion || state.error || "";
      if (notice) { $("playback-status").hidden = false; $("playback-status").textContent = notice; }
      else if (lastNotice && $("playback-status").textContent === lastNotice) $("playback-status").hidden = true;
      lastNotice = notice;
      const time = audio.currentTime - Number($("lyric-offset").value);
      let next = -1; for (let i = 0; i < lines.length && lines[i].time <= time; i++) next = i;
      if (next !== active) {
        active = next; $("lyrics-lines").querySelectorAll("button").forEach((button, index) => {
          button.setAttribute("aria-current", String(index === active)); if (index === active && page === "lyrics") button.scrollIntoView({ block: "center" });
        });
      }
      const stats = state.stats || {};
      $("listening-minutes").textContent = Math.floor((stats.seconds || 0) / 60);
      $("listening-count").textContent = Object.values(stats.counts || {}).reduce((sum, count) => sum + count, 0);
      if (page === "statistics") {
        $("listening-ranking").replaceChildren();
        ui.tracks.filter(track => stats.counts?.[track.id]).sort((a, b) => stats.counts[b.id] - stats.counts[a.id]).slice(0, 20)
          .forEach(track => $("listening-ranking").append(button(track.title + " · " + stats.counts[track.id] + " 次", () => ui.showTrack(track.id))));
      }
    });
    root.addEventListener("click", event => {
      if (event.target.closest('[data-tool="sleep"]')) ui.openDialog($("sleep-dialog"));
    });
    $("sleep-form").addEventListener("submit", event => { event.preventDefault(); nativeCommand("sleep", { minutes: Number($("sleep-minutes").value), finish: $("sleep-finish-song").checked }); ui.closeDialog($("sleep-dialog")); });
    $("sleep-cancel").addEventListener("click", () => { nativeCommand("sleep", { minutes: 0 }); ui.closeDialog($("sleep-dialog")); });
    ui.playCount = id => state.stats?.counts?.[id] || 0;
    lyrics();
  }
  if (window.M3X_UI) initialize(); else window.addEventListener("m3x-ready", initialize, { once: true });
})();
