import copy
import json
from pathlib import Path
import tempfile
import unittest

from classify_artists import Classifier, classify, identity, split_top, STRONG, verify, write_outputs


def record(*artists, path="one.flac", album=None, status="ok", flags=None):
    return {"path": path, "project": "fixture", "artist": list(artists),
            "albumartist": ["Album Team"] if album is None else album,
            "status": status, "flags": flags or []}


def source(records):
    return {"summary": {"total_audio": len(records), "root": "fixture",
                        "generated_at": "2026-10-27"},
            "files": records, "name_variant_candidates": [],
            "role_credit_variant_candidates": []}


def rules():
    return {"groups": ["Team", "Re;IRIS"],
            "shared_prefix_shorthands": [{"text": "\u53cc\u6d77\u4e9c\u7f8e/\u771f\u7f8e",
                                         "members": ["\u53cc\u6d77\u4e9c\u7f8e", "\u53cc\u6d77\u771f\u7f8e"]}],
            "placeholders": ["N/A"], "approved_aliases": {}}


class ParsingTest(unittest.TestCase):
    def setUp(self):
        self.parser = Classifier([
            record("Alpha(CV:Actor A)", "Beta(CV:Actor B)", "\u767d\u77f3\u7d2c(CV:Actor C)",
                   "\u53cc\u6d77\u4e9c\u7f8e(CV:Shared)", "\u53cc\u6d77\u771f\u7f8e(CV:Shared)")], rules())

    def names(self, text, kind):
        return [c["name"] for c in self.parser.parse(text)[0] if c["kind"] == kind]

    def test_cv_punctuation_and_original_whitespace(self):
        raw = " Alpha \uff08CV\uff0e Actor A\uff09 "
        credits, issues = self.parser.parse(raw)
        self.assertEqual(issues, [])
        self.assertEqual([c["name"] for c in credits], ["Alpha", "Actor A"])
        self.assertEqual(credits[0]["raw"], raw)

    def test_fullwidth_cv_and_lowercase(self):
        self.assertEqual(self.names("Alpha(cv:Actor A)", "character"), ["Alpha"])

    def test_collaboration_does_not_swallow_prior_cv_credit(self):
        for separator in [",", "\u3001", "/", " & ", ";", " feat. ", "feat. "]:
            raw = "Alpha(CV:Actor A)" + separator + "Beta(CV:Actor B)"
            self.assertEqual(self.names(raw, "character"), ["Alpha", "Beta"], raw)

    def test_nested_group_and_members(self):
        raw = "Team [Alpha(CV:Actor A)\u3001Beta(CV:Actor B)]"
        self.assertEqual(self.names(raw, "group"), ["Team"])
        self.assertEqual(self.names(raw, "character"), ["Alpha", "Beta"])

    def test_group_only_never_infers_members(self):
        self.assertEqual(self.names("Team", "character"), [])

    def test_implicit_cv_requires_snapshot_relationship(self):
        self.assertEqual(self.names("Alpha(Actor A)", "character"), ["Alpha"])
        credits, issues = self.parser.parse("Alpha(Actor B)")
        self.assertEqual(issues, ["role_actor_conflict"])
        self.assertEqual([c["kind"] for c in credits], ["artist"])

    def test_company_parenthesis_is_not_cv(self):
        self.assertEqual(self.names("Composer (BNSI)", "voice_actor"), [])
        self.assertEqual(self.names("Mitsu.J (Digz, Inc. Group)", "artist"),
                         ["Mitsu.J (Digz, Inc. Group)"])

    def test_literal_slash_and_semicolon_names_are_preserved(self):
        self.assertEqual(self.names("some/artist", "artist"), ["some/artist"])
        self.assertEqual(self.names("Re;IRIS", "group"), ["Re;IRIS"])

    def test_shorthand_before_slash(self):
        raw = "\u53cc\u6d77\u4e9c\u7f8e /\u771f\u7f8e(CV:Shared)"
        self.assertEqual(self.names(raw, "character"),
                         ["\u53cc\u6d77\u4e9c\u7f8e", "\u53cc\u6d77\u771f\u7f8e"])
        self.assertEqual(self.names(raw, "voice_actor"), ["Shared", "Shared"])

    def test_unbalanced_brackets_are_preserved(self):
        raw = "Alpha(CV:Actor A), Beta(CV:Actor B"
        self.assertEqual(self.parser.parse(raw)[1], ["unbalanced_brackets"])
        self.assertEqual(self.names(raw, "artist"), [raw])

    def test_empty_member_is_not_silently_dropped(self):
        self.assertIn("empty_list_member", self.parser.parse("Alpha,,Beta")[1])

    def test_truncated_name_is_not_completed(self):
        raw = "Alpha\u3001\u767d\u77f3 "
        self.assertIn("possible_truncated_name", self.parser.parse(raw)[1])
        self.assertNotIn("\u767d\u77f3\u7d2c", self.names(raw, "character"))

    def test_placeholder_is_not_an_artist(self):
        self.assertEqual(self.parser.parse("N/A"), ([], ["placeholder_artist"]))

    def test_new_explicit_member_group_is_a_proposal(self):
        raw = "New Team(Alpha\u3001Beta)"
        self.assertEqual(self.names(raw, "character"), ["Alpha", "Beta"])
        self.assertEqual(self.parser.parse(raw)[1], ["group_type_proposal"])

    def test_snapshot_bootstrap_does_not_include_feat_in_role_name(self):
        parser = Classifier([record("Alpha(CV:A) feat. Beta(CV:B)")], rules())
        self.assertEqual(set(parser.pairs), {"Alpha", "Beta"})

    def test_cv_affiliation_is_not_invented_as_actor_name(self):
        raw = "Alpha(CV:Actor A from A PROGRAM)"
        self.assertEqual(self.parser.parse(raw)[1], ["complex_cv"])
        self.assertEqual(self.names(raw, "voice_actor"), [])
        self.assertEqual(Classifier([record(raw)], rules()).actors, set())


class ClassificationTest(unittest.TestCase):
    def test_recast_and_multi_value_membership_dedup(self):
        data = source([record("Alpha(CV:A)", "Alpha(CV:A)"),
                       record("Alpha(CV:B)", path="two.flac")])
        before = copy.deepcopy(data)
        result, _ = classify(data, rules(), "hash")
        characters = [e for e in result["entities"] if e["kind"] == "character"]
        actors = [e for e in result["entities"] if e["kind"] == "voice_actor"]
        self.assertEqual([(e["name"], e["track_count"]) for e in characters], [("Alpha", 2)])
        self.assertEqual(len(actors), 2)
        self.assertEqual(len(result["tracks"][0]["memberships"]["character"]), 1)
        self.assertEqual(result["tracks"][0]["source"]["artist"],
                         ["Alpha(CV:A)", "Alpha(CV:A)"])
        self.assertEqual(data, before)

    def test_internal_whitespace_is_only_an_alias_proposal(self):
        data = source([record("Name One(CV:A)"),
                       record("NameOne(CV:A)", path="two.flac")])
        result, review = classify(data, rules(), "hash")
        self.assertEqual(result["summary"]["entities_by_kind"]["character"], 2)
        self.assertEqual(len(review["alias_proposals_not_applied"]), 1)

    def test_album_field_conflict_stays_separate(self):
        data = source([record("Solo", album=["Team", "Team", "Other"],
                              flags=["albumartist_alias_conflict"])])
        result, _ = classify(data, rules(), "hash")
        track = result["tracks"][0]
        self.assertEqual(len(track["memberships"]["album_artist"]), 2)
        self.assertEqual([c["name"] for c in track["credits"] if c["kind"] == "album_artist"],
                         ["Other", "Team"])
        self.assertEqual(track["memberships"]["group"], [])
        self.assertIn("album_artist_field_conflict", track["issues"])

    def test_unreadable_tags_not_confused_with_confirmed_missing(self):
        data = source([record(path="one.wav", album=[], status="no_tags_exposed"),
                       record("Composer", path="two.flac", album=[])])
        result, _ = classify(data, rules(), "hash")
        self.assertNotIn("missing_artist", result["tracks"][0]["issues"])
        self.assertNotIn("missing_album_artist", result["tracks"][0]["issues"])
        self.assertIn("missing_album_artist", result["tracks"][1]["issues"])

    def test_duplicate_paths_and_invalid_field_types_are_rejected(self):
        for data in [source([record(), record()]),
                     source([dict(record(), artist="not an array")])]:
            with self.assertRaises(ValueError):
                classify(data, rules(), "hash")

    def test_empty_artist_is_not_filled_from_album(self):
        result, _ = classify(source([record()]), rules(), "hash")
        self.assertEqual(result["tracks"][0]["memberships"]["artist"], [])
        self.assertIn("missing_artist", result["tracks"][0]["issues"])

    def test_explicit_alias_is_scoped_by_entity_kind(self):
        config = rules()
        config["approved_aliases"] = {"character": {"Alpha": "Canonical"}}
        result, _ = classify(source([record("Alpha(CV:Alpha)")]), config, "hash")
        self.assertEqual(result["tracks"][0]["memberships"]["character"],
                         [identity("character", "Canonical")])
        self.assertEqual(result["tracks"][0]["memberships"]["voice_actor"],
                         [identity("voice_actor", "Alpha")])

    def test_deterministic_outputs_and_readback(self):
        data = source([record("Alpha(CV:A)"), record("Team", path="two.flac")])
        result, review = classify(data, rules(), "hash")
        self.assertEqual((result, review), classify(data, rules(), "hash"))
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            write_outputs(output, result, review)
            first = {p.name: p.read_bytes() for p in output.iterdir()}
            write_outputs(output, result, review)
            self.assertEqual(first, {p.name: p.read_bytes() for p in output.iterdir()})
            verify(data, json.loads((output / "classification.json").read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
