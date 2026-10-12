import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).with_name("body_heirs.py")
A = "00000000-0000-4000-8000-00000000000a"
B = "00000000-0000-4000-8000-00000000000b"


def character(n, status="ALIVE", seconds=0, active=False):
    cid = f"11111111-1111-4111-8111-{n:012d}"
    return cid, {"id": cid, "name": f"C{n}", "status": status, "online-playtime-seconds": seconds,
                 "active": str(active).lower()}


class BodyHeirsTest(unittest.TestCase):
    def run_tool(self, accounts, overrides=None):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        data = root / "data"
        (data / "playerdata").mkdir(parents=True)
        for owner, characters in accounts.items():
            (data / "playerdata" / f"{owner}.json").write_text("{}")
            folder = data / "characterdata" / owner
            folder.mkdir(parents=True)
            for cid, record in characters:
                (folder / f"{cid}.json").write_text(json.dumps(record))
        args = [sys.executable, "-I", str(TOOL), str(data), str(root / "out")]
        if overrides:
            (root / "ov.yml").write_text("version: 1\nheirs:\n" + "".join(f"  {o}: {h}\n" for o, h in overrides.items()))
            args += ["--overrides", str(root / "ov.yml")]
        result = subprocess.run(args, capture_output=True, text=True)
        manifest = (root / "out" / "character-body-heirs.yml").read_text()
        report = (root / "out" / "character-body-heirs-review.md").read_text()
        return result.returncode, manifest, report

    def test_active_living_character_inherits(self):
        old, played = character(1, seconds=500)
        new, current = character(2, seconds=10, active=True)
        code, manifest, report = self.run_tool({A: [(old, played), (new, current)]})
        self.assertEqual(code, 0)
        self.assertEqual(manifest, f"version: 1\nheirs:\n  {A}: {new}\n")
        self.assertIn("## Heir is not the most-played character\n\n- ?", report)

    def test_most_played_living_character_inherits_without_an_active_one(self):
        dead, gone = character(1, status="DEAD", seconds=900, active=True)
        low, little = character(2, seconds=5)
        high, lots = character(3, seconds=50)
        code, manifest, report = self.run_tool({A: [(dead, gone), (low, little), (high, lots)]})
        self.assertIn(f"  {A}: {high}\n", manifest)
        self.assertIn("the active character is dead", report)

    def test_account_with_only_dead_characters_needs_review(self):
        cid, record = character(1, status="DEAD", seconds=60)
        _, manifest, report = self.run_tool({A: [(cid, record)]})
        self.assertIn(f"  {A}: {cid}\n", manifest)
        self.assertIn("no living character", report)

    def test_override_wins_and_must_belong_to_the_account(self):
        one, first = character(1, active=True)
        two, second = character(2)
        _, manifest, _ = self.run_tool({A: [(one, first), (two, second)]}, {A: two})
        self.assertIn(f"  {A}: {two}\n", manifest)
        code, manifest, report = self.run_tool({A: [(one, first)]}, {A: two})
        self.assertEqual(code, 1)
        self.assertNotIn(A, manifest)
        self.assertIn("override-not-found", report)

    def test_duplicate_character_ids_block_both_accounts(self):
        cid, record = character(1, active=True)
        code, manifest, report = self.run_tool({A: [(cid, record)], B: [(cid, record)]})
        self.assertEqual(code, 1)
        self.assertEqual(manifest, "version: 1\nheirs:\n")
        self.assertIn("duplicate-character-id", report)

    def test_account_without_characters_gets_no_heir(self):
        _, manifest, report = self.run_tool({A: []})
        self.assertEqual(manifest, "version: 1\nheirs:\n")
        self.assertIn(f"## Accounts with no characters\n\nThese get no heir. Their current inventory is kept in "
                      f"quarantine at cutover.\n\n- ? {A}", report)


if __name__ == "__main__":
    unittest.main()
