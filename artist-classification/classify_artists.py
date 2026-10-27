"""Classify a report snapshot only. Never open or rewrite source audio files."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re
import unicodedata


HERE = Path(__file__).resolve().parent
SYNTAX = str.maketrans({
    "\uff08": "(", "\uff09": ")", "\uff3b": "[", "\uff3d": "]",
    "\uff1a": ":", "\uff0e": ".", "\uff0c": ",", "\uff1b": ";",
    "\uff0f": "/", "\uff06": "&",
})
STRONG = re.compile(r"[,、]|\bfeat(?:uring)?\.?\s*", re.IGNORECASE)
WEAK = re.compile(r"[/;&]")
CV = re.compile(r"([^()\[\]{},、/;&]+?)\s*\(\s*CV\s*[.:]\s*([^()]+)\)", re.IGNORECASE)
EMBEDDED_CV = re.compile(
    r"([^()\[\]{},、/;&]+?)\s*\(\s*CV\s*[.:]\s*([^()]+)\)", re.IGNORECASE
)
KINDS = ("character", "voice_actor", "group", "artist", "album_artist", "original")
KIND_LABELS = dict(zip(KINDS, (
    "\u89d2\u8272", "\u58f0\u4f18", "\u56e2\u4f53", "\u5176\u4ed6\u7f72\u540d\uff08\u7c7b\u578b\u672a\u5b9a\uff09",
    "\u4e13\u8f91\u827a\u672f\u5bb6", "\u539f\u59cb\u7f72\u540d")))
ISSUE_LABELS = {
    "album_artist_field_conflict": "\u4e13\u8f91\u827a\u672f\u5bb6\u540c\u4e49\u5b57\u6bb5\u503c\u51b2\u7a81",
    "album_tags_unavailable": "\u672a\u66b4\u9732\u6807\u7b7e\uff0c\u65e0\u6cd5\u5224\u5b9a\u4e13\u8f91\u827a\u672f\u5bb6",
    "ambiguous_separator": "\u5206\u9694\u7b26\u5b58\u7591\uff0c\u4fdd\u7559\u6574\u4f53",
    "complex_cv": "CV \u5185\u5bb9\u542b\u540d\u5355\u6216\u6765\u6e90\u8bf4\u660e\uff0c\u6682\u4e0d\u63d0\u53d6",
    "empty_list_member": "\u540d\u5355\u4e2d\u5b58\u5728\u7a7a\u9879",
    "group_type_proposal": "\u62ec\u53f7\u5217\u51fa\u6210\u5458\uff0c\u5916\u5c42\u56e2\u4f53\u7c7b\u578b\u5f85\u786e\u8ba4",
    "missing_album_artist": "\u5df2\u8bfb\u6807\u7b7e\u4e2d\u65e0\u4e13\u8f91\u827a\u672f\u5bb6",
    "missing_artist": "\u5df2\u8bfb\u6807\u7b7e\u4e2d\u65e0\u6b4c\u624b",
    "placeholder_artist": "\u5360\u4f4d\u6587\u672c\uff0c\u4e0d\u751f\u6210\u4eba\u7269",
    "possible_truncated_name": "\u540d\u5355\u672b\u5c3e\u7591\u4f3c\u622a\u65ad\uff0c\u4e0d\u8865\u731c",
    "role_actor_conflict": "\u65e0 CV \u62ec\u53f7\u4e0e\u5feb\u7167\u4e2d\u7684\u89d2\u8272\u58f0\u4f18\u5bf9\u5e94\u51b2\u7a81",
    "source_tags_unavailable": "\u539f\u8bfb\u53d6\u5668\u672a\u66b4\u9732\u6807\u7b7e",
    "unbalanced_brackets": "\u62ec\u53f7\u4e0d\u95ed\u5408\uff0c\u4fdd\u7559\u6574\u4f53",
    "unresolved_parenthesis": "\u62ec\u53f7\u542b\u4e49\u672a\u786e\u8ba4\uff0c\u4fdd\u7559\u6574\u4f53",
    "unresolved_role_parenthesis": "\u89d2\u8272\u62ec\u53f7\u5185\u5bb9\u5f85\u786e\u8ba4",
    "untyped_list_member": "\u540d\u5355\u5305\u542b\u8eab\u4efd\u7c7b\u578b\u672a\u5b9a\u7684\u7f72\u540d",
}


def simple_actor(name):
    return not (STRONG.search(name) or WEAK.search(name)
                or re.search(r"\b(?:from|with)\b", name, re.IGNORECASE))


def identity(kind, name):
    return kind + "-" + hashlib.sha256((kind + "\0" + name).encode()).hexdigest()[:16]


def candidate_key(name):
    """Lossy comparison is for review proposals, never an identity key."""
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", name))


def split_top(text, separators):
    """Split outside matched brackets; retain source slices and empty tokens."""
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
        elif not stack:
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
    syntax = text.translate(SYNTAX).strip()
    at = next((i for i, char in enumerate(syntax) if char in "(["), -1)
    if at <= 0:
        return None
    _, balanced = split_top(syntax, STRONG)
    closing = ")" if syntax[at] == "(" else "]"
    if not balanced or not syntax.endswith(closing):
        return None
    depth = 0
    for i in range(at, len(syntax)):
        if syntax[i] == syntax[at]:
            depth += 1
        elif syntax[i] == closing:
            depth -= 1
            if depth == 0 and i != len(syntax) - 1:
                return None
    return syntax[:at].strip(), syntax[at + 1:-1].strip()


class Classifier:
    def __init__(self, records, rules):
        self.groups = set(rules["groups"])
        self.rules = rules
        self.pairs = defaultdict(set)
        self.actors = set()
        # Only explicit CV syntax establishes the snapshot's relationship evidence.
        for record in records:
            for raw in record["artist"]:
                parts, balanced = split_top(raw, re.compile(STRONG.pattern + "|" + WEAK.pattern,
                                                           re.IGNORECASE))
                if not balanced:
                    continue
                for part in parts:
                    for match in EMBEDDED_CV.finditer(part.translate(SYNTAX)):
                        role, actor = (item.strip() for item in match.groups())
                        if simple_actor(actor):
                            self.pairs[role].add(actor)
                            self.actors.add(actor)
        self.role_candidates = defaultdict(set)
        self.actor_candidates = defaultdict(set)
        for role in self.pairs:
            self.role_candidates[candidate_key(role)].add(role)
        for actor in self.actors:
            self.actor_candidates[candidate_key(actor)].add(actor)

    def credit(self, kind, name, raw, method):
        name = self.rules["approved_aliases"].get(kind, {}).get(name, name)
        return {"kind": kind, "name": name, "entity_id": identity(kind, name),
                "raw": raw, "method": method}

    def atomic(self, raw):
        name = raw.strip()
        syntax = name.translate(SYNTAX)
        if not name:
            return [], ["empty_list_member"]
        if name in self.rules["placeholders"]:
            return [], ["placeholder_artist"]
        # Explicit, reviewable shorthand expansion precedes slash handling.
        for shorthand in self.rules["shared_prefix_shorthands"]:
            match = re.fullmatch(r"(.+?)(\s*\(\s*CV\s*[.:]\s*[^()]+\))?",
                                 syntax, re.IGNORECASE)
            base, suffix = match.groups() if match else (syntax, None)
            if candidate_key(base) == candidate_key(shorthand["text"]):
                credits, issues = [], []
                for member in shorthand["members"]:
                    found, pending = self.atomic(member + (suffix or ""))
                    credits.extend(found)
                    issues.extend(pending)
                for credit in credits:
                    credit.update(raw=raw, method="configured_shorthand")
                return credits, issues
        match = CV.fullmatch(syntax)
        if match:
            role, actor = (part.strip() for part in match.groups())
            if not simple_actor(actor):
                return [self.credit("artist", name, raw, "literal")], ["complex_cv"]
            return [self.credit("character", role, raw, "explicit_cv"),
                    self.credit("voice_actor", actor, raw, "explicit_cv")], []
        if name in self.groups:
            return [self.credit("group", name, raw, "configured_group")], []
        if candidate_key(name) in self.role_candidates:
            return [self.credit("character", name, raw, "observed_role_name")], []
        if candidate_key(name) in self.actor_candidates:
            return [self.credit("voice_actor", name, raw, "observed_cv_name")], []
        return [self.credit("artist", name, raw, "literal_untyped")], []

    def parse_piece(self, raw):
        literal, literal_issues = self.atomic(raw)
        if literal_issues or not literal or all(c["kind"] != "artist" for c in literal):
            return literal, literal_issues
        annotation = outer_credit(raw)
        if annotation:
            prefix, body = annotation
            roles = self.role_candidates.get(candidate_key(prefix), set())
            if roles:
                expected = {candidate_key(actor) for role in roles for actor in self.pairs[role]}
                if candidate_key(body) in expected:
                    return [self.credit("character", prefix, raw, "supported_implicit_cv"),
                            self.credit("voice_actor", body, raw, "supported_implicit_cv")], []
                reason = ("role_actor_conflict" if candidate_key(body) in self.actor_candidates
                          else "unresolved_role_parenthesis")
                return literal, [reason]
            members, balanced = split_top(body, STRONG)
            if balanced and (len(members) > 1 or prefix in self.groups):
                credits, issues = [], []
                for member in members:
                    found, pending = self.parse_piece(member)
                    credits.extend(found)
                    issues.extend(pending)
                if credits and not issues and all(c["kind"] != "artist" for c in credits):
                    return [self.credit("group", prefix, raw, "explicit_member_list")] + credits, (
                        [] if prefix in self.groups else ["group_type_proposal"])
            return literal, ["unresolved_parenthesis"]
        weak, balanced = split_top(raw, WEAK)
        if balanced and len(weak) > 1:
            results = [self.atomic(part) for part in weak]
            if all(credits and not issues and all(c["kind"] != "artist" for c in credits)
                   for credits, issues in results):
                return [credit for credits, _ in results for credit in credits], []
            return literal, ["ambiguous_separator"]
        name = raw.strip()
        if raw != raw.rstrip() and len(name) >= 2 and any(
                role.startswith(name) and role != name for role in self.pairs):
            return literal, ["possible_truncated_name"]
        return self.atomic(raw)

    def parse(self, raw):
        parts, balanced = split_top(raw, STRONG)
        if not balanced:
            return [self.credit("artist", raw.strip(), raw, "unresolved")], ["unbalanced_brackets"]
        credits, issues = [], []
        for part in parts:
            found, pending = self.parse_piece(part)
            credits.extend(found)
            issues.extend(pending)
        if len(parts) > 1 and any(c["method"] == "literal_untyped" for c in credits):
            issues.append("untyped_list_member")
        return credits, sorted(set(issues))


def validate_source(data):
    records = data["files"]
    if not isinstance(records, list) or len(records) != data["summary"]["total_audio"]:
        raise ValueError("Source file count does not match its summary")
    paths = set()
    for record in records:
        if not isinstance(record["path"], str) or not record["path"] or record["path"] in paths:
            raise ValueError("Missing or duplicate source path")
        paths.add(record["path"])
        for field in ("artist", "albumartist"):
            if not isinstance(record[field], list) or not all(
                    isinstance(value, str) for value in record[field]):
                raise ValueError("Expected native arrays of strings: " + field)


def classify(data, rules, source_hash):
    validate_source(data)
    parser = Classifier(data["files"], rules)
    tracks, entities, signatures = [], {}, {}

    def register(credit, track_id, project):
        key = credit["entity_id"]
        if key not in entities:
            entities[key] = {"id": key, "kind": credit["kind"], "name": credit["name"],
                             "track_ids": set(), "projects": set(), "raw_credits": set(),
                             "methods": set()}
        entity = entities[key]
        entity["track_ids"].add(track_id)
        entity["projects"].add(project)
        entity["raw_credits"].add(credit["raw"])
        entity["methods"].add(credit["method"])

    for record in data["files"]:
        track_id = identity("track", record["path"])
        memberships = {kind: set() for kind in KINDS}
        credits, issues = [], []
        for raw in record["artist"]:
            original = parser.credit("original", raw, raw, "unchanged")
            register(original, track_id, record["project"])
            memberships["original"].add(original["entity_id"])
            if raw not in signatures:
                found, pending = parser.parse(raw)
                signatures[raw] = {"raw": raw, "credits": found, "issues": pending,
                                   "paths": [], "projects": set()}
            signature = signatures[raw]
            signature["paths"].append(record["path"])
            signature["projects"].add(record["project"])
            credits.extend(signature["credits"])
            issues.extend(signature["issues"])
        for raw in sorted(set(record["albumartist"])):
            if raw.strip():
                credits.append(parser.credit("album_artist", raw.strip(), raw, "album_credit_only"))
        for credit in credits:
            register(credit, track_id, record["project"])
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
        tracks.append({"id": track_id, "source": record,
                       "memberships": {kind: sorted(ids) for kind, ids in memberships.items()},
                       "credits": credits, "issues": sorted(set(issues))})

    entity_list = []
    for entity in entities.values():
        for key in ("track_ids", "projects", "raw_credits", "methods"):
            entity[key] = sorted(entity[key])
        entity["track_count"] = len(entity["track_ids"])
        entity_list.append(entity)
    entity_list.sort(key=lambda item: (item["kind"], -item["track_count"], item["name"]))
    alias_groups = defaultdict(list)
    for entity in entity_list:
        if entity["kind"] != "original":
            alias_groups[(entity["kind"], candidate_key(entity["name"]))].append(entity)
    proposals = [{"kind": kind, "comparison_key": key, "approval": "pending",
                  "variants": [{"id": e["id"], "name": e["name"],
                                "track_count": e["track_count"]} for e in variants]}
                 for (kind, key), variants in sorted(alias_groups.items()) if len(variants) > 1]
    signature_list = []
    for signature in signatures.values():
        signature["projects"] = sorted(signature["projects"])
        signature["file_count"] = len(signature["paths"])
        signature_list.append(signature)
    signature_list.sort(key=lambda item: (-item["file_count"], item["raw"]))
    summary = {
        "source_files": len(tracks),
        "source_status": dict(Counter(t["source"]["status"] for t in tracks)),
        "unique_artist_signatures": len(signatures),
        "entities_by_kind": dict(Counter(e["kind"] for e in entity_list)),
        "tracks_by_kind": {kind: sum(bool(t["memberships"][kind]) for t in tracks)
                           for kind in KINDS},
        "tracks_with_review_items": sum(bool(t["issues"]) for t in tracks),
        "signature_review_count": sum(bool(s["issues"]) for s in signature_list),
        "alias_proposals": len(proposals),
        "issue_file_counts": dict(sorted(Counter(
            issue for track in tracks for issue in track["issues"]).items())),
    }
    result = {"schema": 1, "approval": "pending_user_confirmation",
              "source_sha256": source_hash, "source_root": data["summary"]["root"],
              "source_generated_at": data["summary"]["generated_at"],
              "rules": rules, "summary": summary, "entities": entity_list, "tracks": tracks}
    review = {
        "approval": "pending_user_confirmation", "source_sha256": source_hash,
        "alias_proposals_not_applied": proposals,
        "untyped_entities": [e for e in entity_list if e["kind"] == "artist"],
        "signatures": signature_list,
        "multiple_cv_relationships": [
            {"character": role, "observed_actors": sorted(actors),
             "note": "Observed differences may be legitimate recasts or spelling variants; not corrections."}
            for role, actors in sorted(parser.pairs.items()) if len(actors) > 1],
        "report_name_candidates_not_applied": data["name_variant_candidates"],
        "report_role_candidates_not_applied": data["role_credit_variant_candidates"],
        "source_review_files": [
            {"path": t["source"]["path"], "issues": t["issues"]}
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
                if entities[key]["kind"] != kind:
                    raise AssertionError("Entity kind mismatch")
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
    lines = ["# \u6b4c\u624b\u5206\u7c7b\u7ed3\u679c\uff08\u5f85\u786e\u8ba4\uff09", "",
             "- Input: report JSON snapshot only; no music files opened.",
             "- Raw source records preserved exactly; no aliases applied automatically.",
             "- Character/CV links are observations, not authoritative identity corrections.",
             "- Group types in rules.json are draft proposals; no member inference.",
             "- Album artist labels stay separate and are never split into song performers.",
             "- Internal whitespace/NFKC variants are proposals only; untyped ARTIST is not proof of vocals.",
             "- Review items are not error verdicts. Rows/files/identities are different counts.",
             "- Source SHA-256: `" + result["source_sha256"] + "`", "",
             "## \u7edf\u8ba1", "", "```json",
             json.dumps(result["summary"], ensure_ascii=False, indent=2), "```", "",
             "## \u5206\u7c7b\u76ee\u5f55", ""]
    for kind in KINDS:
        if kind == "original":
            continue
        lines.extend(["### " + KIND_LABELS[kind], "", "| \u540d\u79f0 | \u6587\u4ef6\u6570 |",
                      "| --- | ---: |"])
        for entity in result["entities"]:
            if entity["kind"] == kind:
                lines.append("| " + md(entity["name"]) + " | " + str(entity["track_count"]) + " |")
        lines.append("")
    lines.extend(["## \u5f85\u786e\u8ba4\u5408\u5e76\uff08\u672a\u5e94\u7528\uff09", ""])
    for proposal in review["alias_proposals_not_applied"]:
        lines.append("- " + KIND_LABELS[proposal["kind"]] + ": " +
                     " / ".join(v["name"] + " (" + str(v["track_count"]) + ")"
                                for v in proposal["variants"]))
    (output / "classification-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    lines = ["# \u539f\u59cb\u7f72\u540d\u5206\u7c7b\u5bf9\u7167\uff08\u5f85\u786e\u8ba4\uff09", "",
             "All distinct ARTIST strings, including unresolved entries; counts are source files.",
             "Source strings use JSON quoting so trailing spaces remain visible.", "",
             "| \u539f\u59cb\u7f72\u540d | \u6587\u4ef6\u6570 | \u5206\u7c7b\u7ed3\u679c | \u5f85\u786e\u8ba4 |",
             "| --- | ---: | --- | --- |"]
    for signature in review["signatures"]:
        credits = list(dict.fromkeys(KIND_LABELS[c["kind"]] + ": " + c["name"]
                                     for c in signature["credits"]))
        lines.append("| " + " | ".join(md(value) for value in [
            json.dumps(signature["raw"], ensure_ascii=False), signature["file_count"],
            "; ".join(credits),
            "; ".join(ISSUE_LABELS.get(issue, issue) for issue in signature["issues"]) or "-"]) + " |")
    (output / "signature-review.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("source", type=Path)
    args = cli.parse_args()
    raw = args.source.read_bytes()
    source_hash = hashlib.sha256(raw).hexdigest()
    data = json.loads(raw)
    rules = json.loads((HERE / "rules.json").read_text(encoding="utf-8"))
    result, review = classify(data, rules, source_hash)
    write_outputs(HERE / "output", result, review)
    verify(data, json.loads((HERE / "output" / "classification.json").read_text(encoding="utf-8")))
    if args.source.read_bytes() != raw:
        raise RuntimeError("Input snapshot changed during the run")
    print(json.dumps(result["summary"], ensure_ascii=False, indent=2))
    print("Source unchanged; source preservation and index consistency checks passed.")
    print("Output:", HERE / "output")


if __name__ == "__main__":
    main()
