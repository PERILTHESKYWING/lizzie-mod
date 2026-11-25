#!/usr/bin/env python3
"""Merge the original project (wimi321/lizzieyzy-next) into this mod's main branch.

Used by .github/workflows/sync-upstream.yml. Only conflicts that are known to be harmless are
resolved automatically:

* translation bundles and CHANGELOG.md, where both sides added lines next to each other: both
  sides are kept (ours first);
* scripts/architecture_budget.json: each conflicting budget takes the larger value, and the
  budgets are tightened to the merged code afterwards.

Anything else (code, workflows, ...) stops the merge so a person can look at it. Merges that
change .github/workflows are also left to a person: the workflow token may not push them.

Usage: python3 scripts/sync_upstream.py merge [--upstream URL] [--branch main]
Prints key=value lines (status=up-to-date|merged|conflict, files=...) and appends them to
$GITHUB_OUTPUT when set.
"""
import argparse
import json
import os
import re
import subprocess
import sys

UPSTREAM = "https://github.com/wimi321/lizzieyzy-next.git"
CONFLICT = re.compile(r"<<<<<<< [^\n]*\n(.*?)=======\n(.*?)>>>>>>> [^\n]*\n", re.S)
BUDGET_ENTRY = re.compile(r'^\s*"([^"]+)": (\d+),?\s*$', re.M)


def union_lines(ours, theirs):
    """Both sides' added lines: ours first, then theirs that ours does not already have."""
    a = ours.splitlines(True)
    return "".join(a + [line for line in theirs.splitlines(True) if line not in a])


def max_budgets(ours, theirs):
    a = dict(BUDGET_ENTRY.findall(ours))
    b = dict(BUDGET_ENTRY.findall(theirs))
    keys = list(a) + [k for k in b if k not in a]
    return "".join(f'    "{k}": {max(int(a.get(k, 0)), int(b.get(k, 0)))},\n' for k in keys)


def auto_resolvable(path):
    return (
        path == "CHANGELOG.md"
        or path == "scripts/architecture_budget.json"
        or re.fullmatch(r"src/main/resources/l10n/DisplayStrings[A-Za-z_]*\.properties", path)
        is not None
    )


def resolve_text(path, text):
    """Resolves all conflict blocks of an auto-resolvable file's text."""
    if path == "scripts/architecture_budget.json":
        text = CONFLICT.sub(lambda m: max_budgets(m.group(1), m.group(2)), text)
        out = re.sub(r",(\s*})", r"\1", text)  # a trailing comma left before a closing brace
        json.loads(out)  # must still be valid JSON
        return out
    return CONFLICT.sub(lambda m: union_lines(m.group(1), m.group(2)), text)


def git(*args, check=True):
    return subprocess.run(["git", *args], check=check, capture_output=True, text=True)


def emit(**values):
    lines = [f"{k}={v}" for k, v in values.items()]
    print("\n".join(lines))
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")


def merge(upstream, branch):
    git("fetch", "--no-tags", upstream, f"{branch}:refs/remotes/upstream/{branch}")
    target = f"upstream/{branch}"
    if git("merge-base", "--is-ancestor", target, "HEAD", check=False).returncode == 0:
        emit(status="up-to-date", files="")
        return 0
    behind = git("rev-list", "--count", f"HEAD..{target}").stdout.strip()
    before = git("rev-parse", "HEAD").stdout.strip()
    result = git("merge", "--no-edit", "--no-ff", target, check=False)
    if result.returncode != 0:
        conflicted = git("diff", "--name-only", "--diff-filter=U").stdout.split()
        stuck = [p for p in conflicted if not auto_resolvable(p)]
        if not conflicted or stuck:
            git("merge", "--abort", check=False)
            emit(status="conflict", files=",".join(stuck or ["(merge failed)"]), behind=behind)
            return 0
        for path in conflicted:
            with open(path, encoding="utf-8") as f:
                text = f.read()
            text = resolve_text(path, text)
            if "<<<<<<<" in text:
                git("merge", "--abort", check=False)
                emit(status="conflict", files=path, behind=behind)
                return 0
            with open(path, "w", encoding="utf-8", newline="") as f:
                f.write(text)
            git("add", path)
        git("commit", "--no-edit")
    # The workflow token may not push workflow changes; leave those merges to a person.
    workflows = [
        p for p in git("diff", "--name-only", before, "HEAD").stdout.split()
        if p.startswith(".github/workflows/")
    ]
    if workflows:
        git("reset", "--hard", before)
        emit(status="conflict", files="workflows:" + ",".join(workflows), behind=behind)
        return 0
    subprocess.run(
        [sys.executable, "scripts/check_architecture_budget.py", "--tighten"], check=False
    )
    if git("status", "--porcelain").stdout.strip():
        git("commit", "-am", "chore: tighten architecture budgets after the upstream merge")
    emit(status="merged", files="", behind=behind)
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("command", choices=["merge"])
    p.add_argument("--upstream", default=UPSTREAM)
    p.add_argument("--branch", default="main")
    args = p.parse_args(argv)
    return merge(args.upstream, args.branch)


if __name__ == "__main__":
    sys.exit(main())
