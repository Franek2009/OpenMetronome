#!/usr/bin/env python3
"""Exercise conservative Git classification, Markdown checks and the final CI gate."""
import itertools
import json
import os
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("ci-policy.py").resolve()
POLICY = runpy.run_path(str(SCRIPT))


class GitPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ci-policy-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "CI Test")
        self.git("config", "user.email", "ci@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", "/dev/null")
        self.base = self.commit({"README.md": "# App\n\n[Guide](docs/guide.md)\n",
                                 "docs/guide.md": "# Guide\n", "app/Main.kt": "class App\n"})

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, stderr=subprocess.PIPE).decode().strip()

    def commit(self, changes=None):
        for name, content in (changes or {}).items():
            target = self.repo / name
            if content is None:
                target.unlink()
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(content)
        self.git("add", "-A")
        self.git("commit", "-qm", "fixture")
        return self.git("rev-parse", "HEAD")

    def scope(self, head, base=None, event="pull_request"):
        base = base or self.base
        payload = {"pull_request": {"base": {"sha": base}, "head": {"sha": head}}}
        if event == "push":
            payload = {"before": base, "after": head}
        return POLICY["classify"](self.repo, event, payload)

    def test_allowlisted_documents_only(self):
        for name in ("README.md", "CHANGELOG.md", "PRIVACY.md", "docs/plans/next.md"):
            with self.subTest(name=name):
                head = self.commit({name: "# Updated\n"})
                self.assertEqual("docs", self.scope(head)["mode"])
                self.assertEqual("docs", self.scope(head, event="push")["mode"])

    def test_application_assets_scripts_workflows_and_unknown_files_are_full(self):
        for name in ("app/Main.kt", "docs/images/icon.png", "assets/samples/source.wav",
                     "scripts/tool.py", ".github/workflows/android.yml", "gradle.properties",
                     "app/schemas/3.json", "CONTRIBUTING.md", "docs/upper.MD"):
            with self.subTest(name=name):
                head = self.commit({name: "changed\n"})
                self.assertEqual("full", self.scope(head)["mode"])

    def test_mixed_changes_and_all_commits_of_push(self):
        self.commit({"app/Main.kt": "class Changed\n"})
        head = self.commit({"README.md": "# Docs after code\n"})
        self.assertEqual("full", self.scope(head, event="push")["mode"])

    def test_pr_compares_with_merge_base_not_unrelated_base_changes(self):
        head = self.commit({"docs/guide.md": "# Updated guide\n"})
        self.git("checkout", "-qb", "advanced-main", self.base)
        advanced = self.commit({"app/Main.kt": "class Advanced\n"})
        self.git("checkout", "-q", "--detach", head)
        report = self.scope(head, advanced)
        self.assertEqual("docs", report["mode"])
        self.assertEqual(self.base, report["base"])

    def test_manual_is_full_even_for_docs(self):
        head = self.commit({"docs/guide.md": "# Updated\n"})
        report = self.scope(head, event="workflow_dispatch")
        self.assertEqual("full", report["mode"])
        self.assertTrue(report["diff_available"])
        self.assertEqual("PASS", POLICY["check_docs"](self.repo, report)["status"])

    def test_missing_zero_mismatched_or_unavailable_comparisons_are_full(self):
        head = self.commit({"docs/guide.md": "# Updated\n"})
        for base in ("0" * 40, "f" * 40, "not-a-sha"):
            report = self.scope(head, base, event="push")
            self.assertEqual("full", report["mode"])
            self.assertFalse(report["diff_available"])
        for event, payload in (("pull_request", {}), ("push", {}), ("unknown", {}),
                               ("push", {"before": self.base, "after": "a" * 40})):
            report = POLICY["classify"](self.repo, event, payload)
            self.assertEqual("full", report["mode"])
            self.assertEqual("NOT RUN", POLICY["check_docs"](self.repo, report)["status"])

    def test_empty_diff_is_full(self):
        self.assertEqual("full", self.scope(self.base)["mode"])

    def test_symlink_or_executable_markdown_is_full(self):
        (self.repo / "README.md").unlink()
        (self.repo / "README.md").symlink_to("app/Main.kt")
        self.assertEqual("full", self.scope(self.commit())["mode"])
        (self.repo / "README.md").unlink()
        (self.repo / "README.md").write_text("# Regular\n")
        (self.repo / "README.md").chmod(0o755)
        self.assertEqual("full", self.scope(self.commit())["mode"])

    def test_rename_checks_both_paths_and_references_to_old_file(self):
        self.git("mv", "docs/guide.md", "docs/new-guide.md")
        report = self.scope(self.commit())
        self.assertEqual("docs", report["mode"])
        checked = POLICY["check_docs"](self.repo, report)
        self.assertEqual("FAIL", checked["status"])
        self.assertTrue(any("README.md" in error for error in checked["errors"]))
        self.git("mv", "docs/new-guide.md", "app/guide.md")
        self.assertEqual("full", self.scope(self.commit())["mode"])

    def test_deleted_doc_link_in_unchanged_source_is_detected(self):
        report = self.scope(self.commit({"docs/guide.md": None}))
        self.assertEqual("docs", report["mode"])
        self.assertEqual("FAIL", POLICY["check_docs"](self.repo, report)["status"])

    def test_unicode_space_filename_and_html_image_links(self):
        report = self.scope(self.commit({"docs/ćwiczenie z pulsem.md": "# Guide\n",
            "README.md": '[Guide](docs/%C4%87wiczenie%20z%20pulsem.md#heading)\n'
                         '<img src="docs/guide.md">\n'}))
        self.assertEqual("PASS", POLICY["check_docs"](self.repo, report)["status"])

    def test_missing_and_outside_targets_are_detected(self):
        report = self.scope(self.commit({"README.md": "[Missing](absent.md)\n[Outside](../outside.md)\n"}))
        result = POLICY["check_docs"](self.repo, report)
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(2, len(result["errors"]))

    def test_fences_inline_code_external_urls_fragments_and_reference_links(self):
        content = '''# Docs
```markdown
[Example](not-real.md)
```
~~~
[Example](not-real-either.md)
~~~
`[Code](not-a-file.md)`
<!-- [Comment](comment-only.md) -->
[Remote](https://example.invalid/missing)
[Mail](mailto:test@example.invalid)
[Heading](#heading)
[Guide][manual]
[manual]: docs/guide.md
'''
        report = self.scope(self.commit({"README.md": content}))
        self.assertEqual("PASS", POLICY["check_docs"](self.repo, report)["status"])
        report = self.scope(self.commit({"README.md": "[Guide][manual]\n[manual]: missing.md\n"}))
        self.assertEqual("FAIL", POLICY["check_docs"](self.repo, report)["status"])

    def test_unrelated_historical_broken_links_do_not_block_deletion(self):
        base = self.commit({"docs/historical.md": "[Old placeholder](missing-forever.md)\n",
                            "docs/remove-me.md": "# Remove\n"})
        report = self.scope(self.commit({"docs/remove-me.md": None}), base)
        self.assertEqual("PASS", POLICY["check_docs"](self.repo, report)["status"])

    def test_scope_cli_outputs_full_and_fails_bad_doc(self):
        head = self.commit({"README.md": "[Broken](missing.md)\n"})
        event = self.repo / "event.json"
        event.write_text(json.dumps({"pull_request": {"base": {"sha": self.base}, "head": {"sha": head}}}))
        env = os.environ | {"GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event),
                            "GITHUB_OUTPUT": str(self.repo / "outputs"), "GITHUB_STEP_SUMMARY": str(self.repo / "summary")}
        result = subprocess.run([sys.executable, SCRIPT, "scope"], cwd=self.repo, env=env, capture_output=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("mode=docs", (self.repo / "outputs").read_text())
        event.write_text("invalid json")
        result = subprocess.run([sys.executable, SCRIPT, "scope"], cwd=self.repo, env=env, capture_output=True)
        self.assertEqual(0, result.returncode)
        report = json.loads((self.repo / "app/build/reports/ci/scope.json").read_text())
        self.assertEqual("full", report["mode"])
        self.assertEqual("NOT RUN", report["documentation"]["status"])


class GateTest(unittest.TestCase):
    def needs(self, mode, statuses):
        result = {name: {"result": value} for name, value in zip(("scope", "verify", "quiet", "upgrade"), statuses)}
        result["scope"]["outputs"] = {"mode": mode, "head": "a" * 40}
        return result

    def test_all_success_failure_cancelled_and_skipped_combinations(self):
        for mode in ("docs", "full"):
            for statuses in itertools.product(("success", "failure", "cancelled", "skipped"), repeat=4):
                with self.subTest(mode=mode, statuses=statuses):
                    expected = statuses[0] == "success" and all(
                        status == ("skipped" if mode == "docs" else "success") for status in statuses[1:])
                    report = POLICY["evaluate"](self.needs(mode, statuses))
                    self.assertEqual(expected, report["status"] == "PASS")
                    if mode == "docs":
                        self.assertEqual("NOT RUN — docs-only", report["android"])

    def test_unknown_scope_missing_or_extra_job_refused(self):
        needs = self.needs("unknown", ("success",) * 4)
        self.assertEqual("FAIL", POLICY["evaluate"](needs)["status"])
        for mutate in (lambda n: n.pop("upgrade"), lambda n: n.update(extra={"result": "success"})):
            needs = self.needs("full", ("success",) * 4)
            mutate(needs)
            self.assertEqual("FAIL", POLICY["evaluate"](needs)["status"])

    def test_workflow_cancelled_after_scope_cannot_turn_docs_green(self):
        for mode, statuses in (("docs", ("success", "skipped", "skipped", "skipped")),
                               ("full", ("success",) * 4)):
            self.assertEqual("FAIL", POLICY["evaluate"](self.needs(mode, statuses), cancelled=True)["status"])

    def test_missing_or_mismatched_source_sha_refused(self):
        needs = self.needs("docs", ("success", "skipped", "skipped", "skipped"))
        self.assertEqual("FAIL", POLICY["evaluate"](needs, expected_head="b" * 40)["status"])
        needs["scope"]["outputs"].pop("head")
        self.assertEqual("FAIL", POLICY["evaluate"](needs)["status"])

    def test_cli_malformed_payload_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, SCRIPT, "result", "--report", str(Path(directory) / "result.json")],
                                    env=os.environ | {"CI_NEEDS_JSON": "not-json"}, capture_output=True)
            self.assertEqual(1, result.returncode)


if __name__ == "__main__":
    unittest.main()
