"""Offline, verified SQLite snapshot for work-ledger migration and audit. No service cutover."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sqlite3


def export(source: Path, output: Path) -> dict:
    if output.exists():
        raise ValueError("Output already exists; choose a new snapshot path")
    ledger = json.loads((source / "group_work.json").read_text(encoding="utf-8"))
    rows = []
    for stub in ledger["work"]:
        if not re.fullmatch(r"gw_[0-9a-f]{24}", stub["id"]):
            raise ValueError("Invalid work ID in ledger")
        value = stub
        if stub.get("archived"):
            value = json.loads((source / "group_work_archive" / (stub["id"] + ".json")).read_text(encoding="utf-8"))
            if value["id"] != stub["id"] or value["owner"]["userId"] != stub["owner"]["userId"]:
                raise ValueError("Archive ownership mismatch")
            value["archived"] = True
        raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        rows.append((value, raw, hashlib.sha256(raw.encode()).hexdigest()))
    if len({v["id"] for v, _, _ in rows}) != len(rows):
        raise ValueError("Duplicate work ID")
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive filesystem claim prevents replacing an operator's existing database.
    with output.open("xb"):
        pass
    db = sqlite3.connect(output)
    try:
        with db:
            db.executescript("""
                CREATE TABLE work(id TEXT PRIMARY KEY,group_id TEXT NOT NULL,user_id TEXT NOT NULL,
                    status TEXT NOT NULL,archived INTEGER NOT NULL,updated_at INTEGER NOT NULL,
                    body TEXT NOT NULL,sha256 TEXT NOT NULL);
                CREATE INDEX work_group_updated ON work(group_id,updated_at);
                CREATE INDEX work_owner ON work(user_id);
                CREATE TABLE metadata(name TEXT PRIMARY KEY,body TEXT NOT NULL);
            """)
            for value, raw, digest in rows:
                db.execute("INSERT INTO work VALUES(?,?,?,?,?,?,?,?)", (
                    value["id"], value["groupId"], value["owner"]["userId"], value["status"],
                    int(value.get("archived", False)), value["updatedAt"], raw, digest))
            db.execute("INSERT INTO metadata VALUES(?,?)", ("schema", "vibelink-work-snapshot-v1"))
            for name in ("work_leases.json", "model_usage.json"):
                path = source / name
                if path.exists():
                    body = path.read_text(encoding="utf-8")
                    json.loads(body)
                    db.execute("INSERT INTO metadata VALUES(?,?)", (name, body))
        actual = db.execute("SELECT id,body,sha256 FROM work").fetchall()
        if len(actual) != len(rows) or any(hashlib.sha256(raw.encode()).hexdigest() != digest for _, raw, digest in actual):
            raise ValueError("Snapshot verification failed")
        if db.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise ValueError("SQLite integrity check failed")
        return {"workCount": len(rows), "verified": True, "serviceCutover": False}
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="A consistent backup of one edition/tenant data directory")
    parser.add_argument("output", type=Path, help="New private SQLite snapshot path")
    args = parser.parse_args()
    print(json.dumps(export(args.source, args.output)))
