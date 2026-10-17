package com.mindai.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class AppDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "mindai.db";
    private static final int VERSION = 5;
    private static final int MAX_NOTIFICATION_PAGE_SIZE = 1000;
    private static final int MAX_ENTITY_PAGE_SIZE = 500;
    private static final int MAX_NOTIFICATION_APPS = 200;
    private static final String[] NOTIFICATION_COLUMNS = {
            "id", "source_key", "package_name", "app_name", "title", "content",
            "posted_at", "captured_at", "had_content_intent", "system_removed_at", "system_user_id",
            "summary_state", "sensitivity"
    };
    private static final String[] RECORD_COLUMNS = {
            "id", "title", "content", "category", "importance", "event_at", "created_at", "updated_at",
            "source_summary_id", "source_notification_ids", "status", "generated_ai", "user_edited", "deleted_at"
    };
    private static final String[] TODO_COLUMNS = {
            "id", "title", "description", "due_at", "priority", "status", "confidence",
            "source_summary_id", "source_notification_ids", "generated_ai", "user_edited",
            "created_at", "updated_at", "deleted_at"
    };
    private static final String[] MEMORY_COLUMNS = {
            "id", "title", "text", "category", "importance", "confidence", "stability", "sensitivity", "status",
            "source_summary_id", "source_notification_ids", "generated_ai", "user_edited",
            "created_at", "updated_at", "deleted_at"
    };
    private static final String[] PROFILE_COLUMNS = {
            "id", "section", "title", "content", "confidence", "stability", "status",
            "source_summary_id", "source_memory_ids", "source_notification_ids", "generated_ai", "user_edited",
            "last_seen_at", "created_at", "updated_at", "deleted_at"
    };
    private final CryptoManager crypto;

    public static final class MdImportResult {
        public int memories;
        public int profileFacts;
    }

    private static final class ImportedProfileLine {
        String section = "";
        String title = "";
        String content = "";
        String category = "fact";
    }

    AppDatabase(Context context, CryptoManager crypto) {
        super(context, DB_NAME, null, VERSION);
        this.crypto = crypto;
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createV1Tables(db);
        createV2Tables(db);
        createV3Tables(db);
        createV4Indexes(db);
        createV5NotificationMetadata(db);
    }

    private void createV1Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE notifications (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "source_key TEXT NOT NULL, package_name TEXT NOT NULL," +
                "app_name TEXT, title TEXT, content TEXT," +
                "posted_at INTEGER NOT NULL, captured_at INTEGER NOT NULL," +
                "content_hash TEXT NOT NULL, version INTEGER DEFAULT 1," +
                "summary_state TEXT DEFAULT 'unsummarized'," +
                "sensitivity TEXT DEFAULT 'normal', removed INTEGER DEFAULT 0," +
                "UNIQUE(source_key, content_hash) ON CONFLICT IGNORE)");
        db.execSQL("CREATE INDEX idx_notifications_posted ON notifications(posted_at DESC)");
        db.execSQL("CREATE INDEX idx_notifications_state ON notifications(summary_state)");

        db.execSQL("CREATE TABLE summary_sessions (" +
                "id TEXT PRIMARY KEY, created_at INTEGER, completed_at INTEGER," +
                "range_start INTEGER, range_end INTEGER, notification_count INTEGER," +
                "overview TEXT, key_points TEXT, status TEXT, error TEXT," +
                "model_info TEXT, notification_ids TEXT)");
        db.execSQL("CREATE INDEX idx_sessions_created ON summary_sessions(created_at DESC)");

        db.execSQL("CREATE TABLE important_records (" +
                "id TEXT PRIMARY KEY, title TEXT, content TEXT, category TEXT," +
                "importance REAL, event_at INTEGER, created_at INTEGER, updated_at INTEGER," +
                "source_summary_id TEXT, source_notification_ids TEXT," +
                "status TEXT, generated_ai INTEGER, user_edited INTEGER, deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_records_updated ON important_records(updated_at DESC)");
        db.execSQL("CREATE INDEX idx_records_status ON important_records(status, deleted_at)");
    }

    private void createV2Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS todos (" +
                "id TEXT PRIMARY KEY, title TEXT, description TEXT, due_at INTEGER," +
                "priority TEXT, status TEXT, confidence REAL," +
                "source_summary_id TEXT, source_notification_ids TEXT," +
                "generated_ai INTEGER, user_edited INTEGER," +
                "created_at INTEGER, updated_at INTEGER, deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_status ON todos(status, deleted_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_due ON todos(due_at)");

        db.execSQL("CREATE TABLE IF NOT EXISTS memories (" +
                "id TEXT PRIMARY KEY, title TEXT, text TEXT, category TEXT," +
                "importance REAL, confidence REAL, stability TEXT, sensitivity TEXT, status TEXT," +
                "source_summary_id TEXT, source_notification_ids TEXT," +
                "generated_ai INTEGER, user_edited INTEGER," +
                "created_at INTEGER, updated_at INTEGER, deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_status ON memories(status, deleted_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_updated ON memories(updated_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS profile_facts (" +
                "id TEXT PRIMARY KEY, section TEXT, title TEXT, content TEXT," +
                "confidence REAL, stability TEXT, status TEXT," +
                "source_summary_id TEXT, source_memory_ids TEXT, source_notification_ids TEXT," +
                "generated_ai INTEGER, user_edited INTEGER, last_seen_at INTEGER," +
                "created_at INTEGER, updated_at INTEGER, deleted_at INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_section ON profile_facts(section, status, deleted_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_updated ON profile_facts(updated_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS need_to_know (" +
                "id TEXT PRIMARY KEY, title TEXT, detail TEXT, reason TEXT, urgency TEXT," +
                "suggested_action TEXT, source_summary_id TEXT, source_notification_ids TEXT," +
                "related_todo_ids TEXT, related_memory_ids TEXT, related_profile_fact_ids TEXT," +
                "created_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_need_to_know_created ON need_to_know(created_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_need_to_know_summary ON need_to_know(source_summary_id)");
    }

    private void createV3Tables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS assistant_brief (" +
                "id TEXT PRIMARY KEY, type TEXT, title TEXT, detail TEXT, reason TEXT," +
                "suggested_action TEXT, urgency TEXT, impact TEXT, rank_score REAL, confidence REAL," +
                "valid_from INTEGER, valid_until INTEGER, status TEXT, snoozed_until INTEGER," +
                "source_summary_id TEXT, source_notification_ids TEXT, related_todo_ids TEXT," +
                "related_calendar_keys TEXT, related_memory_ids TEXT, related_profile_fact_ids TEXT," +
                "feedback TEXT, origin TEXT, created_at INTEGER, updated_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_brief_status_rank ON assistant_brief(status, rank_score DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_brief_updated ON assistant_brief(updated_at DESC)");

        db.execSQL("CREATE TABLE IF NOT EXISTS conversation_messages (" +
                "id TEXT PRIMARY KEY, role TEXT, content TEXT, action_type TEXT," +
                "action_object_id TEXT, action_status TEXT, created_at INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_conversation_created ON conversation_messages(created_at ASC)");
    }

    private void createV4Indexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_active_posted " +
                "ON notifications(removed, posted_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_state_posted " +
                "ON notifications(summary_state, removed, posted_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_package_posted " +
                "ON notifications(package_name, removed, posted_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_status_updated " +
                "ON important_records(status, deleted_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_summary_updated " +
                "ON important_records(source_summary_id, deleted_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_status_due_updated " +
                "ON todos(status, deleted_at, (CASE WHEN due_at=0 THEN 1 ELSE 0 END), due_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_todos_summary_updated " +
                "ON todos(source_summary_id, deleted_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_status_updated " +
                "ON memories(status, deleted_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_summary_updated " +
                "ON memories(source_summary_id, deleted_at, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_status_section_updated " +
                "ON profile_facts(status, deleted_at, section, updated_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_profile_summary_updated " +
                "ON profile_facts(source_summary_id, deleted_at, updated_at DESC)");
    }

    private void createV5NotificationMetadata(SQLiteDatabase db) {
        addColumnIfMissing(db, "notifications", "had_content_intent",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "notifications", "system_removed_at",
                "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "notifications", "system_user_id",
                "INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_notifications_system_key " +
                "ON notifications(source_key, posted_at, system_removed_at)");
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        boolean present = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equals(c.getString(nameIndex))) { present = true; break; }
            }
        }
        if (!present) db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createV2Tables(db);
        if (oldVersion < 3) createV3Tables(db);
        if (oldVersion < 4) createV4Indexes(db);
        if (oldVersion < 5) createV5NotificationMetadata(db);
    }

    public synchronized long saveNotification(String sourceKey, String packageName, String appName,
                                               String title, String content, long postedAt,
                                               String sensitivity) {
        return saveNotification(sourceKey, packageName, appName, title, content, postedAt,
                sensitivity, false, Math.max(0, android.os.Process.myUid() / 100_000));
    }

    public synchronized long saveNotification(String sourceKey, String packageName, String appName,
                                               String title, String content, long postedAt,
                                               String sensitivity, boolean hadContentIntent,
                                               int systemUserId) {
        if (sourceKey == null || sourceKey.isEmpty()) sourceKey = packageName + ":" + postedAt;
        String normalizedTitle = title == null ? "" : title.trim();
        String normalizedContent = content == null ? "" : content.trim();
        String hash = sha256(packageName + "|" + normalizedTitle + "|" + normalizedContent);
        SQLiteDatabase db = getWritableDatabase();
        int version = 1;
        try (Cursor c = db.rawQuery("SELECT MAX(version) FROM notifications WHERE source_key=?", new String[]{sourceKey})) {
            if (c.moveToFirst()) version = Math.max(1, c.getInt(0) + 1);
        }
        ContentValues values = new ContentValues();
        values.put("source_key", sourceKey);
        values.put("package_name", packageName == null ? "" : packageName);
        values.put("app_name", crypto.encrypt(appName));
        values.put("title", crypto.encrypt(normalizedTitle));
        values.put("content", crypto.encrypt(normalizedContent));
        values.put("posted_at", postedAt);
        values.put("captured_at", System.currentTimeMillis());
        values.put("content_hash", hash);
        values.put("version", version);
        values.put("had_content_intent", hadContentIntent ? 1 : 0);
        values.put("system_removed_at", 0);
        values.put("system_user_id", systemUserId);
        values.put("summary_state", "unsummarized");
        values.put("sensitivity", sensitivity == null ? "normal" : sensitivity);
        long id = db.insertWithOnConflict("notifications", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (id < 0) {
            ContentValues metadata = new ContentValues();
            metadata.put("had_content_intent", hadContentIntent ? 1 : 0);
            metadata.put("system_removed_at", 0);
            metadata.put("system_user_id", systemUserId);
            metadata.put("captured_at", System.currentTimeMillis());
            db.update("notifications", metadata, "source_key=? AND content_hash=?",
                    new String[]{sourceKey, hash});
            try (Cursor c = db.query("notifications", new String[]{"id"},
                    "source_key=? AND content_hash=?", new String[]{sourceKey, hash},
                    null, null, "id DESC", "1")) {
                if (c.moveToFirst()) id = c.getLong(0);
            }
        }
        return id;
    }

    public synchronized List<Models.NotificationItem> notifications(long dayStart, long dayEnd,
                                                                     String query, String state,
                                                                     String packageFilter) {
        return notifications(dayStart, dayEnd, query, state, packageFilter, 1000);
    }

    public synchronized List<Models.NotificationItem> notifications(long dayStart, long dayEnd,
                                                                     String query, String state,
                                                                     String packageFilter,
                                                                     int limit) {
        return notifications(dayStart, dayEnd, query, state, packageFilter, 0, limit);
    }

    public synchronized List<Models.NotificationItem> notifications(long dayStart, long dayEnd,
                                                                     String query, String state,
                                                                     String packageFilter,
                                                                     int offset, int limit) {
        List<Models.NotificationItem> result = new ArrayList<>();
        StringBuilder where = new StringBuilder("posted_at>=? AND posted_at<? AND removed=0");
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(dayStart));
        args.add(String.valueOf(dayEnd));
        if (state != null && !state.equals("all")) {
            where.append(" AND summary_state=?");
            args.add(state);
        }
        if (packageFilter != null && !packageFilter.isEmpty()) {
            where.append(" AND package_name=?");
            args.add(packageFilter);
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = safePageSize(limit, MAX_NOTIFICATION_PAGE_SIZE);
        int skipped = 0;
        try (Cursor c = getReadableDatabase().query("notifications", NOTIFICATION_COLUMNS, where.toString(),
                args.toArray(new String[0]), null, null, "posted_at DESC, id DESC",
                normalizedQuery.isEmpty() ? pageClause(offset, safeLimit, MAX_NOTIFICATION_PAGE_SIZE) : null)) {
            while (c.moveToNext()) {
                Models.NotificationItem item = notificationFrom(c);
                if (normalizedQuery.isEmpty() || searchable(item).contains(normalizedQuery)) {
                    if (!normalizedQuery.isEmpty() && skipped++ < Math.max(0, offset)) continue;
                    result.add(item);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public synchronized List<Models.NotificationItem> notificationAppsForDay(long dayStart, long dayEnd) {
        List<Models.NotificationItem> result = new ArrayList<>();
        if (dayEnd <= dayStart) return result;
        String sql = "SELECT n.id, n.package_name, n.app_name, n.posted_at " +
                "FROM notifications n INNER JOIN (" +
                "SELECT package_name, MAX(id) latest_id FROM notifications " +
                "WHERE posted_at>=? AND posted_at<? AND removed=0 GROUP BY package_name" +
                ") latest ON latest.latest_id=n.id " +
                "ORDER BY n.package_name COLLATE NOCASE ASC LIMIT ?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{
                String.valueOf(dayStart), String.valueOf(dayEnd), String.valueOf(MAX_NOTIFICATION_APPS)})) {
            while (c.moveToNext()) {
                Models.NotificationItem item = new Models.NotificationItem();
                item.id = c.getLong(0);
                item.packageName = c.getString(1) == null ? "" : c.getString(1);
                item.appName = crypto.decrypt(c.getString(2));
                item.postedAt = c.getLong(3);
                result.add(item);
            }
        }
        return result;
    }

    public synchronized Models.NotificationItem notification(long id) {
        try (Cursor c = getReadableDatabase().query("notifications", null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            return c.moveToFirst() ? notificationFrom(c) : null;
        }
    }

    public synchronized List<Models.NotificationItem> notificationVersions(String sourceKey) {
        return notificationVersions(sourceKey, 50);
    }

    public synchronized List<Models.NotificationItem> notificationVersions(String sourceKey, int limit) {
        List<Models.NotificationItem> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("notifications", null, "source_key=?",
                new String[]{sourceKey}, null, null, "version DESC",
                String.valueOf(safePageSize(limit, 100)))) {
            while (c.moveToNext()) result.add(notificationFrom(c));
        }
        return result;
    }

    public synchronized List<Models.NotificationItem> notificationsByIds(String commaSeparatedIds, int limit) {
        List<Models.NotificationItem> result = new ArrayList<>();
        if (commaSeparatedIds == null || commaSeparatedIds.trim().isEmpty() || limit <= 0) return result;
        Set<Long> seen = new HashSet<>();
        for (String raw : commaSeparatedIds.split(",")) {
            if (result.size() >= Math.min(limit, 50)) break;
            try {
                long id = Long.parseLong(raw.trim());
                if (id <= 0 || !seen.add(id)) continue;
                Models.NotificationItem item = notification(id);
                if (item != null) result.add(item);
            } catch (NumberFormatException ignored) {}
        }
        return result;
    }

    public synchronized List<Models.NotificationItem> unsummarized(long start, long end, String packageFilter) {
        String where = "summary_state='unsummarized' AND removed=0 AND posted_at>=? AND posted_at<?";
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(start));
        args.add(String.valueOf(end));
        if (packageFilter != null && !packageFilter.isEmpty()) {
            where += " AND package_name=?";
            args.add(packageFilter);
        }
        List<Models.NotificationItem> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("notifications", null, where,
                args.toArray(new String[0]), null, null, "posted_at ASC", "300")) {
            while (c.moveToNext()) result.add(notificationFrom(c));
        }
        return result;
    }

    public synchronized int countToday() {
        long[] range = todayRange();
        return count("posted_at>=? AND posted_at<? AND removed=0", new String[]{String.valueOf(range[0]), String.valueOf(range[1])});
    }

    public synchronized int countUnsummarized() {
        return count("summary_state='unsummarized' AND removed=0", null);
    }

    public synchronized int countPendingRecords() {
        return countTable("important_records", "status='pending' AND deleted_at=0", null);
    }

    public synchronized int countRecords() {
        return countTable("important_records", "deleted_at=0", null);
    }

    public synchronized long lastCapturedAt() {
        return scalarLong("SELECT MAX(captured_at) FROM notifications");
    }

    public synchronized long lastSummaryAt() {
        return scalarLong("SELECT MAX(completed_at) FROM summary_sessions WHERE status='success'");
    }

    public synchronized void deleteNotification(long id) {
        ContentValues values = new ContentValues();
        values.put("removed", 1);
        getWritableDatabase().update("notifications", values, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void markSystemNotificationRemoved(String sourceKey, long postedAt, long removedAt) {
        if (sourceKey == null || sourceKey.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("system_removed_at", removedAt > 0 ? removedAt : System.currentTimeMillis());
        getWritableDatabase().update("notifications", values, "source_key=? AND posted_at=?",
                new String[]{sourceKey, String.valueOf(postedAt)});
    }

    public synchronized Models.SummarySession beginSession(List<Models.NotificationItem> items) {
        Models.SummarySession s = new Models.SummarySession();
        s.id = UUID.randomUUID().toString();
        s.createdAt = System.currentTimeMillis();
        s.status = "processing";
        s.notificationCount = items.size();
        s.rangeStart = items.isEmpty() ? 0 : items.get(0).postedAt;
        s.rangeEnd = items.isEmpty() ? 0 : items.get(items.size() - 1).postedAt;
        StringBuilder ids = new StringBuilder();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Models.NotificationItem item : items) {
                if (ids.length() > 0) ids.append(',');
                ids.append(item.id);
                ContentValues state = new ContentValues();
                state.put("summary_state", "processing");
                db.update("notifications", state, "id=? AND summary_state='unsummarized'",
                        new String[]{String.valueOf(item.id)});
            }
            s.notificationIds = ids.toString();
            insertSession(db, s);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return s;
    }

    public synchronized void completeSession(Models.SummarySession session, Models.SummaryOutput output) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray points = new JSONArray();
            for (Models.KeyPoint point : output.keyPoints) {
                JSONObject o = new JSONObject();
                o.put("title", point.title);
                o.put("detail", point.detail);
                o.put("source_ids", point.sourceIds);
                points.put(o);
            }
            session.completedAt = System.currentTimeMillis();
            session.status = "success";
            session.overview = output.overview;
            session.keyPointsJson = points.toString();
            session.modelInfo = output.actualModel;
            ContentValues values = sessionValues(session);
            db.update("summary_sessions", values, "id=?", new String[]{session.id});

            for (Models.ImportantRecord record : output.records) {
                if (record.importance >= 0.75) insertRecord(db, record, session.id);
            }
            for (Models.TodoItem todo : output.todos) {
                if (todo.confidence >= 0.45 && !todo.title.trim().isEmpty()) insertTodo(db, todo, session.id);
            }
            List<String> memoryIds = new ArrayList<>();
            for (Models.MemoryItem memory : output.memories) {
                String id = upsertMemory(db, memory, session.id);
                if (!id.isEmpty()) memoryIds.add(id);
            }
            for (Models.ProfileFact fact : output.profileUpdates) {
                if (fact.sourceMemoryIds == null || fact.sourceMemoryIds.isEmpty()) fact.sourceMemoryIds = joinStrings(memoryIds);
                upsertProfileFact(db, fact, session.id);
            }
            if (output.needToKnow != null && !"none".equals(output.needToKnow.urgency)) {
                insertNeedToKnow(db, output.needToKnow, session.id);
                Models.AssistantBriefItem brief = new Models.AssistantBriefItem();
                brief.id = "ai:" + output.needToKnow.id;
                brief.type = "info";
                brief.title = output.needToKnow.title;
                brief.detail = output.needToKnow.detail;
                brief.reason = output.needToKnow.reason;
                brief.suggestedAction = output.needToKnow.suggestedAction;
                brief.urgency = output.needToKnow.urgency;
                brief.impact = "critical".equals(brief.urgency) || "high".equals(brief.urgency) ? "high" : "medium";
                brief.rankScore = urgencyScore(brief.urgency) + 20;
                brief.confidence = 0.8;
                brief.status = "active";
                brief.sourceSummaryId = session.id;
                brief.sourceNotificationIds = output.needToKnow.sourceNotificationIds;
                brief.relatedTodoIds = output.needToKnow.relatedTodoIds;
                brief.relatedMemoryIds = output.needToKnow.relatedMemoryIds;
                brief.relatedProfileFactIds = output.needToKnow.relatedProfileFactIds;
                brief.origin = "ai";
                brief.createdAt = System.currentTimeMillis();
                brief.updatedAt = brief.createdAt;
                db.insertWithOnConflict("assistant_brief", null, briefValues(brief), SQLiteDatabase.CONFLICT_REPLACE);
            }

            if (!session.notificationIds.isEmpty()) {
                db.execSQL("UPDATE notifications SET summary_state='summarized' WHERE id IN (" + safeIdList(session.notificationIds) + ")");
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void failSession(Models.SummarySession session, String error) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            session.status = "failed";
            session.completedAt = System.currentTimeMillis();
            session.error = error;
            db.update("summary_sessions", sessionValues(session), "id=?", new String[]{session.id});
            if (!session.notificationIds.isEmpty()) {
                db.execSQL("UPDATE notifications SET summary_state='unsummarized' WHERE id IN (" + safeIdList(session.notificationIds) + ")");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized void recoverInterruptedSessions() {
        SQLiteDatabase db = getWritableDatabase();
        List<String> sessions = new ArrayList<>();
        List<String> idLists = new ArrayList<>();
        try (Cursor c = db.query("summary_sessions", new String[]{"id", "notification_ids"},
                "status='processing'", null, null, null, null)) {
            while (c.moveToNext()) { sessions.add(c.getString(0)); idLists.add(c.getString(1)); }
        }
        if (sessions.isEmpty()) return;
        db.beginTransaction();
        try {
            for (int i = 0; i < sessions.size(); i++) {
                String ids = safeIdList(idLists.get(i));
                if (!ids.isEmpty()) db.execSQL("UPDATE notifications SET summary_state='unsummarized' WHERE id IN (" + ids + ")");
                ContentValues values = new ContentValues();
                values.put("status", "failed"); values.put("completed_at", System.currentTimeMillis());
                values.put("error", crypto.encrypt("上次处理被系统中断，可安全重试"));
                db.update("summary_sessions", values, "id=?", new String[]{sessions.get(i)});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized List<Models.SummarySession> sessions() {
        List<Models.SummarySession> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("summary_sessions", null, null, null,
                null, null, "created_at DESC", "100")) {
            while (c.moveToNext()) result.add(sessionFrom(c));
        }
        return result;
    }

    public synchronized Models.SummarySession latestSuccessfulSession() {
        try (Cursor c = getReadableDatabase().query("summary_sessions", null, "status='success'",
                null, null, null, "completed_at DESC, created_at DESC", "1")) {
            return c.moveToFirst() ? sessionFrom(c) : null;
        }
    }

    public synchronized List<Models.SummarySession> successfulSessionsBetween(long start, long end) {
        List<Models.SummarySession> result = new ArrayList<>();
        if (end <= start) return result;
        try (Cursor c = getReadableDatabase().query("summary_sessions", null,
                "status='success' AND completed_at>=? AND completed_at<?",
                new String[]{String.valueOf(start), String.valueOf(end)}, null, null,
                "completed_at DESC, created_at DESC")) {
            while (c.moveToNext()) result.add(sessionFrom(c));
        }
        return result;
    }

    public synchronized List<Models.TodoItem> todosForSuccessfulSessions(Set<String> sessionIds) {
        List<Models.TodoItem> result = new ArrayList<>();
        String[] ids = sessionArgs(sessionIds);
        if (ids.length == 0) return result;
        try (Cursor c = getReadableDatabase().query("todos", TODO_COLUMNS,
                "deleted_at=0 AND source_summary_id IN (" + placeholders(ids.length) + ")",
                ids, null, null, "updated_at DESC, id ASC")) {
            while (c.moveToNext()) result.add(todoFrom(c));
        }
        return result;
    }

    public synchronized List<Models.MemoryItem> memoriesForSuccessfulSessions(Set<String> sessionIds) {
        List<Models.MemoryItem> result = new ArrayList<>();
        String[] ids = sessionArgs(sessionIds);
        if (ids.length == 0) return result;
        try (Cursor c = getReadableDatabase().query("memories", MEMORY_COLUMNS,
                "deleted_at=0 AND source_summary_id IN (" + placeholders(ids.length) + ")",
                ids, null, null, "updated_at DESC, id ASC")) {
            while (c.moveToNext()) result.add(memoryFrom(c));
        }
        return result;
    }

    public synchronized List<Models.ProfileFact> profileFactsForSuccessfulSessions(Set<String> sessionIds) {
        List<Models.ProfileFact> result = new ArrayList<>();
        String[] ids = sessionArgs(sessionIds);
        if (ids.length == 0) return result;
        try (Cursor c = getReadableDatabase().query("profile_facts", PROFILE_COLUMNS,
                "deleted_at=0 AND source_summary_id IN (" + placeholders(ids.length) + ")",
                ids, null, null, "updated_at DESC, id ASC")) {
            while (c.moveToNext()) result.add(profileFactFrom(c));
        }
        return result;
    }

    public synchronized Models.SummarySession session(String id) {
        try (Cursor c = getReadableDatabase().query("summary_sessions", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? sessionFrom(c) : null;
        }
    }

    private static String[] sessionArgs(Set<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) return new String[0];
        List<String> safe = new ArrayList<>();
        for (String id : sessionIds) if (id != null && !id.trim().isEmpty()) safe.add(id);
        return safe.toArray(new String[0]);
    }

    private static String placeholders(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append(',');
            result.append('?');
        }
        return result.toString();
    }

    public synchronized List<Models.ImportantRecord> records(String status, String query) {
        return records(status, query, 500);
    }

    public synchronized List<Models.ImportantRecord> records(String status, String query, int limit) {
        return records(status, query, 0, limit);
    }

    public synchronized List<Models.ImportantRecord> records(String status, String query, int offset, int limit) {
        List<Models.ImportantRecord> result = new ArrayList<>();
        String where = "deleted_at=0";
        List<String> args = new ArrayList<>();
        if (status != null && !status.equals("all")) {
            where += " AND status=?";
            args.add(status);
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = safePageSize(limit, MAX_ENTITY_PAGE_SIZE);
        int skipped = 0;
        try (Cursor c = getReadableDatabase().query("important_records", RECORD_COLUMNS, where,
                args.toArray(new String[0]), null, null, "updated_at DESC, id ASC",
                normalizedQuery.isEmpty() ? pageClause(offset, safeLimit, MAX_ENTITY_PAGE_SIZE) : null)) {
            while (c.moveToNext()) {
                Models.ImportantRecord record = recordFrom(c);
                if (normalizedQuery.isEmpty() ||
                        (record.title + " " + record.content + " " + record.category)
                                .toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    if (!normalizedQuery.isEmpty() && skipped++ < Math.max(0, offset)) continue;
                    result.add(record);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public synchronized List<Models.ImportantRecord> recordsForSession(String sessionId, int limit) {
        List<Models.ImportantRecord> result = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return result;
        try (Cursor c = getReadableDatabase().query("important_records", RECORD_COLUMNS,
                "source_summary_id=? AND deleted_at=0", new String[]{sessionId}, null, null,
                "updated_at DESC, id ASC", pageClause(0, limit, MAX_ENTITY_PAGE_SIZE))) {
            while (c.moveToNext()) result.add(recordFrom(c));
        }
        return result;
    }

    public synchronized List<Models.ImportantRecord> trashedRecords() {
        List<Models.ImportantRecord> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query("important_records", null, "deleted_at>0", null,
                null, null, "deleted_at DESC", "100")) {
            while (c.moveToNext()) result.add(recordFrom(c));
        }
        return result;
    }

    public synchronized List<Models.ImportantRecord> recordsUsingNotification(long notificationId) {
        List<Models.ImportantRecord> result = new ArrayList<>();
        String needle = String.valueOf(notificationId);
        for (Models.ImportantRecord record : records("all", "")) {
            for (String id : record.sourceNotificationIds.split(",")) if (needle.equals(id.trim())) { result.add(record); break; }
        }
        return result;
    }

    public synchronized void deleteRecordsUsingNotification(long notificationId) {
        for (Models.ImportantRecord record : recordsUsingNotification(notificationId)) deleteRecord(record.id);
    }

    public synchronized Models.ImportantRecord record(String id) {
        try (Cursor c = getReadableDatabase().query("important_records", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? recordFrom(c) : null;
        }
    }

    public synchronized void updateRecord(Models.ImportantRecord record) {
        record.updatedAt = System.currentTimeMillis();
        record.userEdited = true;
        ContentValues v = recordValues(record);
        getWritableDatabase().update("important_records", v, "id=?", new String[]{record.id});
    }

    public synchronized void setRecordStatus(String id, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("important_records", v, "id=?", new String[]{id});
    }

    public synchronized void deleteRecord(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        getWritableDatabase().update("important_records", v, "id=?", new String[]{id});
    }

    public synchronized void restoreRecord(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", 0);
        getWritableDatabase().update("important_records", v, "id=?", new String[]{id});
    }

    public synchronized String addManualRecord(String title, String content, String category) {
        Models.ImportantRecord record = new Models.ImportantRecord();
        record.id = UUID.randomUUID().toString();
        record.title = title;
        record.content = content;
        record.category = category;
        record.importance = 1.0;
        record.createdAt = System.currentTimeMillis();
        record.updatedAt = record.createdAt;
        record.status = "confirmed";
        record.generatedByAi = false;
        record.userEdited = true;
        getWritableDatabase().insertOrThrow("important_records", null, recordValues(record));
        return record.id;
    }

    public synchronized int countOpenTodos() {
        return countTable("todos", "status='open' AND deleted_at=0", null);
    }

    public synchronized int countDueTodayTodos() {
        long[] range = todayRange();
        return countTable("todos", "status='open' AND deleted_at=0 AND due_at>=? AND due_at<?",
                new String[]{String.valueOf(range[0]), String.valueOf(range[1])});
    }

    public synchronized int countMemories() {
        return countTable("memories", "deleted_at=0 AND status!='archived'", null);
    }

    public synchronized int countProfileFacts() {
        return countTable("profile_facts", "deleted_at=0 AND status!='archived'", null);
    }

    public synchronized List<Models.TodoItem> todos(String status, String query, int limit) {
        return todos(status, query, 0, limit);
    }

    public synchronized List<Models.TodoItem> todos(String status, String query, int offset, int limit) {
        List<Models.TodoItem> result = new ArrayList<>();
        String where = "deleted_at=0";
        List<String> args = new ArrayList<>();
        if (status != null && !status.equals("all")) {
            where += " AND status=?";
            args.add(status);
        }
        String order = "CASE WHEN due_at=0 THEN 1 ELSE 0 END, due_at ASC, updated_at DESC";
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = safePageSize(limit, MAX_ENTITY_PAGE_SIZE);
        int skipped = 0;
        try (Cursor c = getReadableDatabase().query("todos", TODO_COLUMNS, where, args.toArray(new String[0]),
                null, null, order + ", id ASC", normalizedQuery.isEmpty() ? pageClause(offset, safeLimit, MAX_ENTITY_PAGE_SIZE) : null)) {
            while (c.moveToNext()) {
                Models.TodoItem todo = todoFrom(c);
                if (normalizedQuery.isEmpty() ||
                        (todo.title + " " + todo.description + " " + todo.priority)
                                .toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    if (!normalizedQuery.isEmpty() && skipped++ < Math.max(0, offset)) continue;
                    result.add(todo);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public synchronized List<Models.TodoItem> todosDueBetween(long start, long end, String status, int limit) {
        List<Models.TodoItem> result = new ArrayList<>();
        if (end <= start) return result;
        String where = "deleted_at=0 AND due_at>=? AND due_at<?";
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(start));
        args.add(String.valueOf(end));
        if (status != null && !status.equals("all")) {
            where += " AND status=?";
            args.add(status);
        }
        try (Cursor c = getReadableDatabase().query("todos", TODO_COLUMNS, where,
                args.toArray(new String[0]), null, null, "due_at ASC, updated_at DESC, id ASC",
                pageClause(0, limit, MAX_ENTITY_PAGE_SIZE))) {
            while (c.moveToNext()) result.add(todoFrom(c));
        }
        return result;
    }

    public synchronized List<Models.TodoItem> todosForSession(String sessionId, int limit) {
        List<Models.TodoItem> result = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return result;
        try (Cursor c = getReadableDatabase().query("todos", TODO_COLUMNS,
                "source_summary_id=? AND deleted_at=0", new String[]{sessionId}, null, null,
                "updated_at DESC, id ASC", pageClause(0, limit, MAX_ENTITY_PAGE_SIZE))) {
            while (c.moveToNext()) result.add(todoFrom(c));
        }
        return result;
    }

    public synchronized Models.TodoItem todo(String id) {
        try (Cursor c = getReadableDatabase().query("todos", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? todoFrom(c) : null;
        }
    }

    public synchronized String addManualTodo(String title, String description, long dueAt, String priority) {
        Models.TodoItem todo = new Models.TodoItem();
        todo.id = UUID.randomUUID().toString();
        todo.title = title;
        todo.description = description;
        todo.dueAt = dueAt;
        todo.priority = priority == null || priority.isEmpty() ? "medium" : priority;
        todo.status = "open";
        todo.confidence = 1.0;
        todo.generatedByAi = false;
        todo.userEdited = true;
        todo.createdAt = System.currentTimeMillis();
        todo.updatedAt = todo.createdAt;
        getWritableDatabase().insertOrThrow("todos", null, todoValues(todo));
        return todo.id;
    }

    public synchronized void updateTodo(Models.TodoItem todo) {
        todo.updatedAt = System.currentTimeMillis();
        todo.userEdited = true;
        getWritableDatabase().update("todos", todoValues(todo), "id=?", new String[]{todo.id});
    }

    public synchronized void setTodoStatus(String id, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("todos", v, "id=?", new String[]{id});
    }

    public synchronized void deleteTodo(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("todos", v, "id=?", new String[]{id});
    }

    public synchronized List<Models.MemoryItem> memories(String status, String query, int limit) {
        return memories(status, query, 0, limit);
    }

    public synchronized List<Models.MemoryItem> memories(String status, String query, int offset, int limit) {
        List<Models.MemoryItem> result = new ArrayList<>();
        String where = "deleted_at=0";
        List<String> args = new ArrayList<>();
        if (status != null && !status.equals("all")) {
            where += " AND status=?";
            args.add(status);
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = safePageSize(limit, MAX_ENTITY_PAGE_SIZE);
        int skipped = 0;
        try (Cursor c = getReadableDatabase().query("memories", MEMORY_COLUMNS, where, args.toArray(new String[0]),
                null, null, "updated_at DESC, id ASC", normalizedQuery.isEmpty() ? pageClause(offset, safeLimit, MAX_ENTITY_PAGE_SIZE) : null)) {
            while (c.moveToNext()) {
                Models.MemoryItem memory = memoryFrom(c);
                if (normalizedQuery.isEmpty() ||
                        (memory.title + " " + memory.text + " " + memory.category)
                                .toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    if (!normalizedQuery.isEmpty() && skipped++ < Math.max(0, offset)) continue;
                    result.add(memory);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public synchronized List<Models.MemoryItem> memoriesForSession(String sessionId, int limit) {
        List<Models.MemoryItem> result = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return result;
        try (Cursor c = getReadableDatabase().query("memories", MEMORY_COLUMNS,
                "source_summary_id=? AND deleted_at=0", new String[]{sessionId}, null, null,
                "updated_at DESC, id ASC", pageClause(0, limit, MAX_ENTITY_PAGE_SIZE))) {
            while (c.moveToNext()) result.add(memoryFrom(c));
        }
        return result;
    }

    public synchronized Models.MemoryItem memory(String id) {
        try (Cursor c = getReadableDatabase().query("memories", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? memoryFrom(c) : null;
        }
    }

    public synchronized String addManualMemory(String title, String text, String category) {
        Models.MemoryItem memory = new Models.MemoryItem();
        memory.id = UUID.randomUUID().toString();
        memory.title = title;
        memory.text = text;
        memory.category = category == null || category.isEmpty() ? "other" : category;
        memory.importance = 1.0;
        memory.confidence = 1.0;
        memory.status = "active";
        memory.generatedByAi = false;
        memory.userEdited = true;
        memory.createdAt = System.currentTimeMillis();
        memory.updatedAt = memory.createdAt;
        getWritableDatabase().insertOrThrow("memories", null, memoryValues(memory));
        return memory.id;
    }

    public synchronized void updateMemory(Models.MemoryItem memory) {
        memory.updatedAt = System.currentTimeMillis();
        memory.userEdited = true;
        getWritableDatabase().update("memories", memoryValues(memory), "id=?", new String[]{memory.id});
    }

    public synchronized void setMemoryStatus(String id, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("memories", v, "id=?", new String[]{id});
    }

    public synchronized void deleteMemory(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("memories", v, "id=?", new String[]{id});
    }

    public synchronized List<Models.ProfileFact> profileFacts(String status, String query, int limit) {
        return profileFacts(status, query, 0, limit);
    }

    public synchronized List<Models.ProfileFact> profileFacts(String status, String query, int offset, int limit) {
        List<Models.ProfileFact> result = new ArrayList<>();
        String where = "deleted_at=0";
        List<String> args = new ArrayList<>();
        if (status != null && !status.equals("all")) {
            where += " AND status=?";
            args.add(status);
        }
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int safeLimit = safePageSize(limit, MAX_ENTITY_PAGE_SIZE);
        int skipped = 0;
        try (Cursor c = getReadableDatabase().query("profile_facts", PROFILE_COLUMNS, where, args.toArray(new String[0]),
                null, null, "section ASC, updated_at DESC, id ASC",
                normalizedQuery.isEmpty() ? pageClause(offset, safeLimit, MAX_ENTITY_PAGE_SIZE) : null)) {
            while (c.moveToNext()) {
                Models.ProfileFact fact = profileFactFrom(c);
                if (normalizedQuery.isEmpty() ||
                        (fact.section + " " + fact.title + " " + fact.content)
                                .toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    if (!normalizedQuery.isEmpty() && skipped++ < Math.max(0, offset)) continue;
                    result.add(fact);
                    if (result.size() >= safeLimit) break;
                }
            }
        }
        return result;
    }

    public synchronized List<Models.ProfileFact> profileFactsForSession(String sessionId, int limit) {
        List<Models.ProfileFact> result = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return result;
        try (Cursor c = getReadableDatabase().query("profile_facts", PROFILE_COLUMNS,
                "source_summary_id=? AND deleted_at=0", new String[]{sessionId}, null, null,
                "updated_at DESC, id ASC", pageClause(0, limit, MAX_ENTITY_PAGE_SIZE))) {
            while (c.moveToNext()) result.add(profileFactFrom(c));
        }
        return result;
    }

    public synchronized Models.ProfileFact profileFact(String id) {
        try (Cursor c = getReadableDatabase().query("profile_facts", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? profileFactFrom(c) : null;
        }
    }

    public synchronized String addManualProfileFact(String section, String title, String content) {
        Models.ProfileFact fact = new Models.ProfileFact();
        fact.id = UUID.randomUUID().toString();
        fact.section = section == null || section.isEmpty() ? "其他" : section;
        fact.title = title;
        fact.content = content;
        fact.confidence = 1.0;
        fact.status = "active";
        fact.generatedByAi = false;
        fact.userEdited = true;
        fact.lastSeenAt = System.currentTimeMillis();
        fact.createdAt = fact.lastSeenAt;
        fact.updatedAt = fact.lastSeenAt;
        getWritableDatabase().insertOrThrow("profile_facts", null, profileFactValues(fact));
        return fact.id;
    }

    public synchronized void updateProfileFact(Models.ProfileFact fact) {
        fact.updatedAt = System.currentTimeMillis();
        fact.userEdited = true;
        getWritableDatabase().update("profile_facts", profileFactValues(fact), "id=?", new String[]{fact.id});
    }

    public synchronized void setProfileFactStatus(String id, String status) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("profile_facts", v, "id=?", new String[]{id});
    }

    public synchronized void deleteProfileFact(String id) {
        ContentValues v = new ContentValues();
        v.put("deleted_at", System.currentTimeMillis());
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("profile_facts", v, "id=?", new String[]{id});
    }

    public synchronized Models.NeedToKnow latestNeedToKnow() {
        try (Cursor c = getReadableDatabase().query("need_to_know", null, null, null,
                null, null, "created_at DESC", "1")) {
            return c.moveToFirst() ? needToKnowFrom(c) : null;
        }
    }

    public synchronized Models.NeedToKnow needToKnowForSession(String sessionId) {
        try (Cursor c = getReadableDatabase().query("need_to_know", null, "source_summary_id=?",
                new String[]{sessionId}, null, null, "created_at DESC", "1")) {
            return c.moveToFirst() ? needToKnowFrom(c) : null;
        }
    }

    public synchronized List<Models.AssistantBriefItem> activeBriefItems(int limit) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues wake = new ContentValues();
        wake.put("status", "active");
        wake.put("updated_at", now);
        db.update("assistant_brief", wake, "status='snoozed' AND snoozed_until>0 AND snoozed_until<=?",
                new String[]{String.valueOf(now)});
        ContentValues expire = new ContentValues();
        expire.put("status", "expired");
        expire.put("updated_at", now);
        db.update("assistant_brief", expire, "status='active' AND valid_until>0 AND valid_until<?",
                new String[]{String.valueOf(now)});
        List<Models.AssistantBriefItem> result = new ArrayList<>();
        try (Cursor c = db.query("assistant_brief", null, "status='active'", null,
                null, null, "rank_score DESC, updated_at DESC", String.valueOf(Math.max(1, Math.min(limit, 50))))) {
            while (c.moveToNext()) result.add(briefFrom(c));
        }
        return result;
    }

    public synchronized Models.AssistantBriefItem briefItem(String id) {
        try (Cursor c = getReadableDatabase().query("assistant_brief", null, "id=?",
                new String[]{id}, null, null, null)) {
            return c.moveToFirst() ? briefFrom(c) : null;
        }
    }

    public synchronized void replaceDerivedBriefs(List<Models.AssistantBriefItem> items) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Set<String> incoming = new HashSet<>();
        if (items != null) for (Models.AssistantBriefItem item : items) if (item != null && !item.id.isEmpty()) incoming.add(item.id);
        db.beginTransaction();
        try {
            try (Cursor c = db.query("assistant_brief", new String[]{"id", "status"}, "origin='local'", null,
                    null, null, null)) {
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    if (incoming.contains(id)) continue;
                    ContentValues v = new ContentValues();
                    v.put("status", "expired");
                    v.put("updated_at", now);
                    db.update("assistant_brief", v, "id=? AND status NOT IN ('dismissed','resolved')", new String[]{id});
                }
            }
            if (items != null) for (Models.AssistantBriefItem item : items) {
                if (item == null || item.id.isEmpty() || item.title.trim().isEmpty()) continue;
                String preservedStatus = "active";
                long preservedSnooze = 0;
                String preservedFeedback = item.feedback == null ? "none" : item.feedback;
                long created = item.createdAt > 0 ? item.createdAt : now;
                try (Cursor c = db.query("assistant_brief", new String[]{"status", "snoozed_until", "feedback", "created_at"},
                        "id=?", new String[]{item.id}, null, null, null)) {
                    if (c.moveToFirst()) {
                        String status = c.getString(0);
                        long snooze = c.getLong(1);
                        if ("dismissed".equals(status)) preservedStatus = "dismissed";
                        else if ("snoozed".equals(status) && snooze > now) { preservedStatus = "snoozed"; preservedSnooze = snooze; }
                        preservedFeedback = c.getString(2);
                        created = c.getLong(3);
                    }
                }
                item.status = preservedStatus;
                item.snoozedUntil = preservedSnooze;
                item.feedback = preservedFeedback == null ? "none" : preservedFeedback;
                item.origin = "local";
                item.createdAt = created;
                item.updatedAt = now;
                db.insertWithOnConflict("assistant_brief", null, briefValues(item), SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void setBriefStatus(String id, String status, long snoozedUntil) {
        ContentValues v = new ContentValues();
        v.put("status", status);
        v.put("snoozed_until", snoozedUntil);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("assistant_brief", v, "id=?", new String[]{id});
    }

    public synchronized void setBriefFeedback(String id, String feedback) {
        ContentValues v = new ContentValues();
        v.put("feedback", feedback);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("assistant_brief", v, "id=?", new String[]{id});
    }

    public synchronized Models.ConversationMessage addConversationMessage(String role, String content,
                                                                           String actionType, String actionObjectId,
                                                                           String actionStatus) {
        Models.ConversationMessage message = new Models.ConversationMessage();
        message.id = UUID.randomUUID().toString();
        message.role = role == null ? "assistant" : role;
        message.content = content == null ? "" : content;
        message.actionType = actionType == null ? "" : actionType;
        message.actionObjectId = actionObjectId == null ? "" : actionObjectId;
        message.actionStatus = actionStatus == null ? "" : actionStatus;
        message.createdAt = System.currentTimeMillis();
        getWritableDatabase().insertOrThrow("conversation_messages", null, conversationValues(message));
        return message;
    }

    public synchronized List<Models.ConversationMessage> conversationMessages(int limit) {
        List<Models.ConversationMessage> result = new ArrayList<>();
        String sql = "SELECT * FROM (SELECT * FROM conversation_messages ORDER BY created_at DESC LIMIT ?) ORDER BY created_at ASC";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(Math.max(1, Math.min(limit, 200))) })) {
            while (c.moveToNext()) result.add(conversationFrom(c));
        }
        return result;
    }

    public synchronized void clearConversation() {
        getWritableDatabase().delete("conversation_messages", null, null);
    }

    public MdImportResult importProfileMarkdown(String markdown) {
        MdImportResult result = new MdImportResult();
        if (markdown == null || markdown.trim().isEmpty()) return result;
        List<ImportedProfileLine> parsed = parseImportedMarkdown(markdown);
        if (parsed.isEmpty()) return result;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Set<String> existingProfiles = existingProfileKeys(db);
            Set<String> existingMemories = existingMemoryKeys(db);
            for (ImportedProfileLine item : parsed) {
                String profileKey = normalize(item.section + " " + item.title);
                if (!profileKey.isEmpty() && existingProfiles.add(profileKey)) {
                    insertImportedProfileFactUnchecked(db, item.section, item.title, item.content);
                    result.profileFacts++;
                }
                String memoryKey = normalize(item.title + " " + item.content);
                if (!memoryKey.isEmpty() && existingMemories.add(memoryKey)) {
                    insertImportedMemoryUnchecked(db, item.title, item.content, item.category);
                    result.memories++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return result;
    }

    public synchronized String exportProfileMemoryMarkdown() {
        StringBuilder b = new StringBuilder();
        b.append("# MindAI 用户画像与记忆\n\n");
        b.append("> 导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date())).append("\n\n");
        b.append("## 用户画像\n\n");
        List<Models.ProfileFact> facts = profileFacts("active", "", 500);
        if (facts.isEmpty()) {
            b.append("_暂无画像_\n\n");
        } else {
            String section = "";
            for (Models.ProfileFact fact : facts) {
                if (!fact.section.equals(section)) {
                    section = fact.section.isEmpty() ? "其他" : fact.section;
                    b.append("### ").append(section).append("\n\n");
                }
                b.append("- **").append(fact.title.isEmpty() ? "未命名" : fact.title).append("**：").append(fact.content).append("\n");
            }
            b.append("\n");
        }
        b.append("## 记忆\n\n");
        List<Models.MemoryItem> memories = memories("active", "", 500);
        if (memories.isEmpty()) {
            b.append("_暂无记忆_\n");
        } else {
            for (Models.MemoryItem memory : memories) {
                b.append("- **").append(memory.title.isEmpty() ? firstSentence(memory.text) : memory.title).append("**");
                if (!memory.category.isEmpty()) b.append(" `").append(memory.category).append("`");
                b.append("：").append(memory.text).append("\n");
            }
        }
        return b.toString();
    }

    public synchronized void clearNotifications() { getWritableDatabase().delete("notifications", null, null); }
    public synchronized void clearAiResults() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("summary_sessions", null, null);
            db.delete("important_records", null, null);
            db.delete("todos", null, null);
            db.delete("memories", null, null);
            db.delete("profile_facts", null, null);
            db.delete("need_to_know", null, null);
            db.delete("assistant_brief", null, null);
            db.execSQL("UPDATE notifications SET summary_state='unsummarized'");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void clearEverything() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("important_records", null, null);
            db.delete("todos", null, null);
            db.delete("memories", null, null);
            db.delete("profile_facts", null, null);
            db.delete("need_to_know", null, null);
            db.delete("assistant_brief", null, null);
            db.delete("conversation_messages", null, null);
            db.delete("summary_sessions", null, null);
            db.delete("notifications", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void applyRetentionPolicy(int days) {
        if (days <= 0 || days >= 36500) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        getWritableDatabase().delete("notifications", "posted_at<?", new String[]{String.valueOf(cutoff)});
        long trashCutoff = System.currentTimeMillis() - 7 * 86_400_000L;
        getWritableDatabase().delete("important_records", "deleted_at>0 AND deleted_at<?", new String[]{String.valueOf(trashCutoff)});
        getWritableDatabase().delete("todos", "deleted_at>0 AND deleted_at<?", new String[]{String.valueOf(trashCutoff)});
        getWritableDatabase().delete("memories", "deleted_at>0 AND deleted_at<?", new String[]{String.valueOf(trashCutoff)});
        getWritableDatabase().delete("profile_facts", "deleted_at>0 AND deleted_at<?", new String[]{String.valueOf(trashCutoff)});
    }

    public int migrateLegacyEncryption(int maxRows) {
        int remaining = Math.max(0, maxRows);
        int migrated = 0;
        String[][] specs = {
                {"notifications", "app_name", "title", "content"},
                {"summary_sessions", "overview", "key_points", "error"},
                {"important_records", "title", "content"},
                {"todos", "title", "description"},
                {"memories", "title", "text"},
                {"profile_facts", "title", "content"},
                {"need_to_know", "title", "detail", "reason", "suggested_action"},
                {"assistant_brief", "title", "detail", "reason", "suggested_action"},
                {"conversation_messages", "content"}
        };
        for (String[] spec : specs) {
            if (remaining <= 0) break;
            int changed = migrateLegacyTable(spec[0], java.util.Arrays.copyOfRange(spec, 1, spec.length), remaining);
            migrated += changed;
            remaining -= changed;
        }
        return migrated;
    }

    private int migrateLegacyTable(String table, String[] columns, int limit) {
        if (columns.length == 0 || limit <= 0) return 0;
        StringBuilder where = new StringBuilder();
        String[] projection = new String[columns.length + 1];
        projection[0] = "rowid";
        for (int i = 0; i < columns.length; i++) {
            projection[i + 1] = columns[i];
            if (where.length() > 0) where.append(" OR ");
            where.append('(').append(columns[i]).append("!='' AND ").append(columns[i]).append(" NOT LIKE 'v2:%')");
        }
        SQLiteDatabase database = getWritableDatabase();
        List<Long> rowIds = new ArrayList<>();
        List<ContentValues> changes = new ArrayList<>();
        try (Cursor c = database.query(table, projection, where.toString(), null, null, null, "rowid ASC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                ContentValues values = new ContentValues();
                for (int i = 0; i < columns.length; i++) {
                    String encoded = c.getString(i + 1);
                    if (encoded == null || encoded.isEmpty() || encoded.startsWith("v2:")) continue;
                    String plain = crypto.decrypt(encoded);
                    if ("[本地加密数据暂时无法读取]".equals(plain)) continue;
                    values.put(columns[i], crypto.encrypt(plain));
                }
                if (values.size() > 0) { rowIds.add(c.getLong(0)); changes.add(values); }
            }
        }
        if (changes.isEmpty()) return 0;
        database.beginTransaction();
        try {
            for (int i = 0; i < changes.size(); i++) {
                database.update(table, changes.get(i), "rowid=?", new String[]{String.valueOf(rowIds.get(i))});
            }
            database.setTransactionSuccessful();
        } finally { database.endTransaction(); }
        return changes.size();
    }

    public synchronized JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONArray ns = new JSONArray();
            try (Cursor c = getReadableDatabase().query("notifications", null, "removed=0", null, null, null, "posted_at DESC")) {
                while (c.moveToNext()) {
                    Models.NotificationItem n = notificationFrom(c);
                    JSONObject o = new JSONObject();
                    o.put("app", n.appName); o.put("title", n.title); o.put("content", n.content);
                    o.put("posted_at", n.postedAt); o.put("summary_state", n.summaryState);
                    ns.put(o);
                }
            }
            JSONArray rs = new JSONArray();
            for (Models.ImportantRecord r : records("all", "")) {
                JSONObject o = new JSONObject();
                o.put("title", r.title); o.put("content", r.content); o.put("category", r.category);
                o.put("status", r.status); o.put("updated_at", r.updatedAt);
                rs.put(o);
            }
            JSONArray ts = new JSONArray();
            for (Models.TodoItem t : todos("all", "", 500)) {
                JSONObject o = new JSONObject();
                o.put("title", t.title); o.put("description", t.description); o.put("due_at", t.dueAt);
                o.put("priority", t.priority); o.put("status", t.status); o.put("updated_at", t.updatedAt);
                ts.put(o);
            }
            JSONArray ms = new JSONArray();
            for (Models.MemoryItem m : memories("all", "", 500)) {
                JSONObject o = new JSONObject();
                o.put("title", m.title); o.put("text", m.text); o.put("category", m.category);
                o.put("importance", m.importance); o.put("status", m.status); o.put("updated_at", m.updatedAt);
                ms.put(o);
            }
            JSONArray ps = new JSONArray();
            for (Models.ProfileFact p : profileFacts("all", "", 500)) {
                JSONObject o = new JSONObject();
                o.put("section", p.section); o.put("title", p.title); o.put("content", p.content);
                o.put("status", p.status); o.put("updated_at", p.updatedAt);
                ps.put(o);
            }
            JSONArray bs = new JSONArray();
            try (Cursor c = getReadableDatabase().query("assistant_brief", null, null, null, null, null, "updated_at DESC")) {
                while (c.moveToNext()) {
                    Models.AssistantBriefItem item = briefFrom(c); JSONObject o = new JSONObject();
                    o.put("id", item.id); o.put("type", item.type); o.put("title", item.title); o.put("detail", item.detail);
                    o.put("reason", item.reason); o.put("urgency", item.urgency); o.put("status", item.status); o.put("updated_at", item.updatedAt); bs.put(o);
                }
            }
            JSONArray cs = new JSONArray();
            for (Models.ConversationMessage message : conversationMessages(200)) {
                JSONObject o = new JSONObject(); o.put("role", message.role); o.put("content", message.content);
                o.put("action_type", message.actionType); o.put("action_status", message.actionStatus); o.put("created_at", message.createdAt); cs.put(o);
            }
            root.put("exported_at", System.currentTimeMillis());
            root.put("notifications", ns);
            root.put("records", rs);
            root.put("todos", ts);
            root.put("memories", ms);
            root.put("profile", ps);
            root.put("assistant_brief", bs);
            root.put("conversation", cs);
        } catch (Exception ignored) {}
        return root;
    }

    public void exportJson(OutputStream output) throws IOException {
        if (output == null) throw new IOException("无法写入导出文件");
        SQLiteDatabase database = getReadableDatabase();
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.setIndent("  ");
        database.beginTransactionNonExclusive();
        try {
            writer.beginObject();
            writer.name("exported_at").value(System.currentTimeMillis());
            writer.name("notifications").beginArray();
            try (Cursor c = database.query("notifications", null, "removed=0", null, null, null, "posted_at DESC")) {
                while (c.moveToNext()) {
                    Models.NotificationItem n = notificationFrom(c);
                    writer.beginObject();
                    writer.name("app").value(n.appName); writer.name("title").value(n.title); writer.name("content").value(n.content);
                    writer.name("posted_at").value(n.postedAt); writer.name("summary_state").value(n.summaryState);
                    writer.endObject();
                }
            }
            writer.endArray();
            writeRecordExport(writer, database);
            writeTodoExport(writer, database);
            writeMemoryExport(writer, database);
            writeProfileExport(writer, database);
            writer.name("assistant_brief").beginArray();
            try (Cursor c = database.query("assistant_brief", null, null, null, null, null, "updated_at DESC")) {
                while (c.moveToNext()) {
                    Models.AssistantBriefItem item = briefFrom(c);
                    writer.beginObject();
                    writer.name("id").value(item.id); writer.name("type").value(item.type); writer.name("title").value(item.title);
                    writer.name("detail").value(item.detail); writer.name("reason").value(item.reason); writer.name("urgency").value(item.urgency);
                    writer.name("status").value(item.status); writer.name("updated_at").value(item.updatedAt);
                    writer.endObject();
                }
            }
            writer.endArray();
            writer.name("conversation").beginArray();
            try (Cursor c = database.query("conversation_messages", null, null, null, null, null, "created_at ASC")) {
                while (c.moveToNext()) {
                    Models.ConversationMessage message = conversationFrom(c);
                    writer.beginObject();
                    writer.name("role").value(message.role); writer.name("content").value(message.content);
                    writer.name("action_type").value(message.actionType); writer.name("action_status").value(message.actionStatus);
                    writer.name("created_at").value(message.createdAt);
                    writer.endObject();
                }
            }
            writer.endArray();
            writer.endObject();
            writer.flush();
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private void writeRecordExport(JsonWriter writer, SQLiteDatabase database) throws IOException {
        writer.name("records").beginArray();
        try (Cursor c = database.query("important_records", null, "deleted_at=0", null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) {
                Models.ImportantRecord r = recordFrom(c); writer.beginObject();
                writer.name("title").value(r.title); writer.name("content").value(r.content); writer.name("category").value(r.category);
                writer.name("status").value(r.status); writer.name("updated_at").value(r.updatedAt); writer.endObject();
            }
        }
        writer.endArray();
    }

    private void writeTodoExport(JsonWriter writer, SQLiteDatabase database) throws IOException {
        writer.name("todos").beginArray();
        try (Cursor c = database.query("todos", null, "deleted_at=0", null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) {
                Models.TodoItem t = todoFrom(c); writer.beginObject();
                writer.name("title").value(t.title); writer.name("description").value(t.description); writer.name("due_at").value(t.dueAt);
                writer.name("priority").value(t.priority); writer.name("status").value(t.status); writer.name("updated_at").value(t.updatedAt); writer.endObject();
            }
        }
        writer.endArray();
    }

    private void writeMemoryExport(JsonWriter writer, SQLiteDatabase database) throws IOException {
        writer.name("memories").beginArray();
        try (Cursor c = database.query("memories", null, "deleted_at=0", null, null, null, "updated_at DESC")) {
            while (c.moveToNext()) {
                Models.MemoryItem m = memoryFrom(c); writer.beginObject();
                writer.name("title").value(m.title); writer.name("text").value(m.text); writer.name("category").value(m.category);
                writer.name("importance").value(m.importance); writer.name("status").value(m.status); writer.name("updated_at").value(m.updatedAt); writer.endObject();
            }
        }
        writer.endArray();
    }

    private void writeProfileExport(JsonWriter writer, SQLiteDatabase database) throws IOException {
        writer.name("profile").beginArray();
        try (Cursor c = database.query("profile_facts", null, "deleted_at=0", null, null, null, "section ASC, updated_at DESC")) {
            while (c.moveToNext()) {
                Models.ProfileFact p = profileFactFrom(c); writer.beginObject();
                writer.name("section").value(p.section); writer.name("title").value(p.title); writer.name("content").value(p.content);
                writer.name("status").value(p.status); writer.name("updated_at").value(p.updatedAt); writer.endObject();
            }
        }
        writer.endArray();
    }

    private Models.NotificationItem notificationFrom(Cursor c) {
        Models.NotificationItem n = new Models.NotificationItem();
        n.id = getLong(c, "id");
        n.sourceKey = getString(c, "source_key");
        n.packageName = getString(c, "package_name");
        n.appName = crypto.decrypt(getString(c, "app_name"));
        n.title = crypto.decrypt(getString(c, "title"));
        n.content = crypto.decrypt(getString(c, "content"));
        n.postedAt = getLong(c, "posted_at");
        n.capturedAt = getLong(c, "captured_at");
        n.hadContentIntentAtCapture = getLong(c, "had_content_intent") == 1;
        n.systemRemovedAt = getLong(c, "system_removed_at");
        n.systemUserId = (int) getLong(c, "system_user_id");
        n.summaryState = getString(c, "summary_state");
        n.sensitivity = getString(c, "sensitivity");
        return n;
    }

    private Models.SummarySession sessionFrom(Cursor c) {
        Models.SummarySession s = new Models.SummarySession();
        s.id = getString(c, "id"); s.createdAt = getLong(c, "created_at");
        s.completedAt = getLong(c, "completed_at"); s.rangeStart = getLong(c, "range_start");
        s.rangeEnd = getLong(c, "range_end"); s.notificationCount = (int)getLong(c, "notification_count");
        s.overview = crypto.decrypt(getString(c, "overview"));
        s.keyPointsJson = crypto.decrypt(getString(c, "key_points"));
        s.status = getString(c, "status"); s.error = crypto.decrypt(getString(c, "error"));
        s.modelInfo = getString(c, "model_info"); s.notificationIds = getString(c, "notification_ids");
        return s;
    }

    private Models.ImportantRecord recordFrom(Cursor c) {
        Models.ImportantRecord r = new Models.ImportantRecord();
        r.id = getString(c, "id"); r.title = crypto.decrypt(getString(c, "title"));
        r.content = crypto.decrypt(getString(c, "content")); r.category = getString(c, "category");
        r.importance = c.getDouble(c.getColumnIndexOrThrow("importance"));
        r.eventAt = getLong(c, "event_at"); r.createdAt = getLong(c, "created_at");
        r.updatedAt = getLong(c, "updated_at"); r.sourceSummaryId = getString(c, "source_summary_id");
        r.sourceNotificationIds = getString(c, "source_notification_ids"); r.status = getString(c, "status");
        r.generatedByAi = getLong(c, "generated_ai") == 1; r.userEdited = getLong(c, "user_edited") == 1;
        r.deletedAt = getLong(c, "deleted_at");
        return r;
    }

    private Models.TodoItem todoFrom(Cursor c) {
        Models.TodoItem t = new Models.TodoItem();
        t.id = getString(c, "id"); t.title = crypto.decrypt(getString(c, "title"));
        t.description = crypto.decrypt(getString(c, "description")); t.dueAt = getLong(c, "due_at");
        t.priority = getString(c, "priority"); t.status = getString(c, "status");
        t.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        t.sourceSummaryId = getString(c, "source_summary_id"); t.sourceNotificationIds = getString(c, "source_notification_ids");
        t.generatedByAi = getLong(c, "generated_ai") == 1; t.userEdited = getLong(c, "user_edited") == 1;
        t.createdAt = getLong(c, "created_at"); t.updatedAt = getLong(c, "updated_at"); t.deletedAt = getLong(c, "deleted_at");
        return t;
    }

    private Models.MemoryItem memoryFrom(Cursor c) {
        Models.MemoryItem m = new Models.MemoryItem();
        m.id = getString(c, "id"); m.title = crypto.decrypt(getString(c, "title"));
        m.text = crypto.decrypt(getString(c, "text")); m.category = getString(c, "category");
        m.importance = c.getDouble(c.getColumnIndexOrThrow("importance"));
        m.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        m.stability = getString(c, "stability"); m.sensitivity = getString(c, "sensitivity"); m.status = getString(c, "status");
        m.sourceSummaryId = getString(c, "source_summary_id"); m.sourceNotificationIds = getString(c, "source_notification_ids");
        m.generatedByAi = getLong(c, "generated_ai") == 1; m.userEdited = getLong(c, "user_edited") == 1;
        m.createdAt = getLong(c, "created_at"); m.updatedAt = getLong(c, "updated_at"); m.deletedAt = getLong(c, "deleted_at");
        return m;
    }

    private Models.ProfileFact profileFactFrom(Cursor c) {
        Models.ProfileFact p = new Models.ProfileFact();
        p.id = getString(c, "id"); p.section = getString(c, "section"); p.title = crypto.decrypt(getString(c, "title"));
        p.content = crypto.decrypt(getString(c, "content")); p.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        p.stability = getString(c, "stability"); p.status = getString(c, "status");
        p.sourceSummaryId = getString(c, "source_summary_id"); p.sourceMemoryIds = getString(c, "source_memory_ids");
        p.sourceNotificationIds = getString(c, "source_notification_ids");
        p.generatedByAi = getLong(c, "generated_ai") == 1; p.userEdited = getLong(c, "user_edited") == 1;
        p.lastSeenAt = getLong(c, "last_seen_at"); p.createdAt = getLong(c, "created_at");
        p.updatedAt = getLong(c, "updated_at"); p.deletedAt = getLong(c, "deleted_at");
        return p;
    }

    private Models.NeedToKnow needToKnowFrom(Cursor c) {
        Models.NeedToKnow n = new Models.NeedToKnow();
        n.id = getString(c, "id"); n.title = crypto.decrypt(getString(c, "title"));
        n.detail = crypto.decrypt(getString(c, "detail")); n.reason = crypto.decrypt(getString(c, "reason"));
        n.urgency = getString(c, "urgency"); n.suggestedAction = crypto.decrypt(getString(c, "suggested_action"));
        n.sourceSummaryId = getString(c, "source_summary_id"); n.sourceNotificationIds = getString(c, "source_notification_ids");
        n.relatedTodoIds = getString(c, "related_todo_ids"); n.relatedMemoryIds = getString(c, "related_memory_ids");
        n.relatedProfileFactIds = getString(c, "related_profile_fact_ids"); n.createdAt = getLong(c, "created_at");
        return n;
    }

    private Models.AssistantBriefItem briefFrom(Cursor c) {
        Models.AssistantBriefItem b = new Models.AssistantBriefItem();
        b.id = getString(c, "id"); b.type = getString(c, "type");
        b.title = crypto.decrypt(getString(c, "title")); b.detail = crypto.decrypt(getString(c, "detail"));
        b.reason = crypto.decrypt(getString(c, "reason"));
        b.suggestedAction = crypto.decrypt(getString(c, "suggested_action"));
        b.urgency = getString(c, "urgency"); b.impact = getString(c, "impact");
        b.rankScore = c.getDouble(c.getColumnIndexOrThrow("rank_score"));
        b.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        b.validFrom = getLong(c, "valid_from"); b.validUntil = getLong(c, "valid_until");
        b.status = getString(c, "status"); b.snoozedUntil = getLong(c, "snoozed_until");
        b.sourceSummaryId = getString(c, "source_summary_id");
        b.sourceNotificationIds = getString(c, "source_notification_ids");
        b.relatedTodoIds = getString(c, "related_todo_ids");
        b.relatedCalendarKeys = getString(c, "related_calendar_keys");
        b.relatedMemoryIds = getString(c, "related_memory_ids");
        b.relatedProfileFactIds = getString(c, "related_profile_fact_ids");
        b.feedback = getString(c, "feedback"); b.origin = getString(c, "origin");
        b.createdAt = getLong(c, "created_at"); b.updatedAt = getLong(c, "updated_at");
        return b;
    }

    private Models.ConversationMessage conversationFrom(Cursor c) {
        Models.ConversationMessage m = new Models.ConversationMessage();
        m.id = getString(c, "id"); m.role = getString(c, "role");
        m.content = crypto.decrypt(getString(c, "content")); m.actionType = getString(c, "action_type");
        m.actionObjectId = getString(c, "action_object_id"); m.actionStatus = getString(c, "action_status");
        m.createdAt = getLong(c, "created_at");
        return m;
    }

    private void insertSession(SQLiteDatabase db, Models.SummarySession s) {
        db.insertOrThrow("summary_sessions", null, sessionValues(s));
    }

    private ContentValues sessionValues(Models.SummarySession s) {
        ContentValues v = new ContentValues();
        v.put("id", s.id); v.put("created_at", s.createdAt); v.put("completed_at", s.completedAt);
        v.put("range_start", s.rangeStart); v.put("range_end", s.rangeEnd); v.put("notification_count", s.notificationCount);
        v.put("overview", crypto.encrypt(s.overview)); v.put("key_points", crypto.encrypt(s.keyPointsJson));
        v.put("status", s.status); v.put("error", crypto.encrypt(s.error)); v.put("model_info", s.modelInfo);
        v.put("notification_ids", s.notificationIds);
        return v;
    }

    private void insertRecord(SQLiteDatabase db, Models.ImportantRecord record, String sessionId) {
        if (isDuplicateRecord(record)) return;
        if (record.id == null || record.id.isEmpty()) record.id = UUID.randomUUID().toString();
        record.createdAt = System.currentTimeMillis(); record.updatedAt = record.createdAt;
        record.sourceSummaryId = sessionId; record.status = "confirmed"; record.generatedByAi = true;
        db.insertWithOnConflict("important_records", null, recordValues(record), SQLiteDatabase.CONFLICT_IGNORE);
    }

    private void insertTodo(SQLiteDatabase db, Models.TodoItem todo, String sessionId) {
        if (isDuplicateTodo(todo)) return;
        if (todo.id == null || todo.id.isEmpty()) todo.id = UUID.randomUUID().toString();
        todo.createdAt = System.currentTimeMillis(); todo.updatedAt = todo.createdAt;
        todo.sourceSummaryId = sessionId; todo.status = todo.status == null || todo.status.isEmpty() ? "open" : todo.status;
        todo.generatedByAi = true;
        db.insertWithOnConflict("todos", null, todoValues(todo), SQLiteDatabase.CONFLICT_IGNORE);
    }

    private String upsertMemory(SQLiteDatabase db, Models.MemoryItem memory, String sessionId) {
        if (memory == null || memory.text.trim().isEmpty()) return "";
        String op = memory.operation == null ? "create" : memory.operation;
        if ("ignore".equals(op) || memory.importance < 0.45 || memory.confidence < 0.45) return "";
        long now = System.currentTimeMillis();
        memory.sourceSummaryId = sessionId;
        memory.generatedByAi = true;
        if (!memory.targetMemoryId.isEmpty()) {
            Models.MemoryItem existing = memory(memory.targetMemoryId);
            if (existing != null) {
                if (existing.userEdited) {
                    memory.status = "disputed";
                } else {
                    existing.title = memory.title.isEmpty() ? existing.title : memory.title;
                    existing.text = memory.text;
                    existing.category = memory.category.isEmpty() ? existing.category : memory.category;
                    existing.importance = memory.importance;
                    existing.confidence = memory.confidence;
                    existing.stability = memory.stability;
                    existing.sensitivity = memory.sensitivity;
                    existing.status = "supersede".equals(op) ? "active" : ("dispute".equals(op) ? "disputed" : "active");
                    existing.sourceSummaryId = sessionId;
                    existing.sourceNotificationIds = mergeIds(existing.sourceNotificationIds, memory.sourceNotificationIds);
                    existing.updatedAt = now;
                    db.update("memories", memoryValues(existing), "id=?", new String[]{existing.id});
                    return existing.id;
                }
            }
        }
        if ("create".equals(op)) {
            String duplicate = findDuplicateMemoryId(memory);
            if (!duplicate.isEmpty()) return duplicate;
        }
        if (memory.id == null || memory.id.isEmpty()) memory.id = UUID.randomUUID().toString();
        if (memory.title.isEmpty()) memory.title = firstSentence(memory.text);
        memory.status = "dispute".equals(op) ? "disputed" : (memory.status == null || memory.status.isEmpty() ? "active" : memory.status);
        memory.createdAt = now; memory.updatedAt = now;
        db.insertWithOnConflict("memories", null, memoryValues(memory), SQLiteDatabase.CONFLICT_IGNORE);
        return memory.id;
    }

    private String upsertProfileFact(SQLiteDatabase db, Models.ProfileFact fact, String sessionId) {
        if (fact == null || fact.content.trim().isEmpty()) return "";
        String op = fact.operation == null ? "add" : fact.operation;
        if ("ignore".equals(op) || fact.confidence < 0.45) return "";
        long now = System.currentTimeMillis();
        fact.sourceSummaryId = sessionId;
        fact.generatedByAi = true;
        if (!fact.targetProfileFactId.isEmpty()) {
            Models.ProfileFact existing = profileFact(fact.targetProfileFactId);
            if (existing != null) {
                if (existing.userEdited) {
                    fact.status = "disputed";
                } else {
                    existing.section = fact.section.isEmpty() ? existing.section : fact.section;
                    existing.title = fact.title.isEmpty() ? existing.title : fact.title;
                    existing.content = fact.content;
                    existing.confidence = fact.confidence;
                    existing.stability = fact.stability;
                    existing.status = "archive".equals(op) ? "archived" : ("dispute".equals(op) ? "disputed" : "active");
                    existing.sourceSummaryId = sessionId;
                    existing.sourceMemoryIds = mergeIds(existing.sourceMemoryIds, fact.sourceMemoryIds);
                    existing.sourceNotificationIds = mergeIds(existing.sourceNotificationIds, fact.sourceNotificationIds);
                    existing.lastSeenAt = now;
                    existing.updatedAt = now;
                    db.update("profile_facts", profileFactValues(existing), "id=?", new String[]{existing.id});
                    return existing.id;
                }
            }
        }
        String duplicate = findDuplicateProfileFactId(fact);
        if (!duplicate.isEmpty() && !"dispute".equals(op)) return duplicate;
        if (fact.id == null || fact.id.isEmpty()) fact.id = UUID.randomUUID().toString();
        if (fact.title.isEmpty()) fact.title = firstSentence(fact.content);
        fact.status = "archive".equals(op) ? "archived" : ("dispute".equals(op) ? "disputed" : "active");
        fact.lastSeenAt = now; fact.createdAt = now; fact.updatedAt = now;
        db.insertWithOnConflict("profile_facts", null, profileFactValues(fact), SQLiteDatabase.CONFLICT_IGNORE);
        return fact.id;
    }

    private void insertNeedToKnow(SQLiteDatabase db, Models.NeedToKnow need, String sessionId) {
        if (need.title.trim().isEmpty() && need.detail.trim().isEmpty()) return;
        if (need.id == null || need.id.isEmpty()) need.id = UUID.randomUUID().toString();
        need.sourceSummaryId = sessionId;
        need.createdAt = System.currentTimeMillis();
        db.insertWithOnConflict("need_to_know", null, needToKnowValues(need), SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static List<ImportedProfileLine> parseImportedMarkdown(String markdown) {
        List<ImportedProfileLine> result = new ArrayList<>();
        String section = "导入画像";
        String titleContext = "";
        boolean code = false;
        Set<String> seen = new HashSet<>();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.startsWith("```")) { code = !code; continue; }
            if (code || line.isEmpty() || "---".equals(line)) continue;
            if (line.matches("^#{1,6}\\s+.*")) {
                int level = headingLevel(line);
                String heading = cleanMarkdown(line.replaceFirst("^#{1,6}\\s+", ""));
                if (heading.isEmpty()) continue;
                if (level <= 2) { section = heading; titleContext = ""; }
                else titleContext = heading;
                continue;
            }
            if (line.matches("^\\|?\\s*[-:| ]+\\|\\s*$")) continue;
            if (line.startsWith("|") && line.endsWith("|")) {
                String[] cells = line.substring(1, line.length() - 1).split("\\|");
                if (cells.length >= 2) {
                    String left = cleanMarkdown(cells[0]);
                    String right = cleanMarkdown(cells[1]);
                    if (left.equals("项目") || left.equals("时间") || left.equals("公司/岗位")) continue;
                    line = left + "：" + right;
                }
            }
            line = line.replaceFirst("^([-*+]|\\d+[.)])\\s+", "").replaceFirst("^\\[[ xX]\\]\\s*", "");
            line = cleanMarkdown(line);
            if (line.isEmpty() || line.length() < 2) continue;
            String key = normalize(section + " " + titleContext + " " + line);
            if (!seen.add(key)) continue;
            String title = titleContext.isEmpty() ? firstSentence(line) : titleContext;
            String content = line;
            int colon = firstColon(line);
            if (colon > 0 && colon < Math.min(line.length() - 1, 40)) {
                title = cleanMarkdown(line.substring(0, colon));
                content = cleanMarkdown(line.substring(colon + 1));
            }
            if (content.isEmpty()) content = line;
            ImportedProfileLine item = new ImportedProfileLine();
            item.section = section;
            item.title = title;
            item.content = content;
            item.category = importedCategory(section + " " + title + " " + content);
            result.add(item);
        }
        return result;
    }

    private Set<String> existingMemoryKeys(SQLiteDatabase db) {
        Set<String> keys = new HashSet<>();
        try (Cursor c = db.query("memories", new String[]{"title", "text"},
                "deleted_at=0", null, null, null, null)) {
            while (c.moveToNext()) keys.add(normalize(crypto.decrypt(c.getString(0)) + " " + crypto.decrypt(c.getString(1))));
        }
        return keys;
    }

    private Set<String> existingProfileKeys(SQLiteDatabase db) {
        Set<String> keys = new HashSet<>();
        try (Cursor c = db.query("profile_facts", new String[]{"section", "title"},
                "deleted_at=0", null, null, null, null)) {
            while (c.moveToNext()) keys.add(normalize(c.getString(0) + " " + crypto.decrypt(c.getString(1))));
        }
        return keys;
    }

    private void insertImportedMemoryUnchecked(SQLiteDatabase db, String title, String text, String category) {
        Models.MemoryItem memory = importedMemory(title, text, category);
        db.insertWithOnConflict("memories", null, memoryValues(memory), SQLiteDatabase.CONFLICT_IGNORE);
    }

    private void insertImportedProfileFactUnchecked(SQLiteDatabase db, String section, String title, String content) {
        Models.ProfileFact fact = importedProfileFact(section, title, content);
        db.insertWithOnConflict("profile_facts", null, profileFactValues(fact), SQLiteDatabase.CONFLICT_IGNORE);
    }

    private boolean insertImportedMemory(SQLiteDatabase db, String title, String text, String category) {
        Models.MemoryItem memory = importedMemory(title, text, category);
        if (!findDuplicateMemoryId(memory).isEmpty()) return false;
        db.insertWithOnConflict("memories", null, memoryValues(memory), SQLiteDatabase.CONFLICT_IGNORE);
        return true;
    }

    private boolean insertImportedProfileFact(SQLiteDatabase db, String section, String title, String content) {
        Models.ProfileFact fact = importedProfileFact(section, title, content);
        if (!findDuplicateProfileFactId(fact).isEmpty()) return false;
        db.insertWithOnConflict("profile_facts", null, profileFactValues(fact), SQLiteDatabase.CONFLICT_IGNORE);
        return true;
    }

    private static Models.MemoryItem importedMemory(String title, String text, String category) {
        Models.MemoryItem memory = new Models.MemoryItem();
        memory.id = UUID.randomUUID().toString();
        memory.title = title == null ? "" : title.trim();
        memory.text = text == null ? "" : text.trim();
        memory.category = category == null || category.isEmpty() ? "other" : category;
        memory.importance = 1.0;
        memory.confidence = 1.0;
        memory.stability = "stable";
        memory.sensitivity = "normal";
        memory.status = "active";
        memory.generatedByAi = false;
        memory.userEdited = true;
        memory.createdAt = System.currentTimeMillis();
        memory.updatedAt = memory.createdAt;
        return memory;
    }

    private static Models.ProfileFact importedProfileFact(String section, String title, String content) {
        Models.ProfileFact fact = new Models.ProfileFact();
        fact.id = UUID.randomUUID().toString();
        fact.section = section == null || section.isEmpty() ? "导入画像" : section.trim();
        fact.title = title == null || title.trim().isEmpty() ? firstSentence(content) : title.trim();
        fact.content = content == null ? "" : content.trim();
        fact.confidence = 1.0;
        fact.stability = "stable";
        fact.status = "active";
        fact.generatedByAi = false;
        fact.userEdited = true;
        fact.lastSeenAt = System.currentTimeMillis();
        fact.createdAt = fact.lastSeenAt;
        fact.updatedAt = fact.lastSeenAt;
        return fact;
    }

    private boolean isDuplicateRecord(Models.ImportantRecord candidate) {
        String candidateText = normalize(candidate.title + " " + candidate.content);
        try (Cursor c = getReadableDatabase().query("important_records", new String[]{"title", "content"},
                "deleted_at=0", null, null, null, null)) {
            while (c.moveToNext()) {
                String existing = normalize(crypto.decrypt(c.getString(0)) + " " + crypto.decrypt(c.getString(1)));
                if (!candidateText.isEmpty() && candidateText.equals(existing)) return true;
            }
        }
        return false;
    }

    private boolean isDuplicateTodo(Models.TodoItem candidate) {
        String candidateText = normalize(candidate.title + " " + candidate.description);
        try (Cursor c = getReadableDatabase().query("todos", new String[]{"title", "description", "due_at"},
                "deleted_at=0 AND status!='done'", null, null, null, null)) {
            while (c.moveToNext()) {
                String existing = normalize(crypto.decrypt(c.getString(0)) + " " + crypto.decrypt(c.getString(1)));
                long existingDue = c.getLong(2);
                boolean sameDue = candidate.dueAt == 0 || existingDue == 0 || Math.abs(candidate.dueAt - existingDue) < 3_600_000L;
                if (!candidateText.isEmpty() && candidateText.equals(existing) && sameDue) return true;
            }
        }
        return false;
    }

    private String findDuplicateMemoryId(Models.MemoryItem candidate) {
        String candidateText = normalize(candidate.title + " " + candidate.text);
        try (Cursor c = getReadableDatabase().query("memories", new String[]{"id", "title", "text"},
                "deleted_at=0 AND status!='archived'", null, null, null, null)) {
            while (c.moveToNext()) {
                String existing = normalize(crypto.decrypt(c.getString(1)) + " " + crypto.decrypt(c.getString(2)));
                if (!candidateText.isEmpty() && candidateText.equals(existing)) return c.getString(0);
            }
        }
        return "";
    }

    private String findDuplicateProfileFactId(Models.ProfileFact candidate) {
        String candidateTitle = normalize(candidate.section + " " + candidate.title);
        try (Cursor c = getReadableDatabase().query("profile_facts", new String[]{"id", "section", "title"},
                "deleted_at=0 AND status!='archived'", null, null, null, null)) {
            while (c.moveToNext()) {
                String existing = normalize(c.getString(1) + " " + crypto.decrypt(c.getString(2)));
                if (!candidateTitle.isEmpty() && candidateTitle.equals(existing)) return c.getString(0);
            }
        }
        return "";
    }

    private ContentValues recordValues(Models.ImportantRecord r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("title", crypto.encrypt(r.title)); v.put("content", crypto.encrypt(r.content));
        v.put("category", r.category); v.put("importance", r.importance); v.put("event_at", r.eventAt);
        v.put("created_at", r.createdAt); v.put("updated_at", r.updatedAt); v.put("source_summary_id", r.sourceSummaryId);
        v.put("source_notification_ids", r.sourceNotificationIds); v.put("status", r.status);
        v.put("generated_ai", r.generatedByAi ? 1 : 0); v.put("user_edited", r.userEdited ? 1 : 0);
        v.put("deleted_at", r.deletedAt);
        return v;
    }

    private ContentValues todoValues(Models.TodoItem t) {
        ContentValues v = new ContentValues();
        v.put("id", t.id); v.put("title", crypto.encrypt(t.title)); v.put("description", crypto.encrypt(t.description));
        v.put("due_at", t.dueAt); v.put("priority", t.priority); v.put("status", t.status);
        v.put("confidence", t.confidence); v.put("source_summary_id", t.sourceSummaryId);
        v.put("source_notification_ids", t.sourceNotificationIds); v.put("generated_ai", t.generatedByAi ? 1 : 0);
        v.put("user_edited", t.userEdited ? 1 : 0); v.put("created_at", t.createdAt); v.put("updated_at", t.updatedAt);
        v.put("deleted_at", t.deletedAt);
        return v;
    }

    private ContentValues memoryValues(Models.MemoryItem m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id); v.put("title", crypto.encrypt(m.title)); v.put("text", crypto.encrypt(m.text));
        v.put("category", m.category); v.put("importance", m.importance); v.put("confidence", m.confidence);
        v.put("stability", m.stability); v.put("sensitivity", m.sensitivity); v.put("status", m.status);
        v.put("source_summary_id", m.sourceSummaryId); v.put("source_notification_ids", m.sourceNotificationIds);
        v.put("generated_ai", m.generatedByAi ? 1 : 0); v.put("user_edited", m.userEdited ? 1 : 0);
        v.put("created_at", m.createdAt); v.put("updated_at", m.updatedAt); v.put("deleted_at", m.deletedAt);
        return v;
    }

    private ContentValues profileFactValues(Models.ProfileFact p) {
        ContentValues v = new ContentValues();
        v.put("id", p.id); v.put("section", p.section); v.put("title", crypto.encrypt(p.title));
        v.put("content", crypto.encrypt(p.content)); v.put("confidence", p.confidence); v.put("stability", p.stability);
        v.put("status", p.status); v.put("source_summary_id", p.sourceSummaryId); v.put("source_memory_ids", p.sourceMemoryIds);
        v.put("source_notification_ids", p.sourceNotificationIds); v.put("generated_ai", p.generatedByAi ? 1 : 0);
        v.put("user_edited", p.userEdited ? 1 : 0); v.put("last_seen_at", p.lastSeenAt);
        v.put("created_at", p.createdAt); v.put("updated_at", p.updatedAt); v.put("deleted_at", p.deletedAt);
        return v;
    }

    private ContentValues needToKnowValues(Models.NeedToKnow n) {
        ContentValues v = new ContentValues();
        v.put("id", n.id); v.put("title", crypto.encrypt(n.title)); v.put("detail", crypto.encrypt(n.detail));
        v.put("reason", crypto.encrypt(n.reason)); v.put("urgency", n.urgency);
        v.put("suggested_action", crypto.encrypt(n.suggestedAction)); v.put("source_summary_id", n.sourceSummaryId);
        v.put("source_notification_ids", n.sourceNotificationIds); v.put("related_todo_ids", n.relatedTodoIds);
        v.put("related_memory_ids", n.relatedMemoryIds); v.put("related_profile_fact_ids", n.relatedProfileFactIds);
        v.put("created_at", n.createdAt);
        return v;
    }

    private ContentValues briefValues(Models.AssistantBriefItem b) {
        ContentValues v = new ContentValues();
        v.put("id", b.id); v.put("type", b.type); v.put("title", crypto.encrypt(b.title));
        v.put("detail", crypto.encrypt(b.detail)); v.put("reason", crypto.encrypt(b.reason));
        v.put("suggested_action", crypto.encrypt(b.suggestedAction)); v.put("urgency", b.urgency);
        v.put("impact", b.impact); v.put("rank_score", b.rankScore); v.put("confidence", b.confidence);
        v.put("valid_from", b.validFrom); v.put("valid_until", b.validUntil); v.put("status", b.status);
        v.put("snoozed_until", b.snoozedUntil); v.put("source_summary_id", b.sourceSummaryId);
        v.put("source_notification_ids", b.sourceNotificationIds); v.put("related_todo_ids", b.relatedTodoIds);
        v.put("related_calendar_keys", b.relatedCalendarKeys); v.put("related_memory_ids", b.relatedMemoryIds);
        v.put("related_profile_fact_ids", b.relatedProfileFactIds); v.put("feedback", b.feedback);
        v.put("origin", b.origin); v.put("created_at", b.createdAt); v.put("updated_at", b.updatedAt);
        return v;
    }

    private ContentValues conversationValues(Models.ConversationMessage m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id); v.put("role", m.role); v.put("content", crypto.encrypt(m.content));
        v.put("action_type", m.actionType); v.put("action_object_id", m.actionObjectId);
        v.put("action_status", m.actionStatus); v.put("created_at", m.createdAt);
        return v;
    }

    private static double urgencyScore(String urgency) {
        if ("critical".equals(urgency)) return 100;
        if ("high".equals(urgency)) return 80;
        if ("medium".equals(urgency)) return 55;
        return 30;
    }

    private int count(String where, String[] args) { return countTable("notifications", where, args); }
    private int countTable(String table, String where, String[] args) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where, args)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }
    private long scalarLong(String sql) {
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) { return c.moveToFirst() ? c.getLong(0) : 0; }
    }
    private static String pageClause(int offset, int limit, int maxLimit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = safePageSize(limit, maxLimit);
        return safeOffset + "," + safeLimit;
    }
    private static int safePageSize(int limit, int maxLimit) { return Math.max(1, Math.min(limit, maxLimit)); }
    private static long getLong(Cursor c, String name) { return c.getLong(c.getColumnIndexOrThrow(name)); }
    private static String getString(Cursor c, String name) { String s = c.getString(c.getColumnIndexOrThrow(name)); return s == null ? "" : s; }
    private static String searchable(Models.NotificationItem n) { return (n.appName + " " + n.title + " " + n.content).toLowerCase(Locale.ROOT); }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : digest) b.append(String.format(Locale.US, "%02x", x));
            return b.toString();
        } catch (Exception e) { return String.valueOf(value.hashCode()); }
    }

    private static String safeIdList(String ids) { return ids.replaceAll("[^0-9,]", ""); }
    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？：；]", ""); }
    private static String joinStrings(List<String> values) {
        StringBuilder b = new StringBuilder();
        if (values != null) for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (b.length() > 0) b.append(',');
            b.append(value.trim());
        }
        return b.toString();
    }
    private static String mergeIds(String a, String b) {
        StringBuilder result = new StringBuilder();
        for (String value : ((a == null ? "" : a) + "," + (b == null ? "" : b)).split(",")) {
            String trimmed = value.trim();
            if (trimmed.isEmpty()) continue;
            boolean exists = false;
            for (String current : result.toString().split(",")) if (trimmed.equals(current.trim())) { exists = true; break; }
            if (!exists) {
                if (result.length() > 0) result.append(',');
                result.append(trimmed);
            }
        }
        return result.toString();
    }
    private static String firstSentence(String text) {
        if (text == null || text.trim().isEmpty()) return "未命名信息";
        String t = text.trim();
        int cut = t.length();
        for (String mark : new String[]{"。", "！", "？", "\n", ".", "!", "?"}) {
            int i = t.indexOf(mark);
            if (i >= 0) cut = Math.min(cut, i + mark.length());
        }
        if (cut > 36) cut = 36;
        return t.substring(0, cut);
    }

    private static int headingLevel(String line) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') level++;
        return Math.max(1, Math.min(6, level));
    }

    private static int firstColon(String value) {
        int a = value.indexOf('：');
        int b = value.indexOf(':');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static String cleanMarkdown(String value) {
        if (value == null) return "";
        String v = value.trim();
        v = v.replaceAll("^>\\s*", "");
        v = v.replaceAll("`([^`]*)`", "$1");
        v = v.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");
        v = v.replaceAll("__([^_]*)__", "$1");
        v = v.replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1");
        v = v.replaceAll("\\s+", " ").trim();
        return v;
    }

    private static String importedCategory(String text) {
        String v = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (v.contains("偏好") || v.contains("喜欢") || v.contains("preference")) return "preference";
        if (v.contains("关系") || v.contains("家庭") || v.contains("朋友") || v.contains("同事")) return "relationship";
        if (v.contains("决定") || v.contains("决策") || v.contains("选择")) return "decision";
        if (v.contains("项目") || v.contains("工作") || v.contains("prd") || v.contains("app")) return "project";
        if (v.contains("风险") || v.contains("注意") || v.contains("问题")) return "risk";
        if (v.contains("设备") || v.contains("账号") || v.contains("订阅") || v.contains("资产")) return "asset";
        return "fact";
    }

    public static long[] todayRange() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis(); c.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{start, c.getTimeInMillis()};
    }
}
