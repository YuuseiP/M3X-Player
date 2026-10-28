import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from assemble_semantic import assemble, compile_catalogue, identity, tag_id, validate_decision, verify

HERE = Path(__file__).resolve().parent
FOLDER = r"D:\Music\IMAS"


def answer(raw, credits=None, status="resolved"):
    return {"id": tag_id(raw), "raw": raw,
            "credits": credits if credits is not None else [["character", "Role"]],
            "status": status, "reason": "Explicitly reviewed credit.",
            "sources": [], "model": "test-agent"}


def fixture():
    record = {"path": r"album\song.flac", "artist": ["Raw (CV: Actor)"],
              "albumartist": ["Album credit"], "extra_artist_fields": {"PERFORMER": ["Actor"]},
              "project": "Project", "status": "ok", "flags": []}
    source = {"summary": {"root": FOLDER, "total_audio": 1, "generated_at": "test"},
              "files": [record]}
    bindings = {"schema": 1, "bindings": [{"folder": FOLDER, "config": "semantic.json"}]}
    profiles = {"semantic.json": {"schema": 1, "id": "idolmaster", "engine": "semantic_exact_lookup"}}
    catalogues = {"semantic.json": {"source_sha256": "hash", "entries": [
        answer("Raw (CV: Actor)"), answer("Album credit", [["group", "Group"]]),
        answer("Actor", [["artist", "Actor"]])]}}
    return source, bindings, profiles, catalogues


class SemanticTest(unittest.TestCase):
    def run_fixture(self, data):
        source, bindings, profiles, catalogues = data
        return assemble(source, "hash", bindings, profiles, catalogues)

    def test_exact_decisions_and_field_roles(self):
        data = fixture()
        original = copy.deepcopy(data[0])
        result = self.run_fixture(data)
        track = result["tracks"][0]
        self.assertEqual(track["memberships"]["character"], [identity("character", "Role", "idolmaster")])
        self.assertEqual(track["memberships"]["group"], [])
        self.assertEqual(track["memberships"]["artist"], [])
        self.assertEqual(len(track["memberships"]["album_artist"]), 1)
        self.assertEqual(len(track["semantic_decisions"]), 3)
        self.assertEqual(data[0], original)
        verify(original, result)

    def test_multi_role_memberships_deduplicated(self):
        data = fixture()
        data[0]["files"][0]["artist"].append("Second raw")
        data[3]["semantic.json"]["entries"][0]["credits"].append(["character", "Other"])
        data[3]["semantic.json"]["entries"].append(answer("Second raw"))
        track = self.run_fixture(data)["tracks"][0]
        self.assertEqual(len(track["memberships"]["character"]), 2)

    def test_unclassified_tag_fails_instead_of_parsing(self):
        data = fixture()
        data[0]["files"][0]["artist"] = ["Role (CV: New actor)"]
        with self.assertRaisesRegex(ValueError, "Unclassified tag"):
            self.run_fixture(data)

    def test_unbound_folder_is_not_classified(self):
        for folder in (FOLDER + "_backup", r"E:\Music\IMAS"):
            data = fixture()
            data[0]["summary"]["root"] = folder
            result = self.run_fixture(data)
            track = result["tracks"][0]
            self.assertIsNone(track["profile"])
            self.assertEqual(len(track["memberships"]["original"]), 1)
            self.assertEqual(track["memberships"]["character"], [])
            self.assertEqual(track["semantic_decisions"], [])

    def test_missing_artist_not_filled_by_album_or_performer(self):
        data = fixture()
        data[0]["files"][0]["artist"] = []
        track = self.run_fixture(data)["tracks"][0]
        self.assertEqual(track["memberships"]["character"], [])
        self.assertEqual(track["memberships"]["artist"], [])
        self.assertIn("missing_artist", track["issues"])

    def test_unresolved_and_placeholder_are_explicit(self):
        data = fixture()
        entry = data[3]["semantic.json"]["entries"][0]
        entry.update(credits=[["artist", entry["raw"]]], status="unresolved")
        self.assertIn("semantic_unresolved", self.run_fixture(data)["tracks"][0]["issues"])
        entry.update(credits=[], status="placeholder")
        track = self.run_fixture(data)["tracks"][0]
        self.assertIn("placeholder_artist", track["issues"])
        self.assertEqual(track["memberships"]["artist"], [])

    def test_changed_snapshot_and_duplicate_lookup_rejected(self):
        data = fixture()
        data[3]["semantic.json"]["source_sha256"] = "different"
        with self.assertRaisesRegex(ValueError, "different source"):
            self.run_fixture(data)
        data = fixture()
        entries = data[3]["semantic.json"]["entries"]
        entries.append(copy.deepcopy(entries[0]))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.run_fixture(data)

    def test_path_boundary_and_duplicate_paths(self):
        for bad in ("..\\secret.flac", "D:\\secret.flac", "song.flac:stream", ""):
            data = fixture()
            data[0]["files"][0]["path"] = bad
            with self.assertRaises(ValueError):
                self.run_fixture(data)
        data = fixture()
        data[0]["files"].append(copy.deepcopy(data[0]["files"][0]))
        data[0]["summary"]["total_audio"] = 2
        with self.assertRaises(ValueError):
            self.run_fixture(data)

    def test_decision_validation(self):
        for changes in ({"credits": [["voice_actor", "Actor"]]}, {"credits": []},
                        {"status": "maybe"}, {"sources": ["javascript:alert(1)"]},
                        {"reason": ""}, {"credits": [["artist", " A "]]},
                        {"credits": [["artist", "A"], ["artist", "A"]]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_decision({**answer("raw"), **changes})

    def test_batch_coverage_checked_before_assembly(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs, decisions = root / "input", root / "decisions"
            inputs.mkdir()
            decisions.mkdir()
            tag = {"id": tag_id("Exact raw"), "raw": "Exact raw", "fields": {"artist": 1}}
            manifest = {"tags": [tag], "tag_count": 1, "batch_count": 1, "source_sha256": "hash"}
            profile = {"models_by_batch": {"batch-01.json": "test-agent"}}
            (inputs / "batch-01.json").write_text(json.dumps([tag]), encoding="utf-8")
            target = decisions / "batch-01.json"
            for rows in ([], [answer("Exact raw"), answer("Exact raw")], [answer("Other raw")]):
                target.write_text(json.dumps(rows), encoding="utf-8")
                with self.assertRaises(ValueError):
                    compile_catalogue(manifest, inputs, decisions, profile)
            target.write_text(json.dumps([answer("Exact raw")]), encoding="utf-8")
            catalogue = compile_catalogue(manifest, inputs, decisions, profile)
            self.assertEqual(catalogue["entries"][0]["raw"], "Exact raw")
            self.assertEqual(catalogue["entries"][0]["model"], "test-agent")
            review = {"model": "review-agent", "checked_ids": [tag["id"]],
                      "changed_ids": [], "decisions": [{"id": tag["id"], "action": "kept"}]}
            reviewed = compile_catalogue(manifest, inputs, decisions, profile, review)
            self.assertEqual(reviewed["entries"][0]["reviewed_by"], "review-agent")
            mapping = {"kind": "character", "original": "Role", "name": "百科名",
                       "sources": ["https://example.org/encyclopedia"],
                       "evidence": "Encyclopedia title names this character."}
            names = {"mappings": [mapping]}
            target.write_text(json.dumps([answer("Exact raw", [
                ["character", "Role"], ["character", "百科名"], ["artist", "Role"]])]),
                encoding="utf-8")
            renamed = compile_catalogue(manifest, inputs, decisions, profile, review, names)
            entry = renamed["entries"][0]
            self.assertEqual(entry["credits"], [["character", "百科名"], ["artist", "Role"]])
            self.assertEqual(entry["name_changes"], [mapping])
            self.assertEqual(entry["raw"], "Exact raw")
            self.assertEqual(entry["reviewed_by"], "review-agent")
            self.assertEqual(json.loads(target.read_bytes())[0]["credits"][0], ["character", "Role"])
            for bad in ({**mapping, "sources": []},
                        {**mapping, "sources": ["javascript:alert(1)"]},
                        {**mapping, "name": ""}):
                with self.subTest(mapping=bad), self.assertRaises(ValueError):
                    compile_catalogue(manifest, inputs, decisions, profile,
                                      names={"mappings": [bad]})
            with self.assertRaises(ValueError):
                compile_catalogue(manifest, inputs, decisions, profile,
                                  names={"mappings": [mapping, mapping]})
            review["checked_ids"].append("unknown")
            with self.assertRaises(ValueError):
                compile_catalogue(manifest, inputs, decisions, profile, review)

    @unittest.skipUnless((HERE / "semantic" / "credits.json").is_file(), "Local semantic catalogue unavailable")
    def test_full_local_snapshot(self):
        manifest_path = HERE / "semantic" / "input" / "manifest.json"
        if not manifest_path.is_file():
            self.skipTest("Personal source manifest is not in Git")
        manifest = json.loads(manifest_path.read_bytes())
        source_path = Path(manifest["source_path"])
        raw = source_path.read_bytes()
        bindings = json.loads((HERE / "folder-bindings.json").read_bytes())
        profiles = {b["config"]: json.loads((HERE / b["config"]).read_bytes())
                    for b in bindings["bindings"]}
        catalogues = {config: json.loads((HERE / config).parent.joinpath(profile["catalogue"]).read_bytes())
                      for config, profile in profiles.items()}
        result = assemble(json.loads(raw), hashlib.sha256(raw).hexdigest(), bindings, profiles, catalogues)
        self.assertEqual(result["summary"]["source_files"], 4235)
        self.assertEqual(result["summary"]["semantic_tag_count"], 1352)
        self.assertEqual(result["summary"]["profile_file_counts"], {"idolmaster": 4235})
        for config, profile in profiles.items():
            if not profile.get("names"):
                continue
            naming = json.loads((HERE / config).parent.joinpath(profile["names"]).read_bytes())
            entries = catalogues[config]["entries"]
            all_credits = {tuple(credit) for entry in entries for credit in entry["credits"]}
            changes = [change for entry in entries for change in entry.get("name_changes", [])]
            for mapping in naming["mappings"]:
                self.assertNotIn((mapping["kind"], mapping["original"]), all_credits)
                self.assertIn((mapping["kind"], mapping["name"]), all_credits)
                self.assertIn(mapping, changes)
        union = next(t for t in result["tracks"] if t["source"]["path"].endswith("003-UNION!!.flac"))
        self.assertIn(identity("character", "\u767d\u77f3\u7d2c", "idolmaster"),
                      union["memberships"]["character"])
        self.assertEqual(len(union["memberships"]["character"]), 17)
        self.assertEqual(source_path.read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
