"""Offline only: no PostgreSQL, network or privileged fixture."""
import copy
import datetime as dt
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import page_runtime_access as op


def document():
    pins = dict(project="synthetic-project", branch="synthetic-branch", host="ep-example.c-2.us-east-1.aws.neon.tech",
                database="synthetic", schema="public", ownerRole="synthetic_owner", runtimeRole="synthetic_runtime",
                schemaOwnerRole="pg_database_owner")
    cp = dict(source="root-attestation", project=pins["project"], branch=pins["branch"], host=pins["host"],
              database=pins["database"], postgresMajor=17, observedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
              rootEvidenceSha256="a" * 64, exclusiveAclWindow=True)
    return dict(pins=pins, controlPlane=cp, ownerPassword="synthetic-secret-do-not-export")


def state(complete=False):
    tables = {t: dict(acl=[], effective={p: complete if p in op.ALLOWED else False
                                      for p in (*op.ALLOWED, *op.FORBIDDEN)}, columns="synthetic-unchanged")
              for t in op.TABLES}
    return dict(profile=op.PROFILE, owner=(1, "synthetic_owner", True, True, True, False, True, True),
                runtime=(2, "synthetic_runtime", False, False, False, False, False, True), tables=tables)


class Connection:
    def __init__(self, force_mode=None, fail_commit=False):
        self.commands = []; self.rollbacks = 0; self.commits = 0; self.closed = False
        self.force_mode = force_mode; self.fail_commit = fail_commit; self.read_only = False

    def execute(self, command, params=()):
        text = command if isinstance(command, str) else command.as_string()
        self.commands.append(text)
        if text.startswith("BEGIN"):
            self.read_only = "READ ONLY" in text
        cursor = Mock()
        cursor.fetchone.return_value = (self.force_mode or ("on" if self.read_only else "off"),)
        return cursor

    def rollback(self): self.rollbacks += 1
    def close(self): self.closed = True
    def commit(self):
        self.commits += 1
        if self.fail_commit: raise RuntimeError("synthetic-secret-do-not-export")


class AccessTest(unittest.TestCase):
    def attempt(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name).resolve(); path.chmod(0o700)
        return path

    def run_fake(self, mode, connection=None, snapshots=None, expected=None):
        doc = document(); before = state(); after = state(True)
        expected = expected or (op.fingerprint(before, doc["controlPlane"]) if mode == "apply" else None)
        connection = connection or Connection()
        connector = Mock(return_value=connection)
        directory = self.attempt()
        with patch.object(op, "inspect", side_effect=snapshots or [before, after]):
            result = op.run(doc, mode, expected, directory, connector)
        return result, connection, connector, directory

    def test_inputs_password_and_pin_boundary(self):
        d = document()
        self.assertEqual(op.inputs(op.canonical(d))["pins"]["schema"], "public")
        for key, value in [("schema", "other"), ("runtimeRole", d["pins"]["ownerRole"]),
                           ("ownerRole", 'owner"; GRANT ALL'), ("host", "ep-example-pooler.c-2.us-east-1.aws.neon.tech")]:
            bad = copy.deepcopy(d); bad["pins"][key] = value
            with self.subTest(key=key), self.assertRaises(op.Denied): op.inputs(op.canonical(bad))
        with self.assertRaises(op.Denied): op.inputs(b'{"pins":{},"pins":{}}')
        with self.assertRaises(op.Denied): op.inputs(b" " * 32769)

    def test_control_pin_freshness_and_explicit_window(self):
        d = document()
        for key, value in [("host", "ep-other.c-2.us-east-1.aws.neon.tech"), ("postgresMajor", 16),
                           ("exclusiveAclWindow", False), ("rootEvidenceSha256", "bad"),
                           ("observedAt", "2020-01-01T00:00:00Z")]:
            bad = copy.deepcopy(d); bad["controlPlane"][key] = value
            with self.subTest(key=key), self.assertRaises(op.Denied): op.inputs(op.canonical(bad))
        bad = copy.deepcopy(d)
        bad["controlPlane"]["observedAt"] = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(hours=1)).isoformat()
        with self.assertRaises(op.Denied): op.inputs(op.canonical(bad))

    def test_runtime_cannot_be_owner_privileged_or_assumable(self):
        s = state(); op.validate_runtime(s["owner"], s["runtime"], [])
        with self.assertRaises(op.Denied): op.validate_runtime(s["owner"], s["owner"], [])
        with self.assertRaises(op.Denied): op.validate_runtime(s["owner"], s["runtime"], [(1,)])
        for flag in range(2, 7):
            runtime = list(s["runtime"]); runtime[flag] = True
            with self.subTest(flag=flag), self.assertRaises(op.Denied): op.validate_runtime(s["owner"], runtime, [])

    def test_acl_public_column_inherited_grant_option_and_foreign_grantee_denied(self):
        effective = state()["tables"][op.TABLES[0]]["effective"]
        op.validate_acl([(1, 1, "UPDATE", False), (1, 2, "SELECT", False)], [], effective, (False, False), 1, 2)
        for acl, columns, any_columns in [([(1, 0, "SELECT", False)], [], (False, False)),
                ([(1, 3, "INSERT", False)], [], (False, False)), ([(1, 2, "SELECT", True)], [], (False, False)),
                ([(3, 2, "SELECT", False)], [], (False, False)), ([], [("page_id", 1, 2, "UPDATE", False)], (True, False)),
                ([], [], (False, True))]:
            with self.subTest(acl=acl), self.assertRaises(op.Denied): op.validate_acl(acl, columns, effective, any_columns, 1, 2)
        for privilege in op.FORBIDDEN:
            inherited = dict(effective); inherited[privilege] = True
            with self.subTest(privilege=privilege), self.assertRaises(op.Denied): op.validate_acl([], [], inherited, (False, False), 1, 2)

    def test_fingerprint_stable_without_password_or_clock_but_cas_sensitive(self):
        d = document(); s = state(); first = op.fingerprint(s, d["controlPlane"])
        d["ownerPassword"] = "changed-secret"; d["controlPlane"]["observedAt"] = "another-time"
        self.assertEqual(first, op.fingerprint(s, d["controlPlane"]))
        s["tables"][op.TABLES[0]]["effective"]["SELECT"] = True
        self.assertNotEqual(first, op.fingerprint(s, d["controlPlane"]))
        self.assertNotIn("secret", json.dumps(s))

    def test_exact_checks_not_weakened_by_extra_expression(self):
        self.assertEqual(op.expression("((root_component_type)::text = 'praxis-dynamic-page'::text)"),
                         op.expression("root_component_type = 'praxis-dynamic-page'"))
        self.assertNotEqual(op.expression("root_component_type = 'praxis-dynamic-page' OR true"),
                            op.expression("root_component_type = 'praxis-dynamic-page'"))

    def test_fk_and_trigger_mutants_denied(self):
        table = op.TABLES[0]
        rows = [(kind, keys, None, None, [], False, False, True, " ", " ", " ", None) for kind, keys in op.KEYS[table]]
        rows += [("c", [], None, None, [], False, False, True, " ", " ", " ", e) for e in op.CHECKS[table]]
        rows += [("f", ["page_id"], "public", op.TABLES[1], ["page_id"], True, True, True, "a", "a", "s", None)]
        op.validate_constraints(table, rows)
        broken = copy.deepcopy(rows); broken[-1] = (*broken[-1][:8], "c", *broken[-1][9:])
        with self.assertRaises(op.Denied): op.validate_constraints(table, broken)
        triggers = []
        for name in op.TABLES:
            triggers += [(name, "tr_" + name + "_immutable", 27, "O", 7, 0, None),
                         (name, "tr_" + name + "_no_truncate", 34, "O", 7, 0, None)]
        triggers += [("ui_layout_draft", "tr_ui_layout_draft_no_truncate", 34, "O", 7, 0, None),
                     ("ui_layout_draft", "tr_ui_layout_released_draft_immutable", 27, "O", 7, 0, "old.state = 'RELEASED'")]
        op.validate_triggers(triggers, 7)
        actual = copy.deepcopy(triggers)
        actual[-1] = (*actual[-1][:6], "CREATE TRIGGER tr_ui_layout_released_draft_immutable BEFORE DELETE OR UPDATE ON public.ui_layout_draft FOR EACH ROW WHEN (((old.state)::text = 'RELEASED'::text)) EXECUTE FUNCTION public.reject_ui_layout_immutable_mutation()")
        op.validate_triggers(actual, 7)
        for changed in [triggers[:-1], [(*r[:3], "D", *r[4:]) for r in triggers]]:
            with self.assertRaises(op.Denied): op.validate_triggers(changed, 7)

    def test_plan_rr_readonly_rollback_zero_grants(self):
        result, c, connect, path = self.run_fake("plan")
        self.assertEqual(result["status"], "PLAN_READ_ONLY_ROLLED_BACK")
        self.assertEqual((c.rollbacks, c.commits), (1, 0)); self.assertTrue(c.closed)
        self.assertIn("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY", c.commands)
        self.assertFalse(any(x.startswith("GRANT") for x in c.commands))
        self.assertEqual(connect.call_args.kwargs["sslmode"], "verify-full")
        self.assertEqual(connect.call_args.kwargs["channel_binding"], "require")
        self.assertNotIn(document()["ownerPassword"], (path / "result.json").read_text())

    def test_apply_exact_four_grants_and_postread_before_commit(self):
        result, c, _, path = self.run_fake("apply")
        self.assertEqual(result["status"], "APPLY_COMMIT_CONFIRMED_ROOT_READBACK_REQUIRED")
        self.assertEqual((c.rollbacks, c.commits, result["granted"]), (0, 1, 4))
        grants = [x for x in c.commands if x.startswith("GRANT")]
        self.assertEqual(set(grants), {f'GRANT {p} ON TABLE public.{t} TO "synthetic_runtime"' for t in op.TABLES for p in op.ALLOWED})
        self.assertTrue((path / "intent.json").is_file())
        self.assertFalse(result["independentReadbackConfirmed"])

    def test_fingerprint_mismatch_before_first_grant(self):
        result, c, _, _ = self.run_fake("apply", expected="f" * 64)
        self.assertEqual(result["code"], "FINGERPRINT_MISMATCH")
        self.assertFalse(any(x.startswith("GRANT") for x in c.commands)); self.assertEqual(c.rollbacks, 1)

    def test_failed_postread_rolls_back_and_does_not_claim_grants(self):
        result, c, _, _ = self.run_fake("apply", snapshots=[state(), state()])
        self.assertEqual(result["code"], "EXACT_SI_READBACK_FAILED")
        self.assertEqual((c.commits, c.rollbacks, result["granted"]), (0, 1, 0))

    def test_commit_uncertain_never_replayed_or_reported_as_rollback(self):
        result, c, _, path = self.run_fake("apply", Connection(fail_commit=True))
        self.assertEqual(result["status"], "COMMIT_UNKNOWN_NO_REPLAY")
        self.assertEqual(c.commits, 1)
        self.assertNotIn("synthetic-secret", (path / "result.json").read_text())
        with self.assertRaises(op.Denied): op.attempt_directory(path)

    def test_wrong_transaction_mode_stops_before_inspection(self):
        result, c, _, _ = self.run_fake("plan", Connection(force_mode="off"))
        self.assertEqual(result["code"], "TRANSACTION_MODE_MISMATCH")
        self.assertFalse(any(x.startswith("GRANT") for x in c.commands))

    def test_invalid_mode_and_expected_fingerprint_do_not_connect(self):
        for mode, expected in [("other", None), ("apply", None), ("apply", "bad"), ("plan", "a" * 64)]:
            connect = Mock()
            with self.subTest(mode=mode), self.assertRaises(op.Denied): op.run(document(), mode, expected, self.attempt(), connect)
            connect.assert_not_called()

    def test_journal_exclusive_private_fsynced_path(self):
        path = self.attempt(); op.write_new(path, "intent.json", {"safe": True})
        self.assertEqual((path / "intent.json").stat().st_mode & 0o777, 0o600)
        with self.assertRaises(FileExistsError): op.write_new(path, "intent.json", {})
        path = self.attempt(); path.chmod(0o755)
        with self.assertRaises(op.Denied): op.attempt_directory(path)


if __name__ == "__main__":
    unittest.main()
