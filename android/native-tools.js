(() => {
  "use strict";
  function initialize() {
    const ui = window.M3X_UI, root = document.getElementById("m3x-concept"), audio = document.getElementById("audio");
    let state = nativeCommand("poll"), config = state.settings || {}, lines = [], active = -1, detailRequest = 0, page = "library", lastNotice = "", selectedLyrics = false;
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
    check("跨专辑切歌淡入淡出", "albumTransitions", processing);
    const fadeHelp = document.createElement("p"); fadeHelp.className = "m3x-caption";
    fadeHelp.textContent = "跨专辑切歌时，结尾和开头约 350ms 淡出／淡入；同专辑不额外淡变。两首歌曲不会重叠混音。"; processing.append(fadeHelp);
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
    function importLyrics(track) {
      if (!track) { ui.toast("请先选择歌曲"); return; }
      const content = sheet("导入 LRC 歌词"), input = document.createElement("input");
      input.type = "url"; input.placeholder = "https://…/歌词.lrc"; input.setAttribute("aria-label", "歌词地址");
      const help = document.createElement("p"); help.className = "m3x-caption"; help.textContent = "导入的 LRC 保存在本应用内，不改写歌曲文件。";
      content.append(help, input, button("导入到本应用", () => nativeCommand("network", { operation: "lyrics", id: track.id, url: input.value })));
    }
    songMenu.append(button("从网址导入歌词", () => importLyrics(ui.tracks.find(track => track.id === $("song-menu-dialog").dataset.trackId) || ui.currentTrack())));

    let lyricRequestId = 0;
    function onlineLyrics(track) {
      if (!track) { ui.toast("请先选择歌曲"); return; }
      const content = sheet("搜索与保存歌词"), dialog = content.closest("dialog");
      const context = document.createElement("p"); context.className = "m3x-caption";
      context.textContent = "本地：" + track.title + " · " + (track.originalArtist || track.artist) + " · " + (track.album || "") + " · " + Number(track.duration || 0).toFixed(1) + " 秒";
      const info = document.createElement("p"); info.className = "m3x-caption";
      info.textContent = "联网查询会向所选来源发送搜索词和候选编号，不上传音频。请核对发行版本、演唱者和时长，再采用歌词。标注写入歌词头，不改歌曲本身的曲名／歌手／专辑标签。";
      const query = document.createElement("input"); query.type = "search"; query.maxLength = 250; query.setAttribute("aria-label", "歌词搜索词");
      query.value = (track.title + " " + (track.originalArtist || track.artist || "")).slice(0, 250);
      const provider = document.createElement("select"); provider.setAttribute("aria-label", "歌词来源"); provider.append(new Option("QQ 音乐", "QQ"), new Option("网易云音乐", "NE"));
      const status = document.createElement("p"); status.className = "m3x-caption"; status.setAttribute("role", "status");
      const results = document.createElement("div"), editor = document.createElement("section"), preview = document.createElement("div");
      results.className = "m3x-lyric-results";
      editor.hidden = true; preview.className = "m3x-lyric-preview";
      let doc = null, token = "", revision = "", busy = false, editable = false, sequence = 0, previewLines = [], selected = false;
      const pending = new Map(), controls = [], fields = {};
      function request(operation, args = {}) {
        return new Promise((resolve, reject) => {
          const requestId = "lyrics-" + Date.now() + "-" + (++lyricRequestId);
          pending.set(requestId, { resolve, reject });
          const result = nativeCommand("lyrics", { id: track.id, requestId, operation, ...args });
          if (result.error) { pending.delete(requestId); reject(new Error(result.error)); }
        });
      }
      const receive = event => {
        const data = event.detail, p = pending.get(data.requestId);
        if (!p || data.id !== track.id) return;
        pending.delete(data.requestId); if (data.error) p.reject(new Error(data.error)); else p.resolve(data);
      };
      window.addEventListener("m3x-lyrics-result", receive);
      function lock(value) { busy = value; controls.forEach(control => { control.disabled = value; }); }
      async function run(action) {
        if (busy) return;
        lock(true); status.textContent = "处理中…";
        try { await action(); } catch (error) {
          status.textContent = error.message || "歌词操作失败";
          try { const fresh = await request("state"); recovery.hidden = !fresh.recovery; recoveryWarning.hidden = !fresh.recovery || fresh.recovery === "complete"; } catch (_) {}
        }
        finally { lock(false); }
      }
      const search = button("联网搜索", () => run(async () => {
        const seq = ++sequence; results.replaceChildren();
        const data = await request("search", { provider: provider.value, query: query.value });
        if (seq !== sequence || !dialog.open) return;
        status.textContent = data.entries.length ? "找到 " + data.entries.length + " 个候选；点击预览，不会自动采用" : "没有找到候选；可调整搜索词或切换来源";
        data.entries.forEach(row => {
          const delta = row.durationMs ? " · " + (row.durationMs / 1000).toFixed(1) + " 秒（与本地差 " + (row.durationMs / 1000 - (track.duration || 0)).toFixed(1) + " 秒）" : " · 时长未知";
          const candidate = button(row.title + " · " + row.artist + " · " + row.album + delta, () => run(async () => {
            const data = await request("fetch", { token: row.token }); if (!dialog.open) return;
            show(data.document, data.token, false); status.textContent = "候选预览；请填写标注并试听后采用";
          }));
          results.append(candidate); controls.push(candidate);
        });
      }));
      controls.push(search, query, provider);
      content.append(context, info, setting("来源", provider), setting("搜索词", query), search, status, results, editor);
      const provenance = document.createElement("p"); provenance.className = "m3x-caption"; editor.append(provenance);
      for (const [key, label] of [["title", "歌词曲名"], ["artist", "歌词歌手"], ["album", "歌词专辑"], ["offsetMs", "整曲偏差（毫秒，正数延后）"], ["note", "版本／试听备注"]]) {
        const input = document.createElement("input"); input.type = key === "offsetMs" ? "number" : "text";
        input.setAttribute("aria-label", label); input.maxLength = key === "note" ? 1000 : 500;
        if (key === "offsetMs") { input.min = -30000; input.max = 30000; input.step = 100; }
        fields[key] = input; controls.push(input); editor.append(setting(label, input));
        input.addEventListener("input", () => { selected = false; renderPreview(); });
      }
      const fine = document.createElement("div");
      for (const amount of [-200, 200]) { const adjust = button((amount > 0 ? "+" : "") + amount + " ms", () => {
        fields.offsetMs.value = Math.min(30000, Math.max(-30000, Number(fields.offsetMs.value) + amount)); selected = false; renderPreview();
      }); controls.push(adjust); fine.append(adjust); }
      function annotations() {
        const value = {}; Object.keys(fields).forEach(key => { value[key] = key === "offsetMs" ? Number(fields[key].value) : fields[key].value; });
        if (!Number.isInteger(value.offsetMs) || Math.abs(value.offsetMs) > 30000) throw new Error("整曲偏差须为 ±30000 内的整数毫秒");
        return value;
      }
      function renderPreview() {
        if (!doc) return;
        const shift = Number(fields.offsetMs.value) / 1000;
        const original = M3X_TOOLS.parseLrc(doc.original || "").lines, translated = M3X_TOOLS.parseLrc(doc.translation || "").lines;
        previewLines = original.concat(translated).map(line => ({ time: Math.max(0, line.time + shift), text: line.text })).sort((a, b) => a.time - b.time);
        preview.replaceChildren();
        previewLines.forEach(line => {
          const entry = button(line.time.toFixed(2) + "  " + line.text, () => { if (ui.currentTrack()?.id === track.id) audio.currentTime = line.time; else ui.toast("请先播放此歌曲再跳转试听"); });
          preview.append(entry);
        });
        provenance.textContent = (doc.provider === "NE" ? "网易云音乐" : "QQ 音乐") + " · ID " + doc.songId + (translated.some(line => line.text) ? " · 原文＋同步译文" : " · 无同步译文，不能双语固化") + (selected ? " · 已采用" : " · 预览／标注尚未保存");
      }
      function show(value, draftToken, isSelected) {
        doc = value; token = draftToken || ""; revision = value.revision || ""; selected = isSelected; editor.hidden = false;
        Object.keys(fields).forEach(key => { fields[key].value = value[key] || (key === "offsetMs" ? 0 : ""); }); renderPreview();
        editor.scrollIntoView({ block: "start" });
      }
      async function save() {
        if (!doc) throw new Error("请先预览候选");
        const data = await request("select", { ...(token ? { token } : { revision }), annotations: annotations() });
        show(data.document, "", true); window.dispatchEvent(new Event("m3x-lyrics-changed")); return data;
      }
      const adopt = button("保存到本应用", () => run(async () => { const data = await save(); status.textContent = data.message; }));
      const embed = button("写入歌曲文件 · 双语歌词", () => run(async () => {
        if (!editable) throw new Error("此歌曲仅支持搜索和采用；固化需要完整的本地受支持音频");
        if (!doc || !M3X_TOOLS.parseLrc(doc.translation || "").lines.some(line => line.text)) throw new Error("此候选没有同步译文，请选择双语候选");
        if (!selected) await save();
        const data = await request("embed", { revision }); status.textContent = data.message; recovery.hidden = false;
        window.dispatchEvent(new Event("m3x-lyrics-changed"));
      }));
      const warning = document.createElement("p"); warning.className = "m3x-caption";
      warning.textContent = "写入歌曲文件会覆盖歌曲原有歌词字段，保留本次写入前的完整恢复副本，并暂停播放；标注偏差已计入双语时间戳。备份占用歌曲大小的空间，卸载应用会删除备份；请勿在其他应用同时编辑歌曲。";
      const recovery = button("恢复写入前的歌曲文件", () => run(async () => {
        const data = await request("restore", { recoverInterrupted: interrupted.checked }); status.textContent = data.message;
        recovery.hidden = true; interrupted.checked = false; window.dispatchEvent(new Event("m3x-lyrics-changed"));
        const fresh = await request("state"); if (fresh.document) show(fresh.document, "", true); else { doc = null; editor.hidden = true; }
      })); recovery.hidden = true;
      const interrupted = document.createElement("input"); interrupted.type = "checkbox";
      const recoveryWarning = setting("仅限未完成写入：允许恢复副本覆盖当前文件（将覆盖外部修改）", interrupted); recoveryWarning.hidden = true;
      controls.push(adopt, embed, recovery, interrupted);
      const saveHelp = document.createElement("p"); saveHelp.className = "m3x-caption"; saveHelp.textContent = "保存到本应用：歌词和逐曲标注可离线使用，歌曲原文件不变。";
      editor.append(fine, saveHelp, adopt, warning, embed, preview); content.append(recoveryWarning, recovery);
      const tick = event => {
        if (!doc || event.detail.id !== track.id) return;
        const time = event.detail.position, nodes = preview.children;
        let active = -1; previewLines.forEach((line, i) => { if (line.time <= time) active = i; });
        Array.from(nodes).forEach((node, i) => node.setAttribute("aria-current", String(i === active || active >= 0 && previewLines[i].time === previewLines[active].time)));
      };
      window.addEventListener("m3x-native-state", tick);
      dialog.addEventListener("close", () => { ++sequence; window.removeEventListener("m3x-lyrics-result", receive); window.removeEventListener("m3x-native-state", tick); pending.forEach(p => p.reject(new Error("面板已关闭"))); pending.clear(); });
      run(async () => {
        const data = await request("state"); if (!dialog.open) return;
        editable = data.writable; if (data.document) show(data.document, "", true);
        recovery.hidden = !data.recovery;
        recoveryWarning.hidden = !data.recovery || data.recovery === "complete";
        status.textContent = data.recovery && data.recovery !== "complete" ? "检测到未完成的写入，请先恢复文件" : "可联网搜索；已采用的歌词和标注可离线使用";
      });
    }

    settings.append(button("歌词写入与恢复记录", () => {
      const content = sheet("歌词写入与恢复记录"), status = document.createElement("p"); status.className = "m3x-caption"; status.textContent = "读取恢复记录…"; content.append(status);
      const requestId = "recoveries-" + Date.now();
      const receive = event => {
        if (event.detail.requestId !== requestId) return;
        window.removeEventListener("m3x-lyrics-result", receive);
        if (!content.closest("dialog")) return;
        status.textContent = event.detail.error || "仅保存到本应用不改歌曲；本页只列出写入歌曲文件后可恢复的整曲副本。完整副本保存在应用内；卸载会删除。歌曲扫描后消失时，也可以从这里进入恢复。";
        const entries = event.detail.entries || [];
        if (!entries.length && !event.detail.error) status.textContent = "暂无写入恢复记录；仅保存到本应用不会创建整曲恢复副本。";
        entries.forEach(item => content.append(button(item.title + " · " + (item.state === "complete" ? "可恢复写入前文件" : "写入未完成"), () => {
          onlineLyrics(ui.tracks.find(track => track.id === item.id) || { id: item.id, title: item.title, originalArtist: "恢复记录", album: item.uri });
        })));
      };
      window.addEventListener("m3x-lyrics-result", receive);
      content.closest("dialog").addEventListener("close", () => window.removeEventListener("m3x-lyrics-result", receive));
      nativeCommand("lyrics", { operation: "recoveries", requestId });
    }));
    songMenu.append(button("搜索／保存／写入歌词", () => onlineLyrics(ui.tracks.find(track => track.id === $("song-menu-dialog").dataset.trackId))));
    const lyricsExplanation = "保存到本应用：只保存歌词和逐曲标注，不修改歌曲。导入到本应用：下载 LRC 到应用内。写入歌曲文件：改写歌曲的歌词标签，暂停播放并保存完整恢复副本。恢复：还原整首歌曲文件。备份占用歌曲大小的空间，卸载应用会删除备份。";
    function explainLyrics() { const content = sheet("歌词保存与写入说明"), p = document.createElement("p"); p.className = "m3x-caption"; p.textContent = lyricsExplanation; content.append(p); }
    function offsetSheet() {
      const content = sheet("调整歌词时间"), home = $("lyric-offset-home"), control = home.querySelector("label");
      const note = document.createElement("p"); note.className = "m3x-caption";
      note.textContent = selectedLyrics ? "此歌曲已采用逐曲标注，偏差已应用。请在搜索／更换歌词中调整逐曲偏差。" : "正值使歌词延后，负值使歌词提前；此偏移对未采用逐曲标注的歌曲生效。";
      content.append(note, control, button("归零", () => { if (selectedLyrics) return; $("lyric-offset").value = 0; $("lyric-offset").dispatchEvent(new Event("change")); }));
      content.closest("dialog").addEventListener("close", () => home.append(control), { once: true });
    }
    $("lyrics-more").addEventListener("click", () => {
      const content = sheet("歌词更多操作"), menu = content.closest("dialog");
      const action = (label, fn) => content.append(button(label, () => { ui.closeDialog(menu, true); fn(); }));
      action("搜索／更换歌词", () => onlineLyrics(ui.currentTrack())); action("调整歌词时间", offsetSheet);
      action("导入歌词", () => importLyrics(ui.currentTrack())); action("歌词保存与写入说明", explainLyrics);
    });
    window.addEventListener("m3x-lyrics-changed", () => lyrics());

    function lyrics() {
      const request = ++detailRequest, track = ui.currentTrack();
      if (!track) return;
      fetch("/api/track?id=" + encodeURIComponent(track.id)).then(response => response.json()).then(details => {
        if (request !== detailRequest) return;
        if (details.lyricWarning) ui.toast(details.lyricWarning);
        selectedLyrics = !!details.lyricSelection;
        $("lyric-offset").disabled = selectedLyrics;
        if (selectedLyrics) { $("lyric-offset").value = 0; $("lyric-offset-label").value = "逐曲标注已应用"; }
        else { $("lyric-offset").value = config.lyricOffset || 0; $("lyric-offset-label").value = $("lyric-offset").value + " 秒"; }
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
    let lastStatistics = "";
    function renderStatistics() {
      const stats = state.stats || {};
      $("listening-minutes").textContent = Math.floor((stats.seconds || 0) / 60);
      $("listening-count").textContent = Object.values(stats.counts || {}).reduce((sum, count) => sum + count, 0);
      if (page !== "statistics") return;
      const key = JSON.stringify([stats, ui.tracks.length]); if (lastStatistics === key) return; lastStatistics = key;
      const calendar = stats.calendar || {}, days = calendar.days || {}, today = new Date(), dates = [];
      const dateKey = date => date.getFullYear() + "-" + String(date.getMonth() + 1).padStart(2, "0") + "-" + String(date.getDate()).padStart(2, "0");
      for (let i = 6; i >= 0; i--) { const date = new Date(today); date.setDate(today.getDate() - i); dates.push(dateKey(date)); }
      $("stats-since").textContent = "时段数据自 " + (calendar.started || dateKey(today)) + " 起记录；旧累计保留。按记录时的本地时间归档。";
      const seconds = dates.map(date => days[date]?.seconds || 0), peak = Math.max(1, ...seconds);
      $("stats-days").replaceChildren();
      dates.forEach((date, index) => {
        const cell = document.createElement("button"); cell.type = "button";
        const value = (seconds[index] / 60).toFixed(1), label = date + " · " + value + " 分钟";
        cell.setAttribute("aria-label", label); cell.title = label;
        const number = document.createElement("span"); number.textContent = value;
        const space = document.createElement("span"); space.className = "m3x-bar-space";
        const bar = document.createElement("i"); bar.style.height = (seconds[index] / peak * 100) + "%"; space.append(bar);
        const name = document.createElement("small"); name.textContent = date.slice(5).replace("-", "/");
        cell.append(number, space, name); cell.addEventListener("click", () => { $("stats-detail").textContent = label; }); $("stats-days").append(cell);
      });
      const hours = new Array(24).fill(0); dates.forEach(date => (days[date]?.hours || []).forEach((value, hour) => { if (hour < 24) hours[hour] += Number(value) || 0; }));
      const maxHour = Math.max(1, ...hours); $("stats-hours").replaceChildren();
      hours.forEach((value, hour) => {
        const cell = document.createElement("button"); cell.type = "button";
        const label = String(hour).padStart(2, "0") + ":00–" + String(hour + 1).padStart(2, "0") + ":00 · " + (value / 60).toFixed(1) + " 分钟";
        cell.setAttribute("aria-label", label); cell.title = label;
        const text = document.createElement("span"); text.textContent = String(hour).padStart(2, "0");
        const bar = document.createElement("i"); bar.style.transform = "scaleX(" + value / maxHour + ")"; text.append(bar); cell.append(text);
        cell.addEventListener("click", () => { $("stats-detail").textContent = label; }); $("stats-hours").append(cell);
      });
      if (!seconds.some(Boolean)) $("stats-detail").textContent = "暂无新时段记录；播放歌曲后自动累计。";
      const ranked = ui.tracks.filter(track => stats.counts?.[track.id]).sort((a, b) => stats.counts[b.id] - stats.counts[a.id]).slice(0, 20);
      $("listening-ranking").replaceChildren();
      ranked.forEach((track, index) => {
        const row = document.createElement("button"); row.type = "button"; row.className = "m3x-rank";
        const rank = document.createElement("small"); rank.textContent = String(index + 1).padStart(2, "0");
        const img = document.createElement("img"); img.src = track.cover || "cover-placeholder.svg"; img.alt = ""; img.loading = "lazy";
        const info = document.createElement("span"), title = document.createElement("strong"), artist = document.createElement("small"), bar = document.createElement("i"), count = document.createElement("b");
        title.textContent = track.title; artist.textContent = track.artist; bar.style.width = (stats.counts[track.id] / stats.counts[ranked[0].id] * 100) + "%";
        info.append(title, artist, bar); count.textContent = stats.counts[track.id] + " 次"; row.append(rank, img, info, count);
        row.addEventListener("click", () => ui.showTrack(track.id)); $("listening-ranking").append(row);
      });
      if (!ranked.length) { const empty = document.createElement("p"); empty.className = "m3x-caption"; empty.textContent = "暂无有效聆听记录"; $("listening-ranking").append(empty); }
    }
    window.addEventListener("m3x-render", event => { page = event.detail.page; classificationSummary(); renderStatistics(); });
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
      renderStatistics();
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
