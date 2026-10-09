#!/usr/bin/env python3
"""Command line front end for the Android string-resource tooling.

    scripts/i18n.py check                  # CI gate: is every locale complete?
    scripts/i18n.py check --show-warnings  # also list every warning
    scripts/i18n.py check --json           # machine-readable report
    scripts/i18n.py plan --locale es       # extract untranslated entries
    scripts/i18n.py merge                  # merge translated bundles back

`check` exits 1 when a locale is incomplete or structurally wrong, so it can be
used directly as a CI gate.  See docs/i18n.md.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

try:  # keep a GBK Windows console from crashing on « ⇄ » or 「」
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover - not a tty, or already configured
    pass

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_i18n as i18n  # noqa: E402  (path set above)

DEFAULT_WORK_DIR = ".i18n-work"
DEFAULT_ALLOW_IDENTICAL = Path(__file__).resolve().parent / "i18n-allow-identical.json"
EXAMPLES_PER_CATEGORY = 3


def _report_errors(errors: list) -> None:
    print(f"\nERRORS ({len(errors)})")
    current = None
    for issue in sorted(errors, key=lambda i: (i.locale, i.location, i.key)):
        if issue.locale != current:
            current = issue.locale
            print(f"  [{current}]")
        print("    " + issue.render())


def _report_warnings(warnings: list, show_all: bool) -> None:
    print(f"\nWARNINGS ({len(warnings)}) — advisory, they do not fail the gate")
    grouped: dict[str, list] = {}
    for issue in warnings:
        grouped.setdefault(issue.category, []).append(issue)
    for category in sorted(grouped):
        items = grouped[category]
        locales = ", ".join(sorted({i.locale for i in items}))
        print(f"  {category}: {len(items)}  ({locales})")
        shown = items if show_all else items[:EXAMPLES_PER_CATEGORY]
        for issue in shown:
            print("      " + issue.render())
        if len(items) > len(shown):
            print(f"      ... {len(items) - len(shown)} more (--show-warnings, or --json)")


def cmd_check(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    locales = tuple(args.locale) if args.locale else i18n.FULL_LOCALES
    overlays = tuple(args.overlay) if args.overlay else i18n.OVERLAY_LOCALES
    modules = args.module or i18n.discover_modules(root)
    allow_identical = {} if args.no_allow_identical else i18n.load_allow_identical(
        Path(args.allow_identical) if args.allow_identical else DEFAULT_ALLOW_IDENTICAL)
    issues = i18n.check_repository(
        root, locales=locales, overlays=overlays, modules=modules,
        fail_on_leftovers=args.fail_on_leftovers, allow_identical=allow_identical,
    )
    errors = [i for i in issues if i.level == "error"]
    warnings = [i for i in issues if i.level == "warning"]

    if args.json:
        print(json.dumps({
            "root": str(root),
            "locales": list(locales),
            "modules": modules,
            "errors": [i.as_dict() for i in errors],
            "warnings": [i.as_dict() for i in warnings],
        }, ensure_ascii=False, indent=2))
        return 1 if errors else 0

    print(f"locales: {', '.join(locales)}")
    print(f"modules: {', '.join(modules)}")
    print(f"translatable keys per locale: {i18n.count_translatable_keys(root, modules)}")
    if errors:
        _report_errors(errors)
    if warnings:
        _report_warnings(warnings, args.show_warnings)
    print(f"\n{len(errors)} error(s), {len(warnings)} warning(s)")
    if not errors:
        print(f"OK: every locale matches the English default")
    return 1 if errors else 0


def cmd_report(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    locales = tuple(args.locale) if args.locale else i18n.FULL_LOCALES
    modules = args.module or i18n.discover_modules(root)
    payload = []
    for locale in locales:
        clashes = i18n.consistency_report(root, locale, modules)
        register = i18n.register_violations(root, locale, modules)
        length = i18n.length_outliers(root, locale, modules)
        payload.append({
            "locale": locale,
            "clashes": [{"english": c.english, "chinese": c.chinese,
                         "variants": c.variants} for c in clashes],
            "register_violations": [{"location": loc, "text": text} for loc, text in register],
            "length_outliers": [{"location": loc, "english": en, "target": t}
                                for loc, en, t in length],
        })

    if args.json:
        print(json.dumps({"root": str(root), "report": payload}, ensure_ascii=False, indent=2))
        return 0

    for entry in payload:
        locale = entry["locale"]
        style = i18n.REGISTER_STYLE.get(locale)
        print(f"\n{'=' * 74}\n{locale}"
              + (f"   (house register: {style[0]})" if style else "")
              + f"\n{'=' * 74}")

        clashes = entry["clashes"]
        print(f"\n[consistency] {len(clashes)} source string(s) rendered more than one way")
        for clash in clashes[:args.max_clashes]:
            print(f"    EN {clash['english']!r}   ZH {clash['chinese']!r}")
            for variant, keys in sorted(clash["variants"].items()):
                shown = ", ".join(keys[:4]) + (" …" if len(keys) > 4 else "")
                print(f"        {variant!r}  ({len(keys)}) {shown}")
        if len(clashes) > args.max_clashes:
            print(f"    ... {len(clashes) - args.max_clashes} more (--max-clashes, or --json)")

        register = entry["register_violations"]
        print(f"\n[register] {len(register)} string(s) outside the house style")
        for item in register[:10]:
            print(f"    {item['location']}: {item['text'][:80]!r}")
        if len(register) > 10:
            print(f"    ... {len(register) - 10} more")

        length = entry["length_outliers"]
        print(f"\n[length] {len(length)} string(s) far longer than English — check the layout")
        for item in length[:5]:
            print(f"    {item['location']}: {len(item['english'])} -> {len(item['target'])}")
            print(f"        EN {item['english'][:80]!r}")
            print(f"        -> {item['target'][:100]!r}")
        if len(length) > 5:
            print(f"    ... {len(length) - 5} more")

    print("\nAdvisory only — this never fails the build. The lists need a human decision;"
          "\nsome splits are correct (grammatical gender, a microphone vs the audio output)."
          "\nSee docs/i18n.md § Review policy.")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    work_dir = Path(args.work_dir)
    if not work_dir.is_absolute():
        work_dir = root / work_dir
    manifest = i18n.plan(root, locale=args.locale, work_dir=work_dir,
                         reference_locale=args.reference, modules=args.module or None)
    if not manifest["files"]:
        print(f"{args.locale}: nothing to translate — every key already exists")
        return 0
    i18n.save_manifest(work_dir, manifest)
    print(f"wrote {len(manifest['files'])} bundle(s), {manifest['entries']} entries")
    for item in manifest["files"]:
        reference = " +reference" if item["reference_bundle"] else ""
        print(f"  {item['module']}/{item['file']}: {item['count']} entries{reference}")
    print("\nTranslate each *.src.xml into a sibling *.out.xml with the same keys, then run `merge`.")
    return 0


def cmd_merge(args: argparse.Namespace) -> int:
    root = Path(args.root).resolve()
    work_dir = Path(args.work_dir)
    if not work_dir.is_absolute():
        work_dir = root / work_dir
    if not (work_dir / "manifest.json").is_file():
        print(f"no manifest at {work_dir / 'manifest.json'}; run `plan` first", file=sys.stderr)
        return 2
    written, problems = i18n.merge(root, i18n.load_manifest(work_dir))
    print(f"merged {written} file(s)")
    for problem in problems:
        print("  PROBLEM " + problem)
    return 1 if problems else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]),
                        help="repository root (defaults to the parent of scripts/)")
    sub = parser.add_subparsers(dest="command", required=True)

    check = sub.add_parser("check", help="verify every locale against the English default")
    check.add_argument("--locale", action="append", help="locale to check (repeatable)")
    check.add_argument("--overlay", action="append", help="declared partial fallback (repeatable)")
    check.add_argument("--module", action="append", help="module to check (repeatable)")
    check.add_argument("--json", action="store_true", help="emit a JSON report")
    check.add_argument("--show-warnings", action="store_true", help="list every warning")
    check.add_argument("--fail-on-leftovers", action="store_true",
                       help="treat values identical to English as errors instead of warnings")
    check.add_argument("--allow-identical", help="baseline of accepted identical values "
                                                 "(default: scripts/i18n-allow-identical.json)")
    check.add_argument("--no-allow-identical", action="store_true",
                       help="ignore the baseline and report every identical value")
    check.set_defaults(func=cmd_check)

    report = sub.add_parser(
        "report",
        help="advisory consistency / register / length report (never fails)")
    report.add_argument("--locale", action="append", help="locale to report (repeatable)")
    report.add_argument("--module", action="append", help="module to report (repeatable)")
    report.add_argument("--json", action="store_true", help="emit a JSON report")
    report.add_argument("--max-clashes", type=int, default=12,
                        help="clash groups to print per locale (default: 12)")
    report.set_defaults(func=cmd_report)

    plan = sub.add_parser("plan", help="extract untranslated entries as per-file bundles")
    plan.add_argument("--locale", required=True, help="target locale, e.g. es or pt-rBR")
    plan.add_argument("--reference", default="zh-rCN",
                      help="locale bundled alongside as a semantic reference (default: zh-rCN; 'none' to skip)")
    plan.add_argument("--work-dir", default=DEFAULT_WORK_DIR, help=f"bundle directory (default: {DEFAULT_WORK_DIR})")
    plan.add_argument("--module", action="append", help="module to include (repeatable)")
    plan.set_defaults(func=cmd_plan)

    merge = sub.add_parser("merge", help="merge translated bundles into the resource tree")
    merge.add_argument("--work-dir", default=DEFAULT_WORK_DIR, help=f"bundle directory (default: {DEFAULT_WORK_DIR})")
    merge.set_defaults(func=cmd_merge)

    args = parser.parse_args(argv)
    if str(getattr(args, "reference", "")).lower() == "none":
        args.reference = None
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
