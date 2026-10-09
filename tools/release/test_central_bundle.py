"""Offline packaging/transport contracts; fixtures do not certify OpenPGP cryptography."""
import hashlib
import io
import json
import pathlib
import stat
import signal
import time
import tempfile
import unittest
import uuid
import zipfile
from unittest import mock

import central_bundle as bundle
import publish_central as publisher

GAV = ("io.github.codexrodrigues", "praxis-config-starter", "0.1.0-rc.161")
KEY = "A" * 40


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="central-bundle-unit-")
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        (self.root / "target").mkdir()
        self.gpg = self.root / "fixture-verifier"
        self.gpg.write_text("#!/usr/bin/env python3\nprint('[GNUPG:] VALIDSIG " + KEY + " 0 0 0 4 0 1 8 00 " + KEY + "')\n")
        self.gpg.chmod(0o700)
        pom = ('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>' + GAV[0] + '</groupId><artifactId>' + GAV[1] + '</artifactId><version>' + GAV[2] + '</version><name>fixture</name><description>fixture</description><url>https://example.invalid</url><licenses/><developers/><scm/></project>').encode()
        (self.root / ".flattened-pom.xml").write_bytes(pom)
        primary = bundle.names(*GAV)
        for name in primary:
            if not name.endswith(".pom"):
                data = io.BytesIO()
                with zipfile.ZipFile(data, "w") as archive:
                    archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
                    if name == primary[1]:
                        archive.writestr(f"META-INF/maven/{GAV[0]}/{GAV[1]}/pom.properties", f"groupId={GAV[0]}\nartifactId={GAV[1]}\nversion={GAV[2]}\n")
                (self.root / "target" / name).write_bytes(data.getvalue())
            (self.root / "target" / (name + ".asc")).write_bytes(b"fixture detached signature, not cryptographic proof")
        # Repository metadata must never become part of the explicit artifact selection.
        (self.root / "target" / "maven-metadata-local.xml").write_text("unrelated")
        self.archive = self.root / "one.zip"
        self.receipt = bundle.build_archive(self.root, self.archive, *GAV, KEY, str(self.gpg))
        self.data = self.archive.read_bytes()

    def rewrite(self, change):
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(self.data)) as archive:
            entries = [(entry, archive.read(entry.filename)) for entry in archive.infolist()]
        with zipfile.ZipFile(output, "w") as archive:
            for entry, data in change(entries):
                archive.writestr(entry, data)
        return output.getvalue()

    def validate(self, data):
        return bundle.validate_archive(data, *GAV, KEY, str(self.gpg))

    def test_positive_independent_validation_and_determinism(self):
        self.assertEqual(self.receipt, self.validate(self.data))
        self.assertEqual(40, len(self.receipt["entries"]))
        second = self.root / "two.zip"
        bundle.build_archive(self.root, second, *GAV, KEY, str(self.gpg))
        self.assertEqual(self.data, second.read_bytes())

    def test_extra_metadata_rejected(self):
        bad = self.rewrite(lambda entries: entries + [("io/github/codexrodrigues/praxis-config-starter/maven-metadata-local.xml", b"bad")])
        with self.assertRaises(ValueError):
            self.validate(bad)

    def test_traversal_rejected(self):
        for path in ("../bad", "/absolute", "io/github/../bad", "other/gav/1/x.jar"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.validate(self.rewrite(lambda entries: entries + [(path, b"bad")]))

    def test_duplicate_path_rejected(self):
        with self.assertWarns(UserWarning):
            bad = self.rewrite(lambda entries: entries + [entries[0]])
        with self.assertRaises(ValueError):
            self.validate(bad)

    def test_missing_artifact_rejected(self):
        with self.assertRaises(ValueError):
            self.validate(self.rewrite(lambda entries: entries[1:]))

    def test_checksum_corruption_rejected(self):
        with self.assertRaises(ValueError):
            self.validate(self.rewrite(lambda entries: [(entry, b"bad" if entry.filename.endswith(".sha256") else data) for entry, data in entries]))

    def test_artifact_corruption_rejected(self):
        with self.assertRaises(ValueError):
            self.validate(self.rewrite(lambda entries: [(entry, data + b"bad" if entry.filename.endswith(".jar") else data) for entry, data in entries]))

    def test_link_entry_rejected(self):
        def linked(entries):
            entries[0][0].external_attr = (stat.S_IFLNK | 0o777) << 16
            return entries
        with self.assertRaises(ValueError):
            self.validate(self.rewrite(linked))

    def test_wrong_tag_gav_rejected(self):
        with self.assertRaises(ValueError):
            bundle.pom_identity((self.root / ".flattened-pom.xml").read_bytes(), (*GAV[:2], "0.1.0-rc.160"))

    def test_renamed_old_jar_with_fixture_valid_signature_is_rejected(self):
        name = bundle.names(*GAV)[1]
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            archive.writestr(f"META-INF/maven/{GAV[0]}/{GAV[1]}/pom.properties", f"groupId={GAV[0]}\nartifactId={GAV[1]}\nversion=0.1.0-rc.160\n")
        (self.root / "target" / name).write_bytes(data.getvalue())
        with self.assertRaisesRegex(ValueError, "Main JAR GAV"):
            bundle.build_archive(self.root, self.root / "old.zip", *GAV, KEY, str(self.gpg))

    def test_missing_or_linked_input_rejected(self):
        name = bundle.names(*GAV)[1]
        path = self.root / "target" / name
        path.unlink()
        with self.assertRaises(ValueError):
            bundle.build_archive(self.root, self.root / "missing.zip", *GAV, KEY, str(self.gpg))
        path.symlink_to(self.archive)
        with self.assertRaises(ValueError):
            bundle.build_archive(self.root, self.root / "linked.zip", *GAV, KEY, str(self.gpg))

    def test_invalid_signature_status_rejected(self):
        self.gpg.write_text("#!/usr/bin/env python3\nprint('[GNUPG:] BADSIG fixture')\n")
        with self.assertRaises(ValueError):
            self.validate(self.data)

    def test_wrong_signer_rejected(self):
        with self.assertRaises(ValueError):
            bundle.validate_archive(self.data, *GAV, "B" * 40, str(self.gpg))

    def test_revoked_signature_status_rejected(self):
        self.gpg.write_text("#!/usr/bin/env python3\nprint('[GNUPG:] VALIDSIG " + KEY + " 0 0 0 4 0 1 8 00 " + KEY + "')\nprint('[GNUPG:] REVKEYSIG fixture')\n")
        with self.assertRaises(ValueError):
            self.validate(self.data)

    def test_verifier_nonzero_even_with_validsig_rejected(self):
        with self.gpg.open("a") as stream:
            stream.write("raise SystemExit(1)\n")
        with self.assertRaises(ValueError):
            self.validate(self.data)

    def test_release_only_coordinates(self):
        for values in (("a..b", "a", "1"), ("a", "../a", "1"), ("a", "a", "1-SNAPSHOT")):
            with self.subTest(values=values), self.assertRaises(ValueError):
                bundle.names(*values)


class PublisherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="central-publisher-unit-")
        self.addCleanup(self.tmp.cleanup)
        self.record = pathlib.Path(self.tmp.name) / "deployment.json"
        self.data = b"already validated archive snapshot"
        self.checked = {"bundleSHA256": hashlib.sha256(self.data).hexdigest(), "gav": list(GAV)}
        self.deployment = str(uuid.uuid4())
        self.calls = []

    def transport(self, request):
        self.calls.append(request)
        if len(self.calls) == 1:
            return self.deployment.encode()
        return json.dumps({"deploymentId": self.deployment, "deploymentState": "PUBLISHED", "purls": [f"pkg:maven/{GAV[0]}/{GAV[1]}@{GAV[2]}"]}).encode()

    def publish(self, transport=None, **kwargs):
        return publisher.publish(self.data, self.checked, self.record, "fixture-token", transport or self.transport, **kwargs)

    def test_same_bytes_single_upload_and_immediate_id(self):
        def request(req):
            if len(self.calls) == 1:
                self.assertEqual(self.deployment, json.loads(self.record.read_text())["deploymentId"])
            return self.transport(req)
        state = self.publish(request)
        self.assertEqual("PUBLISHED", state["state"])
        self.assertIn(self.data, self.calls[0].data)
        self.assertEqual("Bearer fixture-token", self.calls[0].get_header("Authorization"))
        self.assertNotIn("fixture-token", self.record.read_text())
        with self.assertRaises(FileExistsError):
            self.publish()
        self.assertEqual(2, len(self.calls))

    def test_changed_bundle_denies_before_network(self):
        self.checked["bundleSHA256"] = "0" * 64
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse(self.calls)
        self.assertFalse(self.record.exists())

    def test_foreign_receipt_release_or_source_rejected(self):
        checked = {**self.checked, "sourceCommit": "c" * 40, "sourceTree": "d" * 40}
        publisher.validation_context(checked, "v" + GAV[2], "c" * 40, "d" * 40)
        for tag, commit, tree in (("v0.1.0-rc.160", "c" * 40, "d" * 40), ("v" + GAV[2], "f" * 40, "d" * 40), ("v" + GAV[2], "c" * 40, "f" * 40)):
            with self.subTest(tag=tag, commit=commit, tree=tree), self.assertRaises(ValueError):
                publisher.validation_context(checked, tag, commit, tree)

    def test_authenticated_redirects_are_not_followed(self):
        self.assertIsNone(publisher.NoRedirect().redirect_request(None, None, 307, "redirect", {}, "https://other.invalid"))

    def test_remaining_budget_reserves_evidence_time(self):
        self.assertEqual(1920, publisher.remaining_budget(1000, 1600))
        for started, now in ((1000, 1000 + 2401), (2000, 1000), (float("nan"), 1000), (float("inf"), 1000)):
            with self.subTest(started=started, now=now), self.assertRaises(ValueError):
                publisher.remaining_budget(started, now)

    def test_unknown_upload_never_retries(self):
        def failed(req):
            self.calls.append(req)
            raise TimeoutError("fixture")
        with self.assertRaises(RuntimeError):
            self.publish(failed)
        self.assertEqual(1, len(self.calls))
        self.assertEqual("UPLOAD_OUTCOME_UNKNOWN_RECONCILE_DO_NOT_REUPLOAD", json.loads(self.record.read_text())["state"])

    def test_failed_status_retains_id(self):
        def failed(req):
            if self.calls:
                self.calls.append(req)
                return json.dumps({"deploymentId": self.deployment, "deploymentState": "FAILED", "errors": "remote fixture-token"}).encode()
            return self.transport(req)
        with self.assertRaises(RuntimeError):
            self.publish(failed)
        self.assertEqual(self.deployment, json.loads(self.record.read_text())["deploymentId"])
        self.assertNotIn("fixture-token", self.record.read_text())

    def test_status_unknown_retains_id_without_upload_retry(self):
        def invalid(req):
            if self.calls:
                raise ValueError("invalid response")
            return self.transport(req)
        with self.assertRaises(RuntimeError):
            self.publish(invalid)
        self.assertEqual(self.deployment, json.loads(self.record.read_text())["deploymentId"])
        self.assertEqual(1, len(self.calls))

    def test_wait_exhaustion_preserves_id(self):
        with mock.patch.object(publisher.time, "monotonic", side_effect=[0, 1]), self.assertRaises(RuntimeError):
            self.publish(timeout=0)
        self.assertEqual(self.deployment, json.loads(self.record.read_text())["deploymentId"])
        self.assertEqual(1, len(self.calls))

    def test_wrong_published_coordinate_rejected(self):
        def wrong(req):
            if self.calls:
                return json.dumps({"deploymentId": self.deployment, "deploymentState": "PUBLISHED", "purls": ["pkg:maven/other/other@1"]}).encode()
            return self.transport(req)
        with self.assertRaises(RuntimeError):
            self.publish(wrong)
        self.assertEqual("STATUS_UNKNOWN_RECONCILE_EXISTING_ID", json.loads(self.record.read_text())["state"])


class OperationDeadlineTests(unittest.TestCase):
    def test_actual_blocking_operation_times_out_and_restores_handler(self):
        handler = signal.getsignal(signal.SIGALRM)
        timer = signal.getitimer(signal.ITIMER_REAL)
        self.assertEqual((0.0, 0.0), timer)
        with self.assertRaises(TimeoutError):
            publisher.bounded_call(time.monotonic() + 0.02, lambda: time.sleep(0.2))
        self.assertEqual(handler, signal.getsignal(signal.SIGALRM))
        self.assertEqual(timer, signal.getitimer(signal.ITIMER_REAL))

    def test_return_after_deadline_rejected_even_if_operation_returns(self):
        handler = signal.getsignal(signal.SIGALRM)
        with mock.patch.object(publisher.time, "monotonic", side_effect=[0, 2]), self.assertRaises(TimeoutError):
            publisher.bounded_call(1, lambda: b"late")
        self.assertEqual(handler, signal.getsignal(signal.SIGALRM))
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))

    def test_existing_timer_is_not_replaced(self):
        handler = signal.getsignal(signal.SIGALRM)
        signal.setitimer(signal.ITIMER_REAL, 5)
        try:
            with self.assertRaises(RuntimeError):
                publisher.bounded_call(time.monotonic() + 10, lambda: b"not called")
            self.assertGreater(signal.getitimer(signal.ITIMER_REAL)[0], 0)
            self.assertEqual(handler, signal.getsignal(signal.SIGALRM))
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)

    def test_exception_restores_process_signal_state(self):
        handler = signal.getsignal(signal.SIGALRM)
        def fail():
            raise ValueError("fixture")
        with self.assertRaises(ValueError):
            publisher.bounded_call(time.monotonic() + 10, fail)
        self.assertEqual(handler, signal.getsignal(signal.SIGALRM))
        self.assertEqual((0.0, 0.0), signal.getitimer(signal.ITIMER_REAL))


if __name__ == "__main__":
    unittest.main()
