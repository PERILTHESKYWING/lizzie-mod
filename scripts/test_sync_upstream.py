import json
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import sync_upstream  # noqa: E402


class ResolveTest(unittest.TestCase):
    def test_translation_conflicts_keep_both_sides(self):
        text = (
            "A=1\n<<<<<<< HEAD\nMod.key=mod\n=======\nUp.key=up\nMod.key=mod\n"
            ">>>>>>> upstream/main\nZ=9\n"
        )
        out = sync_upstream.resolve_text("src/main/resources/l10n/DisplayStrings_ko.properties", text)
        self.assertEqual("A=1\nMod.key=mod\nUp.key=up\nZ=9\n", out)

    def test_budget_conflicts_take_the_larger_value(self):
        text = (
            '{\n  "max_lines": {\n<<<<<<< HEAD\n    "a.java": 10,\n    "b.java": 5,\n'
            '=======\n    "a.java": 12,\n    "b.java": 4,\n    "c.java": 7,\n'
            ">>>>>>> upstream/main\n  }\n}\n"
        )
        out = json.loads(sync_upstream.resolve_text("scripts/architecture_budget.json", text))
        self.assertEqual({"a.java": 12, "b.java": 5, "c.java": 7}, out["max_lines"])

    def test_only_known_files_are_resolved_automatically(self):
        self.assertTrue(sync_upstream.auto_resolvable("CHANGELOG.md"))
        self.assertTrue(
            sync_upstream.auto_resolvable("src/main/resources/l10n/DisplayStrings_zh_TW.properties")
        )
        self.assertFalse(sync_upstream.auto_resolvable("src/main/java/featurecat/lizzie/Lizzie.java"))
        self.assertFalse(sync_upstream.auto_resolvable(".github/workflows/ci.yml"))


class MergeTest(unittest.TestCase):
    """Runs the real merge against throwaway repositories."""

    def run_git(self, cwd, *args):
        subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)

    def make_repos(self, ours_change, theirs_change, path="CHANGELOG.md"):
        root = tempfile.mkdtemp()
        up = os.path.join(root, "up")
        os.makedirs(up)
        self.run_git(up, "init", "-q", "-b", "main")
        self.run_git(up, "config", "user.email", "t@example.invalid")
        self.run_git(up, "config", "user.name", "t")
        os.makedirs(os.path.join(up, "scripts"))
        with open(os.path.join(up, path), "w") as f:
            f.write("# Log\n\nbase\n")
        with open(os.path.join(up, "scripts", "check_architecture_budget.py"), "w") as f:
            f.write("print('ok')\n")
        self.run_git(up, "add", "-A")
        self.run_git(up, "commit", "-qm", "base")
        mod = os.path.join(root, "mod")
        self.run_git(root, "clone", "-q", up, mod)
        self.run_git(mod, "config", "user.email", "t@example.invalid")
        self.run_git(mod, "config", "user.name", "t")
        with open(os.path.join(mod, path), "w") as f:
            f.write("# Log\n\n" + ours_change + "base\n")
        self.run_git(mod, "commit", "-qam", "mod")
        with open(os.path.join(up, path), "w") as f:
            f.write("# Log\n\n" + theirs_change + "base\n")
        self.run_git(up, "commit", "-qam", "upstream")
        return up, mod

    def merge(self, up, mod):
        out = subprocess.run(
            [sys.executable, os.path.abspath(sync_upstream.__file__), "merge", "--upstream", up],
            cwd=mod,
            capture_output=True,
            text=True,
            env={**os.environ, "GITHUB_OUTPUT": ""},
        )
        return dict(line.split("=", 1) for line in out.stdout.split() if "=" in line)

    def test_adjacent_changelog_entries_merge(self):
        up, mod = self.make_repos("- mod entry\n", "- upstream entry\n")
        self.assertEqual("merged", self.merge(up, mod)["status"])
        with open(os.path.join(mod, "CHANGELOG.md")) as f:
            text = f.read()
        self.assertIn("- mod entry", text)
        self.assertIn("- upstream entry", text)
        self.assertEqual("up-to-date", self.merge(up, mod)["status"])

    def test_workflow_changes_are_left_to_a_person(self):
        up, mod = self.make_repos("- mod entry\n", "- upstream entry\n")
        os.makedirs(os.path.join(up, ".github", "workflows"))
        with open(os.path.join(up, ".github", "workflows", "ci.yml"), "w") as f:
            f.write("name: ci\n")
        self.run_git(up, "add", "-A")
        self.run_git(up, "commit", "-qm", "workflow")
        result = self.merge(up, mod)
        self.assertEqual("conflict", result["status"])
        self.assertIn(".github/workflows/ci.yml", result["files"])
        log = subprocess.run(["git", "log", "-1", "--format=%s"], cwd=mod, capture_output=True, text=True)
        self.assertEqual("mod", log.stdout.strip(), "main is left where it was")

    def test_code_conflicts_stop_the_sync(self):
        up, mod = self.make_repos("mod()\n", "upstream()\n", path="Main.java")
        result = self.merge(up, mod)
        self.assertEqual("conflict", result["status"])
        self.assertEqual("Main.java", result["files"])
        status = subprocess.run(
            ["git", "status", "--porcelain"], cwd=mod, capture_output=True, text=True
        ).stdout
        self.assertEqual("", status.strip(), "an aborted sync leaves the checkout clean")


if __name__ == "__main__":
    unittest.main()
