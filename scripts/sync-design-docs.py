#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Graft contributors
"""Copy Graft's design notes (Obsidian) into docs/design/ as plain Markdown.

The notes are written in Obsidian, where they are the source of truth. This script
mirrors them into the repository so contributors and coding agents can read them:

- [[projects/graft/X|label]] wikilinks become relative Markdown links;
- links to anything outside projects/graft/ become plain text, and are reported;
- Obsidian callouts become GitHub alerts;
- files deleted from the notes are deleted from docs/design/.

Usage:
    scripts/sync-design-docs.py --src <vault>/projects/graft [--out docs/design] [--check]

--src defaults to $GRAFT_DESIGN_DOCS. --check writes nothing and exits 1 if
docs/design/ is out of date.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from urllib.parse import quote

GENERATED = "<!-- Generated from the Graft design notes by scripts/sync-design-docs.py. Edit the notes, not this file. -->"
WIKILINK = re.compile(r"\[\[([^\]]+?)\]\]")
CALLOUT = re.compile(r"^(\s*>\s*)\[!(\w+)\][+-]?\s*(.*)$")
CALLOUT_KIND = {
    "note": "NOTE", "abstract": "NOTE", "info": "NOTE", "question": "NOTE", "example": "NOTE",
    "tip": "TIP", "success": "TIP",
    "important": "IMPORTANT",
    "warning": "WARNING",
    "danger": "CAUTION", "caution": "CAUTION", "failure": "CAUTION", "bug": "CAUTION",
}
FORBIDDEN = ("/Users/", "iCloud~md~obsidian", "C:\\Users\\")


def slug(heading: str) -> str:
    """GitHub-style heading anchor."""
    s = heading.strip().lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


def convert_link(raw: str, note: Path, src: Path, prefix: str, outside: list[str]) -> str:
    # In tables the alias separator is escaped as "\|".
    target, _, label = raw.replace("\\|", "|").partition("|")
    target, _, anchor = target.partition("#")
    label = label or Path(target).name or anchor
    if not target.startswith(prefix):
        outside.append(target)
        return label
    dest = src / (target[len(prefix):] + ".md")
    if not dest.exists():
        raise SystemExit(f"{note.relative_to(src)}: broken link [[{raw}]]")
    rel = os.path.relpath(dest, note.parent).replace(os.sep, "/")
    url = quote(rel) + (f"#{slug(anchor)}" if anchor else "")
    return f"[{label}]({url})"


def convert(text: str, note: Path, src: Path, prefix: str, outside: list[str]) -> str:
    out: list[str] = []
    in_fence = False
    lines = text.split("\n")
    i = 0
    # Insert the generated marker after the front matter, if any.
    if lines and lines[0] == "---":
        end = lines.index("---", 1)
        out.extend(lines[: end + 1])
        i = end + 1
    out.append(GENERATED)
    for line in lines[i:]:
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            out.append(line)
            continue
        if in_fence:
            out.append(line)
            continue
        m = CALLOUT.match(line)
        if m:
            quote_prefix, kind, title = m.groups()
            out.append(f"{quote_prefix}[!{CALLOUT_KIND.get(kind.lower(), 'NOTE')}]")
            if title:
                out.append(f"{quote_prefix}**{title}**")
            continue
        out.append(WIKILINK.sub(lambda mm: convert_link(mm.group(1), note, src, prefix, outside), line))
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", default=os.environ.get("GRAFT_DESIGN_DOCS"), help="the projects/graft folder of the vault")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "docs" / "design"))
    parser.add_argument("--check", action="store_true", help="exit 1 if the output is out of date; write nothing")
    args = parser.parse_args()
    if not args.src:
        parser.error("--src or $GRAFT_DESIGN_DOCS is required")

    src, out = Path(args.src).resolve(), Path(args.out).resolve()
    if not (src / "00 - Overview.md").exists():
        parser.error(f"{src} doesn't look like the Graft design notes (no '00 - Overview.md')")
    prefix = f"projects/{src.name}/"

    wanted: dict[Path, str] = {}
    outside: list[str] = []
    for note in sorted(src.rglob("*.md")):
        rel = note.relative_to(src)
        if any(part.startswith(("_", ".")) for part in rel.parts):
            continue  # private working folders (_spec/) and hidden files
        text = convert(note.read_text(encoding="utf-8"), note, src, prefix, outside)
        for word in FORBIDDEN:
            if word in text:
                raise SystemExit(f"{rel}: contains a local path ({word!r}); remove it from the note")
        wanted[out / rel] = text

    existing = {p for p in out.rglob("*.md")} if out.exists() else set()
    stale = existing - wanted.keys()
    changed = [p for p, t in wanted.items() if not p.exists() or p.read_text(encoding="utf-8") != t]

    if args.check:
        for p in sorted(changed):
            print(f"out of date: {p.relative_to(out)}")
        for p in sorted(stale):
            print(f"stale: {p.relative_to(out)}")
        return 1 if changed or stale else 0

    for p in changed:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(wanted[p], encoding="utf-8")
    for p in stale:
        p.unlink()
    for d in sorted({p.parent for p in stale}, key=lambda x: len(x.parts), reverse=True):
        if d != out and d.exists() and not any(d.iterdir()):
            d.rmdir()

    print(f"{len(wanted)} notes, {len(changed)} written, {len(stale)} removed -> {out}")
    if outside:
        print(f"{len(outside)} links outside Graft's notes became plain text:")
        for t in sorted(set(outside)):
            print(f"  {t}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
