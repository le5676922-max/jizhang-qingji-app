"""Read-only validation of the dedicated emulator's V2 synthetic receipt dataset.

This is not a verifier for a personal ledger or evidence of real payment-app coverage.
Use the complete database snapshot after running both fixture providers.
"""

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sqlite3
import sys


PROVIDERS = ("WECHAT", "ALIPAY")
TABLE_COLUMNS = {
    "entries": {"id", "amount_cents", "type", "business_type", "source", "needs_review",
                "related_entry_id", "deleted_at", "fingerprint"},
    "source_events": {"id", "event_fingerprint", "provider", "evidence", "amount_cents",
                      "direction", "business_type", "completion", "has_stable_identity",
                      "order_hash", "order_namespace", "original_order_hash", "original_order_namespace",
                      "decision", "linked_entry_id", "suggested_entry_id", "related_entry_id"},
    "event_aliases": {"fingerprint", "event_id"},
    "seen_events": {"fingerprint", "observed_at"},
    "merchant_rules": {"provider", "merchant_key", "type", "category"},
    "link_actions": {"event_id", "action", "from_entry_id", "to_entry_id"},
    "recognition_diagnostics": {"channel", "last_success_at", "last_failure_at"},
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def order_hash(provider, namespace, order):
    # Matches DatabaseRecords.providerOrderHash; raw IDs are fixed synthetic fixture constants.
    identity = f"provider-order|{provider}|{namespace}|{order}"
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def counter_dict(values):
    return dict(sorted(Counter(values).items()))


def verify(db):
    require(db.execute("PRAGMA user_version").fetchone()[0] == 3, "Expected SQLite schema version 3")
    require([row[0] for row in db.execute("PRAGMA quick_check")] == ["ok"], "SQLite quick_check failed")
    require(not list(db.execute("PRAGMA foreign_key_check")), "SQLite foreign-key relationships are invalid")
    for table, required_columns in TABLE_COLUMNS.items():
        # Table names come only from this fixed list, never from database content or CLI input.
        columns = {row[1] for row in db.execute(f"PRAGMA table_info({table})")}
        require(required_columns <= columns, f"Missing expected schema columns in {table}")

    entries = [dict(row) for row in db.execute("SELECT * FROM entries ORDER BY id")]
    events = [dict(row) for row in db.execute("SELECT * FROM source_events ORDER BY id")]
    aliases = {row["fingerprint"]: row["event_id"] for row in db.execute("SELECT * FROM event_aliases")}
    seen = {row["fingerprint"] for row in db.execute("SELECT fingerprint FROM seen_events")}
    by_id = {entry["id"]: entry for entry in entries}
    require(len(by_id) == len(entries), "Canonical entry IDs are not unique")
    require(all(entry["source"] in PROVIDERS for entry in entries), "Unexpected canonical source outside the synthetic providers")
    require(all(event["provider"] in PROVIDERS for event in events), "Unexpected event provider outside the synthetic providers")
    require(all(entry["deleted_at"] is None and entry["needs_review"] == 0 for entry in entries),
            "The E2E canonical ledger must contain only active, confirmed records")
    require(all(type(entry["amount_cents"]) is int and entry["amount_cents"] > 0 for entry in entries),
            "Canonical amounts must be positive integer cents")
    require(not any(entry["amount_cents"] in (5000, 1230, 2856, 6666) for entry in entries),
            "Over-refund, transfer, OCR or chat amount incorrectly entered the canonical ledger")
    fingerprints = [entry["fingerprint"] for entry in entries]
    require(all(fingerprints) and len(set(fingerprints)) == len(fingerprints), "Canonical fingerprints are missing or duplicate")
    require(all(fingerprint in seen for fingerprint in fingerprints), "Canonical identity is missing from seen-event history")

    for event in events:
        require(aliases.get(event["event_fingerprint"]) == event["id"], "A source event is missing its primary alias")
        require(event["event_fingerprint"] in seen, "A source event is missing its seen-event tombstone")
        require(type(event["amount_cents"]) is int and event["amount_cents"] > 0, "Event amounts must be positive integer cents")
        if event["decision"] == "REVIEW":
            require(event["linked_entry_id"] is None, "Synthetic REVIEW event must not be linked to a posted canonical record")
        elif event["decision"] == "AUTO":
            canonical = by_id.get(event["linked_entry_id"])
            require(canonical is not None, "AUTO source event has no canonical record")
            require(canonical["source"] == event["provider"], "A source event was linked across payment providers")
            require(canonical["amount_cents"] == event["amount_cents"] and
                    canonical["business_type"] == event["business_type"], "AUTO event and canonical financial facts disagree")
        else:
            require(event["decision"] == "IGNORE", "Unexpected recognition decision")
    require(not any(event["amount_cents"] == 6666 for event in events), "Chat fixture unexpectedly produced a source event")

    summaries = {}
    for provider in PROVIDERS:
        canonical = [entry for entry in entries if entry["source"] == provider]
        source_events = [event for event in events if event["provider"] == provider]
        require(len(canonical) == 5, f"{provider}: expected exactly five canonical records, got {len(canonical)}")
        expenses = [entry for entry in canonical if entry["business_type"] == "EXPENSE"]
        refunds = [entry for entry in canonical if entry["business_type"] == "REFUND"]
        require(Counter(entry["amount_cents"] for entry in expenses) == Counter({2800: 2, 500: 1}),
                f"{provider}: expected two distinct 28.00 payments and one 5.00 sent red packet")
        require(Counter(entry["amount_cents"] for entry in refunds) == Counter({500: 1, 300: 1}),
                f"{provider}: expected separate 5.00 and 3.00 refunds")
        require(all(entry["type"] == "EXPENSE" and entry["related_entry_id"] is None for entry in expenses),
                f"{provider}: expense direction or original-record association is invalid")
        require(all(entry["type"] == "INCOME" for entry in refunds), f"{provider}: refund cash direction must be incoming")

        def identified_events(namespace, synthetic_order):
            digest = order_hash(provider, namespace, synthetic_order)
            matched = [event for event in source_events if event["order_hash"] == digest and event["order_namespace"] == namespace]
            require(matched, f"{provider} {synthetic_order}: missing reliable {namespace} order identity; weak namespaces are not accepted")
            return matched

        def paired_canonical(namespace, synthetic_order, amount, kind, direction):
            matched = identified_events(namespace, synthetic_order)
            evidence = {event["evidence"] for event in matched}
            require({"ACCESSIBILITY", "NOTIFICATION"} <= evidence,
                    f"{provider} {synthetic_order}: missing node/notification evidence for the same reliable order")
            require(all(event["decision"] == "AUTO" and event["completion"] == "COMPLETED" and
                        event["has_stable_identity"] == 1 and event["amount_cents"] == amount and
                        event["business_type"] == kind and event["direction"] == direction for event in matched),
                    f"{provider} {synthetic_order}: paired evidence is not consistently completed, stable and posted")
            linked_ids = {event["linked_entry_id"] for event in matched}
            require(len(linked_ids) == 1 and None not in linked_ids, f"{provider} {synthetic_order}: sources did not share one canonical record")
            record = by_id.get(next(iter(linked_ids)))
            require(record is not None and record["source"] == provider and record["amount_cents"] == amount and
                    record["business_type"] == kind, f"{provider} {synthetic_order}: wrong canonical association")
            return record, matched

        first_payment, _ = paired_canonical("provider_transaction", "SYN_PAY_0001", 2800, "EXPENSE", "OUTGOING")
        second_payment, _ = paired_canonical("provider_transaction", "SYN_PAY_0002", 2800, "EXPENSE", "OUTGOING")
        require(first_payment["id"] != second_payment["id"], f"{provider}: different reliable payment orders were merged by amount")
        payment_original_hash = order_hash(provider, "provider_transaction", "SYN_PAY_0001")
        refund_orders = (("SYN_REFUND_0001", 500), ("SYN_REFUND_0002", 300))
        refund_ids = set()
        for synthetic_order, amount in refund_orders:
            refund, matched = paired_canonical("provider_refund", synthetic_order, amount, "REFUND", "INCOMING")
            refund_ids.add(refund["id"])
            require(refund["related_entry_id"] == first_payment["id"], f"{provider} {synthetic_order}: refund is not linked to payment 0001")
            require(all(event["original_order_hash"] == payment_original_hash and
                        event["original_order_namespace"] == "provider_transaction" and
                        event["related_entry_id"] == first_payment["id"] for event in matched),
                    f"{provider} {synthetic_order}: original payment identity/association is missing or confused with the refund ID")
        require(len(refund_ids) == 2, f"{provider}: separate partial refunds were merged")
        require({entry["id"] for entry in refunds} == refund_ids, f"{provider}: unexpected refund canonical record")

        red_packet = next(entry for entry in expenses if entry["amount_cents"] == 500)
        require(any(event["evidence"] == "ACCESSIBILITY" and event["decision"] == "AUTO" and
                    event["business_type"] == "EXPENSE" and event["direction"] == "OUTGOING" and
                    event["completion"] == "COMPLETED" and event["linked_entry_id"] == red_packet["id"]
                    for event in source_events), f"{provider}: sent red packet lacks completed outgoing node evidence")

        over_refund = identified_events("provider_refund", "SYN_REFUND_0003")
        require(any(event["evidence"] == "ACCESSIBILITY" for event in over_refund), f"{provider}: over-refund screen evidence is missing")
        require(all(event["amount_cents"] == 5000 and event["business_type"] == "REFUND" and
                    event["direction"] == "INCOMING" and event["decision"] == "REVIEW" and event["linked_entry_id"] is None and
                    event["original_order_hash"] == payment_original_hash and event["original_order_namespace"] == "provider_transaction"
                    for event in over_refund), f"{provider}: over-refund did not remain an unposted REVIEW candidate")

        for synthetic_order in ("SCREEN_TRANSFER_001", "SCREEN_TRANSFER_002"):
            transfers = identified_events("provider_transaction", synthetic_order)
            require(any(event["evidence"] == "ACCESSIBILITY" for event in transfers), f"{provider}: transfer screen evidence is missing")
            require(all(event["amount_cents"] == 1230 and event["business_type"] == "TRANSFER" and
                        event["direction"] == "OUTGOING" and event["decision"] == "REVIEW" and event["linked_entry_id"] is None
                        for event in transfers), f"{provider}: friend transfer was treated as posted consumption")
        ocr = [event for event in source_events if event["evidence"] == "OCR" and event["amount_cents"] == 2856]
        require(ocr and all(event["decision"] == "REVIEW" and event["linked_entry_id"] is None for event in ocr),
                f"{provider}: Canvas OCR did not remain a 28.56 unposted REVIEW candidate")
        secure_hash = order_hash(provider, "provider_transaction", "DIFFERENT_SECURE_001")
        require(not any(event["order_hash"] == secure_hash for event in source_events), f"{provider}: protected fixture order unexpectedly produced an event")

        refunded_by_original = {}
        for refund in refunds:
            original = by_id.get(refund["related_entry_id"])
            require(original is not None and original["source"] == provider and original["business_type"] == "EXPENSE" and
                    original["type"] == "EXPENSE" and original["needs_review"] == 0 and original["deleted_at"] is None,
                    f"{provider}: refund refers to an invalid original expense")
            refunded_by_original[original["id"]] = refunded_by_original.get(original["id"], 0) + refund["amount_cents"]
        require(all(total <= by_id[original_id]["amount_cents"] for original_id, total in refunded_by_original.items()),
                f"{provider}: aggregate refunds exceed their original expense")
        gross = sum(entry["amount_cents"] for entry in expenses)
        refunded = sum(entry["amount_cents"] for entry in refunds)
        require(gross - refunded == 5300, f"{provider}: expected 53.00 net expense")
        summaries[provider] = {
            "canonical_records": len(canonical), "expense_records": len(expenses), "refund_records": len(refunds),
            "gross_expense_cents": gross, "refund_cents": refunded, "net_expense_cents": gross - refunded,
            "earned_income_cents": 0,
            "source_events": len(source_events), "decisions": counter_dict(event["decision"] for event in source_events),
            "evidence": counter_dict(event["evidence"] for event in source_events),
            "review_amount_counts": counter_dict(event["amount_cents"] for event in source_events if event["decision"] == "REVIEW"),
            "paired_reliable_orders": 4, "distinct_same_price_payments": True,
        }

    return {
        "verified": True, "database_version": 3,
        "evidence": "Dedicated-emulator synthetic receipts only; not real WeChat, Alipay or S23 Ultra validation",
        "canonical_records": len(entries), "source_events": len(events), "per_provider": summaries,
        "net_expense_cents": sum(summary["net_expense_cents"] for summary in summaries.values()),
        "review_events_unposted": True, "chat_amount_absent": True, "protected_order_identity_absent": True,
        "negative_page_scope": "Database checks exclude the chat amount and known protected order identity; runtime evidence is still required to prove FLAG_SECURE rejection or attribute anonymous OCR observations.",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("database", type=Path, help="Complete V2 synthetic E2E SQLite snapshot")
    parser.add_argument("--output", type=Path, help="Optional JSON result file")
    args = parser.parse_args()
    try:
        with sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True) as db:
            db.row_factory = sqlite3.Row
            summary = verify(db)
        output = json.dumps(summary, ensure_ascii=False, indent=2)
        if args.output:
            args.output.write_text(output + "\n", encoding="utf-8")
        print(output)
    except (AssertionError, sqlite3.Error, OSError, ValueError, TypeError) as error:
        # Validation messages contain fixed fixture identifiers and aggregate facts, not raw ledger text.
        message = str(error) if isinstance(error, AssertionError) else "Database/schema could not be read or result could not be written"
        print(json.dumps({"verified": False, "error": message}, ensure_ascii=False), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
