"""Verify only a copied database from the disposable notification fixture emulator."""
import json
import sqlite3
import sys
from pathlib import Path

path = Path(sys.argv[1]).resolve()
with sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True) as db:
    rows = db.execute("SELECT source, type, amount_cents, category, merchant FROM entries ORDER BY id").fetchall()
    expected = {
        ("WECHAT", "EXPENSE", 1230): 2,
        ("WECHAT", "INCOME", 10000): 1,
        ("WECHAT", "INCOME", 500): 1,
        ("ALIPAY", "EXPENSE", 1230): 2,
        ("ALIPAY", "INCOME", 10000): 1,
        ("ALIPAY", "INCOME", 500): 1,
    }
    actual = {}
    for source, kind, cents, *_ in rows:
        key = (source, kind, cents)
        actual[key] = actual.get(key, 0) + 1
    result = {
        "input": "synthetic fixture notifications on disposable Android emulator",
        "pass": actual == expected,
        "record_count": len(rows),
        "expense_cents": sum(row[2] for row in rows if row[1] == "EXPENSE"),
        "income_cents": sum(row[2] for row in rows if row[1] == "INCOME"),
        "transactions": [dict(zip(("source", "type", "amount_cents", "category", "merchant"), row)) for row in rows],
    }
    output = path.with_suffix(".json")
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    assert actual == expected, "Fixture transactions do not match: inspect the saved JSON."
