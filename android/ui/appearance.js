/* Local, bounded cover sampling. No network service or Android dynamic-color API. */
(function (global) {
  "use strict";
  const fallback = 26;
  function hueFromPixels(data) {
    const bins = new Array(36).fill(0), sums = new Array(36).fill(0);
    for (let i = 0; i < data.length; i += 4) {
      if (data[i + 3] < 128) continue;
      const r = data[i] / 255, g = data[i + 1] / 255, b = data[i + 2] / 255;
      const hi = Math.max(r, g, b), lo = Math.min(r, g, b), delta = hi - lo;
      if (delta < .12 || hi < .18 || lo > .88) continue;
      let h = (hi === r ? (g - b) / delta : hi === g ? (b - r) / delta + 2 : (r - g) / delta + 4) * 60;
      h = (h + 360) % 360;
      const bin = Math.floor(h / 10), weight = delta * (1 - Math.abs((hi + lo) / 2 - .5));
      bins[bin] += weight; sums[bin] += h * weight;
    }
    const best = bins.indexOf(Math.max(...bins));
    return bins[best] > 0 ? Math.round(sums[best] / bins[best]) : fallback;
  }
  function palette(hue, dark) {
    const h = Number.isFinite(hue) ? hue : fallback;
    const color = (s, l) => `hsl(${h}, ${s}%, ${l}%)`;
    return dark ? { bg: color(12, 10), fg: color(12, 94), muted: color(12, 72), surface: color(14, 16), border: color(15, 28), accent: color(55, 77) }
      : { bg: color(28, 97), fg: color(20, 13), muted: color(15, 36), surface: color(28, 91), border: color(18, 77), accent: color(55, 26) };
  }
  if (typeof module !== "undefined" && module.exports) module.exports = { hueFromPixels, palette };
  if (!global.document) return;
  const cache = new Map();
  let request = 0, currentKey = "", currentHue = fallback, options, currentTrack;
  const system = global.matchMedia("(prefers-color-scheme: dark)");
  function paint() {
    const screen = document.querySelector(".m3x-screen");
    const dark = options.theme === "dark" || (options.theme === "system" && system.matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    document.documentElement.style.colorScheme = dark ? "dark" : "light";
    screen.dataset.visualTheme = options.visualTheme;
    const endfield = options.visualTheme === "endfield";
    const colors = endfield ? (dark ? { bg: "#161817", fg: "#f3f3e9", muted: "#b2b5aa", surface: "#262923", border: "#505347", accent: "#ecf22a" }
      : { bg: "#f3f3e9", fg: "#1d211b", muted: "#575e50", surface: "#e6e7da", border: "#b9bdb0", accent: "#4e5515" })
      : options.colorMode === "cover" ? palette(currentHue, dark) : null;
    for (const key of ["bg", "fg", "muted", "surface", "border", "accent"]) {
      if (colors) screen.style.setProperty("--m3x-" + key, colors[key]);
      else screen.style.removeProperty("--m3x-" + key);
    }
    const bg = colors ? colors.bg : dark ? "#191a18" : "#f5f2eb";
    const bar = document.createElement("canvas").getContext("2d");
    bar.fillStyle = bg;
    if (global.nativeCommand && paint.lastBar !== bar.fillStyle + dark) {
      paint.lastBar = bar.fillStyle + dark;
      global.nativeCommand("appearance-bars", { color: bar.fillStyle, dark });
    }
  }
  function apply(state, track) {
    options = { theme: state.theme, visualTheme: state.visualTheme, colorMode: state.colorMode }; currentTrack = track;
    const key = options.visualTheme !== "endfield" && options.colorMode === "cover" ? track?.cover || "" : "";
    if (key !== currentKey) {
      currentKey = key; const token = ++request;
      currentHue = cache.has(key) ? cache.get(key) : fallback;
      if (key && !cache.has(key)) {
        const image = new Image();
        image.onload = () => {
          let hue = fallback;
          try {
            const canvas = document.createElement("canvas"); canvas.width = canvas.height = 24;
            const ctx = canvas.getContext("2d"); ctx.drawImage(image, 0, 0, 24, 24);
            hue = hueFromPixels(ctx.getImageData(0, 0, 24, 24).data);
          } catch (_) { /* Missing or unreadable covers keep the neutral fallback. */ }
          cache.set(key, hue); if (cache.size > 48) cache.delete(cache.keys().next().value);
          if (token === request) { currentHue = hue; paint(); }
        };
        image.onerror = () => { cache.set(key, fallback); if (token === request) { currentHue = fallback; paint(); } };
        image.src = key;
      }
    }
    paint();
  }
  const changed = () => { if (options) apply(options, currentTrack); };
  if (system.addEventListener) system.addEventListener("change", changed); else system.addListener(changed);
  global.M3X_APPEARANCE = { apply };
})(typeof window === "undefined" ? globalThis : window);
