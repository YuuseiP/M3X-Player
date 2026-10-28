(() => {
  "use strict";
  if (window.M3X_NATIVE) return;

  function initialize() {
    const ui = window.M3X_UI;
    const $ = id => document.getElementById("classification-" + id);
    const labels = { character: "角色", group: "组合", artist: "歌手", album_artist: "专辑艺术家", original: "原始标签" };
    const editable = entity => ["character", "group", "artist"].includes(entity.kind);
    let config, edits, saved, entities;
    let kind = "character", selected = null, limit = 50;
    let editingSong, choices;
    let loading = false, saving = false, applied = false;

    const entity = id => ({ ...entities.get(id), ...edits.entities[id] });
    const memberships = track => edits.memberships[track.id] || track.memberships;
    const credits = track => memberships(track).map(entity).filter(editable);
    const dirty = () => JSON.stringify(edits) !== JSON.stringify(saved);
    const query = () => $("search").value.trim().toLocaleLowerCase();
    const matches = (value, search) => value.toLocaleLowerCase().includes(search);
    const button = (text, className, action) => {
      const result = document.createElement("button");
      result.type = "button"; result.textContent = text; result.className = className;
      result.addEventListener("click", action);
      return result;
    };
    const note = (text, className = "m3x-caption") => {
      const result = document.createElement("p"); result.className = className; result.textContent = text; return result;
    };
    function status() {
      $("status").textContent = dirty() ? "有未保存的修改 · 保存后才会应用" : applied ? `当前分类已应用 · ${config.tracks.length.toLocaleString()} 首` : "当前分类已保留 · 保存后应用到曲库";
      $("save").disabled = saving || (applied && !dirty());
      $("save").textContent = saving ? "正在应用…" : applied && !dirty() ? "已应用到曲库" : "保存并应用到曲库";
      $("undo").hidden = !dirty();
      $("undo").disabled = saving;
    }
    async function request(body) {
      const response = await fetch("/api/classification", body ? {
        method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body)
      } : {});
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "无法读取分类");
      return data;
    }
    async function load() {
      if (loading || config) return;
      loading = true; $("load").disabled = true; $("load").textContent = "正在读取分类…"; $("error").hidden = true;
      try {
        const data = await request();
        config = data.config; edits = data.edits; saved = structuredClone(edits); applied = data.applied;
        entities = new Map(config.entities.map(item => [item.id, item]));
        document.querySelector(".classification-path").textContent = config.folder;
        $("summary").textContent = `THE_IDOLMASTER · ${config.tracks.length.toLocaleString()} 首`;
        $("source").hidden = true; $("workspace").hidden = false;
        render(); status();
      } catch (error) {
        $("error").textContent = error.message; $("error").hidden = false;
      } finally {
        loading = false; $("load").disabled = false; $("load").textContent = "重新读取分类";
      }
    }
    async function installPreview() {
      if (ui.tracks[0]?.classificationPreview) return true;
      try {
        const response = await fetch("/api/classification/library");
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "无法打开曲库");
        ui.previewLibrary(data);
        return true;
      } catch (error) { ui.toast(error.message); return false; }
    }
    async function play(track) {
      if (await installPreview()) ui.showTrack(track.id);
    }
    function renameForm(id) {
      const current = entity(id), baseline = entities.get(id);
      const form = document.createElement("form"); form.className = "classification-editor";
      const nameLabel = document.createElement("label"); nameLabel.textContent = "显示名称";
      const name = document.createElement("input"); name.value = current.name; name.required = true; name.maxLength = 120; name.setAttribute("aria-label", "分类显示名称"); nameLabel.append(name);
      const kindLabel = document.createElement("label"); kindLabel.textContent = "分类类型";
      const select = document.createElement("select"); select.setAttribute("aria-label", "分类类型");
      for (const value of ["character", "group", "artist"]) {
        const option = document.createElement("option"); option.value = value; option.textContent = labels[value]; select.append(option);
      }
      select.value = current.kind; kindLabel.append(select);
      const apply = document.createElement("button"); apply.type = "submit"; apply.className = "m3x-text-button"; apply.textContent = "确认名称与类型";
      form.append(nameLabel, kindLabel, apply, note(`初始分类：${labels[baseline.kind]} · ${baseline.name}`));
      const source = config.name_sources.find(item => item.kind === baseline.kind && item.name === baseline.name);
      if (source) {
        const details = document.createElement("details"), summary = document.createElement("summary");
        summary.textContent = `百科依据：${source.original} → ${source.name}`;
        details.append(summary, note(`百科记录：${source.evidence}`));
        for (const url of source.sources) {
          const link = document.createElement("a"); link.href = url; link.target = "_blank"; link.rel = "noopener noreferrer"; link.textContent = "查看百科依据"; link.className = "m3x-caption"; details.append(link);
        }
        form.append(details);
      }
      form.addEventListener("submit", event => {
        event.preventDefault();
        const value = { name: name.value.trim(), kind: select.value };
        if (!value.name) { name.focus(); return; }
        if ([...entities.keys()].some(key => key !== id && entity(key).kind === value.kind && entity(key).name === value.name)) {
          ui.toast("同类名称已存在；此预览不自动合并分类"); return;
        }
        if (baseline.name === value.name && baseline.kind === value.kind) delete edits.entities[id];
        else edits.entities[id] = value;
        status(); render(); ui.toast("调整已暂存，保存后应用到曲库");
      });
      return form;
    }
    function renderTrack(track) {
      const row = document.createElement("div"); row.className = "classification-track"; row.dataset.classificationTrack = track.id;
      const listen = button("", "", () => play(track));
      listen.setAttribute("aria-label", "试听 " + track.title);
      const title = document.createElement("strong"); title.textContent = track.title;
      const names = document.createElement("small"); names.textContent = credits(track).map(item => item.name).join(" / ") || track.artist || "未知歌手";
      const album = document.createElement("small"); album.textContent = track.album || track.path;
      listen.append(title, names, album);
      const edit = button("归属", "", () => editSong(track)); edit.setAttribute("aria-label", "调整归属 " + track.title);
      row.append(listen, edit);
      return row;
    }
    function render() {
      const container = $("results"); container.replaceChildren();
      const search = query();
      const counts = new Map();
      for (const track of config.tracks) for (const id of memberships(track)) counts.set(id, (counts.get(id) || 0) + 1);
      document.querySelectorAll("[data-classification-kind]").forEach(tab => {
        const value = tab.dataset.classificationKind;
        const count = value === "tracks" ? config.tracks.length : [...entities.keys()].filter(id => entity(id).kind === value).length;
        tab.textContent = `${labels[value] || "歌曲"} ${count}`;
        tab.setAttribute("aria-pressed", String(value === kind));
      });
      $("back").hidden = !selected;
      $("search").placeholder = selected || kind === "tracks" ? "搜索歌曲、歌手或专辑" : "搜索分类名称或百科原名";
      let rows;
      if (selected || kind === "tracks") {
        if (selected) {
          const details = document.createElement("details"), summary = document.createElement("summary");
          details.className = "classification-edit-details"; summary.textContent = "编辑名称与类型";
          details.append(summary, renameForm(selected)); container.append(details);
        }
        rows = config.tracks.filter(track =>
          (!selected || memberships(track).includes(selected)) &&
          matches([track.title, track.artist, track.album, track.path, ...credits(track).map(item => item.name)].join(" "), search)
        );
        $("count").textContent = `${selected ? entity(selected).name + " · " : ""}${rows.length} 首`;
        for (const track of rows.slice(0, limit)) container.append(renderTrack(track));
      } else {
        rows = [...entities.keys()].map(entity).filter(item => {
          if (item.kind !== kind) return false;
          const baseline = entities.get(item.id);
          const alias = config.name_sources.find(source => source.kind === baseline.kind && source.name === baseline.name);
          return matches([item.name, baseline.name, alias?.original || ""].join(" "), search);
        })
          .sort((left, right) => left.name.localeCompare(right.name, "zh-Hans", { numeric: true }));
        $("count").textContent = `${rows.length} 个${labels[kind]} · 点击查看歌曲`;
        for (const item of rows.slice(0, limit)) {
          const row = button("", "m3x-playlist-row", () => { selected = item.id; limit = 50; $("search").value = ""; render(); container.scrollTop = 0; });
          row.dataset.classificationEntity = item.id;
          const mark = document.createElement("span"); mark.className = "classification-entity-mark"; mark.textContent = labels[item.kind].slice(0, 1);
          const info = document.createElement("span");
          const name = document.createElement("strong"); name.textContent = item.name;
          const count = document.createElement("small"); count.textContent = `${counts.get(item.id) || 0} 首${edits.entities[item.id] ? " · 已调整" : ""}`;
          info.append(name, count); row.append(mark, info); container.append(row);
        }
      }
      if (!rows.length) container.append(note("没有匹配的结果", "m3x-empty"));
      if (rows.length > limit) container.append(button(`加载更多（${limit} / ${rows.length}）`, "m3x-text-button", () => { limit += 50; const top = container.scrollTop; render(); container.scrollTop = top; }));
    }
    function editSong(track) {
      editingSong = track; choices = new Set(memberships(track));
      $("song-title").textContent = track.title;
      $("song-original").textContent = `原始歌手：${track.artist || "未提供"} · 原始标签与专辑艺术家保留不变`;
      $("song-dialog").querySelector("details").open = false;
      $("credit-search").value = ""; renderChoices(); ui.openDialog($("song-dialog"));
    }
    function renderSelected() {
      $("selected").textContent = "已选：" + ([...choices].map(entity).filter(editable).map(item => item.name).join(" / ") || "无");
    }
    function renderChoices() {
      const search = $("credit-search").value.trim().toLocaleLowerCase();
      const list = $("credit-list"); list.replaceChildren();
      renderSelected();
      const candidates = [...entities.keys()].map(entity).filter(item => editable(item) && matches(item.name, search))
        .sort((left, right) => Number(choices.has(right.id)) - Number(choices.has(left.id)) || left.name.localeCompare(right.name, "zh-Hans"));
      for (const item of candidates.slice(0, 50)) {
        const label = document.createElement("label"), input = document.createElement("input"), text = document.createElement("span");
        input.type = "checkbox"; input.checked = choices.has(item.id); input.value = item.id;
        text.textContent = `${labels[item.kind]} · ${item.name}`;
        input.addEventListener("change", () => {
          if (input.checked) choices.add(item.id); else choices.delete(item.id);
          renderSelected();
        });
        label.append(input, text); list.append(label);
      }
      if (candidates.length > 50) list.append(note(`显示前 50 项，共 ${candidates.length} 项；输入名称查找其他归属。`));
      if (!candidates.length) list.append(note("没有匹配的分类"));
    }
    async function save() {
      if (saving) return false;
      saving = true; status();
      try {
        const submitted = structuredClone(edits);
        const data = await request(submitted);
        saved = data.edits;
        if (JSON.stringify(edits) === JSON.stringify(submitted)) edits = structuredClone(saved);
        applied = true;
        ui.previewLibrary(data.library, true);
        ui.toast("已保存并应用到曲库"); return true;
      } catch (error) {
        ui.toast(error.message); return false;
      } finally { saving = false; status(); }
    }
    $("load").addEventListener("click", load);
    $("search").addEventListener("input", () => { limit = 50; render(); });
    $("credit-search").addEventListener("input", renderChoices);
    $("back").addEventListener("click", () => { selected = null; limit = 50; $("search").value = ""; render(); });
    document.querySelectorAll("[data-classification-kind]").forEach(tab => tab.addEventListener("click", () => {
      kind = tab.dataset.classificationKind; selected = null; limit = 50; $("search").value = ""; render();
    }));
    $("song-apply").addEventListener("click", () => {
      const values = [...choices].sort();
      if (JSON.stringify(values) === JSON.stringify([...editingSong.memberships].sort())) delete edits.memberships[editingSong.id];
      else edits.memberships[editingSong.id] = values;
      status(); render(); ui.closeDialog($("song-dialog")); ui.toast("归属调整已暂存，保存后生效");
    });
    $("save").addEventListener("click", save);
    $("undo").addEventListener("click", () => {
      edits = structuredClone(saved); selected = null; status(); render(); ui.toast("已撤销本次未保存的修改");
    });
    $("reset").addEventListener("click", () => {
      if (!confirm("恢复保留的分类结果？保存后才会替换当前应用的配置。")) return;
      edits = { schema: 1, baseline: edits.baseline, entities: {}, memberships: {} };
      selected = null; status(); render();
    });
    $("export").addEventListener("click", () => {
      const exported = {
        ...config, baseline_sha256: saved.baseline, web_preview_only: true,
        entities: config.entities.map(item => ({ ...item, ...saved.entities[item.id] })),
        tracks: config.tracks.map(track => ({ ...track, memberships: saved.memberships[track.id] || track.memberships }))
      };
      const url = URL.createObjectURL(new Blob([JSON.stringify(exported, null, 2) + "\n"], { type: "application/json" }));
      const link = document.createElement("a"); link.href = url; link.download = "THE_IDOLMASTER-classification.json"; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    $("return-player").addEventListener("click", async () => {
      if (await installPreview()) document.querySelector('.m3x-nav [data-page="player"]').click();
    });
    window.addEventListener("m3x-render", event => {
      if (event.detail.page === "create-library") {
        if (config) { if (!ui.tracks[0]?.classificationPreview) applied = false; status(); }
        else void load();
      }
    });
    window.addEventListener("beforeunload", event => { if (edits && dirty()) { event.preventDefault(); event.returnValue = ""; } });
  }
  if (window.M3X_UI) initialize();
  else window.addEventListener("m3x-ready", initialize, { once: true });
})();
