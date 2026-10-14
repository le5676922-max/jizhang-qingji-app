package com.example.jizhang.data

import android.database.sqlite.SQLiteDatabase

/** Additive migrations; old financial fields and seen-event tombstones remain untouched. */
internal object RecognitionSchema {
    fun install(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE source_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_fingerprint TEXT NOT NULL UNIQUE,
            provider TEXT NOT NULL CHECK(provider IN ('MANUAL','WECHAT','ALIPAY')),
            evidence TEXT NOT NULL,
            amount_cents INTEGER NOT NULL CHECK(amount_cents BETWEEN 1 AND 99999999999),
            direction TEXT NOT NULL,
            business_type TEXT NOT NULL,
            merchant TEXT NOT NULL DEFAULT '',
            occurred_at INTEGER NOT NULL,
            observed_at INTEGER NOT NULL,
            category_suggestion TEXT NOT NULL,
            completion TEXT NOT NULL,
            has_stable_identity INTEGER NOT NULL,
            user_initiated INTEGER NOT NULL DEFAULT 0,
            rule_version TEXT NOT NULL,
            amount_options TEXT NOT NULL DEFAULT '[]',
            order_hash TEXT,
            order_namespace TEXT,
            original_order_hash TEXT,
            original_order_namespace TEXT,
            decision TEXT NOT NULL CHECK(decision IN ('AUTO','REVIEW','IGNORE')),
            reasons TEXT NOT NULL DEFAULT '[]',
            linked_entry_id INTEGER REFERENCES entries(id),
            suggested_entry_id INTEGER REFERENCES entries(id),
            related_entry_id INTEGER REFERENCES entries(id),
            association TEXT NOT NULL DEFAULT 'NONE'
        )""".trimIndent())
        db.execSQL("CREATE INDEX source_events_order ON source_events(provider,order_namespace,order_hash)")
        db.execSQL("CREATE INDEX source_events_entry ON source_events(linked_entry_id)")
        db.execSQL("CREATE INDEX source_events_review ON source_events(decision,observed_at DESC)")
        db.execSQL("CREATE TABLE event_aliases (fingerprint TEXT PRIMARY KEY, event_id INTEGER NOT NULL REFERENCES source_events(id))")
        db.execSQL("""CREATE TABLE merchant_rules (
            provider TEXT NOT NULL, merchant_key TEXT NOT NULL, type TEXT NOT NULL,
            category TEXT NOT NULL, updated_at INTEGER NOT NULL,
            PRIMARY KEY(provider,merchant_key,type)
        )""".trimIndent())
        db.execSQL("""CREATE TABLE link_actions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            event_id INTEGER NOT NULL REFERENCES source_events(id),
            from_entry_id INTEGER REFERENCES entries(id),
            to_entry_id INTEGER REFERENCES entries(id),
            action TEXT NOT NULL, occurred_at INTEGER NOT NULL
        )""".trimIndent())
        db.execSQL("""CREATE TABLE recognition_diagnostics (
            channel TEXT PRIMARY KEY, last_success_at INTEGER NOT NULL DEFAULT 0,
            last_failure_at INTEGER NOT NULL DEFAULT 0, last_failure_reason TEXT NOT NULL DEFAULT '',
            last_duration_ms INTEGER NOT NULL DEFAULT 0
        )""".trimIndent())
    }

    fun preserveLegacyEvents(db: SQLiteDatabase) {
        // Original provider IDs were never saved. Do not infer them from editable notes.
        db.execSQL("""INSERT INTO source_events (
            event_fingerprint,provider,evidence,amount_cents,direction,business_type,merchant,
            occurred_at,observed_at,category_suggestion,completion,has_stable_identity,
            rule_version,decision,reasons,linked_entry_id,association
        ) SELECT fingerprint,source,'LEGACY',amount_cents,
            CASE type WHEN 'EXPENSE' THEN 'OUTGOING' ELSE 'INCOMING' END,
            business_type,merchant,occurred_at,occurred_at,category,'COMPLETED',1,
            'legacy/1',CASE needs_review WHEN 1 THEN 'REVIEW' ELSE 'AUTO' END,
            '["旧版记录原样保留，未根据备注推断订单身份或资金用途"]',id,'LEGACY'
          FROM entries WHERE fingerprint IS NOT NULL""".trimIndent())
        db.execSQL("INSERT INTO event_aliases(fingerprint,event_id) SELECT event_fingerprint,id FROM source_events")
    }
}
