"""Validate only the disposable emulator's synthetic receipt dataset."""
import argparse
import json
import sqlite3
from collections import Counter
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("database", type=Path)
parser.add_argument("--output", type=Path)
args = parser.parse_args()
db = sqlite3.connect(f"file:{args.database.resolve().as_posix()}?mode=ro", uri=True)
db.row_factory = sqlite3.Row
rows = [dict(row) for row in db.execute("SELECT * FROM entries ORDER BY id")]
assert len(rows) == 8, f"Expected 8 synthetic candidates, got {len(rows)}"
expected = Counter({(1230, 0): 2, (500, 0): 1, (2856, 1): 1})
for source in ("WECHAT", "ALIPAY"):
    source_rows = [row for row in rows if row["source"] == source]
    assert Counter((row["amount_cents"], row["needs_review"]) for row in source_rows) == expected
assert all(row["type"] == "EXPENSE" and row["capture_kind"] == "SCREEN" for row in rows)
assert all(row["fingerprint"] and len(row["fingerprint"]) == 64 for row in rows)
assert len({row["fingerprint"] for row in rows}) == len(rows)
assert not any("DIFFERENT_SECURE" in row["note"] or row["amount_cents"] == 6666 for row in rows)
assert db.execute("PRAGMA user_version").fetchone()[0] == 2
summary = {
    "evidence": "Synthetic emulator receipts only; not real WeChat/Alipay payments",
    "candidates": len(rows),
    "confirmed": sum(not row["needs_review"] for row in rows),
    "needs_review": sum(bool(row["needs_review"]) for row in rows),
    "confirmed_expense_cents": sum(row["amount_cents"] for row in rows if not row["needs_review"]),
    "pending_expense_cents": sum(row["amount_cents"] for row in rows if row["needs_review"]),
    "per_source": {source: sum(row["source"] == source for row in rows) for source in ("WECHAT", "ALIPAY")},
    "immutable_capture_provenance": True,
    "database_version": 2,
}
output = json.dumps(summary, ensure_ascii=False, indent=2)
print(output)
if args.output:
    args.output.write_text(output + "\n", encoding="utf-8")
