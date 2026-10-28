import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from classify_artists import (Classifier, SEPARATORS, SYNTAX, classify, identity,
                              load_configuration, split_top, verify, write_outputs)


HERE = Path(__file__).resolve().parent
REPORT_PATH = HERE.parent / "\u6b4c\u624b\u95ee\u9898\u6c47\u62a5_2026-10-26" / "\u6b4c\u624b\u6807\u7b7e\u626b\u63cf\u660e\u7ec6.json"
ROOT = r"D:\Music"
FOLDER = ROOT + r"\idol_master"


def record(*artists, path=r"idol_master\one.flac", album=None, status="ok", flags=None):
    return {"path": path, "project": "fixture", "artist": list(artists),
            "albumartist": ["Album Team"] if album is None else album,
            "status": status, "flags": flags or []}


def source(records, root=ROOT):
    return {"summary": {"total_audio": len(records), "root": root,
                        "generated_at": "2026-10-27"}, "files": records}


def rules():
    return {"schema": 2, "id": "idolmaster", "characters": [
                "Alpha", "Beta", "\u767d\u77f3\u7d2c", "\u53cc\u6d77\u4e9c\u7f8e",
                "\u53cc\u6d77\u771f\u7f8e"],
            "groups": ["Team", "Re;IRIS", "Earth, Wind & Fire", "*(Asterisk)"],
            "shared_prefix_shorthands": [
                {"text": "\u53cc\u6d77\u4e9c\u7f8e/\u771f\u7f8e",
                 "members": ["\u53cc\u6d77\u4e9c\u7f8e", "\u53cc\u6d77\u771f\u7f8e"]}],
            "placeholders": ["N/A"], "credit_overrides": {}}


def bindings():
    return {"schema": 1, "bindings": [{"folder": FOLDER, "config": "rules.json"}]}


def run(records, profile=None, mapping=None, root=ROOT):
    return classify(source(records, root), mapping or bindings(),
                    {"rules.json": profile or rules()}, "hash")


class ParsingTest(unittest.TestCase):
    def setUp(self):
        self.parser = Classifier(rules())

    def names(self, text, kind="character"):
        return [c["name"] for c in self.parser.parse(text)[0] if c["kind"] == kind]

    def test_cv_ignored_with_original_whitespace_preserved(self):
        raw = " Alpha \uff08CV\uff0e Actor A\uff09 "
        credits, issues = self.parser.parse(raw)
        self.assertEqual(issues, [])
        self.assertEqual([c["name"] for c in credits], ["Alpha"])
        self.assertEqual(credits[0]["raw"], raw)
        self.assertEqual(self.names("Alpha(cv:Another actor)"), ["Alpha"])

    def test_known_character_parenthesis_does_not_depend_on_actor(self):
        for text in ["Alpha(Actor A)", "Alpha(Actor B)", "Alpha(CVActor)", "Alpha"]:
            self.assertEqual(self.names(text), ["Alpha"], text)
        self.assertEqual(self.names("Actor A", "artist"), ["Actor A"])
        self.assertEqual(self.names("Actor A (Alpha \u5f79)", "artist"), ["Actor A (Alpha \u5f79)"])

    def test_all_supported_collaboration_separators(self):
        for separator in [",", "\u3001", "/", " & ", ";", "×", " feat. ", " with "]:
            raw = "Alpha(CV:A)" + separator + "Beta(CV:B)"
            self.assertEqual(self.names(raw), ["Alpha", "Beta"], raw)

    def test_feat_boundary_does_not_match_feather(self):
        self.assertEqual(self.names("Feather", "artist"), ["Feather"])
        self.assertEqual(self.names("Alpha feat. Beta(CV:B)"), ["Alpha", "Beta"])
        self.assertEqual(self.names("Alpha featuring Beta(CV:B)"), ["Alpha", "Beta"])

    def test_complete_group_names_precede_splitting(self):
        for text in ["Re;IRIS", "Earth, Wind & Fire"]:
            self.assertEqual(self.names(text, "group"), [text])
            self.assertEqual(self.names(text), [])
        self.assertEqual(self.names("Alpha, Earth, Wind & Fire, Beta"), ["Alpha", "Beta"])
        self.assertEqual(self.names("Alpha, Earth, Wind & Fire, Beta", "group"),
                         ["Earth, Wind & Fire"])

    def test_group_and_explicit_members_with_parenthesis_in_group_name(self):
        raw = "*(Asterisk) [Alpha(CV:A)×Beta(CV:B)]"
        self.assertEqual(self.names(raw, "group"), ["*(Asterisk)"])
        self.assertEqual(self.names(raw), ["Alpha", "Beta"])

    def test_group_only_never_infers_members(self):
        self.assertEqual(self.names("Team"), [])
        self.assertEqual(self.names("Team", "group"), ["Team"])
        self.assertEqual(self.names("Team(Unknown A, Unknown B)", "group"), ["Team"])
        self.assertEqual(self.names("Team(Unknown A, Unknown B)"), [])

    def test_unknown_group_is_not_silently_confirmed(self):
        raw = "New Team(Alpha, Beta)"
        self.assertEqual(self.names(raw, "artist"), [raw])
        self.assertEqual(self.parser.parse(raw)[1], ["group_type_candidate"])

    def test_company_parenthesis_punctuation_preserved(self):
        for text in ["Mitsu.J (Digz, Inc. Group)", "Singer (A & B)", "Artist (A、B)"]:
            self.assertEqual(self.names(text, "artist"), [text])

    def test_unknown_artist_lists_always_split(self):
        for separator in [",", "\u3001", "/", " & ", "\uff06", "\uff0c", ";", "×",
                          " feat. ", " featuring ", " with "]:
            self.assertEqual(self.names("Singer A" + separator + "Singer B", "artist"),
                             ["Singer A", "Singer B"])
        self.assertEqual(self.names("Alpha & Singer A", "artist"), ["Singer A"])
        self.assertEqual(self.names("Alpha & Singer A"), ["Alpha"])

    def test_screenshot_artist_list_split_without_actor_inversion(self):
        raw = "鈴木みのり (藤原 肇役)、椎名豪"
        self.assertEqual(self.names(raw, "artist"), ["鈴木みのり (藤原 肇役)", "椎名豪"])
        self.assertEqual(self.names(raw), [])

    def test_shorthand_protected_inside_longer_slash_list(self):
        shorthand = "\u53cc\u6d77\u4e9c\u7f8e / \u771f\u7f8e(CV:Shared)"
        for raw, expected in [(shorthand, []), ("Alpha(CV:A)/" + shorthand, ["Alpha"])]:
            self.assertEqual(self.names(raw),
                             expected + ["\u53cc\u6d77\u4e9c\u7f8e", "\u53cc\u6d77\u771f\u7f8e"])
            self.assertEqual(self.parser.parse(raw)[1], [])

    def test_unbalanced_brackets_are_preserved_without_override(self):
        raw = "Alpha(CV:A),Beta(CV:B"
        self.assertEqual(self.parser.parse(raw)[1], ["unbalanced_brackets"])
        self.assertEqual(self.names(raw, "artist"), [raw])

    def test_whole_credit_override_precedes_splitting(self):
        config = rules()
        config["credit_overrides"]["A, B & C"] = {
            "credits": [{"kind": "artist", "name": "A, B & C"}]}
        parser = Classifier(config)
        self.assertEqual(parser.parse("A, B & C")[0][0]["name"], "A, B & C")
        self.assertEqual([c["name"] for c in parser.parse("Alpha、A, B & C、Beta")[0]],
                         ["Alpha", "A, B & C", "Beta"])

    def test_empty_member_is_reported(self):
        self.assertIn("empty_list_member", self.parser.parse("Alpha,,Beta")[1])

    def test_unconfirmed_truncated_name_not_completed(self):
        raw = "Alpha\u3001\u767d\u77f3 "
        self.assertIn("possible_truncated_name", self.parser.parse(raw)[1])
        self.assertNotIn("\u767d\u77f3\u7d2c", self.names(raw))

    def test_confirmed_truncated_name_completed_by_config(self):
        config = rules()
        config["credit_overrides"]["\u767d\u77f3"] = {
            "credits": [{"kind": "character", "name": "\u767d\u77f3\u7d2c"}]}
        credits, issues = Classifier(config).parse("Alpha\u3001\u767d\u77f3 ")
        self.assertEqual([c["name"] for c in credits], ["Alpha", "\u767d\u77f3\u7d2c"])
        self.assertEqual(credits[1]["raw"], "\u767d\u77f3 ")
        self.assertEqual(credits[1]["entity_id"], identity("character", "\u767d\u77f3\u7d2c", "idolmaster"))
        self.assertEqual(issues, [])

    def test_known_name_variants_merge_but_unknown_names_do_not(self):
        self.assertEqual(self.names("\u767d\u77f3 \u7d2c(CV:A)"), ["\u767d\u77f3\u7d2c"])
        self.assertEqual(self.names("\uff21\uff4c\uff50\uff48\uff41"), ["Alpha"])
        self.assertEqual(self.names("Name One", "artist"), ["Name One"])
        self.assertEqual(self.names("NameOne", "artist"), ["NameOne"])

    def test_placeholder_not_an_artist(self):
        self.assertEqual(self.parser.parse("N/A"), ([], ["placeholder_artist"]))

    def test_source_suffix_ignored_only_for_known_character(self):
        self.assertEqual(self.names("Alpha(CV:A) from SHOW"), ["Alpha"])
        self.assertEqual(self.names("Singer from SHOW", "artist"), ["Singer from SHOW"])

    def test_new_cv_character_does_not_teach_bare_name(self):
        self.assertEqual(self.names("New(CV:Actor)"), ["New"])
        self.assertEqual(self.parser.parse("New(CV:Actor)")[1], ["new_character"])
        self.assertEqual(self.names("New", "artist"), ["New"])


class ClassificationTest(unittest.TestCase):
    def test_recast_and_native_multivalue_membership_dedup(self):
        data = source([record("Alpha(CV:A)", "Alpha(CV:A)", "Beta"),
                       record("Alpha(CV:B)", path=r"idol_master\two.flac")])
        before = copy.deepcopy(data)
        result, _ = classify(data, bindings(), {"rules.json": rules()}, "hash")
        chars = [e for e in result["entities"] if e["kind"] == "character"]
        self.assertEqual([(e["name"], e["track_count"]) for e in chars], [("Alpha", 2), ("Beta", 1)])
        self.assertEqual(len(result["tracks"][0]["memberships"]["character"]), 2)
        self.assertNotIn("voice_actor", result["tracks"][0]["memberships"])
        self.assertEqual(data, before)

    def test_only_bound_folder_and_descendants_match(self):
        paths = [r"idol_master\one.flac", r"idol_master\sub\two.flac",
                 r"idol_master_backup\three.flac", r"other\idol_master\four.flac",
                 r"other\five.flac", r"IDOL_MASTER\six.flac"]
        result, review = run([record("Alpha(CV:A)", path=p) for p in paths])
        self.assertEqual([t["profile"] for t in result["tracks"]],
                         ["idolmaster", "idolmaster", None, None, None, "idolmaster"])
        self.assertEqual(len(review["signatures"]), 2)
        self.assertEqual(result["summary"]["profile_file_counts"], {"idolmaster": 3, "unbound": 3})
        for t in result["tracks"][2:5]:
            self.assertEqual(t["memberships"]["character"], [])
            self.assertEqual(t["credits"][0]["name"], "Alpha(CV:A)")

    def test_two_profiles_do_not_share_identity_or_parse_cache(self):
        other = rules()
        other["id"] = "other"
        other["characters"] = []
        other["shared_prefix_shorthands"] = []
        mapping = bindings()
        mapping["bindings"].append({"folder": ROOT + r"\other", "config": "other.json"})
        result, review = classify(source([record("Alpha"), record("Alpha", path=r"other\two.flac")]),
                                  mapping, {"rules.json": rules(), "other.json": other}, "hash")
        self.assertEqual(len(review["signatures"]), 2)
        self.assertEqual([c["kind"] for c in result["tracks"][0]["credits"][:1]], ["character"])
        self.assertEqual([c["kind"] for c in result["tracks"][1]["credits"][:1]], ["artist"])
        self.assertNotEqual(identity("character", "Alpha", "idolmaster"),
                            identity("character", "Alpha", "other"))

    def test_deepest_folder_binding_wins(self):
        other = rules()
        other["id"] = "nested"
        mapping = bindings()
        mapping["bindings"].append({"folder": FOLDER + r"\nested", "config": "nested.json"})
        result, _ = classify(source([record("Alpha", path=r"idol_master\nested\one.flac")]),
                             mapping, {"rules.json": rules(), "nested.json": other}, "hash")
        self.assertEqual(result["tracks"][0]["profile"], "nested")

    def test_new_songs_do_not_change_old_classification(self):
        first, _ = run([record("Unknown", "Alpha")])
        later, _ = run([record("Unknown", "Alpha"),
                        record("Unknown(CV:Actor)", path=r"idol_master\new.flac")])
        self.assertEqual(first["tracks"][0], later["tracks"][0])

    def test_album_conflict_remains_separate_and_ordered(self):
        result, _ = run([record("Solo", album=["Team", "Team", "Other"],
                                flags=["albumartist_alias_conflict"])])
        t = result["tracks"][0]
        self.assertEqual([c["name"] for c in t["credits"] if c["kind"] == "album_artist"],
                         ["Other", "Team"])
        self.assertEqual(t["memberships"]["group"], [])
        self.assertIn("album_artist_field_conflict", t["issues"])

    def test_unavailable_tags_and_missing_tags_stay_distinct(self):
        result, _ = run([record(path=r"idol_master\one.wav", album=[], status="no_tags_exposed"),
                         record("Composer", path=r"idol_master\two.flac", album=[])])
        self.assertNotIn("missing_artist", result["tracks"][0]["issues"])
        self.assertNotIn("missing_album_artist", result["tracks"][0]["issues"])
        self.assertIn("missing_album_artist", result["tracks"][1]["issues"])

    def test_empty_artist_not_filled_from_album(self):
        result, _ = run([record()])
        self.assertEqual(result["tracks"][0]["memberships"]["artist"], [])
        self.assertIn("missing_artist", result["tracks"][0]["issues"])

    def test_invalid_source_paths_and_arrays_rejected(self):
        for records in [[record(), record()], [record(), record(path=r"IDOL_MASTER\ONE.FLAC")],
                        [record(path=r"..\outside.flac")], [record(path=r"C:\outside.flac")],
                        [record(path=r"\outside.flac")], [record(path="one.flac:stream")],
                        [dict(record(), artist="not an array")]]:
            with self.subTest(records=records), self.assertRaises(ValueError):
                run(records)

    def test_invalid_bindings_and_catalogue_rejected(self):
        mapping = bindings()
        mapping["bindings"].append({"folder": FOLDER.lower(), "config": "rules.json"})
        with self.assertRaises(ValueError):
            run([record("Alpha")], mapping=mapping)
        config = rules()
        config["characters"].append("Al pha")
        with self.assertRaises(ValueError):
            run([record("Alpha")], profile=config)
        mapping = bindings()
        mapping["bindings"][0]["folder"] = r"D:\Music\..\idol_master"
        with self.assertRaises(ValueError):
            run([record("Alpha")], mapping=mapping)

    def test_deterministic_output_and_readback(self):
        records = [record("Alpha(CV:A)"), record("Team", path=r"idol_master\two.flac")]
        result, review = run(records)
        self.assertEqual((result, review), run(records))
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            write_outputs(output, result, review)
            first = {p.name: p.read_bytes() for p in output.iterdir()}
            write_outputs(output, result, review)
            self.assertEqual(first, {p.name: p.read_bytes() for p in output.iterdir()})
            verify(source(records), json.loads((output / "classification.json").read_text(encoding="utf-8")))


class LocalProfileTest(unittest.TestCase):
    def frozen_configuration(self):
        # These are regression tests for the archived parser, not the active profile.
        mapping = json.loads((HERE / "folder-bindings.json").read_text(encoding="utf-8"))
        for binding in mapping["bindings"]:
            binding["config"] = "rules.json"
        profiles = {"rules.json": json.loads((HERE / "rules.json").read_text(encoding="utf-8"))}
        return mapping, profiles

    def test_current_profile_valid_and_no_bogus_shared_prefix_roles(self):
        mapping, profiles = self.frozen_configuration()
        config = profiles["rules.json"]
        self.assertEqual(len(config["characters"]), 187)
        self.assertNotIn("\u771f\u7f8e", config["characters"])
        self.assertTrue(all(not name.startswith("×") for name in config["characters"]))
        self.assertEqual(len(mapping["bindings"]), 1)

    @unittest.skipUnless(REPORT_PATH.is_file(), "Personal report snapshot is not in Git")
    def test_actual_report_has_no_unknown_cv_characters_or_lost_records(self):
        raw = REPORT_PATH.read_bytes()
        data = json.loads(raw)
        mapping, profiles = self.frozen_configuration()
        result, review = classify(data, mapping, profiles, hashlib.sha256(raw).hexdigest())
        self.assertEqual(result["summary"]["source_files"], 4235)
        self.assertEqual(result["summary"]["entities_by_kind"]["character"], 187)
        self.assertNotIn("new_character", result["summary"]["issue_file_counts"])
        self.assertNotIn("possible_truncated_name", result["summary"]["issue_file_counts"])
        self.assertEqual(result["summary"]["profile_file_counts"], {"idolmaster": 4235})
        self.assertTrue(all(c["kind"] != "voice_actor" for s in review["signatures"] for c in s["credits"]))
        parser = Classifier(profiles["rules.json"])
        for entity in result["entities"]:
            if entity["kind"] != "artist":
                continue
            protected = [m.span() for pattern in parser.protected
                         for m in pattern.finditer(entity["name"].translate(SYNTAX))]
            parts, balanced = split_top(entity["name"], SEPARATORS, protected)
            self.assertTrue(not balanced or len(parts) == 1, entity["name"])
        union = next(t for t in result["tracks"]
                     if t["source"]["path"].endswith("003-UNION!!.flac"))
        self.assertIn(identity("character", "白石紬", "idolmaster"),
                      union["memberships"]["character"])
        self.assertEqual(len(union["memberships"]["character"]), 17)
        self.assertEqual(REPORT_PATH.read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
