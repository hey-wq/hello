"""Sign-in: user accounts and sessions.

Every action in the record is attributed to the signed-in user, so the name
in the record comes from here and cannot be typed in by whoever is using the
screen.

Add a user from the command line:

    python -m customsdesk.auth add maria "Maria Papadopoulou"
"""

from __future__ import annotations

import getpass
import hashlib
import hmac
import os
import secrets
import sqlite3
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    username TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    salt BLOB NOT NULL,
    password_hash BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    expires_at REAL NOT NULL
);
"""

SESSION_SECONDS = 12 * 60 * 60
MIN_PASSWORD_LENGTH = 10
MAX_FAILURES = 5
LOCKOUT_SECONDS = 60


@dataclass
class User:
    username: str
    name: str


class TooManyAttempts(Exception):
    pass


def _hash_password(password: str, salt: bytes) -> bytes:
    return hashlib.scrypt(password.encode("utf-8"), salt=salt, n=2**14, r=8, p=1, dklen=32)


def _hash_token(token: str) -> str:
    # Only a hash of the session token is stored, so a copy of the database
    # cannot be used to sign in.
    return hashlib.sha256(token.encode("ascii")).hexdigest()


class Accounts:
    def __init__(self, data_dir: Path):
        Path(data_dir).mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(Path(data_dir) / "accounts.sqlite3", check_same_thread=False)
        self._db.executescript(SCHEMA)
        self._lock = threading.Lock()
        self._failures: dict[str, tuple[int, float]] = {}

    def has_users(self) -> bool:
        return self._db.execute("SELECT 1 FROM users LIMIT 1").fetchone() is not None

    def set_user(self, username: str, name: str, password: str) -> None:
        username = username.strip().lower()
        if not username or not name.strip():
            raise ValueError("Username and name are required.")
        if len(password) < MIN_PASSWORD_LENGTH:
            raise ValueError(f"The password needs at least {MIN_PASSWORD_LENGTH} characters.")
        salt = secrets.token_bytes(16)
        with self._lock, self._db:
            self._db.execute(
                "INSERT INTO users VALUES (?,?,?,?) ON CONFLICT(username) DO UPDATE SET "
                "name=excluded.name, salt=excluded.salt, password_hash=excluded.password_hash",
                (username, name.strip(), salt, _hash_password(password, salt)),
            )
            self._db.execute("DELETE FROM sessions WHERE username=?", (username,))

    def sign_in(self, username: str, password: str) -> Optional[str]:
        """Return a new session token, or None if the details are wrong."""
        username = username.strip().lower()
        count, since = self._failures.get(username, (0, 0.0))
        if count >= MAX_FAILURES and time.time() - since < LOCKOUT_SECONDS:
            raise TooManyAttempts()

        row = self._db.execute("SELECT salt, password_hash FROM users WHERE username=?", (username,)).fetchone()
        # Hash even when the user does not exist, so timing does not reveal which usernames are real.
        salt, expected = row if row else (b"\0" * 16, b"")
        if not hmac.compare_digest(_hash_password(password, salt), expected):
            self._failures[username] = (count % MAX_FAILURES + 1, time.time())
            return None

        self._failures.pop(username, None)
        token = secrets.token_urlsafe(32)
        with self._lock, self._db:
            self._db.execute("DELETE FROM sessions WHERE expires_at < ?", (time.time(),))
            self._db.execute(
                "INSERT INTO sessions VALUES (?,?,?)", (_hash_token(token), username, time.time() + SESSION_SECONDS)
            )
        return token

    def user_for(self, token: Optional[str]) -> Optional[User]:
        if not token:
            return None
        row = self._db.execute(
            "SELECT u.username, u.name FROM sessions s JOIN users u ON u.username = s.username "
            "WHERE s.token_hash=? AND s.expires_at > ?",
            (_hash_token(token), time.time()),
        ).fetchone()
        return User(*row) if row else None

    def sign_out(self, token: Optional[str]) -> None:
        if token:
            with self._lock, self._db:
                self._db.execute("DELETE FROM sessions WHERE token_hash=?", (_hash_token(token),))

    def bootstrap_from_environment(self) -> None:
        """Create the first user on a host where there is no command line."""
        username = os.environ.get("CUSTOMSDESK_ADMIN_USER")
        password = os.environ.get("CUSTOMSDESK_ADMIN_PASSWORD")
        if username and password and not self.has_users():
            self.set_user(username, os.environ.get("CUSTOMSDESK_ADMIN_NAME", username), password)


def main(argv: list[str]) -> int:
    if len(argv) != 3 or argv[0] != "add":
        print('usage: python -m customsdesk.auth add <username> "<Full name>"')
        return 2
    data_dir = Path(os.environ.get("CUSTOMSDESK_DATA", Path(__file__).resolve().parent.parent / "var"))
    password = getpass.getpass("Password: ")
    if password != getpass.getpass("Repeat password: "):
        print("The passwords do not match.")
        return 1
    try:
        Accounts(data_dir).set_user(argv[1], argv[2], password)
    except ValueError as exc:
        print(exc)
        return 1
    print(f"Saved user {argv[1].lower()}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
