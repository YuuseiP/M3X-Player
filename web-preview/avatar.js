(() => {
  "use strict";
  const input = document.getElementById("avatar-file");
  const dialog = document.getElementById("avatar-dialog");
  const stage = document.getElementById("crop-stage");
  const canvas = document.getElementById("crop-canvas");
  const context = canvas.getContext("2d");
  const slider = document.getElementById("crop-size");
  let image;
  let crop = { x: 0, y: 0, size: 1 };
  let pointer;
  let request = 0;

  function draw() {
    if (!image) return;
    crop.x = Math.max(0, Math.min(image.width - crop.size, crop.x));
    crop.y = Math.max(0, Math.min(image.height - crop.size, crop.y));
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(image, crop.x, crop.y, crop.size, crop.size, 0, 0, canvas.width, canvas.height);
    slider.setAttribute("aria-valuetext", Math.round(crop.size) + " 像素正方形");
  }
  input.addEventListener("change", async () => {
    const file = input.files[0];
    input.value = "";
    if (!file) return;
    const id = ++request;
    if (!["image/jpeg", "image/png", "image/webp", "image/avif"].includes(file.type) || file.size > 25 * 1024 * 1024) {
      window.M3X_UI.toast("请选择小于 25MB 的 JPG、PNG、WebP 或 AVIF 图片");
      return;
    }
    try {
      const decoded = await createImageBitmap(file, { imageOrientation: "from-image" });
      if (id !== request) { decoded.close(); return; }
      image?.close();
      image = decoded;
      const size = Math.min(image.width, image.height);
      crop = { x: (image.width - size) / 2, y: (image.height - size) / 2, size };
      slider.value = 1;
      draw();
      window.M3X_UI.openDialog(dialog);
    } catch {
      window.M3X_UI.toast("无法读取这张图片");
    }
  });
  slider.addEventListener("input", () => {
    if (!image) return;
    const size = Math.min(image.width, image.height) / Number(slider.value);
    crop.x += (crop.size - size) / 2;
    crop.y += (crop.size - size) / 2;
    crop.size = size;
    draw();
  });
  stage.addEventListener("pointerdown", event => {
    if (!image) return;
    stage.setPointerCapture(event.pointerId);
    pointer = { id: event.pointerId, x: event.clientX, y: event.clientY };
  });
  stage.addEventListener("pointermove", event => {
    if (!pointer || pointer.id !== event.pointerId) return;
    const scale = crop.size / stage.getBoundingClientRect().width;
    crop.x -= (event.clientX - pointer.x) * scale;
    crop.y -= (event.clientY - pointer.y) * scale;
    pointer.x = event.clientX; pointer.y = event.clientY;
    draw();
  });
  for (const name of ["pointerup", "pointercancel", "lostpointercapture"]) stage.addEventListener(name, () => { pointer = null; });
  stage.addEventListener("keydown", event => {
    if (!image || !["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
    event.preventDefault();
    const step = crop.size * (event.shiftKey ? 0.05 : 0.01);
    if (event.key === "ArrowLeft") crop.x -= step;
    if (event.key === "ArrowRight") crop.x += step;
    if (event.key === "ArrowUp") crop.y -= step;
    if (event.key === "ArrowDown") crop.y += step;
    draw();
  });
  document.getElementById("save-avatar").addEventListener("click", () => {
    if (!image) return;
    const output = document.createElement("canvas");
    // Store one bounded square JPEG; CSS supplies the circular display mask.
    output.width = output.height = Math.min(384, Math.max(1, Math.round(crop.size)));
    const ctx = output.getContext("2d");
    ctx.fillStyle = "#f5f2eb"; ctx.fillRect(0, 0, output.width, output.height);
    ctx.drawImage(image, crop.x, crop.y, crop.size, crop.size, 0, 0, output.width, output.height);
    const data = output.toDataURL("image/jpeg", 0.78);
    if (window.M3X_UI.saveAvatar(data)) {
      window.M3X_UI.closeDialog(dialog);
      window.M3X_UI.toast("头像已更新");
    }
  });
  dialog.addEventListener("close", () => { image?.close(); image = null; pointer = null; });
})();
