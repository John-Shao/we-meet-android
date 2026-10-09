"""Regressions for the string-resource gate and the backfill merge.

Run with either:
    python -m unittest discover -s scripts -p 'test_*.py'
    python scripts/test_android_i18n.py
"""

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from xml.etree import ElementTree as ET

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import android_i18n as i18n  # noqa: E402

CLI = SCRIPTS / "i18n.py"


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))


def make_repo(root: Path, modules=("app",)) -> Path:
    (root / "settings.gradle.kts").write_text(
        "".join(f'include(":{m}")\n' for m in modules), encoding="utf-8")
    for module in modules:
        (root / module / "src" / "main" / "res" / "values").mkdir(parents=True, exist_ok=True)
    return root


def defaults(root: Path, module: str, body: str) -> None:
    write(root / module / "src" / "main" / "res" / "values" / "strings.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + body + "</resources>\n")


def locale(root: Path, module: str, qualifier: str, body: str) -> None:
    write(root / module / "src" / "main" / "res" / f"values-{qualifier}" / "strings.xml",
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + body + "</resources>\n")


def errors(issues):
    return [i for i in issues if i.level == "error"]


def categories(issues, level="error"):
    return sorted(i.category for i in issues if i.level == level)


class ParseRawTest(unittest.TestCase):
    """`string-array` must not be swallowed by the `string` alternative."""

    def test_string_array_keeps_its_body(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "values" / "x.xml"
            write(path, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                        '    <string name="a">A</string>\n'
                        '    <string-array name="langs">\n'
                        '        <item>中文</item>\n'
                        '        <item>English</item>\n'
                        '    </string-array>\n'
                        "</resources>\n")
            _, entries, _ = i18n.parse_raw(path)
            keys = [e.key for e in entries]
            self.assertEqual(["string:a", "string-array:langs"], keys)
            array = entries[1]
            # the whole element, not just its opening tag
            self.assertIn("</string-array>", "\n".join(array.lines))
            self.assertIn("<item>English</item>", "\n".join(array.lines))

    def test_multiline_comment_is_carried_whole(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "values" / "x.xml"
            write(path, '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                        "    <!-- first line\n"
                        "         second line -->\n"
                        '    <string name="a">A</string>\n'
                        "</resources>\n")
            _, entries, _ = i18n.parse_raw(path)
            self.assertEqual(["    <!-- first line", "         second line -->"], entries[0].comments)

    def test_translatable_false_is_flagged(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "values" / "x.xml"
            write(path, "<resources>\n"
                        '    <string name="brand" translatable="false">We Meet</string>\n'
                        '    <string name="a">A</string>\n'
                        "</resources>\n")
            _, entries, _ = i18n.parse_raw(path)
            self.assertEqual([False, True], [e.translatable for e in entries])


class CheckTest(unittest.TestCase):
    def test_complete_locale_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">Hello %1$s</string>\n')
            locale(root, "app", "de", '    <string name="a">Hallo %1$s</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertEqual([], errors(issues))

    def test_missing_key_and_missing_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n    <string name="b">B</string>\n')
            locale(root, "app", "de", '    <string name="a">A</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertEqual(["missing-key"], categories(issues))
            self.assertEqual("string:b", errors(issues)[0].key)

    def test_stale_key_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            locale(root, "app", "de", '    <string name="a">A</string>\n'
                                      '    <string name="gone">Gone</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertIn("stale-key", categories(issues))

    def test_specifier_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">Hi %1$s and %2$s</string>\n')
            locale(root, "app", "de", '    <string name="a">Hallo %1$s</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertIn("specifiers", categories(issues))

    def test_undeclared_locale_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            locale(root, "app", "es", '    <string name="a">A</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertIn("undeclared-locale", categories(issues))

    def test_partial_language_only_overlay_is_rejected(self):
        """The former values-zh/: a subset of files sitting next to values-zh-rCN/."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n'
                                  '    <string name="v">V</string>\n')
            locale(root, "app", "zh-rCN", '    <string name="a">甲</string>\n'
                                          '    <string name="v">乙</string>\n')
            # ...and values-zh-rCN also carries video.xml, so the language-only
            # directory below is a strict subset of its regional sibling.
            write(root / "app" / "src" / "main" / "res" / "values-zh-rCN" / "video.xml",
                  "<resources>\n" + '    <string name="v">乙</string>\n' + "</resources>\n")
            # the language-only directory holds only one of the two files
            write(root / "app" / "src" / "main" / "res" / "values-zh" / "video.xml",
                  "<resources>\n" + '    <string name="v">乙</string>\n' + "</resources>\n")
            issues = i18n.check_repository(root, locales=("zh-rCN",), overlays=("zh",))
            self.assertIn("overlay", categories(issues))

    def test_complete_language_only_overlay_is_not_flagged(self):
        """A language-only directory that adds its own strings is doing real work."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            locale(root, "app", "zh-rCN", '    <string name="a">甲</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-zh" / "extra.xml",
                  "<resources>\n" + '    <string name="extra">额外</string>\n' + "</resources>\n")
            write(root / "app" / "src" / "main" / "res" / "values" / "extra.xml",
                  "<resources>\n" + '    <string name="extra">Extra</string>\n' + "</resources>\n")
            issues = i18n.check_repository(root, locales=("zh-rCN",), overlays=("zh",))
            self.assertNotIn("overlay", categories(issues))

    def test_plural_needs_other_and_allows_fewer_quantities(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app",
                     '    <plurals name="n">\n        <item quantity="one">%1$d item</item>\n'
                     '        <item quantity="other">%1$d items</item>\n    </plurals>\n')
            # zh-CN legitimately declares `other` only
            locale(root, "app", "zh-rCN",
                   '    <plurals name="n">\n        <item quantity="other">%1$d 项</item>\n    </plurals>\n')
            self.assertEqual([], errors(i18n.check_repository(root, locales=("zh-rCN",))))

            locale(root, "app", "de",
                   '    <plurals name="n">\n        <item quantity="one">%1$d Element</item>\n    </plurals>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertIn("shape", categories(issues))

    def test_extra_plural_quantity_is_only_a_warning(self):
        """French adds CLDR `many`; that is legitimate, not a failure."""
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app",
                     '    <plurals name="n">\n        <item quantity="one">%1$d x</item>\n'
                     '        <item quantity="other">%1$d x</item>\n    </plurals>\n')
            locale(root, "app", "fr",
                   '    <plurals name="n">\n        <item quantity="one">%1$d x</item>\n'
                   '        <item quantity="many">%1$d x</item>\n'
                   '        <item quantity="other">%1$d x</item>\n    </plurals>\n')
            issues = i18n.check_repository(root, locales=("fr",))
            self.assertEqual([], errors(issues))
            self.assertIn("many", " ".join(i.message for i in issues))

    def test_apostrophe_must_be_escaped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">The recording</string>\n')
            locale(root, "app", "fr", "    <string name=\"a\">L'enregistrement</string>\n")
            self.assertIn("escape", categories(i18n.check_repository(root, locales=("fr",))))

            locale(root, "app", "fr", '    <string name="a">L\\\'enregistrement</string>\n')
            self.assertEqual([], errors(i18n.check_repository(root, locales=("fr",))))

    def test_leftover_is_a_warning_and_the_baseline_silences_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">Saving was not confirmed.</string>\n')
            locale(root, "app", "de", '    <string name="a">Saving was not confirmed.</string>\n')
            issues = i18n.check_repository(root, locales=("de",))
            self.assertEqual([], errors(issues))
            self.assertIn("leftover", categories(issues, level="warning"))

            silenced = i18n.check_repository(
                root, locales=("de",), allow_identical={"de": {"Saving was not confirmed."}})
            self.assertNotIn("leftover", categories(silenced, level="warning"))

    def test_allowlist_file_round_trips(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "allow.json"
            write(path, '{"allow": {"de": ["Host"], "fr": ["Document"]}}')
            loaded = i18n.load_allow_identical(path)
            self.assertEqual({"Host"}, loaded["de"])
            self.assertEqual({"Document"}, loaded["fr"])

    def test_missing_allowlist_file_is_harmless(self):
        self.assertEqual({}, i18n.load_allow_identical(Path("does/not/exist.json")))


class MergeTest(unittest.TestCase):
    def test_creates_a_new_file_in_english_order_with_comments(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app",
                     '    <string name="a">A</string>\n'
                     "    <!-- a note -->\n"
                     '    <string name="b">B</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\n"
                  '    <string name="a">Ä</string>\n'
                  '    <string name="b">Bë</string>\n'
                  "</resources>\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml",
                "keys": ["string:a", "string:b"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            written, problems = i18n.merge(root, manifest)
            self.assertEqual([], problems)
            self.assertEqual(1, written)
            text = (root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml").read_text("utf-8")
            self.assertIn("<!-- a note -->", text)
            self.assertLess(text.index('name="a"'), text.index('name="b"'))
            ET.fromstring(text)          # well-formed

    def test_anchorless_key_goes_below_resources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n    <string name="b">B</string>\n')
            locale(root, "app", "de", '    <string name="b">B</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\n" + '    <string name="a">Ä</string>\n' + "</resources>\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml", "keys": ["string:a"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            i18n.merge(root, manifest)
            lines = (root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml").read_text("utf-8").splitlines()
            self.assertTrue(lines[0].startswith("<?xml"))
            self.assertEqual("<resources>", lines[1].strip())
            self.assertIn('name="a"', lines[2])
            ET.fromstring("\n".join(lines))

    def test_insertion_is_anchored_to_the_english_neighbour(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n'
                                  '    <string name="b">B</string>\n'
                                  '    <string name="c">C</string>\n')
            locale(root, "app", "de", '    <string name="a">Ä</string>\n'
                                      '    <string name="c">C</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\n" + '    <string name="b">Bë</string>\n' + "</resources>\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml", "keys": ["string:b"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            i18n.merge(root, manifest)
            text = (root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml").read_text("utf-8")
            self.assertLess(text.index('name="a"'), text.index('name="b"'))
            self.assertLess(text.index('name="b"'), text.index('name="c"'))

    def test_merge_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\n" + '    <string name="a">Ä</string>\n' + "</resources>\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml", "keys": ["string:a"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            i18n.merge(root, manifest)
            target = root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml"
            first = target.read_text("utf-8")
            written, _ = i18n.merge(root, manifest)
            self.assertEqual(0, written)
            self.assertEqual(first, target.read_text("utf-8"))
            self.assertEqual(1, first.count('name="a"'))

    def test_plan_then_merge_round_trip(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n'
                                  '    <string name="b">B %1$s</string>\n')
            locale(root, "app", "zh-rCN", '    <string name="a">甲</string>\n'
                                          '    <string name="b">乙 %1$s</string>\n')
            work = root / ".i18n-work"
            manifest = i18n.plan(root, "de", work, reference_locale="zh-rCN")
            self.assertEqual(1, len(manifest["files"]))
            self.assertEqual(["string:a", "string:b"], manifest["files"][0]["keys"])
            self.assertIsNotNone(manifest["files"][0]["reference_bundle"])
            i18n.save_manifest(work, manifest)

            # an identity "translation" of the source bundle
            source = root / manifest["files"][0]["source_bundle"]
            write(root / manifest["files"][0]["translated_bundle"], source.read_text("utf-8"))
            written, problems = i18n.merge(root, i18n.load_manifest(work))
            self.assertEqual([], problems)
            self.assertEqual(1, written)
            issues = i18n.check_repository(root, locales=("de",))
            self.assertEqual([], errors(issues))

    def test_surplus_bundle_key_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\n" + '    <string name="zzz">Z</string>\n' + "</resources>\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml", "keys": ["string:a"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            _, problems = i18n.merge(root, manifest)
            self.assertTrue(any("unexpected keys" in p for p in problems))
            self.assertTrue(any("missing keys" in p for p in problems))

    def test_merge_keeps_lf_endings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            write(root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml.out.xml",
                  "<resources>\r\n" + '    <string name="a">Ä</string>\r\n' + "</resources>\r\n")
            manifest = {"locale": "de", "files": [{
                "module": "app", "file": "strings.xml", "keys": ["string:a"],
                "translated_bundle": "app/src/main/res/values-de/strings.xml.out.xml",
            }]}
            i18n.merge(root, manifest)
            raw = (root / "app" / "src" / "main" / "res" / "values-de" / "strings.xml").read_bytes()
            self.assertNotIn(b"\r\n", raw)


class DiscoveryTest(unittest.TestCase):
    def test_modules_come_from_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp), modules=("app", "feature-im"))
            self.assertEqual(["app", "feature-im"], i18n.discover_modules(root))

    def test_locale_qualifiers_ignore_non_locale_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            for name in ("values-de", "values-zh-rCN", "values-night", "values-v21", "values-sw600dp"):
                (root / "app" / "src" / "main" / "res" / name).mkdir(parents=True)
            self.assertEqual(["de", "zh-rCN"], i18n.locale_qualifiers(root, "app"))


class CliTest(unittest.TestCase):
    def _run(self, *args, root: Path):
        return subprocess.run(
            [sys.executable, str(CLI), "--root", str(root), *args],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
        )

    def test_check_exit_codes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            locale(root, "app", "de", '    <string name="a">Ä</string>\n')
            self.assertEqual(0, self._run("check", "--locale", "de", root=root).returncode)

            locale(root, "app", "de", '    <string name="a">A</string>\n'
                                      '    <string name="b">B</string>\n')
            failed = self._run("check", "--locale", "de", root=root)
            self.assertEqual(1, failed.returncode)
            self.assertIn("stale-key", failed.stdout)

    def test_check_json_is_machine_readable(self):
        import json
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            defaults(root, "app", '    <string name="a">A</string>\n')
            locale(root, "app", "de", "<resources>\n</resources>\n")
            result = self._run("check", "--locale", "de", "--json", root=root)
            payload = json.loads(result.stdout)
            self.assertEqual(["de"], payload["locales"])
            self.assertTrue(any(e["category"] == "missing-key" for e in payload["errors"]))

    def test_merge_without_manifest_exits_2(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            result = self._run("merge", "--work-dir", ".i18n-work", root=root)
            self.assertEqual(2, result.returncode)


class RepositoryTest(unittest.TestCase):
    """The real repository must pass its own gate."""

    def test_repository_is_clean(self):
        root = SCRIPTS.parent
        issues = i18n.check_repository(root, allow_identical=i18n.load_allow_identical(
            SCRIPTS / "i18n-allow-identical.json"))
        self.assertEqual([], [i.render() for i in issues if i.level == "error"])

    def test_no_stray_values_zh_directory(self):
        root = SCRIPTS.parent
        for module in i18n.discover_modules(root):
            self.assertNotIn("zh", i18n.locale_qualifiers(root, module),
                             f"{module} reintroduced a language-only values-zh/ directory")


if __name__ == "__main__":
    unittest.main(verbosity=2)
