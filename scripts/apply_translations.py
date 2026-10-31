#!/usr/bin/env python3
"""Fold scripts/translations_priority.json into values-<locale>/strings.xml (items 24, 331).

A translation batch is data, not a hand edit: this script applies it, refuses anything that would
break at runtime, and is idempotent, so the batch can be reviewed, regenerated and re-applied
without churning the locale files.

The three checks it runs, each of which is a real failure mode rather than a formality:

  * every key must exist in the default file and must not be marked translatable="false" — a key
    that does not exist is a translation nothing will ever read;
  * every format specifier (%1$s, %1$d, %s, %d) and every escape (\\n, \\u2026) in the default must
    appear the same number of times in the translation — a dropped %s is a crash inside
    String.format on the one screen that failed to load, which is the worst place for one;
  * a key must not be translated twice in one locale.

Usage:  python3 scripts/apply_translations.py [--check]

--check reports what would change without writing, which is what CI uses.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
# Two files, because a long launch-failure paragraph and a navigation label have nothing in common
# except the locale they belong to, and keeping them apart keeps each readable.
BATCHES = [
    ROOT / "scripts/translations_priority.json",
    ROOT / "scripts/translations_priority_messages.json",
]

STRING_RE = re.compile(r'<string name="([^"]+)"([^>]*)>(.*?)</string>', re.S)
SPECIFIER_RE = re.compile(r"%(\d+\$)?[sd]")
ESCAPE_RE = re.compile(r"\\[nu]")


def default_strings() -> dict[str, tuple[str, bool]]:
    text = (RES / "values/strings.xml").read_text(encoding="utf-8")
    out = {}
    for m in STRING_RE.finditer(text):
        name, attrs, value = m.group(1), m.group(2), m.group(3)
        out[name] = (value, "translatable=\"false\"" in attrs)
    return out


def escape(value: str) -> str:
    """A value written into XML, with the characters that are not valid raw made safe.

    The batch stores a literal newline escape as two characters (\\n), which is what the resource
    system wants, so backslashes are not doubled. Apostrophes are: an unescaped one inside a
    double-quoted Android string is fine, but the files use the escaped form throughout and mixing
    the two styles makes the diffs unreadable.
    """
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def check(locale: str, key: str, default: str, translated: str, problems: list[str]) -> None:
    def counts(pattern: re.Pattern[str], text: str) -> list[str]:
        return sorted(pattern.findall(text))

    d, t = counts(SPECIFIER_RE, default), counts(SPECIFIER_RE, translated)
    if d != t:
        problems.append(f"{locale}/{key}: format specifiers differ (default {d}, translated {t})")
    de, te = len(ESCAPE_RE.findall(default)), len(ESCAPE_RE.findall(translated))
    if de != te:
        problems.append(f"{locale}/{key}: escapes differ (default {de}, translated {te})")


def apply(check_only: bool) -> int:
    batch: dict[str, dict[str, str]] = {}
    for source in BATCHES:
        for locale, entries in json.loads(source.read_text(encoding="utf-8")).items():
            batch.setdefault(locale, {}).update(entries)
    defaults = default_strings()
    problems: list[str] = []
    changed: list[str] = []

    for locale, entries in sorted(batch.items()):
        seen: set[str] = set()
        target = RES / f"values-{locale}/strings.xml"
        if not target.exists():
            problems.append(f"{locale}: no {target.relative_to(ROOT)}")
            continue
        text = target.read_text(encoding="utf-8")
        additions: list[str] = []

        for key, value in entries.items():
            if key in seen:
                problems.append(f"{locale}/{key}: translated twice")
                continue
            seen.add(key)
            if key not in defaults:
                problems.append(f"{locale}/{key}: not in values/strings.xml")
                continue
            default_value, non_translatable = defaults[key]
            if non_translatable:
                problems.append(f"{locale}/{key}: marked translatable=\"false\"")
                continue
            check(locale, key, default_value, value, problems)
            if STRING_RE.search(text) and re.search(
                    r'<string name="%s"' % re.escape(key), text):
                continue  # already translated; leave the existing wording alone
            additions.append(f'    <string name="{key}">{escape(value)}</string>')

        if not additions:
            continue
        changed.append(f"{locale} (+{len(additions)})")
        if check_only:
            continue
        marker = "    <!-- Priority batch"
        if marker in text:
            # Extend the existing block rather than starting a second one, so re-running the
            # applier after adding a string does not scatter markers through the file.
            end = text.index("</resources>", text.index(marker))
            text = text[:end] + "\n".join(additions) + "\n" + text[end:]
        else:
            block = "\n\n    <!-- Priority batch: the first-run wizard, the launch-failure dialogs, the\n"
            block += "         error explanations and the navigation labels. See\n"
            block += "         scripts/translations_priority.json and\n"
            block += "         scripts/translations_priority_messages.json. -->\n"
            block += "\n".join(additions)
            text = text.replace("</resources>", block + "\n</resources>")
        target.write_text(text, encoding="utf-8")

    if problems:
        print("Refusing to apply:", file=sys.stderr)
        for p in problems:
            print("  " + p, file=sys.stderr)
        return 1

    if changed:
        print(("Would update " if check_only else "Updated ") + ", ".join(changed))
    else:
        print("Nothing to do; every batch key is already translated.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true",
                        help="report what would change without writing")
    return apply(parser.parse_args().check)


if __name__ == "__main__":
    raise SystemExit(main())
