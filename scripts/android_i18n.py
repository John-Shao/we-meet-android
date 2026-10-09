"""Android string-resource tooling for We Meet Android.

Two jobs, both driven by `scripts/i18n.py`:

* **check** — a CI gate proving every supported locale is complete and
  structurally faithful to the English default (`res/values/`).  It catches the
  failure modes this repository has actually hit: missing keys, a stray
  language-only overlay such as the former `values-zh/`, and half-finished edits
  (broken format specifiers, dropped plural items, unescaped apostrophes).
* **backfill** — extract the untranslated entries of a locale as per-file
  bundles, then merge translated bundles back into the resource tree.  The merge
  is byte-preserving: it only inserts, anchors each new entry next to its
  English neighbour, and is idempotent, so a partial or repeated run is safe.

`merge` is the only function here that writes to `res/`.  Everything is pure
standard library; paths are always derived from the repository root.

Findings are split into **errors** (a locale is wrong or incomplete — the gate
fails) and **warnings** (a human should look).  Warnings are deliberately
low-noise: the text heuristics are script-aware, so Chinese punctuation and
fullwidth brackets never masquerade as defects.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

# ── resource model ──────────────────────────────────────────────────────

KINDS = ("string", "plurals", "string-array")
# Longest alternative first: a bare `string` must not win over `string-array`,
# or `<string-array name="x">` parses as a `<string>` named `x` and its body is
# silently dropped.
ENTRY_ALT = "string-array|plurals|string"
ENTRY_START = re.compile(r"^(\s*)<(" + ENTRY_ALT + r")\b([^>]*)>")
NAME_ATTR = re.compile(r'name="([^"]*)"')

#: Files that hold no translatable copy.
SKIP_FILES = frozenset({"themes.xml", "colors.xml", "styles.xml"})

#: Locales that must be complete.  Adding a language means adding it here (and
#: to `SettingsScreen.LANGUAGE_OPTIONS`); the gate fails loudly on an undeclared
#: `values-<locale>/` directory so a half-shipped language cannot slip in.
FULL_LOCALES: tuple[str, ...] = ("zh-rCN", "de", "fr", "nl")

#: Deliberate partial language fallbacks.  A directory listed here is allowed to
#: be incomplete; one that is not listed fails the gate.  Keep this empty unless
#: a partial fallback is genuinely intended — `values-zh/` was, twice, not.
OVERLAY_LOCALES: tuple[str, ...] = ()

#: Qualifiers that look like a locale but are not one.
NON_LOCALE_QUALIFIERS = frozenset({
    "car", "night", "notnight", "land", "port", "any", "watch", "television",
    "desk", "small", "normal", "large", "xlarge", "round", "widecg", "nowidecg",
    "highdr", "lowdr", "ldrtl", "ldltr",
})
LOCALE_RE = re.compile(r"^[a-z]{2,3}(?:-r[A-Z]{2})?$")

_EXCLUDED_TOP_DIRS = frozenset({
    "build", "release", "artifacts", "gradle", "config", "docs", "scripts",
    "buildSrc", ".gradle", ".gradle-user-home", ".idea", ".kotlin", ".android",
    ".temp", ".i18n-work", "__pycache__",
})

#: Values that carry no prose worth translating, so being identical to English
#: is not evidence of a missing translation.
_LITERAL_OK = re.compile(r"^[\s\W\d]*$|^[%$@#*+\-/\\.,:;()\[\]{}<>=!?'\"&|·×→⇄…—–\s\d]+$")
#: Product and technical tokens that are intentionally the same in every locale.
_BRAND_OK = re.compile(
    r"^(?:We ?Meet|we-meet|OK|AI|AOQ|WebRTC|PPT|PDF|CSV|SHA-256|Token|JSON|HTTP|URL|"
    r"X-WeMeet-Signature|YYYY-MM-DD|https?://\S+|\d+)$"
)
_CJK = re.compile(r"[\u2e80-\u9fff\uff00-\uffef\u3000-\u303f]")

_FORMAT_SPEC = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z%])")
_APOSTROPHE = re.compile(r"(?<!\\)'")
_DATE_FORMAT_HINT = re.compile(r"MMM|yyyy|HH|EEEE|dd")

CATEGORIES = (
    "undeclared-locale", "overlay", "parse", "missing-key", "stale-key",
    "specifiers", "shape", "escape", "qa", "leftover",
)


def specifiers(text: str) -> dict[str, int]:
    """Format specifiers in `text`, as {'%1$s': 2, ...}."""
    out: dict[str, int] = {}
    for match in _FORMAT_SPEC.finditer(text):
        position, conversion = match.group(1), match.group(2)
        if conversion == "%":
            continue
        key = f"%{position + '$' if position else ''}{conversion}"
        out[key] = out.get(key, 0) + 1
    return out


def literalish(text: str) -> bool:
    """True when a value has no prose to translate (symbols, codes, formats)."""
    stripped = text.strip()
    if not stripped:
        return True
    if _LITERAL_OK.match(stripped) or _BRAND_OK.match(stripped):
        return True
    without_specs = _FORMAT_SPEC.sub(" ", stripped)
    without_specs = re.sub(r"\\n|\\'|\"", " ", without_specs)
    without_specs = re.sub(r"[\W\d_]+", " ", without_specs)
    return len(without_specs.strip()) < 3


def is_cjk(text: str) -> bool:
    return bool(_CJK.search(text))


# ── discovery ───────────────────────────────────────────────────────────

def discover_modules(root: Path) -> list[str]:
    """Gradle modules that own resources, taken from settings.gradle.kts."""
    settings = root / "settings.gradle.kts"
    if settings.is_file():
        names = re.findall(
            r'^\s*include\(\s*":([A-Za-z0-9_.\-]+)"\s*\)',
            settings.read_text(encoding="utf-8"),
            re.MULTILINE,
        )
        if names:
            return names
    return sorted(
        p.name for p in root.iterdir()
        if p.is_dir() and (p / "src" / "main" / "res").is_dir()
        and p.name not in _EXCLUDED_TOP_DIRS
    )


def res_dir(root: Path, module: str, locale: str = "") -> Path:
    name = "values" if not locale else f"values-{locale}"
    return root / module / "src" / "main" / "res" / name


def default_files(root: Path, module: str) -> list[Path]:
    directory = res_dir(root, module)
    if not directory.is_dir():
        return []
    return sorted(p for p in directory.glob("*.xml") if p.name not in SKIP_FILES)


def locale_qualifiers(root: Path, module: str) -> list[str]:
    """Locale-looking qualifiers present under a module's res/ directory."""
    res = root / module / "src" / "main" / "res"
    if not res.is_dir():
        return []
    found = []
    for directory in sorted(res.glob("values-*")):
        qualifier = directory.name[len("values-"):]
        if qualifier not in NON_LOCALE_QUALIFIERS and LOCALE_RE.match(qualifier):
            found.append(qualifier)
    return found


# ── reading ─────────────────────────────────────────────────────────────

@dataclass
class RawEntry:
    """One top-level resource, kept as raw lines so bytes survive a merge."""
    kind: str
    name: str
    start: int                 # 0-based inclusive
    end: int                   # 0-based inclusive
    lines: list[str]
    translatable: bool = True
    comments: list[str] = field(default_factory=list)

    @property
    def key(self) -> str:
        return f"{self.kind}:{self.name}"


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines()


def write_lf(path: Path, text: str) -> None:
    """Write UTF-8 with LF endings (`.gitattributes` pins every text file to LF)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))


def parse_raw(path: Path) -> tuple[list[str], list[RawEntry], list[str]]:
    """Split a resource file into (header, entries, footer) preserving lines.

    The header ends at `<resources>`, so a comment sitting directly above the
    *first* entry is collected as that entry's comment rather than being lost in
    the header.  Entry ``start``/``end`` are absolute line indices.
    """
    lines = read_lines(path)
    count = len(lines)
    open_at = next((i for i, line in enumerate(lines) if line.strip().startswith("<resources")), None)
    if open_at is None:
        return lines, [], []
    header = lines[:open_at + 1]
    close_at = next(
        (i for i in range(open_at + 1, count) if lines[i].strip().startswith("</resources>")),
        count,
    )
    footer = lines[close_at:] if close_at < count else []

    entries: list[RawEntry] = []
    pending: list[str] = []
    i = open_at + 1
    while i < close_at:
        line = lines[i]
        match = ENTRY_START.match(line)
        if not match:
            stripped = line.strip()
            if stripped.startswith("<!--"):
                # Comments can span lines; carry the whole block so a generated
                # locale file never inherits an unterminated comment.
                pending.append(line)
                if "-->" not in line:
                    j = i + 1
                    while j < close_at:
                        pending.append(lines[j])
                        if "-->" in lines[j]:
                            break
                        j += 1
                    i = j + 1
                    continue
            elif not stripped:
                pending = []           # a blank line breaks comment association
            i += 1
            continue

        kind, attrs = match.group(2), match.group(3)
        name_match = NAME_ATTR.search(attrs)
        if not name_match:
            i += 1
            continue
        if attrs.rstrip().endswith("/") or f"</{kind}>" in line:
            end = i
        else:
            close = f"</{kind}>"
            end = i
            j = i + 1
            while j < close_at:
                if close in lines[j]:
                    end = j
                    break
                j += 1
        entries.append(RawEntry(
            kind=kind,
            name=name_match.group(1),
            start=i,
            end=end,
            lines=lines[i:end + 1],
            translatable='translatable="false"' not in attrs,
            comments=pending,
        ))
        pending = []
        i = end + 1
    return header, entries, footer


def read_resources(path: Path) -> dict[str, ET.Element]:
    """Parsed resources by `kind:name` (empty when the file does not exist)."""
    if not path.exists():
        return {}
    root = ET.parse(path).getroot()
    return {
        f"{el.tag}:{el.get('name')}": el
        for el in root
        if el.tag in KINDS and el.get("name")
    }


def element_text(element: ET.Element) -> str:
    return "".join(element.itertext())


def dedent(lines: list[str], indent: str = "    ") -> list[str]:
    body = [line for line in lines if line.strip()]
    if not body:
        return lines
    common = min(len(line) - len(line.lstrip()) for line in body)
    return [indent + line[common:] if line.strip() else "" for line in lines]


def wrap(entries: list[RawEntry]) -> str:
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
    for entry in entries:
        out.extend(dedent(entry.lines))
    out.append("</resources>")
    return "\n".join(out) + "\n"


# ── checking ────────────────────────────────────────────────────────────

@dataclass
class Issue:
    level: str          # "error" | "warning"
    category: str
    locale: str
    location: str
    key: str
    message: str

    def render(self) -> str:
        prefix = "error" if self.level == "error" else "warn "
        where = f"{self.location} [{self.locale}]" if self.locale else self.location
        key = f" {self.key}" if self.key else ""
        return f"{prefix} [{self.category}] {where}{key}: {self.message}"

    def as_dict(self) -> dict:
        return {
            "level": self.level, "category": self.category, "locale": self.locale,
            "location": self.location, "key": self.key, "message": self.message,
        }


def _check_overlays(root: Path, module: str, locales: tuple[str, ...],
                    overlays: tuple[str, ...]) -> list[Issue]:
    """A partial language-only overlay next to a regional sibling is a bug.

    This is exactly how `values-zh/` drifted: it served zh-TW/zh-HK with a
    handful of Simplified strings above an otherwise English UI, while zh-CN was
    unaffected and nobody noticed.
    """
    issues: list[Issue] = []
    for overlay in overlays:
        if "-r" in overlay:
            continue
        for sibling in (loc for loc in locales if loc.split("-")[0] == overlay):
            overlay_files = {p.name for p in res_dir(root, module, overlay).glob("*.xml")}
            sibling_files = {p.name for p in res_dir(root, module, sibling).glob("*.xml")}
            if overlay_files and overlay_files < sibling_files:
                # Strict subset: the overlay duplicates part of the regional
                # directory and adds nothing, which is pure drift.
                issues.append(Issue(
                    "error", "overlay", overlay,
                    f"{module}/src/main/res/values-{overlay}", "",
                    f"partial language-only overlay next to values-{sibling}; keep the "
                    f"copy in values-{sibling}/ instead (also present in values-{sibling}: "
                    f"{', '.join(sorted(overlay_files))})",
                ))
            break
    return issues


def _check_text(location: str, locale: str, key: str, en_text: str, text: str,
                suffix: str = "") -> list[Issue]:
    """Script-aware text QA: escapes always, punctuation/whitespace where meaningful."""
    issues: list[Issue] = []

    def add(message: str, category: str = "qa", level: str = "warning") -> None:
        issues.append(Issue(level, category, locale, location, key + suffix, message))

    if not text.strip() and en_text.strip():
        add("empty value", level="error")
        return issues

    # Android requires an escaped apostrophe unless the value is wrapped in
    # double quotes.  Applies to every script.
    quoted = len(text) > 1 and text.startswith('"') and text.endswith('"')
    if not quoted and _APOSTROPHE.search(text):
        add(f"unescaped apostrophe (write \\'): {text[:60]!r}", category="escape", level="error")

    # Punctuation and spacing conventions differ by script; comparing Chinese to
    # English here only produces noise, so restrict these to Latin-script values.
    if is_cjk(text) or is_cjk(en_text):
        return issues

    if (text[:1].isspace() != en_text[:1].isspace()) or (text[-1:].isspace() != en_text[-1:].isspace()):
        add(f"whitespace at the edges differs from English: {text[:50]!r}")
    trimmed = text.rstrip().rstrip('"\u201d\u300d\uff09)]}\'')
    if en_text.rstrip().endswith((".", "?", "!")) and len(en_text) > 25 and trimmed and trimmed[-1].isalnum():
        add(f"sentence does not end with punctuation: {text[:60]!r}")
    if "  " in text and "  " not in en_text:
        add(f"doubled space: {text[:60]!r}")
    if (en_text[:1].isupper() and text[:1].islower() and not text.startswith("%")
            and not _DATE_FORMAT_HINT.search(en_text) and not en_text[:1].islower()):
        add(f"starts lowercase where English starts uppercase: {text[:60]!r}")
    return issues


def _check_entry(location: str, locale: str, key: str, en_el: ET.Element,
                 loc_el: ET.Element) -> list[Issue]:
    """Per-entry structural checks shared by all kinds."""
    issues: list[Issue] = []

    def add(message: str, category: str, suffix: str = "", level: str = "error") -> None:
        issues.append(Issue(level, category, locale, location, key + suffix, message))

    if en_el.tag != loc_el.tag:
        add(f"tag {loc_el.tag} != {en_el.tag}", "shape")
        return issues

    if en_el.tag == "string":
        en_specs, loc_specs = specifiers(element_text(en_el)), specifiers(element_text(loc_el))
        if en_specs != loc_specs:
            add(f"format specifiers differ: en={en_specs} loc={loc_specs}", "specifiers")
        issues.extend(_check_text(location, locale, key, element_text(en_el), element_text(loc_el)))

    elif en_el.tag == "plurals":
        # A locale declares only the CLDR quantities it needs, so the quantity
        # sets legitimately differ (zh-CN has `other` alone, fr adds `many`).
        # `other` is mandatory; quantities present in both must keep the same
        # format specifiers.
        en_q = {c.get("quantity"): c for c in en_el}
        loc_q = {c.get("quantity"): c for c in loc_el}
        if "other" not in loc_q:
            add("plurals has no `other` quantity", "shape")
        for quantity in sorted(set(en_q) & set(loc_q)):
            en_specs = specifiers(element_text(en_q[quantity]))
            loc_specs = specifiers(element_text(loc_q[quantity]))
            if en_specs != loc_specs:
                add(f"format specifiers differ: en={en_specs} loc={loc_specs}", "specifiers", f"<{quantity}>")
            issues.extend(_check_text(location, locale, key, element_text(en_q[quantity]),
                                      element_text(loc_q[quantity]), suffix=f"<{quantity}>"))
        for quantity in sorted(set(loc_q) - set(en_q)):
            add(f"extra quantity <{quantity}> not present in English", "shape", suffix=f"<{quantity}>",
                level="warning")

    else:  # string-array
        en_items, loc_items = list(en_el), list(loc_el)
        if len(en_items) != len(loc_items):
            add(f"{len(loc_items)} items != {len(en_items)} in English", "shape")
        for index, (en_item, loc_item) in enumerate(zip(en_items, loc_items)):
            en_specs, loc_specs = specifiers(element_text(en_item)), specifiers(element_text(loc_item))
            if en_specs != loc_specs:
                add(f"format specifiers differ: en={en_specs} loc={loc_specs}", "specifiers", f"[{index}]")
    return issues


def check_module(root: Path, module: str, locales: tuple[str, ...] = FULL_LOCALES,
                 overlays: tuple[str, ...] = OVERLAY_LOCALES,
                 fail_on_leftovers: bool = False,
                 allow_identical: dict[str, set[str]] | None = None,
                 declared: tuple[str, ...] | None = None) -> list[Issue]:
    """Check every default resource file of one module against every locale.

    `locales` is what gets verified; `declared` is the repository's declared set
    of locale directories.  They are separate so that `check --locale de` does
    not make every other real locale look undeclared.
    """
    issues: list[Issue] = []
    allowed = allow_identical or {}
    declared_set = (set(declared) if declared is not None
                    else set(FULL_LOCALES) | set(OVERLAY_LOCALES) | set(overlays))

    for qualifier in locale_qualifiers(root, module):
        if qualifier not in declared_set:
            issues.append(Issue(
                "error", "undeclared-locale", qualifier,
                f"{module}/src/main/res/values-{qualifier}", "",
                "undeclared locale directory; add it to FULL_LOCALES (complete translation) "
                "or OVERLAY_LOCALES (deliberate partial fallback) in scripts/android_i18n.py, "
                "and to SettingsScreen.LANGUAGE_OPTIONS",
            ))
    issues.extend(_check_overlays(root, module, locales, overlays))

    for default in default_files(root, module):
        translatable = {
            key: el for key, el in read_resources(default).items()
            if el.get("translatable") != "false"
        }
        if not translatable:
            continue
        location = f"{module}/{default.name}"
        for locale in locales:
            target = res_dir(root, module, locale) / default.name
            if not target.exists():
                for key in sorted(translatable):
                    issues.append(Issue("error", "missing-key", locale, location, key,
                                        "locale file does not exist"))
                continue
            try:
                current = read_resources(target)
            except ET.ParseError as exc:
                issues.append(Issue("error", "parse", locale, location, "", f"XML parse error: {exc}"))
                continue

            for key in sorted(set(current) - set(read_resources(default))):
                issues.append(Issue("error", "stale-key", locale, location, key,
                                    "key is not in the English default"))

            for key, en_el in sorted(translatable.items()):
                if key not in current:
                    issues.append(Issue("error", "missing-key", locale, location, key,
                                        "missing translation"))
                    continue
                issues.extend(_check_entry(location, locale, key, en_el, current[key]))
                if current[key].get("translatable") == "false" and en_el.get("translatable") != "false":
                    issues.append(Issue("warning", "shape", locale, location, key,
                                        'unexpected translatable="false"'))
                en_text, loc_text = element_text(en_el), element_text(current[key])
                if (en_el.tag == "string" and en_text == loc_text and en_text.strip()
                        and not literalish(en_text)
                        and loc_text.strip() not in allowed.get(locale, set())):
                    issues.append(Issue(
                        "error" if fail_on_leftovers else "warning", "leftover", locale,
                        location, key, f"identical to the English default: {loc_text[:60]!r}",
                    ))
    return issues


def load_allow_identical(path: Path) -> dict[str, set[str]]:
    """Read the reviewed baseline of values that are legitimately identical.

    Cognates (`Host` in German, `Document` in French), language endonyms and
    format-only strings would otherwise drown the one warning that matters: a
    value nobody translated.
    """
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {locale: set(values) for locale, values in data.get("allow", {}).items()}


def check_repository(root: Path, locales: tuple[str, ...] = FULL_LOCALES,
                     overlays: tuple[str, ...] = OVERLAY_LOCALES,
                     modules: list[str] | None = None,
                     fail_on_leftovers: bool = False,
                     allow_identical: dict[str, set[str]] | None = None,
                     declared: tuple[str, ...] | None = None) -> list[Issue]:
    issues: list[Issue] = []
    for module in (modules or discover_modules(root)):
        issues.extend(check_module(root, module, locales, overlays, fail_on_leftovers,
                                   allow_identical, declared))
    return issues


def count_translatable_keys(root: Path, modules: list[str]) -> int:
    total = 0
    for module in modules:
        for default in default_files(root, module):
            total += sum(1 for el in read_resources(default).values()
                         if el.get("translatable") != "false")
    return total


# ── backfill: extract bundles ───────────────────────────────────────────

def plan(root: Path, locale: str, work_dir: Path, reference_locale: str | None = "zh-rCN",
         modules: list[str] | None = None) -> dict:
    """Write one English bundle per file missing entries for `locale`.

    Returns a manifest describing every (module, file) pair, suitable for
    handing to a translator and feeding back into :func:`merge`.
    """
    manifest: dict = {"locale": locale, "reference": reference_locale, "files": [], "entries": 0}
    for module in (modules or discover_modules(root)):
        for default in default_files(root, module):
            required = {k: el for k, el in read_resources(default).items()
                        if el.get("translatable") != "false"}
            if not required:
                continue
            _, default_entries, _ = parse_raw(default)
            by_key = {entry.key: entry for entry in default_entries}

            target = res_dir(root, module, locale) / default.name
            missing = [k for k in required if k not in set(read_resources(target))]
            if not missing:
                continue

            ordered = [by_key[k] for k in required if k in by_key and k in missing]
            out_dir = work_dir / locale
            stem = f"{module}__{default.name}"
            source_bundle = out_dir / f"{stem}.src.xml"
            write_lf(source_bundle, wrap(ordered))

            reference_bundle = None
            if reference_locale:
                ref_target = res_dir(root, module, reference_locale) / default.name
                if ref_target.exists():
                    _, ref_entries, _ = parse_raw(ref_target)
                    ref_by_key = {entry.key: entry for entry in ref_entries}
                    if all(k in ref_by_key for k in missing):
                        reference_bundle = out_dir / f"{stem}.ref.xml"
                        write_lf(reference_bundle, wrap([ref_by_key[k] for k in missing]))

            manifest["files"].append({
                "module": module,
                "file": default.name,
                "keys": missing,
                "count": len(missing),
                "source_bundle": str(source_bundle.relative_to(root)).replace("\\", "/"),
                "reference_bundle": (str(reference_bundle.relative_to(root)).replace("\\", "/")
                                     if reference_bundle else None),
                "translated_bundle": str((out_dir / f"{stem}.out.xml").relative_to(root)).replace("\\", "/"),
                "target": str(target.relative_to(root)).replace("\\", "/"),
                "target_exists": target.exists(),
            })
            manifest["entries"] += len(missing)
    return manifest


def load_manifest(work_dir: Path) -> dict:
    return json.loads((work_dir / "manifest.json").read_text(encoding="utf-8"))


def save_manifest(work_dir: Path, manifest: dict) -> None:
    write_lf(work_dir / "manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


# ── backfill: merge bundles ─────────────────────────────────────────────

def merge(root: Path, manifest: dict) -> tuple[int, list[str]]:
    """Insert each translated bundle into its target file.

    Existing bytes are never rewritten: new entries are inserted next to their
    nearest preceding English neighbour, or just below `<resources>`.  Keys the
    target already has are skipped, so a repeated or partial run is safe.
    """
    problems: list[str] = []
    written = 0
    locale = manifest["locale"]

    for item in manifest["files"]:
        translated_path = root / item["translated_bundle"]
        if not translated_path.exists():
            problems.append(f"missing translated bundle: {item['translated_bundle']}")
            continue

        default = res_dir(root, item["module"]) / item["file"]
        _, default_entries, _ = parse_raw(default)
        _, translated_entries, _ = parse_raw(translated_path)
        translated = {entry.key: entry.lines for entry in translated_entries}

        wanted = set(item["keys"])
        absent = sorted(wanted - set(translated))
        surplus = sorted(set(translated) - wanted)
        if absent:
            problems.append(f"{item['module']}/{item['file']} [{locale}] bundle is missing keys: {absent}")
        if surplus:
            problems.append(f"{item['module']}/{item['file']} [{locale}] bundle has unexpected keys: {surplus}")

        order = [entry for entry in default_entries if entry.translatable]
        target = res_dir(root, item["module"], locale) / item["file"]

        if not target.exists():
            lines = ['<?xml version="1.0" encoding="utf-8"?>', "<resources>"]
            for entry in order:
                if entry.key in translated:
                    lines.extend(entry.comments)
                    lines.extend(dedent(translated[entry.key]))
            lines.append("</resources>")
            write_lf(target, "\n".join(lines) + "\n")
            written += 1
            continue

        _, target_entries, _ = parse_raw(target)
        lines = read_lines(target)
        present = {entry.key: entry for entry in target_entries}

        new_keys = [entry.key for entry in order
                    if entry.key in wanted and entry.key in translated and entry.key not in present]
        if not new_keys:
            continue

        anchor_of: dict[str, str | None] = {}
        seen: list[str] = []
        for entry in order:
            if entry.key in present:
                seen.append(entry.key)
            else:
                anchor_of[entry.key] = seen[-1] if seen else None

        # Anchor-less keys go directly below <resources>, never below the XML
        # declaration, which would emit a second root element.
        resources_at = next(
            (i for i, line in enumerate(lines) if line.strip().startswith("<resources")), 1
        )
        inserts: dict[int, list[str]] = {}
        for key in new_keys:
            anchor = anchor_of.get(key)
            position = resources_at + 1 if anchor is None else present[anchor].end + 1
            inserts.setdefault(position, []).extend(dedent(translated[key]))
        for position in sorted(inserts, reverse=True):
            lines[position:position] = inserts[position]
        write_lf(target, "\n".join(lines) + "\n")
        written += 1
    return written, problems
