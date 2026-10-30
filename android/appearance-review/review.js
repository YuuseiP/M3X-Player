"use strict";
const $ = id => document.getElementById(id);
const copy = value => JSON.parse(JSON.stringify(value));
const state = {run: "", session: null, pages: [], draft: null, dirty: false, edits: 0, saving: null,
  timer: null, conflict: false, theme: "all", marked: false, active: null, selected: null, tool: "pen", history: new Map()};
let image = null, imageSequence = 0, gesture = null;
const names = {all:"歌曲", characters:"角色", groups:"组合", artists:"歌手", albums:"专辑", folders:"文件夹"};
const toolNames = {pen:"画笔", rect:"方框", arrow:"箭头", pin:"标记"};
function title(page) {
  return page.title.replace(/(all|characters|groups|artists|albums|folders)$/, value => names[value]);
}
function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = text;
  return element;
}
function key() { return "m3x-appearance-draft:" + state.run; }
function payload() {
  return {revision: state.draft.revision, globalNote: state.draft.globalNote, pages: state.draft.pages};
}
function removeBackup() { try { localStorage.removeItem(key()); } catch {} }
function readBackup() { try { return localStorage.getItem(key()); } catch { return null; } }
function contentKey(draft) {
  const pages = {};
  for (const id of Object.keys(draft.pages).sort()) {
    const page = draft.pages[id];
    if (page.note.trim() || page.annotations.length) pages[id] = {sourceSha256:page.sourceSha256,note:page.note,
      annotations:page.annotations.map(({id,type,color,width,points,text})=>({id,type,color,width,points,text}))};
  }
  return JSON.stringify({globalNote:draft.globalNote,pages});
}
function storeBackup() {
  try { localStorage.setItem(key(), JSON.stringify({baseRevision:state.draft.revision, payload:payload(), updatedAt:Date.now()})); }
  catch { showError("浏览器临时备份空间不足。请点击保存到本地，确认保存成功后再关闭。", false); }
}
function status(message, kind = "") {
  $("save-status").textContent = message; $("save-status").dataset.state = kind;
  $("editor-save-status").textContent = message;
}
function showError(message, conflict = false) {
  $("error").hidden = false; $("error-text").textContent = message;
  $("reload-draft").hidden = !conflict; $("retry").hidden = conflict;
  if (conflict) state.conflict = true;
  status("尚未保存到本地", "error");
}
function change() {
  state.dirty = true; state.edits++; storeBackup();
  status("有修改，等待保存…");
  clearTimeout(state.timer); state.timer = setTimeout(() => void saveNow(), 900);
  updateCard(state.active);
  $("global-indicator").textContent = state.draft.globalNote.trim() ? "已填写" : "＋";
}
async function api(url, options = {}) {
  const response = await fetch(url, {cache:"no-store", ...options});
  const data = await response.json();
  if (!response.ok) { const error = new Error(data.error || "无法连接本机保存服务"); error.conflict = response.status === 409; throw error; }
  return data;
}
async function saveNow() {
  clearTimeout(state.timer);
  if (!state.draft || state.conflict) return false;
  if (state.saving) { const ok = await state.saving; return ok && state.dirty ? saveNow() : ok; }
  if (!state.dirty) return true;
  const snapshot = JSON.stringify(payload()), generation = state.edits;
  status("正在保存到本机…");
  state.saving = (async () => {
    try {
      const saved = await api("/api/draft?run=" + encodeURIComponent(state.run), {method:"POST",
        headers:{"Content-Type":"application/json", "X-Review-Token":state.session.token}, body:snapshot});
      state.draft.revision = saved.revision; state.draft.savedAt = saved.savedAt;
      state.dirty = state.edits !== generation;
      if (state.dirty) storeBackup(); else removeBackup();
      $("error").hidden = true;
      status(state.dirty ? "继续保存新修改…" : "已保存到本地 · " + new Date(saved.savedAt).toLocaleTimeString("zh-CN", {hour12:false}), "saved");
      return true;
    } catch (error) {
      showError(error.message || "保存服务已断开，请重启服务后重试；草稿保留在此浏览器。", error.conflict);
      return false;
    } finally { state.saving = null; }
  })();
  const ok = await state.saving;
  if (ok && state.dirty) return saveNow();
  return ok;
}
function entry(page = state.active) {
  if (!page) return null;
  return state.draft.pages[page.id] ||= {sourceSha256:page.sha256, note:"", annotations:[]};
}
function hasNotes(page) {
  const data = state.draft.pages[page.id];
  return Boolean(data && (data.note.trim() || data.annotations.length));
}
function matches(page) {
  const query = $("search").value.trim().toLowerCase(), data = state.draft.pages[page.id];
  return (state.theme === "all" || page.theme === state.theme) && (!state.marked || hasNotes(page)) &&
    (!query || [title(page), page.id, data?.note || "", ...(data?.annotations || []).map(a => a.text)].join(" ").toLowerCase().includes(query));
}
function screenshot(page) { return "/screenshots/" + encodeURIComponent(state.run) + "/" + encodeURIComponent(page.file); }
function updateCount() {
  const shown = state.pages.filter(matches).length, marked = state.pages.filter(hasNotes).length;
  $("count").textContent = `${shown} / ${state.pages.length} 张 · ${marked} 张有批注`;
}
function drawMark(context, mark, number, width, height, sourceWidth = state.active?.width || width) {
  const points = mark.points.map(([x,y]) => [x*width,y*height]);
  const factor = width / sourceWidth;
  context.save(); context.strokeStyle = mark.color; context.fillStyle = mark.color;
  context.lineWidth = mark.width * factor; context.lineJoin = "round"; context.lineCap = "round";
  const [[x,y]] = points;
  if (mark.type === "pen") {
    context.beginPath(); context.moveTo(x,y); for (const [px,py] of points) context.lineTo(px,py); context.stroke();
    if (points.length === 1) { context.beginPath(); context.arc(x,y,context.lineWidth/2,0,Math.PI*2); context.fill(); }
  } else if (mark.type === "rect") {
    context.strokeRect(x,y,points[1][0]-x,points[1][1]-y);
  } else if (mark.type === "arrow") {
    const [ex,ey] = points[1], angle = Math.atan2(ey-y,ex-x), length = Math.max(18*factor,context.lineWidth*4);
    context.beginPath(); context.moveTo(x,y); context.lineTo(ex,ey); context.stroke();
    context.beginPath(); context.moveTo(ex,ey); context.lineTo(ex-length*Math.cos(angle-.45),ey-length*Math.sin(angle-.45));
    context.lineTo(ex-length*Math.cos(angle+.45),ey-length*Math.sin(angle+.45)); context.closePath(); context.fill();
  }
  if (number) {
    const radius = Math.max(15*factor, width < 400 ? 7 : 15), bx = Math.min(Math.max(x,radius+1),width-radius-1), by = Math.min(Math.max(y,radius+1),height-radius-1);
    context.beginPath(); context.arc(bx,by,radius,0,Math.PI*2); context.fill(); context.strokeStyle = "white"; context.lineWidth = Math.max(1,2*factor); context.stroke();
    context.fillStyle = "white"; context.font = `${Math.max(9,18*factor)}px Segoe UI,sans-serif`; context.textAlign = "center"; context.textBaseline = "middle"; context.fillText(String(number),bx,by);
  }
  context.restore();
}
function updateCard(page) {
  if (!page) { if (state.draft) updateCount(); return; }
  const card = document.getElementById("card-" + page.id);
  if (!card) return;
  const data = state.draft.pages[page.id];
  const badge = card.querySelector(".annotation-badge"), note = card.querySelector(".card-note"), overlay = card.querySelector("canvas");
  badge.hidden = !hasNotes(page); badge.textContent = data?.annotations.length ? `${data.annotations.length} 处批注` : "有备注";
  note.textContent = data?.note || data?.annotations.find(a => a.text.trim())?.text || "点击打开并批注";
  const context = overlay.getContext("2d"); context.clearRect(0,0,overlay.width,overlay.height);
  for (const [index,mark] of (data?.annotations || []).entries()) {
    // Thumbnail coordinates use the source image dimensions, independent of the open editor.

    drawMark(context,mark,index+1,overlay.width,overlay.height,page.width);
  }
  updateCount();
}
function renderGrid() {
  $("grid").replaceChildren();
  for (const page of state.pages.filter(matches)) {
    const card = node("button","card"); card.id = "card-" + page.id; card.setAttribute("aria-label", `批注：${title(page)} · ${page.theme === "dark" ? "深色" : "浅色"}`);
    const preview = node("div","card-image"), img = node("img"); img.src = screenshot(page); img.alt = title(page); img.loading = "lazy"; img.decoding = "async";
    const canvas = node("canvas"); canvas.width = 240; canvas.height = Math.round(240*page.height/page.width);
    preview.append(img,canvas,node("span","card-label",page.theme === "dark" ? "深色" : "浅色"),node("span","annotation-badge"));
    const info = node("div","card-info"), heading = node("div","card-title"); heading.append(node("span","",title(page)),node("span","","↗"));
    info.append(heading,node("span","card-id",page.id),node("p","card-note")); card.append(preview,info);
    card.addEventListener("click",() => void openPage(page)); $("grid").append(card); updateCard(page);
  }
  $("empty").hidden = $("grid").children.length > 0; updateCount();
}
async function loadRun(run) {
  if (state.dirty && !await saveNow()) { $("run").value = state.run; return; }
  const session = await api("/api/session?run=" + encodeURIComponent(run));
  state.run = run; state.session = session; state.draft = session.draft; state.conflict = false; state.dirty = false; state.history.clear();
  state.pages = session.manifest.pages.filter(p => p.status === "captured");
  const saved = readBackup();
  if (saved) {
    try {
      const local = JSON.parse(saved);
      if (contentKey(local.payload) === contentKey(state.draft)) {
        // A final save may reach disk while its response is interrupted by navigation.
        removeBackup(); status("已读取本地草稿 · 修订 " + state.draft.revision, "saved");
      } else if (local.baseRevision === state.draft.revision) {
        state.draft.pages = local.payload.pages; state.draft.globalNote = local.payload.globalNote;
        state.dirty = true; state.edits++; state.timer = setTimeout(() => void saveNow(),900);
        status("已恢复浏览器中的未保存草稿");
      } else {
        state.draft.pages = local.payload.pages; state.draft.globalNote = local.payload.globalNote; state.dirty = true;
        showError("浏览器保留了较早的未保存草稿，磁盘已有更新。请备份后加载磁盘草稿。", true);
      }
    } catch { showError("浏览器临时备份损坏，已载入磁盘草稿。"); }
  } else status(state.draft.savedAt ? "已读取本地草稿 · 修订 " + state.draft.revision : "本地草稿已就绪", "saved");
  $("global-note").value = state.draft.globalNote; $("global-indicator").textContent = state.draft.globalNote.trim() ? "已填写" : "＋";
  const meta = session.manifest;
  $("device").textContent = `${meta.device} · Android ${meta.android} · ${meta.version} · ${state.pages.length} 张`;
  $("draft-path").textContent = `android/captures/${run}/review/draft.json`;
  $("unavailable-list").replaceChildren();
  for (const page of meta.pages.filter(p => p.status !== "captured")) $("unavailable-list").append(node("li","",`${page.id}：${page.reason}`));
  $("unavailable").hidden = !$("unavailable-list").children.length;
  $("run").value = run; history.replaceState(null,"","/?run="+encodeURIComponent(run)); renderGrid();
}
function historyFor() {
  if (!state.history.has(state.active.id)) state.history.set(state.active.id,{undo:[],redo:[]});
  return state.history.get(state.active.id);
}
function remember() { const h = historyFor(); h.undo.push(copy(entry().annotations)); if (h.undo.length > 80) h.undo.shift(); h.redo = []; }
function historyStep(redo) {
  const h = historyFor(), from = redo ? h.redo : h.undo, to = redo ? h.undo : h.redo;
  if (!from.length) return;
  to.push(copy(entry().annotations)); entry().annotations = from.pop(); state.selected = entry().annotations.at(-1)?.id || null;
  change(); renderAnnotations(); draw();
}
function renderAnnotations() {
  const data = entry(), marks = data.annotations;
  $("annotation-count").textContent = `${marks.length} 处标注`; $("annotation-list").replaceChildren();
  for (const [index,mark] of marks.entries()) {
    const row = node("button","annotation-row"); row.setAttribute("aria-pressed",String(mark.id === state.selected)); row.dataset.annotation = mark.id;
    const number = node("span","annotation-number",String(index+1)); number.style.backgroundColor = mark.color;
    row.append(number,node("span","annotation-preview",mark.text || toolNames[mark.type]+" · 待填写要求"));
    row.addEventListener("click",()=>{state.selected = mark.id; renderAnnotations(); draw();}); $("annotation-list").append(row);
  }
  $("annotation-empty").hidden = marks.length > 0;
  const selected = marks.find(a => a.id === state.selected);
  $("annotation-editor").hidden = !selected;
  if (selected) {
    $("annotation-label").textContent = `标注 ${marks.indexOf(selected)+1} · ${toolNames[selected.type]} 的修改要求`;
    if (document.activeElement !== $("annotation-text")) $("annotation-text").value = selected.text;
  }
  $("undo").disabled = !historyFor().undo.length; $("redo").disabled = !historyFor().redo.length;
}
function fitCanvas() {
  if (!state.active || !$("editor").open) return;
  const bounds = $("stage").getBoundingClientRect(), page = state.active;
  const factor = $("zoom").value === "fit" ? Math.min((bounds.width-48)/page.width,(bounds.height-48)/page.height,1) : Number($("zoom").value);
  $("canvas-wrap").style.width = Math.max(100,Math.round(page.width*factor))+"px";
}
function draw() {
  if (!image || !state.active) return;
  const canvas = $("canvas"), context = canvas.getContext("2d"), page = state.active;
  context.clearRect(0,0,canvas.width,canvas.height); context.drawImage(image,0,0,canvas.width,canvas.height);
  for (const [index,mark] of entry().annotations.entries()) drawMark(context,mark,index+1,page.width,page.height);
  if (gesture) drawMark(context,gesture,entry().annotations.length+1,page.width,page.height);
  const selected = entry().annotations.find(a => a.id === state.selected);
  if (selected) {
    const xs = selected.points.map(p => p[0]*page.width), ys = selected.points.map(p => p[1]*page.height);
    context.save(); context.strokeStyle = "#385bd7"; context.lineWidth = 1.5; context.setLineDash([5,5]);
    context.strokeRect(Math.min(...xs)-20,Math.min(...ys)-20,Math.max(...xs)-Math.min(...xs)+40,Math.max(...ys)-Math.min(...ys)+40); context.restore();
  }
}
async function openPage(page) {
  const sequence = ++imageSequence; state.active = page; state.selected = null; gesture = null; image = null;
  $("editor-title").textContent = title(page); $("editor-id").textContent = page.id + " · " + (page.theme === "dark" ? "深色" : "浅色");
  $("dimensions").textContent = `${page.width} × ${page.height} · 真机原图`;
  $("page-note").value = entry().note; $("zoom").value = "fit";
  const visible = state.pages.filter(matches), index = visible.findIndex(p => p.id === page.id);
  $("position").textContent = `${index+1} / ${visible.length}`; $("previous").disabled = index <= 0; $("next").disabled = index >= visible.length-1;
  if (!$("editor").open) $("editor").showModal();
  renderAnnotations();
  $("canvas").width = page.width; $("canvas").height = page.height; fitCanvas();
  const nextImage = new Image(); nextImage.src = screenshot(page);
  try { await nextImage.decode(); if (sequence !== imageSequence) return; image = nextImage; draw(); fitCanvas(); }
  catch { showError("无法加载原始截图，请检查本机服务和截图文件。"); }
}
function navigate(delta) {
  const visible = state.pages.filter(matches), index = visible.findIndex(p => p.id === state.active.id);
  if (visible[index+delta]) void openPage(visible[index+delta]);
}
function pointer(event) {
  const r = $("canvas").getBoundingClientRect();
  return [Math.min(1,Math.max(0,(event.clientX-r.left)/r.width)),Math.min(1,Math.max(0,(event.clientY-r.top)/r.height))];
}
$("canvas").addEventListener("pointerdown", event => {
  if (event.button !== 0 || !image || gesture) return;
  event.preventDefault(); $("canvas").setPointerCapture(event.pointerId);
  const point = pointer(event);
  gesture = {id:crypto.randomUUID(),type:state.tool,color:$("color").value,width:Number($("width").value),points:state.tool === "rect" || state.tool === "arrow" ? [point,point] : [point],text:"",pointerId:event.pointerId};
  draw();
});
$("canvas").addEventListener("pointermove", event => {
  if (!gesture || gesture.pointerId !== event.pointerId) return;
  const point = pointer(event);
  if (gesture.type === "pen" && gesture.points.length < 2999) {
    const previous = gesture.points.at(-1);
    if (Math.hypot(point[0]-previous[0],point[1]-previous[1]) > .0005) gesture.points.push(point);
  } else if (gesture.type === "rect" || gesture.type === "arrow") gesture.points[1] = point;
  draw();
});
$("canvas").addEventListener("pointerup", event => {
  if (!gesture || gesture.pointerId !== event.pointerId) return;
  const mark = gesture; gesture = null; delete mark.pointerId;
  if (mark.type === "rect" || mark.type === "arrow") {
    mark.points[1] = pointer(event);
    if (Math.hypot(mark.points[0][0]-mark.points[1][0],mark.points[0][1]-mark.points[1][1]) < .003) { draw(); return; }
  }
  remember(); entry().annotations.push(mark); state.selected = mark.id; change(); renderAnnotations(); draw();
});
$("canvas").addEventListener("pointercancel",()=>{gesture = null; draw();});
$("annotation-text").addEventListener("input",()=>{
  const mark = entry().annotations.find(a => a.id === state.selected); if (!mark) return;
  mark.text = $("annotation-text").value; change();
  const row = $("annotation-list").querySelector(`[data-annotation="${mark.id}"] .annotation-preview`);
  if (row) row.textContent = mark.text || toolNames[mark.type]+" · 待填写要求";
});
$("page-note").addEventListener("input",()=>{entry().note = $("page-note").value; change();});
$("global-note").addEventListener("input",()=>{state.draft.globalNote = $("global-note").value; change();});
$("delete-annotation").addEventListener("click",()=>{remember(); entry().annotations = entry().annotations.filter(a => a.id !== state.selected); state.selected = null; change(); renderAnnotations(); draw();});
$("undo").addEventListener("click",()=>historyStep(false)); $("redo").addEventListener("click",()=>historyStep(true));
$("tools").addEventListener("click",event=>{const button = event.target.closest("[data-tool]"); if (!button) return; state.tool = button.dataset.tool; for (const b of $("tools").querySelectorAll("button")) b.setAttribute("aria-pressed",String(b === button));});
$("theme-tabs").addEventListener("click",event=>{const button = event.target.closest("[data-theme]"); if (!button) return; state.theme = button.dataset.theme; for (const b of $("theme-tabs").querySelectorAll("button")) b.setAttribute("aria-pressed",String(b === button)); renderGrid();});
$("only-marked").addEventListener("click",()=>{state.marked = !state.marked; $("only-marked").setAttribute("aria-pressed",String(state.marked)); renderGrid();});
$("search").addEventListener("input",renderGrid);
$("save").addEventListener("click",()=>void saveNow()); $("editor-save").addEventListener("click",()=>void saveNow()); $("retry").addEventListener("click",()=>void saveNow());
$("close-editor").addEventListener("click",()=>$("editor").close());
$("editor").addEventListener("close",()=>{gesture = null; imageSequence++; void saveNow(); renderGrid();});
$("previous").addEventListener("click",()=>navigate(-1)); $("next").addEventListener("click",()=>navigate(1));
$("zoom").addEventListener("change",fitCanvas); new ResizeObserver(fitCanvas).observe($("stage"));
function downloadBackup() {
  if (!state.draft) return;
  const blob = new Blob([JSON.stringify({...state.draft,...payload()},null,2)],{type:"application/json"}), url = URL.createObjectURL(blob);
  const link = node("a"); link.href = url; link.download = state.run+"-draft-backup.json"; link.click(); setTimeout(()=>URL.revokeObjectURL(url),1000);
}
$("backup").addEventListener("click",downloadBackup);
$("reload-draft").addEventListener("click",()=>{downloadBackup(); removeBackup(); state.dirty = false; location.reload();});
$("run").addEventListener("change",()=>void loadRun($("run").value).catch(error=>showError(error.message)));
$("reload-draft").textContent = "备份并加载磁盘草稿";
document.addEventListener("keydown",event=>{
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") { event.preventDefault(); void saveNow(); return; }
  if (!$("editor").open || /INPUT|TEXTAREA|SELECT/.test(event.target.tagName)) return;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "z") {event.preventDefault(); historyStep(event.shiftKey); return;}
  const tool = {p:"pen",r:"rect",a:"arrow",n:"pin"}[event.key.toLowerCase()];
  if (tool) $("tools").querySelector(`[data-tool="${tool}"]`).click();
  if (event.key === "ArrowLeft") navigate(-1); if (event.key === "ArrowRight") navigate(1);
});
window.addEventListener("beforeunload",event=>{if(state.dirty){storeBackup(); event.preventDefault(); event.returnValue = "";}});
document.addEventListener("visibilitychange",()=>{if(document.hidden && state.dirty) void saveNow();});
async function initialize() {
  const result = await api("/api/runs");
  for (const run of result.runs) { const option = node("option","",run.id.replace("appearance-","")+" · "+run.count+" 张"); option.value = run.id; $("run").append(option); }
  const requested = new URLSearchParams(location.search).get("run"), selected = result.runs.some(r => r.id === requested) ? requested : result.defaultRun;
  await loadRun(selected);
}
void initialize().catch(error=>{showError(error.message); $("save").disabled = true;});
