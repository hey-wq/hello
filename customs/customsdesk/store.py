"""SQLite persistence and the due-diligence record.

The record is an append-only list of events per shipment. Each event carries
the hash of the one before it, so a later change to any entry is detectable.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

from .models import Shipment

SCHEMA = """
CREATE TABLE IF NOT EXISTS shipments (
    id TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    importer_key TEXT NOT NULL,
    status TEXT NOT NULL,
    body TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    shipment_id TEXT NOT NULL,
    at TEXT NOT NULL,
    actor TEXT NOT NULL,
    type TEXT NOT NULL,
    payload TEXT NOT NULL,
    prev_hash TEXT NOT NULL,
    hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS confirmed_codes (
    importer_key TEXT NOT NULL,
    description TEXT NOT NULL,
    code TEXT NOT NULL,
    PRIMARY KEY (importer_key, description)
);
CREATE TABLE IF NOT EXISTS approved_values (
    shipment_id TEXT NOT NULL,
    code8 TEXT NOT NULL,
    origin TEXT NOT NULL,
    eur_per_kg REAL NOT NULL
);
"""

GENESIS = "0" * 64


def now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _event_hash(prev_hash: str, shipment_id: str, at: str, actor: str, type_: str, payload: str) -> str:
    material = "\n".join([prev_hash, shipment_id, at, actor, type_, payload])
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, data_dir: Path):
        self.data_dir = Path(data_dir)
        self.files_dir = self.data_dir / "files"
        self.files_dir.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(self.data_dir / "customsdesk.sqlite3", check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.executescript(SCHEMA)
        self._lock = threading.Lock()

    # -- shipments ---------------------------------------------------------

    def save(self, shipment: Shipment, importer_key: str) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO shipments (id, created_at, importer_key, status, body) VALUES (?,?,?,?,?) "
                "ON CONFLICT(id) DO UPDATE SET importer_key=excluded.importer_key, "
                "status=excluded.status, body=excluded.body",
                (shipment.id, shipment.created_at, importer_key, shipment.status.value, shipment.model_dump_json()),
            )

    def get(self, shipment_id: str) -> Optional[Shipment]:
        row = self._db.execute("SELECT body FROM shipments WHERE id=?", (shipment_id,)).fetchone()
        return Shipment.model_validate_json(row["body"]) if row else None

    def list(self) -> list[Shipment]:
        rows = self._db.execute("SELECT body FROM shipments ORDER BY created_at DESC, rowid DESC").fetchall()
        return [Shipment.model_validate_json(r["body"]) for r in rows]

    def importer_has_approved(self, importer_key: str, excluding: str) -> bool:
        row = self._db.execute(
            "SELECT 1 FROM shipments WHERE importer_key=? AND status='approved' AND id<>? LIMIT 1",
            (importer_key, excluding),
        ).fetchone()
        return row is not None

    # -- files -------------------------------------------------------------

    def save_file(self, shipment_id: str, filename: str, data: bytes) -> str:
        digest = hashlib.sha256(data).hexdigest()
        folder = self.files_dir / shipment_id
        folder.mkdir(parents=True, exist_ok=True)
        (folder / Path(filename).name).write_bytes(data)
        return digest

    # -- what the office has learned --------------------------------------

    def confirmed_codes(self, importer_key: str) -> dict[str, str]:
        rows = self._db.execute(
            "SELECT description, code FROM confirmed_codes WHERE importer_key=?", (importer_key,)
        ).fetchall()
        return {r["description"]: r["code"] for r in rows}

    def remember_code(self, importer_key: str, description: str, code: str) -> None:
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO confirmed_codes VALUES (?,?,?) "
                "ON CONFLICT(importer_key, description) DO UPDATE SET code=excluded.code",
                (importer_key, description, code),
            )

    def remember_value(self, shipment_id: str, code8: str, origin: str, eur_per_kg: float) -> None:
        with self._lock, self._db:
            self._db.execute("INSERT INTO approved_values VALUES (?,?,?,?)", (shipment_id, code8, origin, eur_per_kg))

    def past_values(self, code8: str, origin: str, excluding: str) -> list[float]:
        rows = self._db.execute(
            "SELECT eur_per_kg FROM approved_values WHERE code8=? AND origin=? AND shipment_id<>?",
            (code8, origin, excluding),
        ).fetchall()
        return [r["eur_per_kg"] for r in rows]

    # -- the record --------------------------------------------------------

    def log(self, shipment_id: str, actor: str, type_: str, **payload) -> None:
        with self._lock, self._db:
            last = self._db.execute(
                "SELECT hash FROM events WHERE shipment_id=? ORDER BY seq DESC LIMIT 1", (shipment_id,)
            ).fetchone()
            prev_hash = last["hash"] if last else GENESIS
            at = now()
            body = json.dumps(payload, sort_keys=True, ensure_ascii=False, default=str)
            digest = _event_hash(prev_hash, shipment_id, at, actor, type_, body)
            self._db.execute(
                "INSERT INTO events (shipment_id, at, actor, type, payload, prev_hash, hash) VALUES (?,?,?,?,?,?,?)",
                (shipment_id, at, actor, type_, body, prev_hash, digest),
            )

    def events(self, shipment_id: str) -> list[dict]:
        rows = self._db.execute("SELECT * FROM events WHERE shipment_id=? ORDER BY seq", (shipment_id,)).fetchall()
        return [
            {
                "seq": r["seq"],
                "at": r["at"],
                "actor": r["actor"],
                "type": r["type"],
                "payload": json.loads(r["payload"]),
                "prev_hash": r["prev_hash"],
                "hash": r["hash"],
            }
            for r in rows
        ]

    def record_intact(self, shipment_id: str) -> bool:
        rows = self._db.execute("SELECT * FROM events WHERE shipment_id=? ORDER BY seq", (shipment_id,)).fetchall()
        prev = GENESIS
        for r in rows:
            expected = _event_hash(prev, r["shipment_id"], r["at"], r["actor"], r["type"], r["payload"])
            if r["prev_hash"] != prev or r["hash"] != expected:
                return False
            prev = r["hash"]
        return True
