package com.mindai.app;

import java.util.ArrayList;
import java.util.List;

public final class Models {
    private Models() {}

    public static final class NotificationItem {
        public long id;
        public String sourceKey = "";
        public String packageName = "";
        public String appName = "";
        public String title = "";
        public String content = "";
        public long postedAt;
        public long capturedAt;
        public boolean hadContentIntentAtCapture;
        public long systemRemovedAt;
        public int systemUserId;
        public String summaryState = "unsummarized";
        public String sensitivity = "normal";
    }

    public static final class SummarySession {
        public String id = "";
        public long createdAt;
        public long completedAt;
        public long rangeStart;
        public long rangeEnd;
        public int notificationCount;
        public String overview = "";
        public String keyPointsJson = "[]";
        public String status = "pending";
        public String error = "";
        public String modelInfo = "";
        public String notificationIds = "";
    }

    public static final class ImportantRecord {
        public String id = "";
        public String title = "";
        public String content = "";
        public String category = "其他";
        public double importance;
        public long eventAt;
        public long createdAt;
        public long updatedAt;
        public String sourceSummaryId = "";
        public String sourceNotificationIds = "";
        public String status = "pending";
        public boolean generatedByAi = true;
        public boolean userEdited;
        public long deletedAt;
    }

    public static final class SummaryOutput {
        public String overview = "";
        public String actualModel = "";
        public int filteredNotificationCount;
        public final List<KeyPoint> keyPoints = new ArrayList<>();
        public final List<ImportantRecord> records = new ArrayList<>();
        public final List<TodoItem> todos = new ArrayList<>();
        public final List<MemoryItem> memories = new ArrayList<>();
        public final List<ProfileFact> profileUpdates = new ArrayList<>();
        public final List<CalendarCandidate> calendarCandidates = new ArrayList<>();
        public NeedToKnow needToKnow;
    }

    public static final class CalendarCandidate {
        public String title = "";
        public String description = "";
        public String location = "";
        public long startAt;
        public long endAt;
        public boolean allDay;
        public boolean userInvolved;
        public double confidence;
        public String sourceNotificationIds = "";
    }

    public static final class KeyPoint {
        public String title = "";
        public String detail = "";
        public String sourceIds = "";
    }

    public static final class TodoItem {
        public String id = "";
        public String title = "";
        public String description = "";
        public long dueAt;
        public String priority = "medium";
        public String status = "open";
        public double confidence;
        public String sourceSummaryId = "";
        public String sourceNotificationIds = "";
        public boolean generatedByAi = true;
        public boolean userEdited;
        public long createdAt;
        public long updatedAt;
        public long deletedAt;
    }

    public static final class MemoryItem {
        public String id = "";
        public String title = "";
        public String text = "";
        public String category = "other";
        public double importance;
        public double confidence;
        public String stability = "evolving";
        public String sensitivity = "normal";
        public String status = "active";
        public String operation = "create";
        public String targetMemoryId = "";
        public String sourceSummaryId = "";
        public String sourceNotificationIds = "";
        public boolean generatedByAi = true;
        public boolean userEdited;
        public long createdAt;
        public long updatedAt;
        public long deletedAt;
    }

    public static final class ProfileFact {
        public String id = "";
        public String section = "其他";
        public String title = "";
        public String content = "";
        public double confidence;
        public String stability = "evolving";
        public String status = "active";
        public String operation = "add";
        public String targetProfileFactId = "";
        public String sourceSummaryId = "";
        public String sourceMemoryIds = "";
        public String sourceNotificationIds = "";
        public boolean generatedByAi = true;
        public boolean userEdited;
        public long lastSeenAt;
        public long createdAt;
        public long updatedAt;
        public long deletedAt;
    }

    public static final class NeedToKnow {
        public String id = "";
        public String title = "";
        public String detail = "";
        public String reason = "";
        public String urgency = "none";
        public String suggestedAction = "";
        public String sourceSummaryId = "";
        public String sourceNotificationIds = "";
        public String relatedTodoIds = "";
        public String relatedMemoryIds = "";
        public String relatedProfileFactIds = "";
        public long createdAt;
    }

    public static final class CalendarEvent {
        public long id;
        public long calendarId;
        public String instanceKey = "";
        public String calendarName = "";
        public String title = "";
        public String description = "";
        public String location = "";
        public long startAt;
        public long endAt;
        public boolean allDay;
        public boolean readOnly;
        public String availability = "busy";
    }

    public static final class CalendarSource {
        public long id;
        public String name = "";
        public String accountName = "";
        public String accountType = "";
        public int color;
        public boolean visible = true;
        public boolean writable;
    }

    public static final class AssistantBriefItem {
        public String id = "";
        public String type = "info";
        public String title = "";
        public String detail = "";
        public String reason = "";
        public String suggestedAction = "";
        public String urgency = "low";
        public String impact = "medium";
        public double rankScore;
        public double confidence;
        public long validFrom;
        public long validUntil;
        public String status = "active";
        public long snoozedUntil;
        public String sourceSummaryId = "";
        public String sourceNotificationIds = "";
        public String relatedTodoIds = "";
        public String relatedCalendarKeys = "";
        public String relatedMemoryIds = "";
        public String relatedProfileFactIds = "";
        public String feedback = "none";
        public String origin = "local";
        public long createdAt;
        public long updatedAt;
    }

    public static final class ConversationMessage {
        public String id = "";
        public String role = "assistant";
        public String content = "";
        public String actionType = "";
        public String actionObjectId = "";
        public String actionStatus = "";
        public long createdAt;
    }

    public static final class AssistantPlan {
        public String type = "answer";
        public String message = "";
        public String action = "none";
        public String targetId = "";
        public String title = "";
        public String description = "";
        public String query = "";
        public String priority = "medium";
        public String category = "other";
        public long dueAt;
        public long startAt;
        public long endAt;
        public boolean allDay;
        public boolean requiresConfirmation;
        public double confidence;
        public String actualModel = "";
    }
}
