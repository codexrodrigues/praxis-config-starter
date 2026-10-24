#!/usr/bin/env python3
"""Explicit owner administration of the two Config V70 table ACLs. No runtime hook."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys

TABLES = ("ui_page_catalog", "ui_page_creation_evidence")
PROFILE = "config-v70-page-runtime-select-insert-1"
ROOT = Path(__file__).resolve().parents[2]
V70 = "V70__create_page_catalog_and_creation_evidence.sql"
ALLOWED = {"SELECT", "INSERT"}
FORBIDDEN = ("UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER", "MAINTAIN")
FUNCTION_BODY = "BEGIN\n    RAISE EXCEPTION 'ui layout revisions and releases are immutable';\nEND;"
COLUMNS = {
    TABLES[0]: [("page_id", "uuid"), ("application_id", "character varying(255)"),
        ("tenant_id", "character varying(255)"), ("environment", "character varying(64)"),
        ("route_namespace", "character varying(512)"), ("root_component_type", "character varying(64)"),
        ("root_component_id", "character varying(36)"), ("created_at", "timestamp with time zone")],
    TABLES[1]: [("page_id", "uuid"), ("application_id", "character varying(255)"),
        ("tenant_id", "character varying(255)"), ("environment", "character varying(64)"),
        ("actor_ref", "character varying(255)"), ("creation_key", "uuid"), ("draft_id", "uuid"),
        ("command_hash", "character varying(64)"), ("evidence_hash", "character varying(64)"),
        ("evidence_text", "text")],
}
CHECKS = {
    TABLES[0]: ["root_component_type = 'praxis-dynamic-page'", "root_component_id = page_id::text",
        "route_namespace ~ '^/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$'"],
    TABLES[1]: ["command_hash ~ '^[0-9a-f]{64}$'", "evidence_hash ~ '^[0-9a-f]{64}$'",
        "octet_length(evidence_text) <= 2097152 AND json_typeof(evidence_text::json) = 'object'"],
}
KEYS = {
    TABLES[0]: [("p", ["page_id"]), ("u", ["page_id", "application_id", "tenant_id", "environment"]),
        ("u", ["tenant_id", "environment", "root_component_type", "root_component_id"])],
    TABLES[1]: [("p", ["page_id"]), ("u", ["draft_id"]),
        ("u", ["application_id", "tenant_id", "environment", "actor_ref", "creation_key"])],
}
FKS = {
    TABLES[0]: [(["page_id"], TABLES[1], ["page_id"], True)],
    TABLES[1]: [(["page_id"], TABLES[0], ["page_id"], False),
        (["draft_id"], "ui_layout_draft", ["id"], True),
        (["page_id", "application_id", "tenant_id", "environment"], TABLES[0],
         ["page_id", "application_id", "tenant_id", "environment"], False)],
}


class Denied(Exception):
    pass


def require(condition, code):
    if not condition:
        raise Denied(code)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "DUPLICATE_INPUT_KEY")
        result[key] = value
    return result


def fields(value, expected):
    require(isinstance(value, dict) and set(value) == set(expected), "INPUT_SHAPE")


def inputs(raw, now=None):
    require(len(raw) <= 32768, "INPUT_TOO_LARGE")
    document = json.loads(raw, object_pairs_hook=unique,
                          parse_constant=lambda _: (_ for _ in ()).throw(Denied("NON_JSON_NUMBER")))
    fields(document, ("pins", "controlPlane", "ownerPassword"))
    pins, cp = document["pins"], document["controlPlane"]
    fields(pins, ("project", "branch", "host", "database", "schema", "ownerRole", "runtimeRole", "schemaOwnerRole"))
    for key, value in pins.items():
        require(isinstance(value, str) and 0 < len(value) <= 253, "PIN_INVALID")
        if key in ("database", "ownerRole", "runtimeRole", "schemaOwnerRole"):
            require(re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]{0,62}", value), "IDENTIFIER_INVALID")
    require(re.fullmatch(r"ep-[a-z0-9-]+\.[a-z0-9.-]+\.neon\.tech", pins["host"])
            and "-pooler." not in pins["host"], "DIRECT_HOST_REQUIRED")
    require(pins["schema"] == "public" and pins["runtimeRole"] != pins["ownerRole"], "ROLE_SCHEMA_BOUNDARY")
    fields(cp, ("source", "project", "branch", "host", "database", "postgresMajor", "observedAt",
                "rootEvidenceSha256", "exclusiveAclWindow"))
    require(cp["source"] == "root-attestation" and type(cp["postgresMajor"]) is int
            and cp["postgresMajor"] == 17 and cp["exclusiveAclWindow"] is True, "CONTROL_ATTESTATION")
    for key in ("project", "branch", "host", "database"):
        require(cp[key] == pins[key], "CONTROL_PIN_MISMATCH")
    require(isinstance(cp["rootEvidenceSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", cp["rootEvidenceSha256"]), "CONTROL_HASH")
    control_fresh(cp, now)
    require(isinstance(document["ownerPassword"], str) and document["ownerPassword"], "CREDENTIAL_REQUIRED")
    return document


def control_fresh(cp, now=None):
    now = now or dt.datetime.now(dt.timezone.utc)
    observed = dt.datetime.fromisoformat(cp["observedAt"].replace("Z", "+00:00"))
    require(observed.tzinfo is not None and 0 <= (now - observed).total_seconds() <= 1800, "CONTROL_STALE")


def expression(value):
    """Compare closed V70 CHECK tokens; ignore parser parentheses and implicit text casts only."""
    tokens = re.findall(r"'(?:''|[^'])*'|::[a-z_]+|[a-z_][a-z_0-9]*|[0-9]+|<=|[=~()]|\S", value, re.I)
    return [t if t.startswith("'") else t.lower() for t in tokens if t not in ("(", ")", "::text")]


def query(connection, text, params=()):
    return connection.execute(text, params).fetchall()


def inspect(connection, pins):
    settings = query(connection, "SELECT current_user,session_user,current_database(),current_setting('server_version_num')::int,current_setting('transaction_isolation')")[0]
    require(settings[:3] == (pins["ownerRole"], pins["ownerRole"], pins["database"])
            and 170000 <= settings[3] < 180000 and settings[4] == "repeatable read", "OWNER_DATABASE_VERSION")
    require(connection.pgconn.ssl_in_use, "CLIENT_TLS_REQUIRED")
    roles = query(connection, """SELECT oid::bigint,rolname,rolsuper,rolcreatedb,rolcreaterole,
        rolreplication,rolbypassrls,rolcanlogin FROM pg_catalog.pg_roles WHERE rolname IN (%s,%s) ORDER BY rolname""",
        (pins["ownerRole"], pins["runtimeRole"]))
    require(len(roles) == 2, "ROLES_MISSING")
    owner = next(r for r in roles if r[1] == pins["ownerRole"])
    runtime = next(r for r in roles if r[1] == pins["runtimeRole"])
    closure = query(connection, """WITH RECURSIVE active(oid) AS (
        SELECT roleid FROM pg_catalog.pg_auth_members WHERE member=%s AND (inherit_option OR set_option)
        UNION SELECT m.roleid FROM pg_catalog.pg_auth_members m JOIN active a ON m.member=a.oid
        WHERE m.inherit_option OR m.set_option) SELECT oid::bigint FROM active ORDER BY oid""", (runtime[0],))
    validate_runtime(owner, runtime, closure)
    schema = query(connection, """SELECT n.oid::bigint,r.rolname,
        has_schema_privilege(%s,n.oid,'USAGE'),has_schema_privilege(%s,n.oid,'CREATE'),
        has_database_privilege(%s,current_database(),'CREATE'),
        has_parameter_privilege(%s,'session_replication_role','SET')
        FROM pg_catalog.pg_namespace n JOIN pg_catalog.pg_roles r ON r.oid=n.nspowner WHERE n.nspname='public'""",
        (runtime[1], runtime[1], runtime[1], runtime[1]))
    require(len(schema) == 1 and schema[0][1] == pins["schemaOwnerRole"] and schema[0][2]
            and not any(schema[0][3:]), "RUNTIME_SCHEMA_BOUNDARY")
    history = query(connection, """SELECT version,script,type,success FROM public.flyway_schema_history
        WHERE version='70' ORDER BY installed_rank""")
    require(history == [("70", V70, "SQL", True)], "V70_HISTORY_REQUIRED")
    function = query(connection, """SELECT p.oid::bigint,p.proowner::bigint,p.prosrc,l.lanname,p.prosecdef,
        p.proconfig,p.pronargs,p.prorettype::int,p.provolatile,p.proleakproof
        FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace
        JOIN pg_catalog.pg_language l ON l.oid=p.prolang
        WHERE n.nspname='public' AND p.proname='reject_ui_layout_immutable_mutation'""")
    require(len(function) == 1, "IMMUTABILITY_FUNCTION_MISSING")
    f = function[0]
    require(f[1] == owner[0] and f[2].strip() == FUNCTION_BODY and f[3:] == ("plpgsql", False, None, 0, 2279, "v", False), "IMMUTABILITY_FUNCTION_DRIFT")
    state = {"profile": PROFILE, "pins": pins, "owner": owner, "runtime": runtime,
             "schema": schema, "history": history, "function": function, "tables": {}}
    for table in TABLES:
        relation = query(connection, """SELECT c.oid::bigint,c.relowner::bigint,c.relkind,c.relpersistence,
            c.relrowsecurity,c.relforcerowsecurity,c.relispartition,c.relhassubclass
            FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
            WHERE n.nspname='public' AND c.relname=%s""", (table,))
        require(len(relation) == 1 and relation[0][1:] == (owner[0], "r", "p", False, False, False, False), "TABLE_OWNER_OR_SHAPE")
        oid = relation[0][0]
        columns = query(connection, """SELECT a.attname,pg_catalog.format_type(a.atttypid,a.atttypmod),a.attnotnull,
            a.attidentity,a.attgenerated,a.attisdropped,a.attcollation=t.typcollation,
            pg_catalog.pg_get_expr(d.adbin,d.adrelid)
            FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
            LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
            WHERE a.attrelid=%s AND a.attnum>0 ORDER BY a.attnum""", (oid,))
        require(columns == [(n, t, True, "", "", False, True, None) for n, t in COLUMNS[table]], "V70_COLUMNS_DRIFT")
        constraints = query(connection, """SELECT c.contype,
            ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
                  JOIN pg_catalog.pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY ord),
            nr.nspname,cr.relname,
            ARRAY(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(num,ord)
                  JOIN pg_catalog.pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY ord),
            c.condeferrable,c.condeferred,c.convalidated,c.confupdtype,c.confdeltype,c.confmatchtype,
            pg_catalog.pg_get_expr(c.conbin,c.conrelid)
            FROM pg_catalog.pg_constraint c LEFT JOIN pg_catalog.pg_class cr ON cr.oid=c.confrelid
            LEFT JOIN pg_catalog.pg_namespace nr ON nr.oid=cr.relnamespace
            WHERE c.conrelid=%s ORDER BY c.conname""", (oid,))
        validate_constraints(table, constraints)
        acl = query(connection, """SELECT a.grantor::bigint,a.grantee::bigint,a.privilege_type,a.is_grantable
            FROM pg_catalog.pg_class c CROSS JOIN LATERAL
            aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=%s
            ORDER BY a.grantor,a.grantee,a.privilege_type""", (oid,))
        column_acl = query(connection, """SELECT a.attname,x.grantor::bigint,x.grantee::bigint,x.privilege_type,x.is_grantable
            FROM pg_catalog.pg_attribute a CROSS JOIN LATERAL aclexplode(a.attacl) x
            WHERE a.attrelid=%s ORDER BY a.attnum,x.grantee,x.privilege_type""", (oid,))
        effective = {p: query(connection, "SELECT has_table_privilege(%s,%s,%s)", (runtime[1], oid, p))[0][0]
                     for p in ("SELECT", "INSERT", *FORBIDDEN)}
        any_columns = query(connection, "SELECT has_any_column_privilege(%s,%s,'UPDATE'),has_any_column_privilege(%s,%s,'REFERENCES')",
                            (runtime[1], oid, runtime[1], oid))[0]
        validate_acl(acl, column_acl, effective, any_columns, owner[0], runtime[0])
        state["tables"][table] = {"relation": relation, "columns": columns, "constraints": constraints,
                                   "acl": acl, "columnAcl": column_acl, "effective": effective}
    triggers = query(connection, """SELECT c.relname,t.tgname,t.tgtype,t.tgenabled,t.tgfoid::bigint,t.tgnargs,
        CASE WHEN t.tgqual IS NULL THEN NULL ELSE pg_catalog.pg_get_triggerdef(t.oid,false) END
        FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
        JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
        AND c.relname=ANY(%s) AND NOT t.tgisinternal ORDER BY c.relname,t.tgname""", ([*TABLES, "ui_layout_draft"],))
    validate_triggers(triggers, f[0])
    state["triggers"] = triggers
    return state


def validate_runtime(owner, runtime, closure):
    require(owner[0] != runtime[0] and owner[1] != runtime[1], "OWNER_IS_RUNTIME")
    require(not any(runtime[2:7]) and runtime[7] is True, "RUNTIME_NOT_ORDINARY")
    require(not closure, "RUNTIME_ACTIVE_ROLE_MEMBERSHIP")


def validate_constraints(table, rows):
    require(all(r[7] is True for r in rows), "UNVALIDATED_CONSTRAINT")
    keys = sorted((r[0], r[1]) for r in rows if r[0] in ("p", "u"))
    require(keys == sorted(KEYS[table]), "V70_KEYS_DRIFT")
    checks = sorted(expression(r[11]) for r in rows if r[0] == "c")
    require(checks == sorted(expression(e) for e in CHECKS[table]), "V70_CHECK_DRIFT")
    fks = []
    for r in rows:
        require(r[0] in ("p", "u", "f", "c"), "UNEXPECTED_CONSTRAINT")
        if r[0] == "f":
            require(r[2] == "public" and r[5] == r[6] and r[8:11] == ("a", "a", "s"), "V70_FK_POLICY")
            fks.append((r[1], r[3], r[4], r[5]))
        else:
            require(not r[5] and not r[6], "UNEXPECTED_DEFERRABLE_CONSTRAINT")
    require(sorted(fks) == sorted(FKS[table]), "V70_FK_DRIFT")


def validate_triggers(rows, function_oid):
    expected = []
    for table in TABLES:
        expected.extend([(table, "tr_" + table + "_immutable", 27, "O", function_oid, 0, None),
                         (table, "tr_" + table + "_no_truncate", 34, "O", function_oid, 0, None)])
    expected.extend([("ui_layout_draft", "tr_ui_layout_draft_no_truncate", 34, "O", function_oid, 0, None),
                     ("ui_layout_draft", "tr_ui_layout_released_draft_immutable", 27, "O", function_oid, 0, "old.state = 'RELEASED'")])
    def normalize(row):
        value = row[6]
        if value is not None and value.startswith("CREATE TRIGGER "):
            require(" WHEN (" in value and ") EXECUTE FUNCTION " in value, "TRIGGER_WHEN_UNAVAILABLE")
            value = value.split(" WHEN (", 1)[1].rsplit(") EXECUTE FUNCTION ", 1)[0]
        return (*row[:6], None if value is None else expression(value))
    require(sorted(map(normalize, rows)) == sorted(map(normalize, expected)), "V70_TRIGGER_DRIFT")


def validate_acl(rows, columns, effective, any_columns, owner_oid, runtime_oid):
    require(not any(effective[p] for p in FORBIDDEN) and not any(any_columns), "RUNTIME_FORBIDDEN_PRIVILEGE")
    for grantor, grantee, privilege, grantable in rows:
        require(grantor == owner_oid and grantee in (owner_oid, runtime_oid), "UNEXPECTED_ACL_GRANTEE_OR_GRANTOR")
        if grantee == runtime_oid:
            require(privilege in ALLOWED and not grantable, "RUNTIME_ACL_NOT_MINIMAL")
    require(not columns, "COLUMN_ACL_NOT_ADMITTED")


def proposal(state):
    return [{"table": t, "privileges": sorted(p for p in ALLOWED if not state["tables"][t]["effective"][p])}
            for t in TABLES if not all(state["tables"][t]["effective"][p] for p in ALLOWED)]


def fingerprint(state, cp):
    return digest({"state": state, "rootEvidenceSha256": cp["rootEvidenceSha256"], "proposal": proposal(state)})


def write_new(directory, name, value):
    descriptor = os.open(directory / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(canonical(value) + b"\n"); output.flush(); os.fsync(output.fileno())
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def attempt_directory(path):
    require(path.is_absolute() and path == path.resolve(strict=True), "ATTEMPT_PATH")
    info = path.stat()
    require(stat.S_ISDIR(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o700
            and info.st_uid == os.getuid() and not any(path.iterdir()), "ATTEMPT_DIRECTORY_NOT_NEW_PRIVATE")


def run(document, mode, expected, directory, connector=None):
    import psycopg
    from psycopg import sql
    require(mode in ("plan", "apply"), "MODE_INVALID")
    require((mode == "plan" and expected is None) or (mode == "apply" and isinstance(expected, str)
            and re.fullmatch(r"[0-9a-f]{64}", expected)), "EXPECTED_FINGERPRINT_REQUIRED")
    pins, cp = document["pins"], document["controlPlane"]
    attempt_directory(directory)
    write_new(directory, "intent.json", {"profile": PROFILE, "mode": mode, "expectedFingerprint": expected,
              "pinsSha256": digest(pins), "rootEvidenceSha256": cp["rootEvidenceSha256"]})
    connector = connector or psycopg.connect
    connection = None
    committed = False
    commit_started = False
    result = {"profile": PROFILE, "mode": mode, "status": "FAILED_STOP_AND_INSPECT", "granted": 0,
              "grantStatementsCompleted": 0}
    try:
        connection = connector(host=pins["host"], port=5432, dbname=pins["database"], user=pins["ownerRole"],
            password=document["ownerPassword"], sslmode="verify-full", channel_binding="require", connect_timeout=10,
            target_session_attrs="read-write", autocommit=True)
        connection.execute("BEGIN ISOLATION LEVEL REPEATABLE READ " + ("READ ONLY" if mode == "plan" else "READ WRITE"))
        connection.execute("SET LOCAL statement_timeout='5s'")
        connection.execute("SET LOCAL lock_timeout='2s'")
        connection.execute("SET LOCAL search_path=pg_catalog")
        read_only = connection.execute("SELECT current_setting('transaction_read_only')").fetchone()[0]
        require(read_only == ("on" if mode == "plan" else "off"), "TRANSACTION_MODE_MISMATCH")
        if mode == "apply":
            connection.execute("LOCK TABLE public.ui_page_catalog,public.ui_page_creation_evidence IN ACCESS EXCLUSIVE MODE")
        before = inspect(connection, pins)
        approved = fingerprint(before, cp)
        changes = proposal(before)
        result.update(fingerprint=approved, proposal=changes, ownerOid=before["owner"][0], runtimeOid=before["runtime"][0],
                      ownerLegacyFlags=list(before["owner"][2:7]), tlsVerifyFull=True, channelBindingRequired=True)
        if mode == "plan":
            connection.rollback()
            result["status"] = "PLAN_READ_ONLY_ROLLED_BACK"
        else:
            require(approved == expected, "FINGERPRINT_MISMATCH")
            control_fresh(cp)
            for change in changes:
                # Only these four literal statements can ever be emitted. No identifier comes from input except the pinned existing role.
                require(change["table"] in TABLES and set(change["privileges"]) <= ALLOWED, "GRANT_ALLOWLIST")
                for privilege in change["privileges"]:
                    connection.execute(sql.SQL("GRANT " + privilege + " ON TABLE public." + change["table"] + " TO {}").format(sql.Identifier(pins["runtimeRole"])))
                    result["grantStatementsCompleted"] += 1
            after = inspect(connection, pins)
            require(not proposal(after), "EXACT_SI_READBACK_FAILED")
            structural = lambda s: {**s, "tables": {t: {k: v for k, v in x.items() if k not in ("acl", "effective")} for t, x in s["tables"].items()}}
            require(structural(before) == structural(after), "CATALOG_CHANGED_DURING_APPLY")
            commit_started = True
            connection.commit(); committed = True
            result["granted"] = result["grantStatementsCompleted"]
            result["status"] = "APPLY_COMMIT_CONFIRMED_ROOT_READBACK_REQUIRED"
    except BaseException as failure:
        result["code"] = str(failure) if isinstance(failure, Denied) else "OPERATION_FAILED"
        result["status"] = "COMMIT_UNKNOWN_NO_REPLAY" if commit_started and not committed else "FAILED_STOP_AND_INSPECT"
        if connection is not None and not committed:
            try:
                connection.rollback()
            except BaseException:
                pass
    finally:
        if connection is not None:
            try:
                connection.close()
            except BaseException:
                result["connectionCloseConfirmed"] = False
    result["independentReadbackConfirmed"] = False
    try:
        write_new(directory, "result.json", result)
    except BaseException:
        result["status"] = "LOCAL_RECEIPT_FAILED_NO_REPLAY"
        result["commitConfirmedInProcess"] = committed
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "apply"))
    parser.add_argument("--expected-fingerprint")
    parser.add_argument("--attempt-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        require(args.mode != "apply" or isinstance(args.expected_fingerprint, str)
                and re.fullmatch(r"[0-9a-f]{64}", args.expected_fingerprint), "EXPECTED_FINGERPRINT_REQUIRED")
        require(args.mode != "plan" or args.expected_fingerprint is None, "PLAN_EXPECTED_HASH_FORBIDDEN")
        document = inputs(sys.stdin.buffer.read(32769))
        result = run(document, args.mode, args.expected_fingerprint, args.attempt_dir)
        print(json.dumps({k: result[k] for k in ("status", "fingerprint", "granted", "code") if k in result}))
        return 0 if result["status"] in ("PLAN_READ_ONLY_ROLLED_BACK", "APPLY_COMMIT_CONFIRMED_ROOT_READBACK_REQUIRED") else 1
    except BaseException as failure:
        print(json.dumps({"status": "FAILED_STOP_AND_INSPECT", "code": str(failure) if isinstance(failure, Denied) else "INVALID_INPUT_OR_LOCAL_FAILURE"}))
        return 1


if __name__ == "__main__":
    sys.exit(main())
