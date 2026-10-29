(() => {
  "use strict";
  window.M3X_NATIVE = true;
  const boot = JSON.parse(M3XNative.bootstrap());
  localStorage.setItem("m3x-shiny-preview-v1", JSON.stringify(boot.ui));
  localStorage.setItem("m3x-player-tools-v1", JSON.stringify(boot.tools));
  window.nativeCommand = (name, data = {}) => {
    const result = JSON.parse(M3XNative.command(name, JSON.stringify(data)));
    if (result.error && name !== "poll") window.M3X_UI?.toast(result.error);
    return result;
  };
  window.fetch = async (path, options = {}) => {
    if (!options.body) {
      const data = await new Promise((resolve, reject) => {
        const request = new XMLHttpRequest();
        request.open("GET", path.replace(/^\/api\//, "/native-api/"));
        request.onload = () => {
          try { resolve(JSON.parse(request.responseText)); } catch (error) { reject(error); }
        };
        request.onerror = () => reject(new Error("无法读取本地曲库"));
        request.send();
      });
      return { ok: !data.error, status: data.error ? 400 : 200, json: async () => data };
    }
    const data = JSON.parse(M3XNative.request(path, options.body || ""));
    return { ok: !data.error, status: data.error ? 400 : 200, json: async () => data };
  };
  window.structuredClone = value => JSON.parse(JSON.stringify(value));
  for (const prototype of [Element.prototype, Document.prototype, DocumentFragment.prototype]) {
    if (!prototype.append) prototype.append = function (...nodes) {
      for (const node of nodes) this.appendChild(typeof node === "string" ? document.createTextNode(node) : node);
    };
    if (!prototype.replaceChildren) prototype.replaceChildren = function (...nodes) {
      while (this.firstChild) this.removeChild(this.firstChild);
      this.append(...nodes);
    };
  }
  if (!window.ResizeObserver) window.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} };
  if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = function () {};
  if (!window.crypto.randomUUID) window.crypto.randomUUID = () => {
    const values = new Uint8Array(16); crypto.getRandomValues(values);
    return Array.from(values, x => x.toString(16).padStart(2, "0")).join("");
  };
  if (!window.createImageBitmap) window.createImageBitmap = file => new Promise((resolve, reject) => {
    const image = new Image(), url = URL.createObjectURL(file);
    image.onload = () => { URL.revokeObjectURL(url); image.close = () => {}; resolve(image); };
    image.onerror = reject; image.src = url;
  });
  function dialogs() {
    document.querySelectorAll("dialog").forEach(dialog => {
      if (dialog.showModal) return;
      Object.defineProperty(dialog, "open", { get() { return this.hasAttribute("open"); } });
      dialog.showModal = function () {
        this._focus = document.activeElement; this.setAttribute("open", "");
        this.querySelector("button, input, select")?.focus();
      };
      dialog.close = function () {
        this.removeAttribute("open"); this.dispatchEvent(new Event("close"));
        this._focus?.focus();
      };
    });
  }
  function install() {
    dialogs();
    const root = document.getElementById("m3x-concept");
    let press = null;
    root.addEventListener("touchstart", event => {
      const button = event.target.closest("button"), point = event.touches[0];
      press = event.touches.length === 1 && button && !button.disabled
        ? { button, id: point.identifier, x: point.clientX, y: point.clientY, time: Date.now() } : null;
    }, { passive: true });
    root.addEventListener("touchmove", event => {
      const point = event.touches[0];
      if (press && (!point || Math.abs(point.clientX - press.x) > 8 || Math.abs(point.clientY - press.y) > 8)) press = null;
    }, { passive: true });
    root.addEventListener("touchcancel", () => { press = null; }, { passive: true });
    root.addEventListener("touchend", event => {
      const tap = press; press = null;
      const point = event.changedTouches[0];
      if (!tap || !point || point.identifier !== tap.id || Date.now() - tap.time > 500
          || tap.button.disabled || event.target.closest("button") !== tap.button) return;
      // The M3X system still delays synthesized mouse clicks, even with touch-action: manipulation.
      event.preventDefault();
      tap.button.dispatchEvent(new MouseEvent("click", {
        bubbles: true, cancelable: true, view: window, detail: 1, clientX: point.clientX, clientY: point.clientY
      }));
    }, { passive: false });
    if (!window.PointerEvent) {
      const stage = document.getElementById("crop-stage");
      if (!stage.setPointerCapture) stage.setPointerCapture = () => {};
      for (const [touch, pointer] of [["touchstart", "pointerdown"], ["touchmove", "pointermove"], ["touchend", "pointerup"], ["touchcancel", "pointercancel"]]) {
        stage.addEventListener(touch, event => {
          const point = event.changedTouches[0]; if (!point) return;
          event.preventDefault();
          const translated = new MouseEvent(pointer, { bubbles: true, clientX: point.clientX, clientY: point.clientY });
          translated.pointerId = point.identifier; stage.dispatchEvent(translated);
        });
      }
    }
    const audio = document.getElementById("audio");
    let snapshot = { paused: true, position: 0, duration: 0 }, source = "", oldId = "", oldPlaying = false, oldReady = false;
    let rate = boot.audio.rate || 1, pitch = boot.audio.preservePitch !== false;
    const send = name => audio.dispatchEvent(new Event(name));
    Object.defineProperties(audio, {
      src: { get: () => source, set: value => { source = String(value); } },
      currentSrc: { get: () => source },
      currentTime: { get: () => snapshot.position || 0, set: value => {
        snapshot.position = Number(value); nativeCommand("seek", { position: Number(value) }); send("seeking"); send("timeupdate");
      } },
      duration: { get: () => snapshot.duration || 0 },
      paused: { get: () => snapshot.paused !== false },
      ended: { get: () => false },
      seeking: { get: () => false },
      readyState: { get: () => snapshot.ready ? 4 : snapshot.duration > 0 ? 1 : 0 },
      error: { get: () => snapshot.error ? { message: snapshot.error } : null },
      playbackRate: { get: () => rate, set: value => { rate = Number(value); nativeCommand("configure", { rate, preservePitch: pitch }); } },
      defaultPlaybackRate: { get: () => rate, set: value => { rate = Number(value); } },
      preservesPitch: { get: () => pitch, set: value => { pitch = !!value; nativeCommand("configure", { rate, preservePitch: pitch }); } }
    });
    audio.load = () => { const id = source.split("/").pop(); if (id) snapshot = nativeCommand("load", { id, play: false }); };
    audio.play = async () => {
      const id = source.split("/").pop();
      snapshot = id && id !== snapshot.id ? nativeCommand("load", { id, play: true }) : nativeCommand("play");
      oldPlaying = !snapshot.paused;
      send(snapshot.paused ? "pause" : "play"); send("timeupdate");
    };
    audio.pause = () => {
      snapshot = nativeCommand("pause"); oldPlaying = !snapshot.paused;
      send(snapshot.paused ? "pause" : "play");
    };
    let pollTimer;
    const poll = () => {
      clearTimeout(pollTimer);
      if (document.hidden) return;
      pollTimer = setTimeout(poll, snapshot.paused ? 1200 : 400);
      const state = nativeCommand("poll");
      if (!state || state.error === "播放服务正在启动") return;
      snapshot = state;
      if (state.id && window.M3X_UI && !window.M3X_UI.tracks.some(track => track.id === state.id)) {
        window.dispatchEvent(new Event("m3x-native-library"));
        return;
      }
      if (state.id && state.id !== oldId) {
        oldId = state.id; source = "native/" + state.id;
        window.dispatchEvent(new CustomEvent("m3x-native-track", { detail: state.id })); send("loadedmetadata");
      }
      if (state.ready && !oldReady) send("canplay");
      oldReady = state.ready;
      if (!state.paused !== oldPlaying) { oldPlaying = !state.paused; send(oldPlaying ? "play" : "pause"); }
      send("timeupdate");
      window.dispatchEvent(new CustomEvent("m3x-native-state", { detail: state }));
    };
    pollTimer = setTimeout(poll, 400);
    document.addEventListener("visibilitychange", poll);
    document.getElementById("m3x-concept").addEventListener("click", event => {
      if (event.target.closest('[data-action="choose-folder"]')) { event.stopImmediatePropagation(); nativeCommand("directories"); }
    }, true);
  }
  install();
})();
