const fs = require("node:fs");
const path = require("node:path");
const babel = require("@babel/core");
const root = path.resolve(__dirname, "../web-preview");
const output = path.join(__dirname, "app/src/main/assets/player");
fs.mkdirSync(output, { recursive: true });
const polyfills = ["es.promise", "es.array.from", "es.array.find", "es.array.find-index", "es.array.includes", "es.array.flat", "es.array.flat-map",
  "es.array.iterator", "es.string.iterator", "es.object.assign", "es.object.entries", "es.object.values", "es.object.from-entries",
  "es.object.has-own", "es.string.pad-start", "es.string.pad-end", "es.string.match-all", "es.promise.all-settled", "es.global-this"];
let prelude = "";
const seen = new Set();
// Bundle only the ES polyfills required by the Android 7.1 system WebView.
function moduleSource(file) {
  file = require.resolve(file);
  if (seen.has(file)) return;
  seen.add(file);
  const source = fs.readFileSync(file, "utf8");
  for (const match of source.matchAll(/require\(['"](.+?)['"]\)/g)) moduleSource(require.resolve(match[1], { paths: [path.dirname(file)] }));
}
for (const feature of polyfills) moduleSource("core-js/modules/" + feature);
const ids = new Map([...seen].map((file, index) => [file, index]));
prelude = "(function(){var modules={";
for (const [file, index] of ids) {
  const source = fs.readFileSync(file, "utf8").replace(/require\(['"](.+?)['"]\)/g, (_, name) => `require(${ids.get(require.resolve(name, { paths: [path.dirname(file)] }))})`);
  prelude += index + ":function(module,exports,require){" + source + "},";
}
prelude += "},cache={};function require(id){if(cache[id])return cache[id].exports;var m=cache[id]={exports:{}};modules[id](m,m.exports,require);return m.exports;}";
for (const feature of polyfills) prelude += `require(${ids.get(require.resolve("core-js/modules/" + feature))});`;
prelude += "})();";
fs.writeFileSync(path.join(output, "polyfills.js"), prelude);
for (const name of ["app.js", "avatar.js", "player-tools.js", "native-bridge.js", "native-tools.js"]) {
  const filename = name.startsWith("native-") ? path.join(__dirname, name) : path.join(root, name);
  const result = babel.transformSync(fs.readFileSync(filename, "utf8"), {
    presets: [["@babel/preset-env", { targets: { chrome: "51" }, modules: false }]],
    comments: false, sourceType: "script", compact: false
  });
  fs.writeFileSync(path.join(output, name), result.code);
}
let html = fs.readFileSync(path.join(root, "index.html"), "utf8")
  .replace('<link rel="icon" href="covers/01.jpg">', "")
  .replace('<span class="m3x-caption">0.1</span>', '<span class="m3x-caption">0.2.0</span>')
  .replace('浏览器原生音频', 'ExoPlayer · AudioTrack')
  .replace('<script defer src="tracks.js"></script>', '<script defer src="polyfills.js"></script><script defer src="native-bridge.js"></script><script>window.M3X_TRACKS=[];</script>')
  .replace('<script defer src="player-tools.js"></script>', '<script defer src="player-tools.js"></script><script defer src="native-tools.js"></script>');
fs.writeFileSync(path.join(output, "index.html"), html);
let css = fs.readFileSync(path.join(root, "styles.css"), "utf8");
let darkRules = [];
css = css.replace(/([^{}]+)\{([^{}]*)\}/g, (full, selector, body) => {
  let dark = body.replace(/light-dark\(([^,()]+),\s*([^()]+)\)/g, "$2");
  if (dark !== body && !selector.trim().startsWith("@")) darkRules.push(selector.trim().split(",").map(s => "html[data-theme=dark] " + s.trim()).join(",") + "{" + dark + "}");
  return selector + "{" + body.replace(/light-dark\(([^,()]+),\s*([^()]+)\)/g, "$1") + "}";
});
css += "\n" + darkRules.join("\n") + "\n#m3x-concept{display:flex;justify-content:center;align-items:center;padding:0;min-height:100vh}#m3x-concept .m3x-screen{width:100%;height:100vh;border:0;border-radius:0;box-shadow:none}#m3x-concept dialog{margin:0;border:0;padding:0;background:transparent;display:none}#m3x-concept dialog[open]{display:flex;position:absolute;z-index:10}#m3x-concept .m3x-track-artist{gap:0}#m3x-concept .m3x-track-artist small{margin-left:8px}#m3x-concept .m3x-cover-open{height:240px}#m3x-concept .m3x-screen[data-layout=catalog] .m3x-cover-open{height:80px}";
css += `
#m3x-concept .m3x-sheet-scrim{top:0;left:0;right:0;bottom:0}
#m3x-concept .m3x-sheet-overlay[open]{display:flex;flex-direction:column;justify-content:flex-end;align-items:stretch}
#m3x-concept .m3x-sheet{width:100%;flex-shrink:1}
#m3x-concept .m3x-cover-open{width:240px;max-width:100%}
#m3x-concept .m3x-cover{width:100%;height:100%}
#m3x-concept #crop-stage{width:240px;height:240px;max-width:100%}
#m3x-concept .m3x-crop-circle{top:0;left:0;right:0;bottom:0}
#m3x-concept .m3x-mini-cover{margin-right:10px}
#m3x-concept .m3x-sheet-tabs button{margin-right:20px}
#m3x-concept .m3x-signal div{display:flex;align-items:flex-start}
#m3x-concept .m3x-signal dt{width:66px;flex-shrink:0;margin-right:8px}
#m3x-concept .m3x-signal dd{min-width:0;word-wrap:break-word}
#m3x-concept .m3x-sheet input[type=url],#m3x-concept .m3x-sheet form>input{display:block;width:100%;height:42px;margin:8px 0;padding:8px;border:1px solid var(--m3x-border);border-radius:6px;background:var(--m3x-surface)}
#m3x-concept .m3x-setting input[type=number]{width:90px;height:40px;padding:6px;background:var(--m3x-surface);border:1px solid var(--m3x-border);border-radius:6px}
#m3x-concept .m3x-setting select{font:inherit;max-width:150px;color:var(--m3x-fg);background:var(--m3x-bg)}
#m3x-concept #toast{z-index:50}
#m3x-concept .m3x-filters button{margin-right:18px}
#m3x-concept .m3x-swatch{margin-right:10px}
#m3x-concept .m3x-nav svg{margin-right:7px}
#m3x-concept .m3x-playlist-row img{margin-right:12px}
#m3x-concept .m3x-peq-row{display:flex;align-items:center;padding:6px 0}
#m3x-concept .m3x-peq-row span{width:20px;font-size:10px}
#m3x-concept .m3x-peq-row input{min-width:0;width:24%;margin-left:4px;padding:6px 2px;background:var(--m3x-surface);border:1px solid var(--m3x-border)}
#m3x-concept .m3x-peq-row select{width:54px}
#m3x-concept .m3x-peq-row input[type=checkbox]{width:16px;margin:0 4px}
#m3x-concept #eq-bands{display:block}
#m3x-concept .m3x-eq-band{display:inline-block;width:46%;margin-right:4%}
#m3x-concept .m3x-lyric-offset{display:flex;flex-wrap:wrap}
#m3x-concept .m3x-lyric-offset input{width:100%}
#m3x-concept .m3x-lyric-offset output{margin-left:auto}
@supports not (display:grid){
#m3x-concept .m3x-screen[data-layout=catalog] .m3x-player{display:block}
#m3x-concept .m3x-screen[data-layout=catalog] .m3x-cover-open{float:left;margin-right:12px}
#m3x-concept .m3x-screen[data-layout=catalog] .m3x-song{min-height:80px}
#m3x-concept .m3x-screen[data-layout=catalog] .m3x-player-tools{clear:both}
}`;
css = css.replace(/#([0-9a-f]{8}|[0-9a-f]{4})\b/gi, (_, hex) => {
  if (hex.length === 4) hex = [...hex].map(value => value + value).join("");
  return `rgba(${parseInt(hex.slice(0, 2), 16)},${parseInt(hex.slice(2, 4), 16)},${parseInt(hex.slice(4, 6), 16)},${(parseInt(hex.slice(6), 16) / 255).toFixed(4)})`;
});
fs.writeFileSync(path.join(output, "styles.css"), css);
fs.copyFileSync(path.join(root, "cover-placeholder.svg"), path.join(output, "cover-placeholder.svg"));
fs.cpSync(path.join(root, "fonts"), path.join(output, "fonts"), { recursive: true });
const licenses = fs.readdirSync(path.join(__dirname, "licenses")).filter(name => name.endsWith(".txt"))
  .map(name => name + "\n\n" + fs.readFileSync(path.join(__dirname, "licenses", name), "utf8")).join("\n\n────────────────\n\n");
fs.writeFileSync(path.join(output, "licenses.txt"), licenses);
console.log("Android 7.1 assets prepared:", output);
