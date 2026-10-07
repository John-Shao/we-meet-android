"""Delivery boundary regressions: fixture rejection, signature and tamper detection."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("candidate", Path(__file__).with_name("package-work-candidate.py"))
candidate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(candidate)


class CandidateTest(unittest.TestCase):
    def test_fixture_and_metadata_traversal_are_rejected(self):
        meta = {"applicationId": "com.we.meet", "variantName": "debug", "elements": [
            {"outputFile": "app-debug.apk", "versionName": "0.3.0-work.2", "versionCode": 4}]}
        self.assertEqual(("0.3.0-work.2", 4), candidate.candidate_metadata(meta))
        for change in ({"applicationId": "com.we.meet.fixturework"}, {"variantName": "release"},
                       {"elements": [{**meta["elements"][0], "outputFile": "../app.apk"}]},
                       {"elements": [{**meta["elements"][0], "versionName": "../../escape"}]}):
            with self.assertRaises(candidate.CandidateError):
                candidate.candidate_metadata({**meta, **change})

    def test_native_identity_and_verified_debug_signer_must_match(self):
        badging = "package: name='com.we.meet' versionCode='4' versionName='0.3.0-work.2'\napplication-debuggable\n"
        signing = "Verifies\nSigner #1 certificate DN: C=US, O=Android, CN=Android Debug\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertEqual("a" * 64, candidate.audit_apk(badging, signing, "0.3.0-work.2", 4))
        for modified_badging, modified_signing in (
            (badging.replace("com.we.meet", "com.we.meet.fixturework"), signing),
            (badging, signing.replace("Verifies", "DOES NOT VERIFY")),
            (badging, signing.replace("Android Debug", "Release Publisher")),
            (badging.replace("versionCode='4'", "versionCode='3'"), signing),
        ):
            with self.assertRaises(candidate.CandidateError):
                candidate.audit_apk(modified_badging, modified_signing, "0.3.0-work.2", 4)

    def test_archive_changes_and_failing_tests_stop_delivery(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            apk = directory / "app-debug.apk"
            apk.write_bytes(b"synthetic candidate")
            manifest = directory / "candidate.json"
            manifest.write_text(json.dumps({"artifact": {"name": apk.name, "bytes": apk.stat().st_size, "sha256": candidate.digest(apk)}}))
            sums = directory / "SHA256SUMS"
            sums.write_text(f"{candidate.digest(apk)}  app-debug.apk\n{candidate.digest(manifest)}  candidate.json\n")
            candidate.verify_archive(directory)
            apk.write_bytes(b"tampered candidate")
            with self.assertRaises(candidate.CandidateError):
                candidate.verify_archive(directory)
            result = directory / "TEST-sample.xml"
            result.write_text('<testsuite tests="2" failures="1" errors="0" skipped="0"/>')
            with self.assertRaises(candidate.CandidateError):
                candidate.test_summary(directory)
            result.write_text('<testsuite tests="2" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(2, candidate.test_summary(directory)["tests"])


if __name__ == "__main__":
    unittest.main()
