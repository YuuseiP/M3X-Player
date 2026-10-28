"""Apply folder-bound artist profiles to a JSON snapshot, never to audio tags."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
import unicodedata


HERE = Path(__file__).resolve().parent
SYNTAX = str.maketrans({
    "\uff08": "(", "\uff09": ")", "\uff3b": "[", "\uff3d": "]",
    "\uff1a": ":", "\uff0e": ".", "\uff0c": ",", "\uff1b": ";",
    "\uff0f": "/", "\uff06": "&",
})
SEPARATORS = re.compile(r"[,、/;&×]|\b(?:feat(?:uring)?|with)\b\.?\s*", re.IGNORECASE)
FROM = re.compile(r"\s+from\s+", re.IGNORECASE)
CV = re.compile(r"([^()\[\]{},、/;&×]+?)\s*\(\s*CV\s*[.:]\s*[^()]+\)",
                re.IGNORECASE)
KINDS = ("character", "group", "artist", "album_artist", "original")
KIND_LABELS = dict(zip(KINDS, (
    "\u89d2\u8272", "\u56e2\u4f53", "\u5176\u4ed6\u7f72\u540d",
    "\u4e13\u8f91\u827a\u672f\u5bb6", "\u539f\u59cb\u7f72\u540d")))
ISSUE_LABELS = {
    "album_artist_field_conflict": "\u4e13\u8f91\u827a\u672f\u5bb6\u5b57\u6bb5\u51b2\u7a81",
    "album_tags_unavailable": "\u672a\u66b4\u9732\u4e13\u8f91\u6807\u7b7e",
    "empty_list_member": "\u540d\u5355\u6709\u7a7a\u9879",
    "group_type_candidate": "\u65b0\u56e2\u540d\u5f85\u52a0\u5165\u914d\u7f6e",
    "missing_album_artist": "\u7f3a\u5c11\u4e13\u8f91\u827a\u672f\u5bb6",
    "missing_artist": "\u7f3a\u5c11\u6b4c\u624b",
    "new_character": "\u660e\u786e CV \u65b0\u89d2\u8272\uff0c\u672a\u5199\u5165\u56fa\u5b9a\u540d\u5355",
    "placeholder_artist": "\u5360\u4f4d\u6587\u672c\uff0c\u4e0d\u751f\u6210\u4eba\u7269",
    "possible_truncated_name": "\u7591\u4f3c\u622a\u65ad\u59d3\u540d\uff0c\u4e0d\u8865\u731c",
    "source_tags_unavailable": "\u539f\u8bfb\u53d6\u5668\u672a\u66b4\u9732\u6807\u7b7e",
    "unbalanced_brackets": "\u62ec\u53f7\u4e0d\u95ed\u5408",
    "unresolved_group_members": "\u56e2\u540d\u4fdd\u7559\uff0c\u4e0d\u63a8\u65ad\u672a\u786e\u8ba4\u6210\u5458",
    "unresolved_parenthesis": "\u672a\u77e5\u62ec\u53f7\u7f72\u540d\u4fdd\u7559",
    "untyped_list_member": "\u540d\u5355\u542b\u672a\u786e\u8ba4\u7f72\u540d",
}


def identity(kind, name, scope=""):
    value = kind + "\0" + name
    if scope:
        value = scope + "\0" + value
    return kind + "-" + hashlib.sha256(value.encode()).hexdigest()[:16]


def candidate_key(name):
    """Match only against a profile's frozen character catalogue."""
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", name))


def split_top(text, separators, protected=()):
    """Split outside matched brackets, retaining source slices and empty tokens."""
    syntax = text.translate(SYNTAX)
    stack, parts, start, pos = [], [], 0, 0
    closing = {")": "(", "]": "[", "}": "{"}
    while pos < len(syntax):
        char = syntax[pos]
        if char in "([{":
            stack.append(char)
        elif char in closing:
            if not stack or stack.pop() != closing[char]:
                return [text], False
        elif not stack and not any(start <= pos < end for start, end in protected):
            match = separators.match(syntax, pos)
            if match:
                parts.append(text[start:pos])
                start = pos = match.end()
                continue
        pos += 1
    if stack:
        return [text], False
    return parts + [text[start:]], True


def outer_credit(text):
    """Return the final top-level annotation, including names like *(Asterisk)."""
    syntax = text.strip().translate(SYNTAX)
    if not split_top(syntax, SEPARATORS)[1]:
        return None
    depth, at = 0, None
    for i, char in enumerate(syntax):
        if char in "([":
            if depth == 0:
                at = i
            depth += 1
        elif char in ")]":
            depth -= 1
            if depth == 0 and i == len(syntax) - 1 and at:
                return syntax[:at].strip(), syntax[at + 1:i].strip()
    return None


class Classifier:
    def __init__(self, rules=None):
        self.rules = rules
        self.scope = rules["id"] if rules else ""
        self.groups = set(rules["groups"]) if rules else set()
        self.characters = {candidate_key(name): name for name in rules["characters"]} if rules else {}
        self.protected = [re.compile(re.escape(name.translate(SYNTAX))) for name in self.groups]
        if rules:
            self.protected += [re.compile(re.escape(name.translate(SYNTAX)))
                               for name in rules["credit_overrides"]]
            self.protected += [
                re.compile(r"\s*".join(re.escape(c) for c in item["text"] if not c.isspace()))
                for item in rules["shared_prefix_shorthands"]]

    def credit(self, kind, name, raw, method):
        return {"kind": kind, "name": name, "entity_id": identity(kind, name, self.scope),
                "raw": raw, "method": method}

    def literal(self, raw, issues=()):
        name = raw.strip()
        return ([self.credit("artist", name, raw, "literal")] if name else [], list(issues))

    def atomic(self, raw):
        name = raw.strip()
        if not name:
            return [], ["empty_list_member"]
        override = self.rules["credit_overrides"].get(name)
        if override is not None:
            return [self.credit(c["kind"], c["name"], raw, "configured_override")
                    for c in override["credits"]], override.get("issues", [])
        # Whole names are protected before punctuation is treated as a separator.
        if name in self.groups:
            return [self.credit("group", name, raw, "configured_group")], []
        role = self.characters.get(candidate_key(name))
        if role:
            return [self.credit("character", role, raw, "configured_character")], []
        if name in self.rules["placeholders"]:
            return [], ["placeholder_artist"]
        annotation = outer_credit(name)
        base = annotation[0] if annotation and re.match(r"CV\s*[.:]", annotation[1], re.I) else name
        for shorthand in self.rules["shared_prefix_shorthands"]:
            if candidate_key(base) == candidate_key(shorthand["text"]):
                return [self.credit("character", member, raw, "configured_shorthand")
                        for member in shorthand["members"]], []
        match = CV.fullmatch(name.translate(SYNTAX))
        if match and not SEPARATORS.search(match[1]):
            role = match[1].strip()
            canonical = self.characters.get(candidate_key(role))
            return [self.credit("character", canonical or role, raw, "explicit_cv_ignored")], (
                [] if canonical else ["new_character"])
        return None

    def parse(self, raw):
        if not self.rules:
            return self.literal(raw)
        atomic = self.atomic(raw)
        if atomic is not None:
            return atomic
        # Protect groups, approved whole names and shorthands inside credit lists.
        protected = [m.span() for pattern in self.protected
                     for m in pattern.finditer(raw.translate(SYNTAX))]
        parts, balanced = split_top(raw, SEPARATORS, protected)
        if not balanced:
            return self.literal(raw, ["unbalanced_brackets"])
        if len(parts) > 1:
            results = [self.parse(part) for part in parts]
            credits = [c for found, _ in results for c in found]
            issues = [i for _, pending in results for i in pending]
            if any(c["kind"] == "artist" for c in credits):
                issues.append("untyped_list_member")
            return credits, sorted(set(issues))
        attribution, _ = split_top(raw, FROM)
        if len(attribution) == 2:
            found, issues = self.parse(attribution[0])
            if found and all(c["kind"] == "character" for c in found):
                for credit in found:
                    credit.update(raw=raw, method="character_source_annotation_ignored")
                return found, issues
        annotation = outer_credit(raw)
        if annotation:
            prefix, body = annotation
            role = self.characters.get(candidate_key(prefix))
            if role:
                return [self.credit("character", role, raw, "character_annotation_ignored")], []
            members, pending = self.parse(body)
            typed = members and all(c["kind"] in ("character", "group") for c in members)
            if prefix in self.groups:
                group = self.credit("group", prefix, raw, "explicit_member_list")
                return ([group] + members, pending) if typed else (
                    [group], ["unresolved_group_members"])
            return self.literal(raw, ["group_type_candidate" if typed else "unresolved_parenthesis"])
        name = raw.strip()
        if raw != raw.rstrip() and len(name) >= 2 and any(
                role.startswith(name) and role != name for role in self.characters.values()):
            return self.literal(raw, ["possible_truncated_name"])
        return self.literal(raw)


def absolute_folder(value):
    path = PureWindowsPath(value)
    if not path.is_absolute() or ".." in path.parts or any(":" in p for p in path.parts[1:]):
        raise ValueError("Expected an absolute Windows folder without traversal: " + value)
    return path


def validate_configuration(bindings, profiles):
    if bindings["schema"] != 1:
        raise ValueError("Unsupported folder binding schema")
    folders, profile_ids = set(), set()
    for binding in bindings["bindings"]:
        folder = absolute_folder(binding["folder"])
        if folder in folders:
            raise ValueError("Duplicate folder binding: " + str(folder))
        folders.add(folder)
        if binding["config"] not in profiles:
            raise ValueError("Missing bound profile: " + binding["config"])
    for rules in profiles.values():
        if rules["schema"] != 2 or not rules["id"] or rules["id"] in profile_ids:
            raise ValueError("Invalid or duplicate profile identity")
        profile_ids.add(rules["id"])
        characters = rules["characters"]
        if (not isinstance(characters, list)
                or not all(isinstance(name, str) and name.strip() == name and name for name in characters)
                or len({candidate_key(name) for name in characters}) != len(characters)):
            raise ValueError("Character catalogue has empty or colliding names")
        for key in ("groups", "placeholders"):
            if not isinstance(rules[key], list) or not all(
                    isinstance(name, str) and name.strip() == name and name for name in rules[key]):
                raise ValueError("Expected a list of nonempty names: " + key)
        for shorthand in rules["shared_prefix_shorthands"]:
            if not shorthand["text"] or not shorthand["members"] or any(
                    name not in characters for name in shorthand["members"]):
                raise ValueError("Shorthand must reference configured characters")
        for raw, override in rules["credit_overrides"].items():
            if not raw or not override["credits"]:
                raise ValueError("Empty whole-credit override")
            for credit in override["credits"]:
                if credit["kind"] not in ("character", "group", "artist") or not credit["name"].strip():
                    raise ValueError("Invalid whole-credit override")
                if credit["kind"] in ("character", "group"):
                    catalogue = characters if credit["kind"] == "character" else rules["groups"]
                    if credit["name"] not in catalogue:
                        raise ValueError("Override must reference a configured identity")
            if any(issue not in ISSUE_LABELS for issue in override.get("issues", [])):
                raise ValueError("Unknown override issue")


def select_profile(path, bindings, profiles):
    matches = [b for b in bindings["bindings"]
               if absolute_folder(b["folder"]) == path or absolute_folder(b["folder"]) in path.parents]
    if not matches:
        return None
    binding = max(matches, key=lambda b: len(absolute_folder(b["folder"]).parts))
    return profiles[binding["config"]]


def validate_source(data):
    root = absolute_folder(data["summary"]["root"])
    records = data["files"]
    if not isinstance(records, list) or len(records) != data["summary"]["total_audio"]:
        raise ValueError("Source file count does not match its summary")
    paths = set()
    for record in records:
        path = PureWindowsPath(record["path"])
        if (not record["path"] or path.drive or path.root or ".." in path.parts
                or not path.parts or any(":" in p for p in path.parts) or path in paths):
            raise ValueError("Missing, duplicate or escaping source path")
        paths.add(path)
        for field in ("artist", "albumartist"):
            if not isinstance(record[field], list) or not all(
                    isinstance(value, str) for value in record[field]):
                raise ValueError("Expected native arrays of strings: " + field)
    return root


def classify(data, bindings, profiles, source_hash):
    root = validate_source(data)
    validate_configuration(bindings, profiles)
    parsers = {rules["id"]: Classifier(rules) for rules in profiles.values()}
    parsers[""] = Classifier()
    tracks, entities, signatures = [], {}, {}

    def register(credit, track_id, project, scope):
        key = credit["entity_id"]
        if key not in entities:
            entities[key] = {"id": key, "profile": scope or None,
                             "kind": credit["kind"], "name": credit["name"],
                             "track_ids": set(), "projects": set(), "raw_credits": set(),
                             "methods": set()}
        entity = entities[key]
        entity["track_ids"].add(track_id)
        entity["projects"].add(project)
        entity["raw_credits"].add(credit["raw"])
        entity["methods"].add(credit["method"])

    for record in data["files"]:
        profile = select_profile(root / record["path"], bindings, profiles)
        scope = profile["id"] if profile else ""
        parser = parsers[scope]
        track_id = identity("track", record["path"])
        memberships = {kind: set() for kind in KINDS}
        credits, issues = [], []
        for raw in record["artist"]:
            original = parser.credit("original", raw, raw, "unchanged")
            register(original, track_id, record["project"], scope)
            memberships["original"].add(original["entity_id"])
            key = (scope, raw)
            if key not in signatures:
                found, pending = parser.parse(raw)
                signatures[key] = {"profile": scope or None, "raw": raw, "credits": found,
                                   "issues": pending, "paths": [], "projects": set()}
            signature = signatures[key]
            signature["paths"].append(record["path"])
            signature["projects"].add(record["project"])
            credits.extend(signature["credits"])
            issues.extend(signature["issues"])
        for raw in sorted(set(record["albumartist"])):
            if raw.strip():
                credits.append(parser.credit("album_artist", raw.strip(), raw, "album_credit_only"))
        for credit in credits:
            register(credit, track_id, record["project"], scope)
            memberships[credit["kind"]].add(credit["entity_id"])
        if record["status"] != "ok":
            issues.append("source_tags_unavailable")
        elif not any(value.strip() for value in record["artist"]):
            issues.append("missing_artist")
        if not any(value.strip() for value in record["albumartist"]):
            issues.append("missing_album_artist" if record["status"] == "ok"
                          else "album_tags_unavailable")
        if "albumartist_alias_conflict" in record["flags"]:
            issues.append("album_artist_field_conflict")
        tracks.append({"id": track_id, "profile": scope or None, "source": record,
                       "memberships": {kind: sorted(ids) for kind, ids in memberships.items()},
                       "credits": credits, "issues": sorted(set(issues))})

    entity_list = []
    for entity in entities.values():
        for key in ("track_ids", "projects", "raw_credits", "methods"):
            entity[key] = sorted(entity[key])
        entity["track_count"] = len(entity["track_ids"])
        entity_list.append(entity)
    entity_list.sort(key=lambda e: (e["profile"] or "", e["kind"], -e["track_count"], e["name"]))
    alias_groups = defaultdict(list)
    for entity in entity_list:
        if entity["kind"] not in ("original", "character"):
            alias_groups[(entity["profile"] or "", entity["kind"], candidate_key(entity["name"]))].append(entity)
    proposals = [{"profile": scope or None, "kind": kind, "comparison_key": key,
                  "variants": [{"id": e["id"], "name": e["name"],
                                "track_count": e["track_count"]} for e in variants]}
                 for (scope, kind, key), variants in sorted(alias_groups.items()) if len(variants) > 1]
    signature_list = []
    for signature in signatures.values():
        signature["projects"] = sorted(signature["projects"])
        signature["file_count"] = len(signature["paths"])
        signature_list.append(signature)
    signature_list.sort(key=lambda s: (-s["file_count"], s["profile"] or "", s["raw"]))
    summary = {
        "source_files": len(tracks),
        "profile_file_counts": dict(sorted(Counter(t["profile"] or "unbound" for t in tracks).items())),
        "source_status": dict(Counter(t["source"]["status"] for t in tracks)),
        "unique_artist_signatures": len(signatures),
        "entities_by_kind": dict(Counter(e["kind"] for e in entity_list)),
        "tracks_by_kind": {kind: sum(bool(t["memberships"][kind]) for t in tracks) for kind in KINDS},
        "tracks_with_review_items": sum(bool(t["issues"]) for t in tracks),
        "signature_review_count": sum(bool(s["issues"]) for s in signature_list),
        "alias_proposals": len(proposals),
        "issue_file_counts": dict(sorted(Counter(
            issue for track in tracks for issue in track["issues"]).items())),
    }
    result = {"schema": 2, "decision": "offline_policy_confirmed_unknowns_preserved",
              "source_sha256": source_hash, "source_root": data["summary"]["root"],
              "source_generated_at": data["summary"]["generated_at"],
              "folder_bindings": bindings, "profiles": profiles,
              "summary": summary, "entities": entity_list, "tracks": tracks}
    review = {
        "source_sha256": source_hash, "alias_proposals_not_applied": proposals,
        "literal_entities": [e for e in entity_list if e["kind"] == "artist"],
        "signatures": signature_list,
        "report_name_candidates_not_applied": data.get("name_variant_candidates", []),
        "report_role_candidates_not_applied": data.get("role_credit_variant_candidates", []),
        "source_review_files": [{"path": t["source"]["path"], "issues": t["issues"]}
                                for t in tracks if t["issues"]],
    }
    verify(data, result)
    return result, review


def verify(data, result):
    """Check completeness, exact source preservation and index consistency."""
    tracks, entities = result["tracks"], {e["id"]: e for e in result["entities"]}
    if [t["source"] for t in tracks] != data["files"]:
        raise AssertionError("Source records were lost, reordered or changed")
    if len({t["id"] for t in tracks}) != len(tracks):
        raise AssertionError("Duplicate track identity")
    expected = defaultdict(set)
    for track in tracks:
        for kind, ids in track["memberships"].items():
            if len(ids) != len(set(ids)):
                raise AssertionError("Repeated membership")
            for key in ids:
                if entities[key]["kind"] != kind or entities[key]["profile"] != track["profile"]:
                    raise AssertionError("Entity kind or profile mismatch")
                expected[key].add(track["id"])
    if any(set(e["track_ids"]) != expected[e["id"]] for e in entities.values()):
        raise AssertionError("Forward/reverse indexes disagree")


def md(value):
    return str(value).replace("|", "\\|").replace("\r", "").replace("\n", "<br>")


def write_outputs(output, result, review):
    output.mkdir(parents=True, exist_ok=True)
    for name, content in (("classification.json", result), ("review.json", review)):
        (output / name).write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n",
                                   encoding="utf-8")
    lines = ["# \u6b4c\u624b\u5206\u7c7b\u7ed3\u679c", "",
             "- Offline derived index only; no audio, tags or APK modified.",
             "- Folder-bound profiles only; unbound ARTIST values remain literal.",
             "- Known character names merge across CV/spacing variants; no CV categories.",
             "- Group-only credits never imply members; explicit known members are indexed.",
             "- Unknown credits remain literal. Album artists never fill song performers.",
             "- Counts overlap: one track can appear under multiple characters/groups.",
             "- Source SHA-256: `" + result["source_sha256"] + "`", "",
             "## \u7edf\u8ba1", "", "```json",
             json.dumps(result["summary"], ensure_ascii=False, indent=2), "```", "",
             "## \u5206\u7c7b\u76ee\u5f55", ""]
    for kind in KINDS:
        if kind == "original":
            continue
        lines.extend(["### " + KIND_LABELS[kind], "",
                      "| Profile | \u540d\u79f0 | \u6587\u4ef6\u6570 |", "| --- | --- | ---: |"])
        for entity in result["entities"]:
            if entity["kind"] == kind:
                lines.append("| " + md(entity["profile"] or "unbound") + " | " +
                             md(entity["name"]) + " | " + str(entity["track_count"]) + " |")
        lines.append("")
    lines.extend(["## \u975e\u89d2\u8272\u522b\u540d\u5efa\u8bae\uff08\u672a\u5408\u5e76\uff09", ""])
    for proposal in review["alias_proposals_not_applied"]:
        lines.append("- " + KIND_LABELS[proposal["kind"]] + ": " +
                     " / ".join(v["name"] + " (" + str(v["track_count"]) + ")"
                                for v in proposal["variants"]))
    (output / "classification-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    lines = ["# \u539f\u59cb\u7f72\u540d\u5206\u7c7b\u5bf9\u7167", "",
             "All ARTIST strings, including unresolved entries; counts are source files.",
             "JSON quoting preserves visible trailing spaces. CV identities are not indexed.", "",
             "| Profile | \u539f\u59cb\u7f72\u540d | \u6587\u4ef6\u6570 | \u5206\u7c7b\u7ed3\u679c | \u590d\u6838\u9879 |",
             "| --- | --- | ---: | --- | --- |"]
    for signature in review["signatures"]:
        credits = list(dict.fromkeys(KIND_LABELS[c["kind"]] + ": " + c["name"]
                                     for c in signature["credits"]))
        lines.append("| " + " | ".join(md(value) for value in [
            signature["profile"] or "unbound", json.dumps(signature["raw"], ensure_ascii=False),
            signature["file_count"], "; ".join(credits),
            "; ".join(ISSUE_LABELS.get(i, i) for i in signature["issues"]) or "-"]) + " |")
    (output / "signature-review.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def load_configuration(path):
    bindings = json.loads(path.read_text(encoding="utf-8-sig"))
    profiles = {b["config"]: json.loads((path.parent / b["config"]).read_text(encoding="utf-8-sig"))
                for b in bindings["bindings"]}
    validate_configuration(bindings, profiles)
    return bindings, profiles


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    cli.add_argument("--bindings", type=Path, default=HERE / "folder-bindings.json")
    cli.add_argument("--output", type=Path, default=HERE / "output")
    args = cli.parse_args()
    raw = args.source.read_bytes()
    source_hash = hashlib.sha256(raw).hexdigest()
    data = json.loads(raw)
    bindings, profiles = load_configuration(args.bindings)
    result, review = classify(data, bindings, profiles, source_hash)
    write_outputs(args.output, result, review)
    verify(data, json.loads((args.output / "classification.json").read_text(encoding="utf-8")))
    if args.source.read_bytes() != raw:
        raise RuntimeError("Input snapshot changed during the run")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print("Source unchanged; source preservation and index consistency checks passed.")
    print("Output:", args.output)


if __name__ == "__main__":
    main()
