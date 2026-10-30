(async () => {
  "use strict";
  let library;
  try {
    const response = await fetch("/api/library");
    if (response.ok) library = await response.json();
  } catch { /* The prepared library also works without the scan service. */ }
  const tracks = library?.tracks || window.M3X_TRACKS;
  let indexById = new Map(tracks.map((track, index) => [track.id, index]));
  let entityById = new Map((library?.classification?.entities || []).map(entity => [entity.id, entity]));
  let libraryRevision = 0;
  const listRenderKeys = new WeakMap();
  const compareNames = new Intl.Collator("zh-Hans", { numeric: true }).compare;
  const listLimits = {};
  const listPageSize = window.M3X_NATIVE ? 40 : 150;
  window.M3X_TRACKS = tracks;
  const root = document.getElementById("m3x-concept");
  const screen = root.querySelector(".m3x-screen");
  const audio = document.getElementById("audio");
  const seek = document.getElementById("seek");
  const search = root.querySelector(".m3x-search");
  const dialog = document.getElementById("output-dialog");
  const status = document.getElementById("playback-status");
  if (window.M3X_NATIVE) { status.hidden = true; status.textContent = ""; }
  const modes = ["列表循环", "随机播放", "单曲循环"];
  const nickname = document.getElementById("nickname");
  const playlistDialog = document.getElementById("playlist-dialog");
  const folderDialog = document.getElementById("folder-dialog");
  const storageKey = "m3x-shiny-preview-v1";
  let saved = {};
  try { saved = JSON.parse(localStorage.getItem(storageKey)) || {}; } catch { /* Private browsing may disable storage. */ }
  const state = {
    index: Math.max(0, saved.trackId ? tracks.findIndex(track => track.id === saved.trackId) : Number.isInteger(saved.index) && tracks[saved.index] ? saved.index : 0),
    layout: saved.layout === "catalog" ? "catalog" : "album",
    mode: Number.isInteger(saved.mode) && modes[saved.mode] ? saved.mode : 0,
    favorites: new Set(Array.isArray(saved.favorites) ? saved.favorites.filter(id => typeof id === "string") : []),
    history: Array.isArray(saved.history) ? [...new Set(saved.history.filter(id => typeof id === "string"))].slice(0, 20) : [],
    playlists: Array.isArray(saved.playlists) ? saved.playlists.filter(item => typeof item.id === "string" && typeof item.name === "string" && Array.isArray(item.trackIds)).map(item => ({ id: item.id, name: item.name.slice(0, 40), trackIds: [...new Set(item.trackIds.filter(id => typeof id === "string"))] })) : [],
    queue: Array.isArray(saved.queue) ? saved.queue.filter(id => typeof id === "string") : [],
    queueCleared: saved.queueCleared === true,
    hidden: new Set(Array.isArray(saved.hidden) ? saved.hidden.filter(id => typeof id === "string") : []),
    sort: ["original", "title", "artist", "album", "duration", "duration-desc", "added", "year"].includes(saved.sort) ? saved.sort : "original",
    groupSort: ["name", "name-desc", "count"].includes(saved.groupSort) ? saved.groupSort : "name",
    filenameTitle: saved.filenameTitle === true, showCovers: saved.showCovers !== false,
    avatar: typeof saved.avatar === "string" && saved.avatar.startsWith("data:image/jpeg;base64,") ? saved.avatar : "",
    nickname: typeof saved.nickname === "string" && saved.nickname.trim() ? saved.nickname.trim().slice(0, 20) : "本地听众",
    libraryView: saved.libraryView === "grid" ? "grid" : "list",
    visualTheme: saved.visualTheme === "endfield" ? "endfield" : "classic",
    colorMode: saved.colorMode === "cover" ? "cover" : "fixed",
    theme: ["system", "light", "dark"].includes(saved.theme) ? saved.theme : "system",
    startPage: ["library", "player", "profile"].includes(saved.startPage) ? saved.startPage : "library",
    rememberPosition: saved.rememberPosition !== false,
    page: ["library", "player", "profile"].includes(saved.startPage) ? saved.startPage : "library",
    query: "", favoriteQuery: "", filter: "all", groupKey: null, playlistId: "", playlistTarget: "", sheetOpen: false, sheetTab: "signal"
  };
  let pendingSeek = state.rememberPosition && Number.isFinite(saved.position) ? Math.max(0, Math.min(saved.position, tracks[state.index]?.duration || 0)) : 0;
  let dragging = false;
  let toastTimer;
  let playlistAction = "create";
  let scanTimer;
  let renderedScanReport = "";
  let renderedScanSources = "";
  let menuIndex = state.index;
  let renderedTrack = null;
  root.addEventListener("error", event => {
    if (event.target.tagName === "IMG" && !event.target.src.endsWith("cover-placeholder.svg")) event.target.src = "cover-placeholder.svg";
  }, true);
  root.addEventListener("keydown", event => {
    if (event.key === "Tab") root.classList.add("keyboard-navigation");
  });
  function pointerInput() {
    root.classList.remove("keyboard-navigation");
  }
  root.addEventListener("touchstart", pointerInput, { passive: true });
  root.addEventListener("mousedown", pointerInput);
  audio.volume = 1;
  const paths = {
    library: '<path d="M4 4v16M9 4v16M14 4v16M18 4l3 16"/>',
    disc: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3M5 5l2 2m10 10 2 2M5 19l2-2M17 7l2-2"/><circle cx="12" cy="12" r="8"/>',
    edit: '<path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Z"/>',
    right: '<path d="m9 5 7 7-7 7"/>',
    play: '<path d="m9 5 11 7-11 7Z" fill="currentColor" stroke="none"/>',
    pause: '<path d="M9 5v14M15 5v14" stroke-width="3"/>',
    previous: '<path d="M5 5v14m13-14L7 12l11 7Z"/>',
    next: '<path d="M19 5v14M6 5l11 7-11 7Z"/>',
    queue: '<path d="M4 5h12M4 10h12M4 15h7m7-3v8m-3-3 3 3 3-3"/>',
    heart: '<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>',
    up: '<path d="m6 15 6-6 6 6"/>',
    back: '<path d="m14 5-7 7 7 7M7 12h13"/>',
    close: '<path d="m6 6 12 12M6 18 18 6"/>'
  };
  Object.assign(paths, {
    plus: '<path d="M12 5v14M5 12h14"/>',
    "add-list": '<path d="M4 5h14M4 10h10M4 15h7m7-3v8m-4-4h8"/>',
    sequential: '<path d="M4 6h15m-4-4 4 4-4 4M20 18H5m4-4-4 4 4 4"/>',
    shuffle: '<path d="M3 6h3c5 0 7 12 12 12h3m-4-4 4 4-4 4M3 18h3c5 0 7-12 12-12h3m-4-4 4 4-4 4"/>',
    repeat: '<path d="M5 7h14l-3-3m3 3-3 3M19 17H5l3-3m-3 3 3 3M4 12V9m16 3v3"/><path d="M11 10h1v5"/>',
    scan: '<path d="M20 7a8 8 0 1 0 0 10M20 3v5h-5"/>',
    list: '<path d="M8 5h13M8 12h13M8 19h13M3 5h1M3 12h1M3 19h1"/>',
    grid: '<path d="M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z"/>',
    folder: '<path d="M3 6h7l2 2h9v12H3Z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    remove: '<path d="M5 12h14"/>',
    more: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>'
  });
  function icon(name) {
    return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + paths[name] + "</svg>";
  }
  root.querySelectorAll("[data-icon]").forEach(element => { element.outerHTML = icon(element.dataset.icon); });
  const time = value => {
    const seconds = Math.floor(Number.isFinite(value) ? Math.max(0, value) : 0);
    return Math.floor(seconds / 60) + ":" + String(seconds % 60).padStart(2, "0");
  };
  const quality = track => track.bits && track.sampleRate ? `${track.bits}bit · ${track.sampleRate / 1000}kHz` : track.sampleRate ? `${track.sampleRate / 1000}kHz` : track.codec || "音频";
  const format = track => (track.codec || "FLAC") + " · " + quality(track);
  function text(key, value) {
    root.querySelectorAll(`[data-text="${key}"]`).forEach(element => {
      if (element.textContent !== String(value)) element.textContent = value;
    });
  }
  function announce(message) {
    root.querySelector(".m3x-live").textContent = message;
  }
  function toast(message) {
    const element = document.getElementById("toast");
    clearTimeout(toastTimer);
    element.textContent = message;
    element.hidden = false;
    toastTimer = setTimeout(() => { element.hidden = true; }, 2400);
  }
  function persist(reportError = false) {
    try {
      localStorage.setItem(storageKey, JSON.stringify({
        index: state.index, trackId: tracks[state.index]?.id, layout: state.layout, mode: state.mode,
        favorites: [...state.favorites], history: state.history, nickname: state.nickname,
        playlists: state.playlists, avatar: state.avatar, queue: state.queue, queueCleared: state.queueCleared,
        hidden: [...state.hidden], sort: state.sort, groupSort: state.groupSort, filenameTitle: state.filenameTitle, showCovers: state.showCovers,
        theme: state.theme, libraryView: state.libraryView, visualTheme: state.visualTheme, colorMode: state.colorMode, startPage: state.startPage, rememberPosition: state.rememberPosition,
        position: state.rememberPosition ? (audio.readyState ? audio.currentTime : pendingSeek) : 0
      }));
      if (window.M3X_NATIVE) window.nativeCommand("ui-save", JSON.parse(localStorage.getItem(storageKey)));
      return true;
    } catch {
      if (reportError) toast("无法保存，请检查浏览器存储空间");
      return false;
    }
  }
  function queueIndices() {
    if (state.queueCleared) return [];
    const indices = state.queue.map(id => indexById.get(id)).filter(index => index !== undefined && !state.hidden.has(tracks[index].id));
    return state.queue.some(id => indexById.has(id)) ? indices : tracks.map((_, index) => index).filter(index => !state.hidden.has(tracks[index].id));
  }
  const displayTitle = track => state.filenameTitle ? track.filename || track.title : track.title;
  const folderPath = track => (track.folder || "").replace(/\\/g, "/").replace(/^\.\/|\/$/g, "").replace(/^\.$/, "");
  const matchesQuery = (track, query) => !query.trim() || (track.title + " " + track.artist + " " + (track.originalArtist || "") + " " + track.album + " " + (track.filename || "") + " " + (track.folder || "")).toLocaleLowerCase().includes(query.trim().toLocaleLowerCase());
  const favoriteIndices = () => sortedIndices(tracks.map((_, index) => index).filter(index =>
    state.favorites.has(tracks[index].id) && !state.hidden.has(tracks[index].id) && matchesQuery(tracks[index], state.favoriteQuery)));
  function groupValues(track) {
    if (state.filter === "folders") {
      const prefix = state.groupKey ? state.groupKey + "/" : "";
      const child = folderPath(track).slice(prefix.length).split("/")[0];
      return child ? [prefix + child] : [];
    }
    const kind = { characters: "character", groups: "group", artists: "artist" }[state.filter];
    if (library?.classification && kind) return (track.credits || []).filter(item => item.kind === kind).map(item => item.id);
    return [state.filter === "albums" ? track.album : state.filter === "artists" ? track.artist : track.folder || "."];
  }
  const groupLabel = key => state.filter === "folders" ? (key || "").split("/").pop() :
    entityById.get(key)?.name || (key === "." ? "根目录" : key || "");
  function sortedIndices(indices) {
    if (state.sort === "original") return indices;
    return [...indices].sort((a, b) => {
      const left = tracks[a], right = tracks[b];
      if (state.sort === "duration" || state.sort === "duration-desc") return (left.duration - right.duration) * (state.sort === "duration" ? 1 : -1);
      if (state.sort === "added") return (right.added || 0) - (left.added || 0) || a - b;
      if (state.sort === "year") return String(right.year || "").localeCompare(String(left.year || "")) || a - b;
      if (state.sort === "album") return left.album.localeCompare(right.album) || (parseInt(left.discNumber) || 0) - (parseInt(right.discNumber) || 0) || (parseInt(left.trackNumber) || 0) - (parseInt(right.trackNumber) || 0) || a - b;
      if (state.sort === "original") return a - b;
      return compareNames(String(state.sort === "title" ? displayTitle(left) : left.artist), String(state.sort === "title" ? displayTitle(right) : right.artist)) || a - b;
    });
  }
  function libraryIndices(includeSubfolders = false) {
    const query = state.query.trim().toLocaleLowerCase();
    return sortedIndices(tracks.map((_, index) => index).filter(index => {
      const track = tracks[index];
      const currentFolder = state.groupKey || "";
      const inGroup = state.filter === "folders" ?
        folderPath(track) === currentFolder || (includeSubfolders && (!currentFolder || folderPath(track).startsWith(currentFolder + "/"))) :
        state.groupKey === null || groupValues(track).includes(state.groupKey);
      return !state.hidden.has(track.id) && inGroup && matchesQuery(track, query);
    }));
  }
  function renderGroups() {
    const grouping = ["albums", "artists", "characters", "groups", "folders"].includes(state.filter);
    const groups = document.getElementById("library-groups");
    const groupBack = document.getElementById("group-back");
    const folders = state.filter === "folders";
    const grouped = new Map();
    if (grouping && (folders || state.groupKey === null)) {
      for (const index of libraryIndices(folders)) {
        for (const key of groupValues(tracks[index])) {
          if (!grouped.has(key)) grouped.set(key, []);
          grouped.get(key).push(index);
        }
      }
    }
    const browsingGroups = grouping && (folders ? grouped.size > 0 : state.groupKey === null);
    const hasSongs = folders && libraryIndices().length > 0;
    groups.hidden = !browsingGroups;
    screen.querySelector('[data-setting="sort"]').closest("label").hidden = browsingGroups && !hasSongs;
    screen.querySelector('[data-setting="groupSort"]').closest("label").hidden = !browsingGroups;
    screen.querySelector('[data-list="library"]').hidden = browsingGroups && !hasSongs;
    groupBack.hidden = state.groupKey === null;
    document.getElementById("group-label").textContent = folders ? "上一级" : groupLabel(state.groupKey);
    const location = document.getElementById("folder-location");
    location.hidden = !folders;
    location.textContent = state.groupKey ? state.groupKey.split("/").join(" / ") : (library?.folder || "").split(/[\\/]/).pop() || "曲库根目录";
    location.title = location.textContent;
    screen.querySelector(".m3x-library-controls").classList.toggle("has-mixed-folders", browsingGroups && hasSongs);
    if (!browsingGroups) {
      if (groups.firstChild) groups.replaceChildren();
      listRenderKeys.delete(groups);
      return;
    }
    const groupLimit = window.M3X_NATIVE ? listLimits.groups || listPageSize : Infinity;
    const renderKey = JSON.stringify([libraryRevision, state.filter, state.groupKey, state.query, state.groupSort, state.showCovers, state.libraryView, [...state.hidden], groupLimit]);
    if (listRenderKeys.get(groups) === renderKey) return;
    listRenderKeys.set(groups, renderKey);
    groups.replaceChildren();
    const ordered = [...grouped].sort((a, b) => {
      const nameOrder = compareNames(groupLabel(a[0]), groupLabel(b[0]));
      if (state.groupSort === "count") return b[1].length - a[1].length || nameOrder;
      return state.groupSort === "name-desc" ? -nameOrder : nameOrder;
    });
    for (const [key, indices] of ordered.slice(0, groupLimit)) {
      const button = document.createElement("button"); button.type = "button";
      button.className = "m3x-playlist-row"; button.dataset.group = key;
      const cover = document.createElement(folders ? "span" : "img");
      if (folders) { cover.className = "m3x-folder-icon"; cover.innerHTML = icon("folder"); }
      else { cover.src = tracks[indices[0]].cover || "cover-placeholder.svg"; cover.alt = ""; cover.loading = "lazy"; cover.hidden = !state.showCovers; }
      const info = document.createElement("span");
      const name = document.createElement("strong"); name.textContent = groupLabel(key);
      const count = document.createElement("small"); count.textContent = indices.length + " 首";
      info.append(name, count);
      if (state.libraryView === "grid") {
        const art = document.createElement("span"); art.className = "m3x-card-art";
        cover.hidden = false;
        if (!folders && !state.showCovers) cover.src = "cover-placeholder.svg";
        art.append(cover); button.append(art, info);
      } else button.append(cover, info);
      groups.append(button);
      if (folders) button.insertAdjacentHTML("beforeend", icon("right"));
    }
    if (ordered.length > groupLimit) {
      const more = document.createElement("button"); more.type = "button"; more.className = "m3x-text-button";
      more.dataset.moreList = "groups"; more.textContent = "加载更多（" + groupLimit + " / " + ordered.length + "）"; groups.append(more);
    }
    if (!grouped.size) {
      const empty = document.createElement("p"); empty.className = "m3x-empty"; empty.textContent = "没有匹配的音乐"; groups.append(empty);
    }
  }
  function renderProgress() {
    const duration = Number.isFinite(audio.duration) ? audio.duration : tracks[state.index]?.duration || 0;
    const position = dragging ? Number(seek.value) : audio.currentTime || pendingSeek;
    seek.max = duration;
    if (!dragging) seek.value = Math.min(position, duration);
    seek.setAttribute("aria-valuetext", time(position) + "，总时长 " + time(duration));
    text("position", time(position));
    text("duration", time(duration));
  }
  function renderPlayback() {
    root.querySelectorAll('[data-action="play"]').forEach(button => {
      const label = audio.paused ? "播放" : "暂停";
      if (button.getAttribute("aria-label") !== label || !button.firstElementChild) {
        button.setAttribute("aria-label", label);
        button.innerHTML = icon(audio.paused ? "play" : "pause");
      }
    });
    text("engine", audio.error ? "读取失败" : window.M3X_NATIVE ? "ExoPlayer · AudioTrack" : "浏览器音频");
    renderProgress();
  }
  function renderLists() {
    const queue = queueIndices();
    root.querySelectorAll("[data-list]").forEach(list => {
      if (list.closest("[data-panel][hidden]")) return;
      if (list.dataset.list === "upcoming" && state.layout !== "catalog") return;
      if (list.hidden) {
        if (list.firstChild) list.replaceChildren();
        listRenderKeys.delete(list);
        return;
      }
      const nodes = [];
      const indices = list.dataset.list === "recent" ? state.history.map(id => indexById.get(id)).filter(index => index !== undefined) :
        list.dataset.list === "upcoming" ? [...queue.slice(queue.indexOf(state.index) + 1), ...queue.slice(0, Math.max(0, queue.indexOf(state.index)))] :
        list.dataset.list === "playlist" ? (state.playlists.find(item => item.id === state.playlistId)?.trackIds || []).map(id => indexById.get(id)).filter(index => index !== undefined) :
        list.dataset.list === "queue" ? queueIndices() : list.dataset.list === "favorites" ? favoriteIndices() : libraryIndices();
      const limit = listLimits[list.dataset.list] || listPageSize;
      const renderKey = JSON.stringify([libraryRevision, indices, limit, state.index, state.showCovers, state.filenameTitle, list.dataset.list === "library" ? state.libraryView : "list"]);
      if (listRenderKeys.get(list) === renderKey) return;
      listRenderKeys.set(list, renderKey);
      indices.slice(0, limit).forEach(index => {
        const track = tracks[index];
        if (state.hidden.has(track.id)) return;
        const button = document.createElement("button");
        button.type = "button";
        button.className = "m3x-track";
        button.dataset.track = index;
        button.dataset.context = list.dataset.list;
        button.setAttribute("aria-current", String(index === state.index));
        button.setAttribute("aria-label", track.title + "，" + track.artist + "，播放");
        const cover = document.createElement("img");
        cover.className = "m3x-swatch";
        cover.src = track.cover || "cover-placeholder.svg";
        cover.alt = "";
        cover.width = cover.height = 40;
        cover.loading = "lazy";
        cover.hidden = !state.showCovers;
        const info = document.createElement("span");
        info.className = "m3x-track-info";
        const title = document.createElement("span");
        title.className = "m3x-track-title";
        title.textContent = displayTitle(track);
        title.title = track.title;
        const artist = document.createElement("span");
        artist.className = "m3x-track-artist";
        const artistName = document.createElement("span");
        artistName.textContent = track.artist;
        artist.append(artistName);
        artist.title = track.artist;
        info.append(title, artist);
        const details = document.createElement("span");
        details.className = "m3x-track-details";
        const resolution = document.createElement("small");
        resolution.textContent = quality(track);
        artist.append(resolution);
        details.textContent = track.duration > 0 ? time(track.duration) : "—";
        if (list.dataset.list === "library" && state.libraryView === "grid") {
          const art = document.createElement("span"); art.className = "m3x-card-art";
          cover.hidden = false; if (!state.showCovers) cover.src = "cover-placeholder.svg";
          art.append(cover); button.append(art, info, details);
        } else button.append(cover, info, details);
        if (["playlist", "queue", "library", "recent", "favorites"].includes(list.dataset.list)) {
          const row = document.createElement("div");
          row.className = "m3x-track-row";
          const remove = document.createElement("button");
          remove.type = "button"; remove.className = "m3x-icon";
          if (list.dataset.list === "playlist") remove.dataset.removeTrack = track.id;
          else if (list.dataset.list === "queue") remove.dataset.removeQueue = track.id;
          else remove.dataset.menuTrack = index;
          remove.setAttribute("aria-label", (["playlist", "queue"].includes(list.dataset.list) ? "移除 " : "歌曲操作 ") + track.title);
          remove.innerHTML = icon(["playlist", "queue"].includes(list.dataset.list) ? "remove" : "more");
          row.append(button, remove); nodes.push(row);
        } else nodes.push(button);
      });
      if (indices.length > limit) {
        const more = document.createElement("button"); more.type = "button"; more.className = "m3x-text-button";
        more.dataset.moreList = list.dataset.list; more.textContent = "加载更多（" + limit + " / " + indices.length + "）"; nodes.push(more);
      }
      if (!nodes.length) {
        const empty = document.createElement("div");
        empty.className = "m3x-empty";
        empty.textContent = list.dataset.list === "recent" ? "暂无播放记录" : list.dataset.list === "playlist" ? "歌单暂无歌曲" :
          list.dataset.list === "favorites" && !state.favoriteQuery.trim() ? "暂无收藏，播放歌曲时点亮爱心即可添加" : "没有匹配的歌曲";
        if (window.M3X_NATIVE && list.dataset.list === "library" && !tracks.length) {
          const choose = document.createElement("button"); choose.type = "button"; choose.className = "m3x-text-button";
          choose.dataset.action = "choose-folder"; choose.textContent = "选择音乐目录"; empty.append(choose);
        }
        nodes.push(empty);
      }
      list.replaceChildren(...nodes);
    });
  }
  function renderProfile() {
    if (document.activeElement !== nickname) nickname.value = state.nickname;
    text("favoriteCount", tracks.filter(track => state.favorites.has(track.id) && !state.hidden.has(track.id)).length);
    text("historyCount", state.history.filter(id => indexById.has(id)).length + " 首");
    text("songCount", tracks.length);
    text("playlistTotal", state.playlists.length);
    text("hiddenCount", state.hidden.size + " 首");
    if (state.page === "profile") renderCollectionPreviews();
    const avatar = document.getElementById("avatar-image");
    avatar.hidden = !state.avatar;
    if (state.avatar && avatar.getAttribute("src") !== state.avatar) avatar.src = state.avatar;
    screen.querySelector(".m3x-avatar span").hidden = Boolean(state.avatar);
  }
  function renderCollectionPreviews() {
    const favorites = [...new Set([...state.history, ...state.favorites])].filter(id => state.favorites.has(id) && indexById.has(id) && !state.hidden.has(id)).slice(0, 4);
    const items = state.playlists.slice(0, 4);
    for (const [id, entries] of [["favorite-preview", favorites], ["playlist-preview", items]]) {
      const target = document.getElementById(id);
      const key = JSON.stringify([libraryRevision, entries]);
      if (listRenderKeys.get(target) === key) continue;
      listRenderKeys.set(target, key); target.replaceChildren();
      entries.forEach(entry => {
        const playlist = id === "playlist-preview";
        const track = playlist ? tracks[indexById.get(entry.trackIds.find(value => indexById.has(value)))] : tracks[indexById.get(entry)];
        const card = document.createElement("button"); card.type = "button"; card.className = "m3x-preview-card";
        if (playlist) card.dataset.playlist = entry.id;
        else { card.dataset.track = indexById.get(entry); card.dataset.context = "favorites"; }
        const art = document.createElement("span"); art.className = "m3x-card-art";
        const img = document.createElement("img"); img.src = track?.cover || "cover-placeholder.svg"; img.alt = ""; img.loading = "lazy";
        art.append(img);
        const title = document.createElement("span"); title.textContent = playlist ? entry.name : track.title;
        card.append(art, title); card.title = title.textContent; target.append(card);
      });
      if (!entries.length) {
        const empty = document.createElement("p"); empty.className = "m3x-caption";
        empty.textContent = id === "favorite-preview" ? "暂无收藏，点亮歌曲的爱心即可添加" : "还没有歌单";
        target.append(empty);
        if (id === "playlist-preview") { const add = document.createElement("button"); add.type = "button"; add.className = "m3x-text-button"; add.dataset.action = "new-playlist"; add.textContent = "+ 创建歌单"; target.append(add); }
      }
    }
  }
  const libraryScrolls = new Map();
  const libraryScrollKey = view => JSON.stringify([state.filter, state.groupKey, state.query, state.sort, state.groupSort, view]);
  root.querySelectorAll("[data-library-view]").forEach(button => button.addEventListener("click", () => {
    const browser = document.getElementById("library-browser");
    libraryScrolls.set(libraryScrollKey(state.libraryView), browser.scrollTop);
    state.libraryView = button.dataset.libraryView; render(); persist(true);
    browser.scrollTop = libraryScrolls.get(libraryScrollKey(state.libraryView)) || 0;
  }));
  function render() {
    const track = tracks[state.index];
    const queue = queueIndices();
    window.M3X_APPEARANCE.apply(state, track);
    screen.dataset.libraryView = state.libraryView;
    screen.querySelectorAll("[data-library-view]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.libraryView === state.libraryView)));
    screen.querySelector('[data-setting="colorMode"]').disabled = state.visualTheme === "endfield";
    screen.dataset.layout = state.layout;
    screen.querySelectorAll("[data-panel]").forEach(panel => { panel.hidden = panel.dataset.panel !== state.page; });
    screen.querySelectorAll(".m3x-nav [data-page]").forEach(button => {
      const section = ["settings", "statistics", "favorites", "playlists", "playlist"].includes(state.page) ? "profile" : ["queue", "lyrics"].includes(state.page) ? "player" : state.page === "create-library" ? "library" : state.page;
      if (button.dataset.page === section) button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
    screen.querySelector(".m3x-mini").hidden = state.page === "player";
    text("title", track ? displayTitle(track) : "暂无歌曲");
    text("singer", track?.artist || "");
    text("album", track?.album || "");
    text("format", track ? format(track) : "");
    text("next", tracks.length ? state.mode === 1 ? "随机选择下一首" : tracks[nextIndex()]?.title || "队列播放完毕" : "暂无歌曲");
    text("trackCount", tracks.length + " 首");
    text("queueCount", queue.length + " 首歌曲");
    const playlist = state.playlists.find(item => item.id === state.playlistId);
    text("playlistName", playlist?.name || "歌单");
    text("playlistCount", (playlist?.trackIds.filter(id => indexById.has(id)).length || 0) + " 首");
    const cover = screen.querySelector(".m3x-cover");
    const coverSource = track?.cover || "cover-placeholder.svg";
    if (cover.getAttribute("src") !== coverSource) cover.src = coverSource;
    cover.alt = track ? track.album + " 专辑封面" : "";
    const miniCover = screen.querySelector(".m3x-mini-cover");
    if (miniCover.getAttribute("src") !== coverSource) miniCover.src = coverSource;
    screen.querySelector(".m3x-title").title = track?.title || "";
    screen.querySelector(".m3x-artist").title = track?.artist || "";
    const favorite = screen.querySelector('[data-action="favorite"]');
    favorite.setAttribute("aria-pressed", String(state.favorites.has(track?.id)));
    favorite.setAttribute("aria-label", state.favorites.has(track?.id) ? "取消收藏当前歌曲" : "收藏当前歌曲");
    screen.querySelectorAll('[data-action="play"], [data-action="next"], [data-action="previous"], [data-action="favorite"], [data-action="add-playlist"], [data-action="song-menu"]').forEach(button => { button.disabled = !track || (["next", "previous"].includes(button.dataset.action) && !queue.length); });
    const mode = screen.querySelector('[data-action="mode"]');
    mode.innerHTML = icon(["sequential", "shuffle", "repeat"][state.mode]);
    mode.setAttribute("aria-label", "播放模式：" + modes[state.mode]);
    screen.querySelectorAll("[data-filter]").forEach(button => button.setAttribute("aria-pressed", String(button.dataset.filter === state.filter)));
    screen.querySelectorAll('[data-filter="characters"], [data-filter="groups"]').forEach(button => { button.hidden = !library?.classification; });
    const createEntry = screen.querySelector(".m3x-create-entry");
    createEntry.querySelector("strong").textContent = library?.classification ? "管理曲库分类" : "创建曲库";
    createEntry.querySelector("small").textContent = library?.classification ? "THE_IDOLMASTER · 当前分类已应用" : "THE_IDOLMASTER · 使用已保留的分类";
    screen.querySelectorAll("[data-setting]").forEach(input => {
      if (input.type === "checkbox") input.checked = state[input.dataset.setting];
      else input.value = state[input.dataset.setting];
    });
    text("librarySize", tracks.length + " 首 · " + (tracks.reduce((size, item) => size + item.bytes, 0) / 1024 ** 3).toFixed(2) + " GiB");
    renderProfile();
    if (state.page === "library") renderGroups();
    renderLists();
    renderPlaylists();
    renderPlayback();
    positionDialogs();
    window.dispatchEvent(new CustomEvent("m3x-render", { detail: { page: state.page } }));
    if (renderedTrack !== track?.id) {
      renderedTrack = track?.id;
      window.dispatchEvent(new CustomEvent("m3x-track", { detail: track || null }));
    }
  }
  function renderPlaylists() {
    const container = document.getElementById("profile-playlists");
    const choices = document.getElementById("playlist-choices");
    const renderKey = JSON.stringify([libraryRevision, state.playlists, state.playlistTarget]);
    if (listRenderKeys.get(container) === renderKey) return;
    listRenderKeys.set(container, renderKey);
    container.replaceChildren();
    choices.replaceChildren();
    for (const playlist of state.playlists) {
      const available = playlist.trackIds.map(id => tracks[indexById.get(id)]).filter(Boolean);
      for (const target of [container, choices]) {
        const button = document.createElement("button");
        button.type = "button"; button.className = "m3x-playlist-row";
        if (target === container) button.dataset.playlist = playlist.id;
        else button.dataset.addToPlaylist = playlist.id;
        const image = document.createElement("img");
        image.src = available[0]?.cover || "cover-placeholder.svg"; image.alt = "";
        const info = document.createElement("span");
        const name = document.createElement("strong"); name.textContent = playlist.name;
        const detail = document.createElement("small");
        const added = playlist.trackIds.includes(state.playlistTarget);
        detail.textContent = target === choices && added ? "已添加" : available.length + " 首";
        info.append(name, detail);
        const mark = document.createElement("span"); mark.innerHTML = icon(target === choices && added ? "check" : "right");
        button.append(image, info, mark);
        button.disabled = target === choices && added;
        target.append(button);
      }
    }
    if (!state.playlists.length) {
      const empty = document.createElement("p"); empty.className = "m3x-empty"; empty.textContent = "还没有歌单，点击右上角 + 创建";
      container.append(empty);
    }
  }
  const closingDialogs = new WeakMap();
  function openDialog(element) {
    clearTimeout(closingDialogs.get(element));
    element.classList.remove("is-closing");
    element.dataset.instant = "false";
    if (!element.open) element.showModal();
    positionDialogs();
    element.classList.add("is-open");
  }
  function closeDialog(element, instant = false) {
    clearTimeout(closingDialogs.get(element));
    element.classList.remove("is-open");
    if (!element.open) return;
    if (instant || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      element.classList.remove("is-closing"); element.close(); return;
    }
    element.classList.add("is-closing");
    const finish = () => {
      element.classList.remove("is-closing");
      if (!element.classList.contains("is-open") && element.open) element.close();
    };
    closingDialogs.set(element, setTimeout(finish, 160));
  }
  function openPlaylist(action, target = tracks[state.index]?.id) {
    playlistAction = action;
    state.playlistTarget = target;
    document.getElementById("playlist-dialog-title").textContent = action === "add" ? "添加到歌单" : action === "rename" ? "重命名歌单" : "新建歌单";
    document.getElementById("playlist-choices").hidden = action !== "add";
    document.querySelector('#playlist-form label').textContent = action === "rename" ? "歌单名称" : "新建歌单";
    document.querySelector('#playlist-form button').textContent = action === "rename" ? "保存" : "创建";
    const input = document.getElementById("playlist-name");
    input.value = action === "rename" ? state.playlists.find(item => item.id === state.playlistId)?.name || "" : "";
    input.setCustomValidity("");
    renderPlaylists();
    openDialog(playlistDialog);
    if (action !== "add") input.focus();
  }
  function openLibrary(filter) {
    state.filter = filter; state.groupKey = null; state.query = ""; search.value = ""; state.page = "library";
  }
  async function api(path, body) {
    const response = await fetch(path, body ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) } : {});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "操作失败");
    return data;
  }
  function openSongMenu(index) {
    if (!tracks[index]) return;
    menuIndex = index;
    document.getElementById("song-menu-title").textContent = displayTitle(tracks[index]);
    document.getElementById("song-menu-artist").textContent = tracks[index].artist;
    document.getElementById("song-menu-dialog").dataset.trackId = tracks[index].id;
    openDialog(document.getElementById("song-menu-dialog"));
  }
  function renderHidden() {
    const container = document.getElementById("hidden-list");
    container.replaceChildren();
    for (const track of tracks.filter(item => state.hidden.has(item.id))) {
      const button = document.createElement("button"); button.type = "button"; button.className = "m3x-sheet-action";
      button.dataset.restore = track.id; button.textContent = track.title + " · 恢复"; container.append(button);
    }
    if (!container.childElementCount) {
      const empty = document.createElement("p"); empty.className = "m3x-empty"; empty.textContent = "暂无隐藏歌曲"; container.append(empty);
    }
  }
  async function showSongInfo(index) {
    const track = tracks[index];
    if (!track) return;
    const target = document.getElementById("song-info");
    target.textContent = "正在读取";
    openDialog(document.getElementById("info-dialog"));
    try {
      const info = await api("/api/track?id=" + encodeURIComponent(track.id));
      target.replaceChildren();
      const counts = window.M3X_UI?.playCount?.(track.id) || 0;
      for (const [name, value] of [
        ["歌曲", info.title], ["歌手", info.artist], ["专辑", info.album],
        ["编码", info.codec], ["音质", quality(info)], ["比特率", info.bitrate ? Math.round(info.bitrate / 1000) + " kbps" : "未提供"],
        ["声道", info.channels], ["时长", time(info.duration)], ["音轨", info.trackNumber || "未提供"],
        ["年份", info.year || "未提供"], ["播放次数", counts], ["大小", (info.bytes / 1024 ** 2).toFixed(1) + " MiB"], ["文件路径", info.path]
      ]) {
        const row = document.createElement("div"), label = document.createElement("dt"), detail = document.createElement("dd");
        label.textContent = name; detail.textContent = value; row.append(label, detail); target.append(row);
      }
    } catch { target.textContent = "无法读取歌曲信息，请检查文件是否仍在原目录"; }
  }
  function playNext(index) {
    const track = tracks[index], current = tracks[state.index];
    if (!track || !current) return;
    if (track.id === current.id) { toast("正在播放这首歌曲"); return; }
    const queue = queueIndices().map(item => tracks[item].id).filter(id => id !== track.id);
    if (!queue.includes(current.id)) queue.unshift(current.id);
    queue.splice(queue.indexOf(current.id) + 1, 0, track.id);
    state.queue = queue; state.queueCleared = false;
    render(); persist(true); toast("已设为下一首");
  }
  function removeQueue(id) {
    const before = queueIndices();
    const position = before.findIndex(index => tracks[index].id === id);
    const wasPlaying = !audio.paused;
    state.queue = before.map(index => tracks[index].id).filter(item => item !== id);
    state.queueCleared = !state.queue.length;
    if (tracks[state.index]?.id === id) {
      if (state.queue.length) loadTrack(tracks.findIndex(track => track.id === state.queue[position % state.queue.length]), wasPlaying, false);
      else audio.pause();
    }
    render(); persist(true);
  }
  function renderScan() {
    const scan = library?.scan;
    const previewClassification = Boolean(library?.classification) && !window.M3X_NATIVE;
    const directories = library?.directories || (library?.folder ? [{ path: library.folder }] : []);
    document.getElementById("music-folder").textContent = previewClassification ? "THE_IDOLMASTER · 分类已应用，无需重新扫描" : `${directories.length} 个目录 · 点击开始扫描后生效`;
    const sourcesKey = JSON.stringify([directories, Boolean(scan?.running)]);
    if (sourcesKey !== renderedScanSources) {
      const list = document.getElementById("scan-directories");
      list.replaceChildren();
      directories.forEach((directory, index) => {
        const row = document.createElement("li"), label = document.createElement("span"), path = document.createElement("small");
        const name = directory.path.replace(/[\\/]+$/, "").split(/[\\/]/).pop() || directory.path;
        label.textContent = name; path.textContent = directory.path; label.append(path);
        row.innerHTML = icon("folder"); row.append(label);
        if (window.M3X_NATIVE) {
          const remove = document.createElement("button");
          remove.type = "button"; remove.className = "m3x-icon"; remove.dataset.removeRoot = index;
          remove.setAttribute("aria-label", `移除扫描目录 ${name}`); remove.innerHTML = icon("remove");
          remove.disabled = Boolean(scan?.running); row.append(remove);
        }
        list.append(row);
      });
      renderedScanSources = sourcesKey;
    }
    const mediaStore = document.getElementById("include-media-store");
    document.getElementById("media-store-setting").hidden = !window.M3X_NATIVE;
    mediaStore.checked = library?.options?.includeMediaStore === true;
    mediaStore.disabled = Boolean(scan?.running);
    const rate = scan?.rate > 0 ? ` · ${Math.round(scan.rate)} 首/秒` : "";
    const message = scan?.running ? (scan.phase === "discovering" ? "正在读取目录…" : `扫描中 ${scan.processed} / ${scan.total}${rate}`) : scan?.error || (scan?.cancelled ? "扫描已停止" : scan?.lastScan ? `已扫描 ${tracks.length} 首${rate}` : "尚未扫描");
    text("scanStatus", previewClassification ? "已使用保存的分类" : message);
    if (window.M3X_NATIVE && library?.classification?.builtin) {
      const summary = document.getElementById("builtin-classification");
      if (summary) summary.textContent = `内置分类 · 已匹配 ${library.classification.matched} 首 · 其余保留原始歌手`;
    }
    const progress = document.getElementById("scan-progress");
    progress.hidden = !scan?.running;
    progress.max = scan?.total || 1; progress.value = scan?.processed || 0;
    screen.querySelectorAll('[data-action="scan"]').forEach(button => { button.disabled = Boolean(scan?.running); button.hidden = previewClassification; });
    screen.querySelectorAll('[data-action="choose-folder"], [data-action="scan-options"]').forEach(button => { button.disabled = Boolean(scan?.running); });
    screen.querySelector('[data-action="scan-options"]').hidden = previewClassification;
    screen.querySelector('[data-action="stop-scan"]').hidden = !scan?.running;
    const report = document.getElementById("scan-report");
    report.hidden = Boolean(scan?.running) || !(scan?.errors?.length || scan?.reportError || scan?.error);
    const reportKey = JSON.stringify([scan?.errors, scan?.reportError, scan?.error, scan?.failed, scan?.cueFailed, scan?.unsupported]);
    if (reportKey !== renderedScanReport) {
      renderedScanReport = reportKey;
      document.getElementById("scan-report-summary").textContent = `音频失败 ${scan?.failed || 0} · CUE 警告 ${scan?.cueFailed || 0} · 不支持 ${scan?.unsupported || 0}`;
      const list = document.getElementById("scan-errors");
      list.textContent = "";
      for (const error of scan?.errors || []) {
        const row = document.createElement("li");
        const label = { audio: "音频", cue: "CUE", unsupported: "不支持" }[error.kind] || "扫描";
        row.textContent = typeof error === "string" ? error : `${label} · ${error.path}\n${error.message}`;
        list.append(row);
      }
      if (scan?.reportError) {
        const row = document.createElement("li"); row.textContent = scan.reportError; list.append(row);
      }
      if (scan?.error) {
        const row = document.createElement("li"); row.textContent = scan.error; list.append(row);
      }
    }
  }
  function applyLibrary(data) {
    const previous = tracks[state.index];
    const previousSource = previous?.src;
    tracks.splice(0, tracks.length, ...data.tracks);
    libraryRevision++;
    indexById = new Map(tracks.map((track, index) => [track.id, index]));
    entityById = new Map((data.classification?.entities || []).map(entity => [entity.id, entity]));
    const nativeId = window.M3X_NATIVE ? window.nativeCommand("poll").id : null;
    if (window.M3X_NATIVE) state.queue = window.nativeCommand("queue").ids || state.queue;
    state.index = Math.max(0, tracks.findIndex(track => track.id === (nativeId || previous?.id)));
    if (!window.M3X_NATIVE && tracks[state.index]?.src !== previousSource) {
      audio.pause();
      pendingSeek = 0;
      if (tracks.length) { audio.src = tracks[state.index].src; audio.load(); }
      else { audio.removeAttribute("src"); audio.load(); }
    }
    render(); persist();
  }
  async function pollScan() {
    try {
      const data = await api("/api/scan-status");
      library.scan = data.scan;
      renderScan();
      if (library.scan.running) scanTimer = setTimeout(pollScan, 700);
      else {
        library = await api("/api/library");
        renderScan();
        applyLibrary(library);
        toast(library.scan.error || (library.scan.cancelled ? "扫描已停止，保留原曲库" : `扫描完成 · ${tracks.length} 首 · 音频失败 ${library.scan.failed || 0} · CUE 警告 ${library.scan.cueFailed || 0} · 不支持 ${library.scan.unsupported || 0}`));
      }
    } catch { toast("扫描服务连接失败"); }
  }
  async function scanLibrary() {
    if (library?.scan.running) { toast("正在扫描"); return; }
    try {
      library = await api("/api/scan", {});
      renderScan(); clearTimeout(scanTimer);
      toast("开始扫描");
      scanTimer = setTimeout(pollScan, 300);
    } catch (error) { toast(error.message || "无法开始扫描"); }
  }
  let browsingFolder = null;
  async function browseFolder(path) {
    const errorLabel = document.getElementById("folder-error");
    errorLabel.hidden = true;
    document.getElementById("select-folder").disabled = true;
    try {
      const data = await api("/api/folders?path=" + encodeURIComponent(path || ""));
      browsingFolder = data;
      document.getElementById("folder-path").value = data.path;
      const container = document.getElementById("folder-children");
      container.replaceChildren();
      for (const folder of data.folders) {
        const button = document.createElement("button");
        button.type = "button"; button.className = "m3x-folder-row"; button.dataset.folder = folder.path;
        const name = document.createElement("span"); name.textContent = folder.name;
        button.innerHTML = icon("folder"); button.append(name); container.append(button);
      }
      if (!data.folders.length) {
        const empty = document.createElement("p"); empty.className = "m3x-empty"; empty.textContent = "没有子文件夹"; container.append(empty);
      }
      document.getElementById("folder-parent").disabled = data.path === data.parent;
      document.getElementById("select-folder").disabled = false;
    } catch (error) {
      errorLabel.textContent = error.message || "无法打开文件夹"; errorLabel.hidden = false;
    }
  }
  async function play() {
    if (!tracks.length) return;
    if (state.queueCleared) { state.queue = [tracks[state.index].id]; state.queueCleared = false; render(); }
    const index = state.index;
    try {
      await audio.play();
      status.hidden = true;
    } catch (error) {
      if (error.name === "AbortError" || index !== state.index) return;
      status.textContent = error.name === "NotAllowedError" ? "请点击播放按钮" : "播放失败，请尝试其他曲目";
      status.dataset.error = "true";
      status.hidden = false;
      announce(status.textContent);
    }
  }
  function loadTrack(index, autoplay, manual = true) {
    if (!tracks[index]) return;
    if (manual) window.dispatchEvent(new Event("m3x-manual-track"));
    state.index = index;
    pendingSeek = 0;
    audio.src = tracks[index].src;
    audio.load();
    status.dataset.error = "false";
    status.hidden = true;
    if (renderedTrack === tracks[index].id) window.dispatchEvent(new CustomEvent("m3x-track", { detail: tracks[index] }));
    render();
    persist();
    if (autoplay) void play();
  }
  function nextIndex() {
    const queue = queueIndices();
    const offset = state.mode === 1 && queue.length > 1 ? 1 + Math.floor(Math.random() * (queue.length - 1)) : 1;
    return queue[(queue.indexOf(state.index) + offset + queue.length) % queue.length];
  }
  function previousIndex() {
    const queue = queueIndices();
    const current = queue.indexOf(state.index);
    return queue[((current < 0 ? queue.length : current) + queue.length - 1) % queue.length];
  }
  function positionDialogs() {
    const opened = screen.querySelectorAll("dialog[open]");
    if (!opened.length) return;
    const rect = screen.getBoundingClientRect();
    opened.forEach(element => {
      Object.assign(element.style, { left: rect.left + window.scrollX + "px", top: rect.top + window.scrollY + "px", width: rect.width + "px", height: rect.height + "px" });
    });
  }
  function syncSheet(instant = false) {
    if (state.sheetOpen) {
      if (!dialog.open || dialog.classList.contains("is-closing")) openDialog(dialog);
      dialog.dataset.instant = String(instant);
    } else if (dialog.open) closeDialog(dialog, instant);
    screen.querySelectorAll('[data-action="output"]').forEach(button => button.setAttribute("aria-expanded", String(state.sheetOpen)));
    screen.querySelectorAll("[data-sheet-tab]").forEach(button => button.setAttribute("aria-selected", String(button.dataset.sheetTab === state.sheetTab)));
    screen.querySelectorAll("[data-sheet-panel]").forEach(panel => { panel.hidden = panel.dataset.sheetPanel !== state.sheetTab; });
  }
  root.addEventListener("click", event => {
    const button = event.target.closest("button");
    if (!button) return;
    const action = button.dataset.action;
    if (button.type === "submit") return;
    if (button.hasAttribute("data-close")) {
      closeDialog(button.closest("dialog")); return;
    } else if (button.dataset.moreList) {
      listLimits[button.dataset.moreList] = (listLimits[button.dataset.moreList] || listPageSize) + listPageSize;
    } else if (button.dataset.menuTrack !== undefined) {
      openSongMenu(Number(button.dataset.menuTrack)); return;
    } else if (button.dataset.removeQueue) {
      removeQueue(button.dataset.removeQueue); return;
    } else if (button.dataset.restore) {
      state.hidden.delete(button.dataset.restore); renderHidden();
    } else if (button.dataset.group !== undefined) {
      state.groupKey = button.dataset.group;
      document.getElementById("library-browser").scrollTop = 0;
    } else if (button.dataset.folder) {
      void browseFolder(button.dataset.folder); return;
    } else if (button.dataset.removeRoot !== undefined) {
      void api("/api/root-remove", { index: Number(button.dataset.removeRoot) }).then(data => {
        library = data; renderScan(); toast("已移除目录，点击开始扫描后更新曲库");
      }).catch(error => toast(error.message));
      return;
    } else if (button.dataset.playlist) {
      state.playlistId = button.dataset.playlist; state.page = "playlist";
    } else if (button.dataset.addToPlaylist) {
      const playlist = state.playlists.find(item => item.id === button.dataset.addToPlaylist);
      const id = state.playlistTarget;
      if (!playlist || !id || playlist.trackIds.includes(id)) return;
      playlist.trackIds.push(id);
      if (!persist(true)) { playlist.trackIds.pop(); return; }
      closeDialog(playlistDialog); toast("已添加到「" + playlist.name + "」");
    } else if (button.dataset.removeTrack) {
      const playlist = state.playlists.find(item => item.id === state.playlistId);
      if (!playlist) return;
      const previous = playlist.trackIds;
      playlist.trackIds = previous.filter(id => id !== button.dataset.removeTrack);
      if (!persist(true)) { playlist.trackIds = previous; return; }
      toast("已从歌单移除");
    } else if (button.dataset.page) {
      state.page = button.dataset.page;
    } else if (button.dataset.track !== undefined) {
      if (!["queue", "upcoming"].includes(button.dataset.context)) {
        state.queue = button.dataset.context === "library" ? libraryIndices().map(index => tracks[index].id) :
          button.dataset.context === "favorites" ? favoriteIndices().map(index => tracks[index].id) :
          button.dataset.context === "playlist" ? (state.playlists.find(item => item.id === state.playlistId)?.trackIds || []).filter(id => indexById.has(id) && !state.hidden.has(id)) :
          [...screen.querySelectorAll('[data-list="' + button.dataset.context + '"] [data-track]')].map(item => tracks[Number(item.dataset.track)].id);
        state.queueCleared = false;
      }
      state.page = "player";
      loadTrack(Number(button.dataset.track), true);
      return;
    } else if (button.dataset.filter) {
      state.filter = button.dataset.filter;
      state.groupKey = null;
    } else if (action === "group-back") {
      state.groupKey = state.filter === "folders" ? state.groupKey?.split("/").slice(0, -1).join("/") || null : null;
      document.getElementById("library-browser").scrollTop = 0;
    } else if (action === "song-menu") {
      openSongMenu(state.index); return;
    } else if (action === "song-info" || action === "play-next" || action === "menu-add-playlist" || action === "hide-song") {
      closeDialog(document.getElementById("song-menu-dialog"), true);
      if (action === "song-info") { void showSongInfo(menuIndex); return; }
      if (action === "play-next") { playNext(menuIndex); return; }
      if (action === "menu-add-playlist") { openPlaylist("add", tracks[menuIndex].id); return; }
      state.hidden.add(tracks[menuIndex].id);
      toast("歌曲已隐藏");
    } else if (action === "hidden-songs") {
      renderHidden(); openDialog(document.getElementById("hidden-dialog")); return;
    } else if (action === "restore-all") {
      state.hidden.clear(); renderHidden();
    } else if (action === "clear-queue") {
      state.queue = []; state.queueCleared = true; audio.pause(); toast("队列已清空");
    } else if (action === "locate-current") {
      const position = queueIndices().indexOf(state.index);
      if (position < 0) { toast("当前歌曲不在队列中"); return; }
      listLimits.queue = Math.max(listLimits.queue || listPageSize, position + 1);
      renderLists();
      screen.querySelector('[data-list="queue"] [aria-current="true"]')?.scrollIntoView({ block: "center" }); return;
    } else if (action === "scan-report-export") {
      if (window.M3X_NATIVE) window.nativeCommand("scan-report-export");
      else {
        const url = URL.createObjectURL(new Blob([JSON.stringify(library.scan, null, 2)], { type: "application/json" }));
        const link = document.createElement("a"); link.href = url; link.download = "澄-扫描报告.json"; link.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
      return;
    } else if (action === "stop-scan") {
      void api("/api/scan-cancel", {}).then(() => toast("正在停止扫描")).catch(() => toast("无法停止扫描")); return;
    } else if (action === "scan-options") {
      document.getElementById("skip-short").checked = library?.options?.minDuration === 60;
      document.getElementById("excluded-folders").value = (library?.options?.excludedFolders || []).join("\n");
      document.getElementById("scan-options-error").hidden = true;
      openDialog(document.getElementById("scan-options-dialog")); return;
    } else if (action === "open-library") {
      openLibrary("all");
    } else if (action === "open-favorites") {
      state.page = "favorites"; state.favoriteQuery = "";
      document.getElementById("favorites-search").value = "";
    } else if (action === "open-playlists" || action === "back-playlists") {
      state.page = "playlists";
    } else if (action === "edit-profile") {
      nickname.focus(); nickname.select(); return;
    } else if (action === "avatar") {
      document.getElementById("avatar-file").click(); return;
    } else if (action === "new-playlist" || action === "add-playlist" || action === "rename-playlist") {
      openPlaylist(action === "add-playlist" ? "add" : action === "rename-playlist" ? "rename" : "create"); return;
    } else if (action === "play-playlist") {
      state.queue = (state.playlists.find(item => item.id === state.playlistId)?.trackIds || []).filter(id => indexById.has(id) && !state.hidden.has(id));
      if (!state.queue.length) { toast("歌单暂无歌曲"); return; }
      state.queueCleared = false;
      state.page = "player"; loadTrack(tracks.findIndex(track => track.id === state.queue[0]), true); return;
    } else if (action === "scan") {
      void scanLibrary(); return;
    } else if (action === "choose-folder") {
      openDialog(folderDialog); void browseFolder(library?.folder); return;
    } else if (action === "play") {
      if (audio.paused) void play(); else audio.pause();
      return;
    } else if (["next", "previous"].includes(action)) {
      loadTrack(action === "next" ? nextIndex() : previousIndex(), true);
      return;
    } else if (action === "favorite") {
      const id = tracks[state.index].id;
      if (state.favorites.has(id)) state.favorites.delete(id); else state.favorites.add(id);
      announce(state.favorites.has(id) ? "已收藏" : "已取消收藏");
    } else if (action === "mode") {
      state.mode = (state.mode + 1) % modes.length;
      toast(modes[state.mode]);
    } else if (action === "output") {
      state.sheetOpen = true;
      state.sheetTab = button.dataset.openTab;
      syncSheet(event.detail === 0);
      return;
    } else if (action === "close-output") {
      state.sheetOpen = false;
      syncSheet(event.detail === 0);
      return;
    } else if (button.dataset.sheetTab) {
      state.sheetTab = button.dataset.sheetTab;
      syncSheet(event.detail === 0);
      return;
    }
    render();
    const viewOnly = button.dataset.page || button.dataset.filter || button.dataset.group !== undefined || button.dataset.moreList ||
      ["group-back", "open-library", "open-favorites", "open-playlists", "back-playlists"].includes(action);
    if (!viewOnly) persist();
    if (button.dataset.page && !button.closest(".m3x-nav")) {
      screen.querySelector('[data-panel="' + state.page + '"] button')?.focus({ preventScroll: true });
    }
  });
  search.addEventListener("input", () => {
    state.query = search.value;
    renderGroups();
    renderLists();
    announce("找到 " + screen.querySelectorAll('[data-list="library"] [data-track]').length + " 首歌曲");
  });
  document.getElementById("favorites-search").addEventListener("input", event => {
    state.favoriteQuery = event.target.value;
    renderLists();
    announce("找到 " + favoriteIndices().length + " 首收藏歌曲");
  });
  nickname.addEventListener("input", () => {
    const value = nickname.value.trim().slice(0, 20);
    if (!value) return;
    state.nickname = value;
    persist(true);
  });
  nickname.addEventListener("blur", () => { nickname.value = state.nickname; });
  nickname.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.isComposing) { event.preventDefault(); nickname.blur(); }
    if (event.key === "Escape") nickname.blur();
  });
  document.getElementById("playlist-form").addEventListener("submit", event => {
    event.preventDefault();
    const input = document.getElementById("playlist-name");
    const name = input.value.trim();
    if (!name || state.playlists.some(item => item.name.toLocaleLowerCase() === name.toLocaleLowerCase() && !(playlistAction === "rename" && item.id === state.playlistId))) {
      input.setCustomValidity(name ? "已存在同名歌单" : "请输入歌单名称"); input.reportValidity(); return;
    }
    const previous = structuredClone(state.playlists);
    if (playlistAction === "rename") state.playlists.find(item => item.id === state.playlistId).name = name;
    else state.playlists.push({ id: crypto.randomUUID(), name, trackIds: playlistAction === "add" && state.playlistTarget ? [state.playlistTarget] : [] });
    if (!persist(true)) { state.playlists = previous; return; }
    closeDialog(playlistDialog); render();
    toast(playlistAction === "rename" ? "歌单已重命名" : playlistAction === "add" ? "已创建歌单并添加歌曲" : "歌单已创建");
  });
  document.getElementById("playlist-name").addEventListener("input", event => event.target.setCustomValidity(""));
  document.getElementById("folder-form").addEventListener("submit", event => {
    event.preventDefault(); void browseFolder(document.getElementById("folder-path").value);
  });
  document.getElementById("folder-path").addEventListener("input", () => { document.getElementById("select-folder").disabled = true; });
  document.getElementById("folder-parent").addEventListener("click", () => { if (browsingFolder) void browseFolder(browsingFolder.parent); });
  document.getElementById("select-folder").addEventListener("click", async () => {
    if (!browsingFolder) return;
    try {
      const wasClassified = Boolean(library?.classification);
      library = await api("/api/folder", { folder: browsingFolder.path });
      if (wasClassified) {
        state.filter = "all"; state.groupKey = null; state.query = ""; search.value = "";
        applyLibrary(library);
      }
      renderScan(); closeDialog(folderDialog);
      toast("目录已保存，点击开始扫描后更新曲库");
    } catch (error) {
      const label = document.getElementById("folder-error");
      label.textContent = error.message; label.hidden = false;
    }
  });
  document.getElementById("scan-options-form").addEventListener("submit", async event => {
    event.preventDefault();
    const error = document.getElementById("scan-options-error");
    error.hidden = true;
    try {
      library = await api("/api/scan-options", {
        minDuration: document.getElementById("skip-short").checked ? 60 : 0,
        excludedFolders: document.getElementById("excluded-folders").value.split("\n")
      });
      closeDialog(document.getElementById("scan-options-dialog"));
      renderScan(); toast("筛选已保存，点击开始扫描后生效");
    } catch (failure) { error.textContent = failure.message; error.hidden = false; }
  });
  document.getElementById("include-media-store").addEventListener("change", async event => {
    try {
      library = await api("/api/scan-options", { includeMediaStore: event.target.checked });
      toast("来源已保存，点击开始扫描后生效");
    } catch (error) { toast(error.message); }
    renderScan();
  });
  screen.querySelectorAll("[data-setting]").forEach(input => input.addEventListener("change", () => {
    state[input.dataset.setting] = input.type === "checkbox" ? input.checked : input.value;
    render();
    persist(true);
    announce("设置已保存");
  }));
  seek.addEventListener("input", () => { dragging = true; renderProgress(); });
  seek.addEventListener("change", () => {
    audio.currentTime = Number(seek.value);
    pendingSeek = 0;
    dragging = false;
    renderProgress();
    persist();
  });
  audio.addEventListener("loadedmetadata", () => {
    if (pendingSeek) { audio.currentTime = pendingSeek; pendingSeek = 0; }
    renderPlayback();
  });
  audio.addEventListener("timeupdate", renderProgress);
  for (const name of ["play", "pause", "canplay"]) audio.addEventListener(name, renderPlayback);
  audio.addEventListener("play", () => {
    const id = tracks[state.index]?.id;
    if (!id) return;
    state.history = [id, ...state.history.filter(item => item !== id)].slice(0, 20);
    renderProfile();
    renderLists();
    persist();
  });
  audio.addEventListener("pause", () => persist());
  audio.addEventListener("ended", () => {
    if (window.M3X_NATIVE) return;
    if (window.M3X_UI?.stopAtEnd?.()) return;
    const queue = queueIndices();
    if (state.mode === 2 && queue.includes(state.index)) { audio.currentTime = 0; void play(); }
    else if (queue.length) loadTrack(nextIndex(), true, false);
    else { status.textContent = "队列播放完毕"; status.dataset.error = "false"; status.hidden = false; persist(); }
  });
  audio.addEventListener("error", () => {
    status.textContent = "无法读取音频，请尝试下一首";
    status.dataset.error = "true";
    status.hidden = false;
    renderPlayback();
  });
  dialog.addEventListener("click", event => {
    if (event.target === dialog) { state.sheetOpen = false; syncSheet(); }
  });
  dialog.addEventListener("cancel", event => {
    event.preventDefault(); state.sheetOpen = false; syncSheet(true);
  });
  dialog.addEventListener("close", () => { state.sheetOpen = false; syncSheet(true); });
  screen.querySelector(".m3x-sheet-tabs").addEventListener("keydown", event => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    state.sheetTab = event.key === "Home" ? "signal" : event.key === "End" ? "processing" : state.sheetTab === "signal" ? "processing" : "signal";
    syncSheet(true);
    screen.querySelector('[data-sheet-tab="' + state.sheetTab + '"]').focus();
  });
  screen.querySelectorAll("dialog:not(#output-dialog)").forEach(element => {
    element.addEventListener("click", event => { if (event.target === element) closeDialog(element); });
    element.addEventListener("cancel", event => { event.preventDefault(); closeDialog(element, true); });
  });
  window.addEventListener("resize", positionDialogs);
  window.addEventListener("m3x-native-library", () => {
    void api("/api/library").then(data => {
      library = data; applyLibrary(data); renderScan();
      if (library.scan.running) { clearTimeout(scanTimer); scanTimer = setTimeout(pollScan, 700); }
    }).catch(() => toast("无法读取本地曲库"));
  });
  window.addEventListener("m3x-native-track", event => {
    const index = tracks.findIndex(track => track.id === event.detail);
    if (index < 0 || index === state.index) return;
    state.queue = window.nativeCommand("queue").ids || state.queue;
    state.queueCleared = false;
    state.index = index; pendingSeek = 0;
    state.history = [event.detail, ...state.history.filter(id => id !== event.detail)].slice(0, 20);
    render();
  });
  window.addEventListener("scroll", positionDialogs);
  new ResizeObserver(positionDialogs).observe(screen);
  window.addEventListener("pagehide", () => persist());
  if (tracks[state.index]) audio.src = tracks[state.index].src;
  render();
  screen.querySelectorAll(".m3x-browser-only").forEach(element => { element.hidden = Boolean(window.M3X_NATIVE); });
  renderScan();
  if (library?.scan.running) scanTimer = setTimeout(pollScan, 700);
  window.M3X_UI = {
    toast, openDialog, closeDialog,
    back() {
      if (state.page === "library") {
        if (state.groupKey === null) return false;
        state.groupKey = state.filter === "folders" ? state.groupKey?.split("/").slice(0, -1).join("/") || null : null;
        document.getElementById("library-browser").scrollTop = 0;
      } else {
        state.page = { playlist: "playlists", favorites: "profile", playlists: "profile", settings: "profile",
          statistics: "profile", queue: "player", lyrics: "player" }[state.page] || "library";
      }
      render(); return true;
    },
    currentTrack: () => tracks[state.index], tracks, library: () => library,
    play, next: () => loadTrack(nextIndex(), true),
    previous: () => loadTrack(previousIndex(), true),
    previewLibrary(data, showLibrary = false) {
      if (showLibrary || !tracks[0]?.classificationPreview) {
        state.filter = "all"; state.groupKey = null; state.query = ""; search.value = "";
      }
      if (showLibrary) state.page = "library";
      library = data; applyLibrary(data); renderScan();
    },
    updateTrack(id, data) {
      const track = tracks[indexById.get(id)];
      if (!track) return;
      for (const key of ["duration", "sampleRate", "bits", "channels", "codec", "cover", "bitrate", "year", "trackNumber", "discNumber", "replayGain"]) {
        if (data[key] !== undefined) track[key] = data[key];
      }
      libraryRevision++;
      render();
    },
    showTrack: id => { const index = tracks.findIndex(track => track.id === id); if (index >= 0) { state.page = "player"; loadTrack(index, true); } },
    saveAvatar(data) {
      const previous = state.avatar; state.avatar = data;
      if (!persist(true)) { state.avatar = previous; return false; }
      renderProfile(); return true;
    }
  };
  if (window.M3X_NATIVE) console.info("M3X UI ready at " + Math.round(performance.now()) + " ms");
  window.dispatchEvent(new Event("m3x-ready"));
})();
