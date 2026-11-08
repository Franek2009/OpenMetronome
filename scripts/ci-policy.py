#!/usr/bin/env python3
"""CI scope, local Markdown links and final gate. Standard library; never builds/signs."""
import argparse
import html
from html.parser import HTMLParser
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from urllib.parse import unquote, urlsplit

SHA = re.compile(r"[0-9a-f]{40}")
ROOT_DOCS = {"README.md", "CHANGELOG.md", "PRIVACY.md"}


def git(repo, *args):
    return subprocess.check_output(["git", *args], cwd=repo, stderr=subprocess.PIPE)


def parse_changes(raw):
    fields = raw.decode("utf-8").split("\0")
    changes = []
    while fields and fields[0]:
        header = fields.pop(0).split()
        if len(header) != 5 or not header[0].startswith(":"):
            raise ValueError("Invalid raw diff")
        old_mode, new_mode = header[0][1:], header[1]
        status = header[4]
        if not re.fullmatch(r"[AMDTURC][0-9]*", status):
            raise ValueError("Unknown diff status")
        count = 2 if status[0] in "RC" else 1
        paths = [fields.pop(0) for _ in range(count)]
        if not all(paths):
            raise ValueError("Missing diff path")
        changes.append(dict(status=status[0], paths=paths, old_mode=old_mode, new_mode=new_mode))
    if fields != [""]:
        raise ValueError("Incomplete raw diff")
    return changes


def documentation(path):
    canonical = PurePosixPath(path)
    return (not canonical.is_absolute() and str(canonical) == path and ".." not in canonical.parts
            and (path in ROOT_DOCS or (path.startswith("docs/") and path.endswith(".md"))))


def docs_only(changes):
    return bool(changes) and all(
        change["status"] in {"A", "M", "D", "R", "C"}
        and {change["old_mode"], change["new_mode"]} <= {"000000", "100644"}
        and all(documentation(path) for path in change["paths"])
        for change in changes)


def classify(repo, event_name, event):
    head = git(repo, "rev-parse", "HEAD").decode().strip()
    report = dict(mode="full", head=head, base=None, changes=[], diff_available=False,
                  reason="Unavailable comparison; full verification required")
    try:
        if event_name == "pull_request":
            candidate = event["pull_request"]["base"]["sha"]
            expected_head = event["pull_request"]["head"]["sha"]
        elif event_name == "push":
            candidate = event["before"]
            expected_head = event["after"]
        elif event_name == "workflow_dispatch":
            report["reason"] = "Manual dispatch always requires full verification"
            candidate = git(repo, "rev-parse", "HEAD^").decode().strip()
            expected_head = head
        else:
            return report
        if not SHA.fullmatch(candidate) or candidate == "0" * 40 or expected_head != head:
            return report
        if event_name == "pull_request":
            base = git(repo, "merge-base", candidate, head).decode().strip()
        else:
            subprocess.run(["git", "merge-base", "--is-ancestor", candidate, head], cwd=repo,
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            base = candidate
        changes = parse_changes(git(repo, "diff", "--raw", "-z", "--no-abbrev", "--find-renames", base, head, "--"))
        report.update(base=base, changes=changes, diff_available=True)
        if event_name != "workflow_dispatch":
            report["mode"] = "docs" if docs_only(changes) else "full"
            report["reason"] = ("Only allowlisted Markdown files changed" if report["mode"] == "docs"
                                else "Application, unknown paths, file types or an empty diff require full verification")
    except (KeyError, TypeError, ValueError, IndexError, UnicodeError, subprocess.SubprocessError):
        # Any incomplete comparison keeps the conservative full mode.
        pass
    return report


def without_code(text):
    lines, fence = [], None
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})(.*)$", line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence) and not marker[2].strip():
                fence = None
            lines.append("")
        elif fence is None:
            lines.append(line)
    rendered = re.sub(r"<!--.*?-->", "", "\n".join(lines), flags=re.S)
    return re.sub(r"(`+).*?\1", "", rendered)


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attributes):
        self.links.extend(value for key, value in attributes if key in {"href", "src"} and value)


def markdown_links(text):
    text = without_code(text)
    definitions = {}
    for match in re.finditer(r"(?m)^\s{0,3}\[([^\]\n]+)\]:\s*(<[^>\n]+>|\S+)", text):
        definitions[" ".join(match[1].lower().split())] = match[2]
    links = [m[1] for m in re.finditer(
        r"(?<!\\)!?\[[^\]\n]*\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+[\"'][^\n]*?[\"'])?\s*\)", text)]
    for match in re.finditer(r"(?<!\\)!?\[([^\]\n]+)\](?:\[([^\]\n]*)\])?(?!\(|:)", text):
        label = match[2] or match[1]
        value = definitions.get(" ".join(label.lower().split()))
        if value:
            links.append(value)
    parser = HtmlLinks()
    parser.feed(text)
    return links + parser.links


def local_target(repo, source, link):
    target = urlsplit(html.unescape(link.strip("<>")))
    if target.scheme or target.netloc or not target.path:
        return None
    path = unquote(target.path)
    return ((repo / path.lstrip("/")) if path.startswith("/") else source.parent / path).resolve()


def check_docs(repo, report):
    repo = Path(repo).resolve()
    if not report["diff_available"]:
        return dict(status="NOT RUN", reason="No reliable diff; Android full verification selected", checked=[], errors=[])
    changed, removed = set(), set()
    for change in report["changes"]:
        if change["status"] == "D":
            removed.add((repo / change["paths"][0]).resolve())
        else:
            current = change["paths"][-1]
            if current.endswith(".md"):
                changed.add(current)
            if change["status"] == "R":
                removed.add((repo / change["paths"][0]).resolve())
    sources = set(changed)
    if removed:
        sources.update(path for path in git(repo, "ls-files", "-z", "*.md").decode().split("\0") if path)
    errors = []
    for name in sorted(sources):
        source = repo / name
        try:
            if not source.resolve().is_relative_to(repo):
                raise ValueError("Markdown source resolves outside the repository")
            for link in markdown_links(source.read_text(encoding="utf-8")):
                target = local_target(repo, source, link)
                if target is None:
                    continue
                # Unchanged docs are inspected only for references to removed files.
                if name not in changed and target not in removed:
                    continue
                if not target.is_relative_to(repo) or not target.exists():
                    errors.append(f"{name}: missing or outside-repository target {link!r}")
        except (OSError, UnicodeError, ValueError) as error:
            errors.append(f"{name}: {error}")
    return dict(status="FAIL" if errors else "PASS", checked=sorted(sources), errors=errors,
                reason="Local file links only; external URLs and heading fragments are not fetched or validated")


def evaluate(needs, cancelled=False, expected_head=None):
    expected = {"scope", "verify", "quiet", "upgrade"}
    errors = ["Workflow was cancelled"] if cancelled else []
    if set(needs) != expected:
        errors.append("Missing or unexpected required jobs")
    jobs = {name: needs.get(name, {}).get("result", "missing") for name in sorted(expected)}
    mode = needs.get("scope", {}).get("outputs", {}).get("mode", "missing")
    head = needs.get("scope", {}).get("outputs", {}).get("head", "missing")
    if not isinstance(head, str) or not SHA.fullmatch(head) or (expected_head is not None and head != expected_head):
        errors.append("Missing or mismatched source SHA")
    if jobs["scope"] != "success":
        errors.append("Scope and lightweight checks did not succeed")
    required = "skipped" if mode == "docs" else "success"
    if mode not in {"docs", "full"}:
        errors.append("Unknown CI scope")
    for name in ("verify", "quiet", "upgrade"):
        if jobs[name] != required:
            errors.append(f"{name}: expected {required}, got {jobs[name]}")
    return dict(mode=mode, head=head, status="FAIL" if errors else "PASS", jobs=jobs, errors=errors,
                android="NOT RUN — docs-only" if mode == "docs" else ("PASS" if not errors else "NOT PASSED"))


def write_report(path, report):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, ensure_ascii=True) + "\n")


def summary(title, report):
    text = f"## {title}\n\n```json\n{json.dumps(report, indent=2, ensure_ascii=True)}\n```\n"
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(text)
    print(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("scope", "result"))
    parser.add_argument("--report")
    args = parser.parse_args()
    if args.command == "result":
        try:
            cancelled = os.environ.get("CI_CANCELLED", "false")
            if cancelled not in {"true", "false"}:
                raise ValueError("Invalid cancellation state")
            report = evaluate(json.loads(os.environ.get("CI_NEEDS_JSON", "{}")), cancelled == "true",
                              os.environ.get("CI_EXPECTED_HEAD"))
        except (TypeError, AttributeError, ValueError):
            report = dict(status="FAIL", errors=["Invalid job result payload"])
        write_report(args.report or "app/build/reports/ci/result.json", report)
        summary("CI result", report)
        return 0 if report["status"] == "PASS" else 1
    event = {}
    try:
        event = json.loads(Path(os.environ.get("GITHUB_EVENT_PATH", "")).read_text())
    except (OSError, ValueError):
        pass
    repo = Path.cwd()
    report = classify(repo, os.environ.get("GITHUB_EVENT_NAME", "unknown"), event)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"mode={report['mode']}\nhead={report['head']}\n")
    report["documentation"] = check_docs(repo, report)
    whitespace = []
    if report["diff_available"]:
        result = subprocess.run(["git", "diff", "--check", report["base"], report["head"], "--"],
                                text=True, capture_output=True)
        if result.returncode:
            whitespace.append(result.stdout.strip() or result.stderr.strip() or "Diff check failed")
    report["diff_errors"] = whitespace
    write_report(args.report or "app/build/reports/ci/scope.json", report)
    summary("CI scope", report)
    return 1 if whitespace or report["documentation"]["errors"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
