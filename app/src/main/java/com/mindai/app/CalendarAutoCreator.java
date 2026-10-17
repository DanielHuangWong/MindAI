package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Applies the final local safety gate before an AI calendar candidate can reach CalendarProvider. */
final class CalendarAutoCreator {
    private static final long DUPLICATE_WINDOW = 10 * 60_000L;

    private CalendarAutoCreator() {}

    static int createSafeCandidates(Context context, Models.SummarySession session,
                                    List<Models.CalendarCandidate> candidates) {
        if (session == null || candidates == null || candidates.isEmpty()) return 0;
        if (!ComplianceSettings.canAutoWriteCalendar(context)) return 0;
        CalendarRepository repository = new CalendarRepository(context);
        if (!repository.canRead() || !repository.canWrite() || repository.defaultWritableCalendarId() <= 0) return 0;
        int created = 0;
        JSONArray committed = new JSONArray();
        for (Models.CalendarCandidate candidate : candidates) {
            if (!isStructurallySafe(candidate, System.currentTimeMillis())) continue;
            List<Models.CalendarEvent> nearby = repository.events(candidate.startAt - 86_400_000L, candidate.endAt + 86_400_000L);
            if (duplicatesOrConflicts(candidate, nearby)) continue;
            String source = candidate.sourceNotificationIds == null || candidate.sourceNotificationIds.isEmpty()
                    ? "" : "\n来源通知：" + candidate.sourceNotificationIds;
            String description = "[MindAI 自动整理]" + source +
                    (candidate.description == null || candidate.description.isEmpty() ? "" : "\n" + candidate.description);
            try {
                long eventId = repository.create(candidate.title, description, candidate.location, candidate.startAt,
                        candidate.endAt, candidate.allDay, 0);
                committed.put(new JSONObject()
                        .put("id", eventId)
                        .put("title", candidate.title)
                        .put("description", description)
                        .put("location", candidate.location == null ? "" : candidate.location)
                        .put("start_at", candidate.startAt)
                        .put("end_at", candidate.endAt)
                        .put("all_day", candidate.allDay));
                created++;
            } catch (Exception ignored) {
                // CalendarProvider is external state. A failed write must not be represented as committed.
            }
        }
        context.getSharedPreferences("mindai_processing_changes", Context.MODE_PRIVATE).edit()
                .putInt("calendar:" + session.id, created)
                .putString("calendar_items:" + session.id, committed.toString())
                .apply();
        return created;
    }

    static boolean isStructurallySafe(Models.CalendarCandidate candidate, long now) {
        return candidate != null
                && candidate.title != null && !candidate.title.trim().isEmpty()
                && candidate.userInvolved
                && candidate.confidence >= 0.92
                && candidate.startAt >= now - 5 * 60_000L
                && candidate.endAt > candidate.startAt
                && candidate.sourceNotificationIds != null && !candidate.sourceNotificationIds.trim().isEmpty();
    }

    static boolean duplicatesOrConflicts(Models.CalendarCandidate candidate, List<Models.CalendarEvent> events) {
        String wanted = normalize(candidate.title);
        for (Models.CalendarEvent event : events) {
            boolean sameTime = Math.abs(event.startAt - candidate.startAt) <= DUPLICATE_WINDOW;
            if (sameTime && normalize(event.title).equals(wanted)) return true;
            boolean overlaps = event.startAt < candidate.endAt && event.endAt > candidate.startAt;
            if (overlaps && !"free".equals(event.availability)) return true;
        }
        return false;
    }

    static int committedCalendarCount(Context context, String sessionId) {
        SharedPreferences changes = context.getSharedPreferences("mindai_processing_changes", Context.MODE_PRIVATE);
        return changes.getInt("calendar:" + (sessionId == null ? "" : sessionId), 0);
    }

    static List<Models.CalendarEvent> committedCalendarEvents(Context context, String sessionId) {
        List<Models.CalendarEvent> result = new ArrayList<>();
        if (sessionId == null || sessionId.isEmpty()) return result;
        String raw = context.getSharedPreferences("mindai_processing_changes", Context.MODE_PRIVATE)
                .getString("calendar_items:" + sessionId, "[]");
        try {
            JSONArray items = new JSONArray(raw == null ? "[]" : raw);
            for (int i = 0; i < items.length(); i++) {
                JSONObject object = items.optJSONObject(i);
                if (object == null || object.optLong("id", 0) <= 0) continue;
                Models.CalendarEvent event = new Models.CalendarEvent();
                event.id = object.optLong("id");
                event.title = object.optString("title");
                event.description = object.optString("description");
                event.location = object.optString("location");
                event.startAt = object.optLong("start_at");
                event.endAt = object.optLong("end_at");
                event.allDay = object.optBoolean("all_day");
                event.calendarName = "MindAI 自动整理";
                event.instanceKey = event.id + ":" + event.startAt;
                result.add(event);
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s，。,.：:;；_-]+", "");
    }
}
