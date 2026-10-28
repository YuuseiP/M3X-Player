"""Assemble exact agent decisions into a read-only index; never parse credits."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PureWindowsPath
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
KINDS = ("character", "group", "artist", "album_artist", "original")
ISSUE_LABELS = {
    "semantic_unresolved": "\u8bed\u4e49\u8bc6\u522b\u4ecd\u6709\u4e0d\u786e\u5b9a\u9879",
    "placeholder_artist": "\u6b4c\u624b\u6807\u7b7e\u662f\u5360\u4f4d\u6587\u672c",
    "missing_artist": "\u7f3a\u5c11\u6b4c\u624b\u6807\u7b7e",
    "missing_album_artist": "\u7f3a\u5c11\u4e13\u8f91\u827a\u672f\u5bb6",
    "source_tags_unavailable": "\u539f\u8bfb\u53d6\u5668\u672a\u66b4\u9732\u6807\u7b7e",
    "album_artist_field_conflict": "\u4e13\u8f91\u827a\u672f\u5bb6\u5b57\u6bb5\u51b2\u7a81",
}


def read_json(path):
    return json.loads(path.read_bytes())


def identity(kind, name, scope=""):
    value = (scope + "\0" if scope else "") + kind + "\0" + name
    return kind + "-" + hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def tag_id(raw):
    return "tag-" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def fields(record):
    yield "artist", record["artist"]
    yield "albumartist", record["albumartist"]
    for key, values in record.get("extra_artist_fields", {}).items():
        yield "extra:" + key, values


def validate_decision(item):
    if item["status"] not in {"resolved", "unresolved", "placeholder"}:
        raise ValueError("Invalid semantic status: " + item["id"])
    if not isinstance(item["reason"], str) or not item["reason"].strip():
        raise ValueError("Missing semantic reason: " + item["id"])
    if not isinstance(item["credits"], list):
        raise ValueError("Expected a credit list: " + item["id"])
    seen = set()
    for credit in item["credits"]:
        if (not isinstance(credit, list) or len(credit) != 2
                or credit[0] not in KINDS[:3] or not isinstance(credit[1], str)
                or not credit[1] or credit[1].strip() != credit[1]):
            raise ValueError("Invalid semantic credit: " + item["id"])
        if tuple(credit) in seen:
            raise ValueError("Duplicate semantic credit: " + item["id"])
        seen.add(tuple(credit))
    if (item["status"] == "placeholder") != (not item["credits"]):
        raise ValueError("Only placeholders may have no credits: " + item["id"])
    if not isinstance(item["sources"], list) or any(
            not isinstance(url, str) or urlsplit(url).scheme not in {"https", "http"}
            or not urlsplit(url).netloc for url in item["sources"]):
        raise ValueError("Invalid source URL: " + item["id"])


def compile_catalogue(manifest, inputs, decisions, profile, review=None, names=None):
    """Check complete agent-authored batches, then attach exact raw strings."""
    name_lookup = {}
    for mapping in names["mappings"] if names else []:
        key = (mapping["kind"], mapping["original"])
        validate_decision({"id": "name:" + mapping["original"], "status": "resolved",
                           "reason": mapping["evidence"], "sources": mapping["sources"],
                           "credits": [[mapping["kind"], mapping["name"]]]})
        if not mapping["sources"] or not mapping["original"] or key in name_lookup:
            raise ValueError("Missing source or duplicate encyclopedia name: " + repr(key))
        name_lookup[key] = mapping
    tags = manifest["tags"]
    expected = {tag["id"]: tag for tag in tags}
    if len(expected) != len(tags) or len(tags) != manifest["tag_count"]:
        raise ValueError("Duplicate or missing manifest tags")
    if len({tag["raw"] for tag in tags}) != len(tags):
        raise ValueError("Duplicate raw tag in manifest")
    checked = set()
    if review:
        checked = set(review["checked_ids"])
        if (len(checked) != len(review["checked_ids"]) or not checked <= expected.keys()
                or not set(review["changed_ids"]) <= checked
                or len(review["decisions"]) != len(checked)
                or {entry["id"] for entry in review["decisions"]} != checked):
            raise ValueError("Invalid targeted semantic review coverage")
    for tag in tags:
        if tag["id"] != tag_id(tag["raw"]):
            raise ValueError("Tag ID does not match its exact source")
    entries, seen = [], set()
    for batch in range(1, manifest["batch_count"] + 1):
        name = f"batch-{batch:02}.json"
        assigned = read_json(inputs / name)
        batch_ids = {tag["id"] for tag in assigned}
        if len(batch_ids) != len(assigned) or any(expected.get(t["id"]) != t for t in assigned):
            raise ValueError("Input batch differs from manifest: " + name)
        answers = read_json(decisions / name)
        if not isinstance(answers, list):
            raise ValueError("Expected an agent decision array: " + name)
        answer_ids = [answer["id"] for answer in answers]
        if len(answer_ids) != len(set(answer_ids)) or set(answer_ids) != batch_ids:
            raise ValueError("Incomplete or duplicate agent decisions: " + name)
        if seen & batch_ids:
            raise ValueError("Overlapping input batches")
        seen.update(batch_ids)
        for answer in answers:
            validate_decision(answer)
            entry = {**answer, "raw": expected[answer["id"]]["raw"],
                     "fields": expected[answer["id"]]["fields"],
                     "batch": name, "model": profile["models_by_batch"][name]}
            changes = [name_lookup[tuple(credit)] for credit in answer["credits"]
                       if tuple(credit) in name_lookup]
            if changes:
                entry["name_changes"] = changes
                entry["credits"] = [list(credit) for credit in dict.fromkeys(
                    (kind, name_lookup[(kind, name)]["name"])
                    if (kind, name) in name_lookup else (kind, name)
                    for kind, name in answer["credits"])]
            if answer["id"] in checked:
                entry["reviewed_by"] = review["model"]
            entries.append(entry)
    if seen != set(expected):
        raise ValueError("Agent batches do not cover the entire manifest")
    return {"schema": 1, "engine": "semantic_exact_lookup",
            "source_sha256": manifest["source_sha256"],
            "entries": sorted(entries, key=lambda item: item["id"]),
            "consistency_review": review}


def absolute_folder(value):
    path = PureWindowsPath(value)
    if not path.is_absolute() or ".." in path.parts or any(":" in p for p in path.parts[1:]):
        raise ValueError("Expected an absolute Windows folder")
    return path


def assemble(source, source_hash, bindings, profiles, catalogues):
    root = absolute_folder(source["summary"]["root"])
    if len(source["files"]) != source["summary"]["total_audio"]:
        raise ValueError("Source count does not match summary")
    if bindings["schema"] != 1:
        raise ValueError("Unsupported binding schema")
    folders = [absolute_folder(b["folder"]) for b in bindings["bindings"]]
    if len(set(folders)) != len(folders):
        raise ValueError("Duplicate folder binding")
    lookups, profile_ids = {}, set()
    for config, profile in profiles.items():
        if (profile["schema"] != 1 or profile["engine"] != "semantic_exact_lookup"
                or not profile["id"] or profile["id"] in profile_ids):
            raise ValueError("Invalid semantic profile")
        profile_ids.add(profile["id"])
        catalogue = catalogues[config]
        if catalogue["source_sha256"] != source_hash:
            raise ValueError("Decisions belong to a different source snapshot")
        lookup = {}
        for entry in catalogue["entries"]:
            validate_decision(entry)
            if entry["raw"] in lookup or entry["id"] != tag_id(entry["raw"]):
                raise ValueError("Duplicate or invalid exact lookup key")
            lookup[entry["raw"]] = entry
        lookups[config] = lookup

    tracks, entities, paths = [], {}, set()

    def register(kind, name, raw, scope, record, track_id):
        key = identity(kind, name, scope)
        if key not in entities:
            entities[key] = {"id": key, "profile": scope or None, "kind": kind, "name": name,
                             "track_ids": set(), "projects": set(), "raw_credits": set(),
                             "methods": ["semantic_agent" if scope else "unchanged"]}
        entity = entities[key]
        entity["track_ids"].add(track_id)
        entity["projects"].add(record["project"])
        entity["raw_credits"].add(raw)
        return key

    for record in source["files"]:
        relative = PureWindowsPath(record["path"])
        if (not relative.parts or relative.drive or relative.root or ".." in relative.parts
                or any(":" in p for p in relative.parts) or relative in paths):
            raise ValueError("Missing, duplicate or escaping source path")
        paths.add(relative)
        for _, values in fields(record):
            if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
                raise ValueError("Expected native tag arrays")
        full = root / relative
        matches = [b for b in bindings["bindings"] if
                   absolute_folder(b["folder"]) == full or absolute_folder(b["folder"]) in full.parents]
        binding = max(matches, key=lambda b: len(absolute_folder(b["folder"]).parts)) if matches else None
        config = binding["config"] if binding else None
        scope = profiles[config]["id"] if config else ""
        track_id = identity("track", record["path"])
        memberships = {kind: set() for kind in KINDS}
        evidence, credits, issues = [], [], set()
        for field, values in fields(record):
            for raw in values:
                if field == "artist":
                    memberships["original"].add(register("original", raw, raw, scope, record, track_id))
                if not config:
                    continue
                if raw not in lookups[config]:
                    raise ValueError("Unclassified tag; obtain an agent decision first: " + repr(raw))
                entry = lookups[config][raw]
                evidence.append({"field": field, **entry})
                if field not in {"artist", "albumartist"}:
                    continue
                for semantic_kind, name in entry["credits"]:
                    kind = semantic_kind if field == "artist" else "album_artist"
                    key = register(kind, name, raw, scope, record, track_id)
                    memberships[kind].add(key)
                    credits.append({"kind": kind, "name": name, "entity_id": key, "raw": raw,
                                    "field": field, "semantic_kind": semantic_kind,
                                    "method": "semantic_agent", "decision_id": entry["id"]})
                if field == "artist":
                    if entry["status"] == "unresolved":
                        issues.add("semantic_unresolved")
                    elif entry["status"] == "placeholder":
                        issues.add("placeholder_artist")
        if record["status"] != "ok":
            issues.add("source_tags_unavailable")
        elif not any(value.strip() for value in record["artist"]):
            issues.add("missing_artist")
        if record["status"] == "ok" and not any(v.strip() for v in record["albumartist"]):
            issues.add("missing_album_artist")
        if "albumartist_alias_conflict" in record.get("flags", []):
            issues.add("album_artist_field_conflict")
        tracks.append({"id": track_id, "profile": scope or None, "source": record,
                       "memberships": {kind: sorted(ids) for kind, ids in memberships.items()},
                       "credits": credits, "semantic_decisions": evidence, "issues": sorted(issues)})
    for entity in entities.values():
        for key in ("track_ids", "projects", "raw_credits"):
            entity[key] = sorted(entity[key])
        entity["track_count"] = len(entity["track_ids"])
    entity_list = sorted(entities.values(), key=lambda e: (e["kind"], -e["track_count"], e["name"]))
    entries = [e for catalogue in catalogues.values() for e in catalogue["entries"]]
    summary = {
        "source_files": len(tracks), "semantic_tag_count": len(entries),
        "semantic_status": dict(Counter(e["status"] for e in entries)),
        "models": dict(Counter(e["model"] for e in entries)),
        "tags_with_sources": sum(bool(e["sources"]) for e in entries),
        "targeted_review_tags": sum(bool(e.get("reviewed_by")) for e in entries),
        "source_status": dict(Counter(t["source"]["status"] for t in tracks)),
        "profile_file_counts": dict(Counter(t["profile"] or "unbound" for t in tracks)),
        "entities_by_kind": dict(Counter(e["kind"] for e in entity_list)),
        "tracks_by_kind": {k: sum(bool(t["memberships"][k]) for t in tracks) for k in KINDS},
        "tracks_with_review_items": sum(bool(t["issues"]) for t in tracks),
        "issue_file_counts": dict(Counter(i for t in tracks for i in t["issues"])),
    }
    result = {"schema": 2, "engine": "semantic_exact_lookup",
              "generated_at": datetime.now(timezone.utc).isoformat(),
              "source_root": source["summary"]["root"], "source_sha256": source_hash,
              "source_generated_at": source["summary"]["generated_at"],
              "folder_bindings": bindings, "profiles": profiles, "summary": summary,
              "issue_labels": ISSUE_LABELS, "entities": entity_list, "tracks": tracks}
    verify(source, result)
    return result


def verify(source, result):
    if [t["source"] for t in result["tracks"]] != source["files"]:
        raise AssertionError("Source records changed")
    if len({t["id"] for t in result["tracks"]}) != len(result["tracks"]):
        raise AssertionError("Duplicate track ID")
    entities = {e["id"]: e for e in result["entities"]}
    reverse = defaultdict(set)
    for track in result["tracks"]:
        for kind, ids in track["memberships"].items():
            if len(ids) != len(set(ids)):
                raise AssertionError("Duplicate membership")
            for key in ids:
                if entities[key]["kind"] != kind or entities[key]["profile"] != track["profile"]:
                    raise AssertionError("Membership kind/scope mismatch")
                reverse[key].add(track["id"])
    if any(set(e["track_ids"]) != reverse[e["id"]] for e in entities.values()):
        raise AssertionError("Index disagreement")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    cli.add_argument("--bindings", type=Path, default=HERE / "folder-bindings.json")
    cli.add_argument("--output", type=Path, default=HERE / "output")
    args = cli.parse_args()
    original = args.source.read_bytes()
    source_hash = hashlib.sha256(original).hexdigest()
    source, bindings = json.loads(original), read_json(args.bindings)
    profiles, catalogues, destinations = {}, {}, {}
    for binding in bindings["bindings"]:
        config = binding["config"]
        if config in profiles:
            continue
        profile_path = args.bindings.parent / config
        profile = read_json(profile_path)
        manifest = read_json(profile_path.parent / "input" / "manifest.json")
        if manifest["source_sha256"] != source_hash:
            raise ValueError("Source changed; obtain fresh semantic decisions")
        catalogue = compile_catalogue(manifest, profile_path.parent / "input",
                                      profile_path.parent / "decisions", profile,
                                      read_json(profile_path.parent / profile["consistency_review"])
                                      if profile.get("consistency_review") else None,
                                      read_json(profile_path.parent / profile["names"])
                                      if profile.get("names") else None)
        expected_raw = {raw for record in source["files"] for _, values in fields(record) for raw in values}
        if {entry["raw"] for entry in catalogue["entries"]} != expected_raw:
            raise ValueError("Manifest does not cover all source artist-related fields")
        profiles[config], catalogues[config] = profile, catalogue
        destinations[config] = profile_path.parent / profile["catalogue"]
    result = assemble(source, source_hash, bindings, profiles, catalogues)
    if args.source.read_bytes() != original:
        raise RuntimeError("Source snapshot changed during assembly")
    for config, catalogue in catalogues.items():
        write_json(destinations[config], catalogue)
    write_json(args.output / "classification.json", result)
    verify(source, read_json(args.output / "classification.json"))
    report = ["# Semantic Classification / \u8bed\u4e49\u5206\u7c7b", "",
              "All judgments are saved agent decisions; assembly performs exact lookup only.",
              "Missing/new tags stop assembly; there is no parser fallback.",
              "Source SHA-256: `" + source_hash + "`", "", "```json",
              json.dumps(result["summary"], ensure_ascii=False, indent=2), "```", "",
              "## Unresolved tags / \u672a\u786e\u8ba4\u7f72\u540d", ""]
    for catalogue in catalogues.values():
        for entry in catalogue["entries"]:
            if entry["status"] == "unresolved":
                report.append("- " + json.dumps(entry["raw"], ensure_ascii=False) + ": " + entry["reason"])
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "semantic-report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print("All source records preserved; complete agent decisions and bidirectional indexes verified.")


if __name__ == "__main__":
    main()
