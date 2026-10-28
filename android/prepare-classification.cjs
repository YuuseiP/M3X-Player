const crypto = require("node:crypto");

function compileClassification(raw, edits) {
  const baseline = crypto.createHash("sha256").update(raw).digest("hex");
  const data = JSON.parse(raw);
  if (data.schema !== 1) throw new Error("Unsupported classification schema");
  if (edits && (edits.schema !== 1 || edits.baseline !== baseline)) throw new Error("Classification draft does not match the baseline");
  const kinds = new Set(["character", "group", "artist"]);
  const entities = data.entities.filter(item => kinds.has(item.kind)).map(item => ({
    ...item, ...edits?.entities?.[item.id]
  }));
  const ids = new Set(entities.map(item => item.id));
  const allIds = new Set(data.entities.map(item => item.id));
  for (const item of entities) {
    if (!kinds.has(item.kind) || !item.name.trim() || item.name.length > 120) throw new Error("Invalid classification entity");
  }
  const paths = new Set();
  const tracks = data.tracks.map(item => {
    const path = item.path.replace(/\\/g, "/");
    if (!path || path.startsWith("/") || path.includes(":") || path.split("/").includes("..") || paths.has(path))
      throw new Error("Invalid or duplicate classification path");
    paths.add(path);
    const memberships = edits?.memberships?.[item.id] || item.memberships;
    if (!Array.isArray(memberships) || memberships.some(id => !allIds.has(id)))
      throw new Error("Invalid classification membership");
    return { path, bytes: item.bytes, artist: item.artist, memberships: memberships.filter(id => ids.has(id)) };
  });
  // Only exact, unambiguous original tags are reusable; never infer or split names.
  const tags = new Map();
  for (const item of tracks) {
    if (!item.artist.trim()) continue;
    const previous = tags.get(item.artist);
    if (previous === null) continue;
    if (previous && [...previous].sort().join("\0") !== [...item.memberships].sort().join("\0")) tags.set(item.artist, null);
    else tags.set(item.artist, item.memberships);
  }
  return {
    schema: 1, baseline, folderName: "THE_IDOLMASTER", namePolicy: data.name_policy,
    nameSources: data.name_sources, entities, tracks,
    tags: Object.fromEntries([...tags].filter(([, memberships]) => memberships !== null))
  };
}

module.exports = { compileClassification };
