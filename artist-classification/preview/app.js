"use strict";
const $ = selector => document.querySelector(selector);
const audio = $("#audio");
const labels = { character: "角色", group: "组合 / 集合署名", artist: "其他署名", original: "原始署名" };
const english = { character: "CHARACTER", group: "GROUP", artist: "OTHER CREDITS", original: "ORIGINAL CREDITS" };
let library, entities, trackMap, kind = "character", selection = null, visible = [], limit = 60;
let queue = [], current = null;
const number = value => value.toLocaleString("zh-CN");
const escapeHTML = value => String(value).replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
const normalize = value => value.normalize("NFKC").toLocaleLowerCase().replace(/\s+/g, "");
const names = (track, type) => track.memberships[type].map(id => entities.get(id)).filter(Boolean);
const time = value => Number.isFinite(value) ? `${Math.floor(value / 60)}:${String(Math.floor(value % 60)).padStart(2, "0")}` : "0:00";
const projectTracks = () => library.tracks.filter(track => !$("#project").value || track.project === $("#project").value);

function chip(entity) {
  return `<button class="chip ${entity.kind}" data-entity="${escapeHTML(entity.id)}">${escapeHTML(entity.name)}</button>`;
}

function renderDirectory() {
  const allowed = new Set(projectTracks().map(track => track.id));
  const query = normalize($("#entity-search").value);
  const items = library.entities.filter(entity => entity.kind === kind && normalize(entity.name).includes(query))
    .map(entity => ({ ...entity, count: entity.track_ids.filter(id => allowed.has(id)).length }))
    .filter(entity => entity.count).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name, "ja"));
  $("#entity-count").textContent = `${number(items.length)} 项`;
  $("#entities").innerHTML = items.map(entity =>
    `<button data-entity="${escapeHTML(entity.id)}" aria-pressed="${selection === entity.id}"><span>${escapeHTML(entity.name)}</span><small>${number(entity.count)}</small></button>`
  ).join("") || '<p class="empty">没有匹配的分类</p>';
}

function choose(id) {
  selection = id;
  if (id) {
    kind = entities.get(id).kind;
    $("#entity-search").value = "";
  }
  $("#track-search").value = "";
  limit = 60;
  render();
}

function render() {
  $("#kinds").querySelectorAll("button").forEach(button => button.setAttribute("aria-pressed", button.dataset.kind === kind));
  renderDirectory();
  const entity = entities.get(selection);
  let tracks = projectTracks().filter(track => !entity || track.memberships[entity.kind].includes(entity.id));
  const base = tracks;
  const variants = new Set(base.flatMap(track => track.artist));
  const multiple = base.filter(track => track.memberships.character.length > 1).length;
  $("#kind-label").textContent = entity ? `${english[entity.kind]} / ${labels[entity.kind]}` : "COLLECTION / 全部歌曲";
  $("#selection-title").textContent = entity ? entity.name : "全部歌曲";
  $("#selection-description").textContent = entity
    ? `${number(base.length)} 首歌曲 · ${number(variants.size)} 种原始署名${multiple ? ` · ${number(multiple)} 首多人演唱` : ""}${entity.kind === "group" ? " · 不自动推断成员" : ""}`
    : `${number(base.length)} 个本地音频文件；同一首歌只展示一次。`;
  const query = normalize($("#track-search").value);
  tracks = tracks.filter(track =>
    (!$("#review-only").checked || track.issues.length) &&
    (!query || normalize([track.title, track.album, ...track.artist, ...names(track, "character").map(e => e.name), ...names(track, "group").map(e => e.name)].join(" ")).includes(query))
  );
  visible = tracks;
  $("#result-count").textContent = `${number(tracks.length)} 首${tracks.length > limit ? ` · 已显示 ${limit} 首` : ""}`;
  $("#play-selection").disabled = !tracks.some(track => track.available);
  $("#tracks").innerHTML = tracks.slice(0, limit).map((track, index) => {
    const credits = ["character", "group", "artist"].flatMap(type => names(track, type));
    return `<article class="track${current?.id === track.id ? " is-current" : ""}" data-track="${escapeHTML(track.id)}">
      <div class="track-top">
        <button class="track-play" data-play="${track.id}" aria-label="播放 ${escapeHTML(track.title)}" ${track.available ? "" : "disabled"}>${current?.id === track.id && !audio.paused ? "Ⅱ" : String(index + 1).padStart(2, "0")}</button>
        <div class="track-main">
          <button class="track-title" data-play="${track.id}" ${track.available ? "" : "disabled"}>${escapeHTML(track.title)}</button>
          <div class="track-album">${escapeHTML(track.album || "无专辑标签")}</div>
          <div class="track-tags">${credits.map(chip).join("") || '<span class="unavailable">暂无可用分类，保留此歌曲</span>'}</div>
          ${$("#show-original").checked ? `<p class="raw-credit"><b>原始署名</b>${escapeHTML(track.artist.join(" / ") || "无可读取的 ARTIST 标签")}</p>` : ""}
          ${track.available ? "" : '<span class="unavailable">原文件不存在，无法播放</span>'}
        </div>
        <div class="track-meta">${escapeHTML(track.format)}<span>${(track.bytes / 1048576).toFixed(1)} MB</span></div>
      </div>
      <details><summary>文件与分类依据${track.issues.length ? `<span class="review-mark">${track.issues.length} 项待复核</span>` : ""}</summary>
        <p>来源：${escapeHTML(track.project)}<br>路径：${escapeHTML(track.path)}</p>
        <p>原始 ARTIST：${escapeHTML(JSON.stringify(track.artist))}</p>
        ${(track.decisions || []).map(entry => `<p><b>语义判断 · ${escapeHTML(entry.status)}</b><br>${escapeHTML(entry.reason)}<br><small>初判 ${escapeHTML(entry.model)}${entry.reviewed_by ? ` · 定向复核 ${escapeHTML(entry.reviewed_by)}` : ""}</small>${entry.sources.map(url => ` · <a href="${escapeHTML(url)}" target="_blank" rel="noopener noreferrer">核实来源</a>`).join("")}</p>`).join("")}
        ${(track.decisions || []).flatMap(entry => entry.name_changes || []).map(change => `<p><b>百科名称：${escapeHTML(change.original)} → ${escapeHTML(change.name)}</b><br>${escapeHTML(change.evidence)}${change.sources.map(url => ` · <a href="${escapeHTML(url)}" target="_blank" rel="noopener noreferrer">百科来源</a>`).join("")}</p>`).join("")}
        <p>${track.issues.length ? track.issues.map(issue => escapeHTML(library.issue_labels[issue] || issue)).join("；") : "当前分类未标记复核项；不代表每一项均有外部来源核实。"}</p>
      </details>
    </article>`;
  }).join("");
  $("#empty").hidden = Boolean(tracks.length);
  $("#load-more").hidden = tracks.length <= limit;
}

function playbackError(message) {
  $("#play-error").textContent = message;
  $("#play-error").hidden = false;
}

function updatePlaying() {
  $("#play").textContent = audio.paused ? "▶" : "Ⅱ";
  $("#play").setAttribute("aria-label", audio.paused ? "播放" : "暂停");
  document.querySelectorAll(".track").forEach(row => {
    const active = row.dataset.track === current?.id;
    row.classList.toggle("is-current", active);
    const button = row.querySelector(".track-play");
    button.textContent = active && !audio.paused ? "Ⅱ" : String(visible.findIndex(track => track.id === row.dataset.track) + 1).padStart(2, "0");
  });
}

async function playTrack(id, replaceQueue = true) {
  const track = trackMap.get(id);
  if (!track?.available) return;
  if (replaceQueue) queue = visible.filter(item => item.available).map(item => item.id);
  const changed = current?.id !== id;
  current = track;
  $("#play-error").hidden = true;
  if (changed) {
    audio.src = track.src;
    $("#seek").value = "0";
    $("#seek").disabled = true;
    $("#elapsed").textContent = $("#duration").textContent = "0:00";
  }
  $("#now-title").textContent = track.title;
  const credits = ["character", "group", "artist"].flatMap(type => names(track, type));
  $("#now-artist").textContent = credits.map(entity => entity.name).join(" / ") || track.artist.join(" / ") || "无歌手标签";
  $("#now-format").textContent = track.format;
  $("#play").disabled = false;
  try {
    await audio.play();
  } catch (error) {
    if (error.name !== "AbortError") playbackError("无法播放此文件；请检查文件是否可读，以及浏览器是否支持 " + track.format + " 格式。");
  }
  updatePlaying();
}

function step(offset) {
  if (!queue.length) return;
  const index = queue.indexOf(current?.id);
  const next = index + offset;
  if (next < 0 || next >= queue.length) return;
  playTrack(queue[next], false);
}

$("#kinds").addEventListener("click", event => {
  const button = event.target.closest("[data-kind]");
  if (!button || !library) return;
  kind = button.dataset.kind;
  $("#entity-search").value = "";
  const allowed = new Set(projectTracks().map(track => track.id));
  choose(library.entities.find(entity => entity.kind === kind && entity.track_ids.some(id => allowed.has(id)))?.id || null);
});
document.addEventListener("click", event => {
  if (!library) return;
  const entity = event.target.closest("[data-entity]");
  const play = event.target.closest("[data-play]");
  if (entity) choose(entity.dataset.entity);
  if (play) {
    if (current?.id === play.dataset.play && !audio.paused) audio.pause();
    else playTrack(play.dataset.play);
  }
});
$("#entity-search").addEventListener("input", () => library && renderDirectory());
for (const selector of ["#track-search", "#show-original", "#review-only"]) {
  $(selector).addEventListener("input", () => { if (library) { limit = 60; render(); } });
}
$("#project").addEventListener("change", () => { if (library) { limit = 60; render(); } });
$("#all-tracks").addEventListener("click", () => library && choose(null));
$("#load-more").addEventListener("click", () => { limit += 60; render(); });
$("#play-selection").addEventListener("click", () => {
  const first = visible.find(track => track.available);
  if (first) playTrack(first.id);
});
$("#play").addEventListener("click", () => {
  if (!audio.paused) audio.pause();
  else if (current) playTrack(current.id, false);
});
$("#previous").addEventListener("click", () => step(-1));
$("#next").addEventListener("click", () => step(1));
$("#seek").addEventListener("input", () => { if (Number.isFinite(audio.duration)) audio.currentTime = Number($("#seek").value); });
audio.addEventListener("loadedmetadata", () => {
  $("#duration").textContent = time(audio.duration);
  $("#seek").max = Number.isFinite(audio.duration) ? audio.duration : 100;
  $("#seek").disabled = !Number.isFinite(audio.duration);
});
audio.addEventListener("timeupdate", () => { $("#seek").value = audio.currentTime; $("#elapsed").textContent = time(audio.currentTime); });
audio.addEventListener("play", updatePlaying);
audio.addEventListener("pause", updatePlaying);
audio.addEventListener("ended", () => step(1));
audio.addEventListener("error", () => playbackError("音频读取或解码失败。可以继续浏览分类；请尝试其他歌曲（推荐 FLAC）。"));

(async () => {
  try {
    const response = await fetch("/api/classification");
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    library = await response.json();
    if (library.engine === "semantic_exact_lookup") {
      const summary = library.classification_summary;
      $("#classification-method").textContent = `模型语义分类 · ${number(summary.semantic_tag_count)} 条署名已逐项记录 · ${number(summary.semantic_status.unresolved || 0)} 条仍有疑义。页面只读取已保存的决定，不再解析署名。`;
    }
    entities = new Map(library.entities.map(entity => [entity.id, entity]));
    trackMap = new Map(library.tracks.map(track => [track.id, track]));
    for (const type of Object.keys(labels)) {
      const count = library.entities.filter(entity => entity.kind === type).length;
      $(`[data-kind="${type}"] strong`).textContent = number(count);
    }
    $("#totals").innerHTML = `<span><strong>${number(library.tracks.length)}</strong>本地歌曲</span><span><strong>${number(library.entities.filter(e => e.kind === "character").length)}</strong>角色</span><span><strong>${number(library.entities.filter(e => e.kind === "group").length)}</strong>组合 / 集合署名</span>`;
    for (const project of [...new Set(library.tracks.map(track => track.project))].sort()) {
      const option = document.createElement("option");
      option.value = option.textContent = project;
      $("#project").append(option);
    }
    const missing = library.tracks.filter(track => !track.available).length;
    $("#source-root").textContent = `素材目录：${library.root} · 快照：${library.generated_at}${missing ? ` · ${missing} 个原文件不可用` : ""}`;
    choose(library.entities.find(entity => entity.kind === "character")?.id || null);
  } catch (error) {
    $("#selection-title").textContent = "未能读取分类结果";
    $("#totals").textContent = "本地数据加载失败";
    $("#load-error").hidden = false;
    $("#load-error").textContent = `请确认预览服务与离线分类结果可用，然后刷新页面。${error.message}`;
  }
})();
