package com.mindai.app;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import org.json.JSONObject;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the assistant request independently from an Activity so navigation and
 * configuration changes do not cancel or duplicate the request.
 */
public final class AssistantConversationManager {
    public static final String IDLE = "idle";
    public static final String THINKING = "thinking";
    public static final String RESPONDING = "responding";
    public static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";

    public interface Listener {
        void onAssistantStateChanged(Snapshot snapshot);
    }

    public static final class Snapshot {
        public final String requestId;
        public final String state;
        public final String detail;
        public final String lastAssistantMessageId;
        public final String confirmationMessage;
        public final int unreadCount;
        public final boolean minimized;
        public final boolean conversationVisible;

        Snapshot(String requestId, String state, String detail, String lastAssistantMessageId,
                 String confirmationMessage, int unreadCount, boolean minimized,
                 boolean conversationVisible) {
            this.requestId = requestId;
            this.state = state;
            this.detail = detail;
            this.lastAssistantMessageId = lastAssistantMessageId;
            this.confirmationMessage = confirmationMessage;
            this.unreadCount = unreadCount;
            this.minimized = minimized;
            this.conversationVisible = conversationVisible;
        }

        public boolean running() { return THINKING.equals(state) || RESPONDING.equals(state); }

        public boolean canStartRequest() {
            return !running() && !AWAITING_CONFIRMATION.equals(state);
        }

        public boolean showStatusPill() {
            if (conversationVisible || IDLE.equals(state)) return false;
            return !COMPLETED.equals(state) || unreadCount > 0;
        }

        public String chatTitle() {
            if (THINKING.equals(state)) return "思考中";
            if (RESPONDING.equals(state)) return "回复中";
            if (AWAITING_CONFIRMATION.equals(state)) return "需要确认";
            if (COMPLETED.equals(state)) return "回复完成";
            if (FAILED.equals(state)) return "回复失败";
            return "";
        }

        public String pillText() {
            if (THINKING.equals(state)) return "助理正在思考…";
            if (RESPONDING.equals(state)) return "正在生成回复…";
            if (AWAITING_CONFIRMATION.equals(state)) return "需要你确认一项操作";
            if (COMPLETED.equals(state)) return "助理已回复 · 查看";
            if (FAILED.equals(state)) return "回复失败 · 重试";
            return "";
        }
    }

    private static final String PREFS = "mindai_assistant_session";
    private static final String KEY_REQUEST = "request_id";
    private static final String KEY_STATE = "state";
    private static final String KEY_DETAIL = "detail";
    private static final String KEY_LAST_MESSAGE = "last_message_id";
    private static final String KEY_UNREAD = "unread_count";
    private static final String KEY_MINIMIZED = "minimized";
    private static final String KEY_PENDING_PLAN = "pending_plan";
    private static final String KEY_CONFIRMATION = "confirmation_message";

    private final Context context;
    private final AppDatabase db;
    private final CryptoManager crypto;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "mindai-assistant");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private String requestId;
    private String state;
    private String detail;
    private String lastAssistantMessageId;
    private String confirmationMessage;
    private int unreadCount;
    private boolean minimized;
    private boolean conversationVisible;
    private boolean appForeground;
    private Models.AssistantPlan pendingPlan;

    AssistantConversationManager(Context context, AppDatabase db, CryptoManager crypto) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.crypto = crypto;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        requestId = prefs.getString(KEY_REQUEST, "");
        state = prefs.getString(KEY_STATE, IDLE);
        detail = prefs.getString(KEY_DETAIL, "");
        lastAssistantMessageId = prefs.getString(KEY_LAST_MESSAGE, "");
        unreadCount = prefs.getInt(KEY_UNREAD, 0);
        minimized = prefs.getBoolean(KEY_MINIMIZED, false);
        confirmationMessage = decryptPreference(prefs.getString(KEY_CONFIRMATION, ""));
        pendingPlan = planFromJson(decryptPreference(prefs.getString(KEY_PENDING_PLAN, "")));
        if (THINKING.equals(state) || RESPONDING.equals(state)) {
            state = FAILED;
            detail = "上次回复因 App 进程中断，未自动重放任何操作";
            minimized = true;
            persistLocked();
        } else if (AWAITING_CONFIRMATION.equals(state) && pendingPlan == null) {
            state = FAILED;
            detail = "待确认操作已失效，请重新发起";
            confirmationMessage = "";
            persistLocked();
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        main.post(() -> listener.onAssistantStateChanged(snapshot()));
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public synchronized Snapshot snapshot() {
        return new Snapshot(requestId, state, detail, lastAssistantMessageId,
                confirmationMessage, unreadCount, minimized, conversationVisible);
    }

    public synchronized void setAppForeground(boolean foreground) { appForeground = foreground; }

    public void setConversationVisible(boolean visible) {
        boolean changed;
        synchronized (this) {
            changed = conversationVisible != visible || (visible && (unreadCount > 0 || minimized));
            conversationVisible = visible;
            if (visible) {
                unreadCount = 0;
                minimized = false;
            } else if (!IDLE.equals(state)) {
                minimized = true;
            }
            persistLocked();
        }
        if (visible) cancelReplyNotification();
        if (changed) dispatch();
        if (visible) scheduleCompletedStateClear();
    }

    public boolean start(String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return false;
        String thisRequest;
        synchronized (this) {
            if (!snapshot().canStartRequest()) return false;
            requestId = thisRequest = UUID.randomUUID().toString();
            state = THINKING;
            detail = "正在理解你的问题并查找相关信息";
            lastAssistantMessageId = "";
            confirmationMessage = "";
            pendingPlan = null;
            unreadCount = 0;
            persistLocked();
        }
        db.addConversationMessage("user", text, "", "", "");
        dispatch();
        main.postDelayed(() -> markResponding(thisRequest), 500L);
        executor.execute(() -> runRequest(thisRequest, text));
        return true;
    }

    public boolean confirmPendingAction() {
        final Models.AssistantPlan plan;
        final String thisRequest;
        synchronized (this) {
            if (!AWAITING_CONFIRMATION.equals(state) || pendingPlan == null) return false;
            plan = pendingPlan;
            pendingPlan = null;
            requestId = thisRequest = UUID.randomUUID().toString();
            state = THINKING;
            detail = "正在执行已确认的操作";
            confirmationMessage = "";
            persistLocked();
        }
        dispatch();
        main.postDelayed(() -> markResponding(thisRequest), 400L);
        executor.execute(() -> {
            try {
                AssistantEngine.Result result = new AssistantEngine(context).confirm(plan);
                finish(thisRequest, result.success, result.success ? "操作已经处理" : safeDetail(result.message));
            } catch (RuntimeException error) {
                recordFailure("操作失败，未修改数据");
                finish(thisRequest, false, "操作失败，未修改数据");
            }
        });
        return true;
    }

    public boolean cancelPendingAction() {
        synchronized (this) {
            if (!AWAITING_CONFIRMATION.equals(state)) return false;
            pendingPlan = null;
            confirmationMessage = "";
        }
        Models.ConversationMessage message = db.addConversationMessage("assistant", "已取消这项操作。", "none", "", "cancelled");
        finishWithMessage(requestId, true, "操作已取消", message.id);
        return true;
    }

    public void clearConversation() {
        db.clearConversation();
        synchronized (this) {
            requestId = "";
            state = IDLE;
            detail = "";
            lastAssistantMessageId = "";
            confirmationMessage = "";
            unreadCount = 0;
            minimized = false;
            pendingPlan = null;
            persistLocked();
        }
        cancelReplyNotification();
        dispatch();
    }

    private void runRequest(String thisRequest, String text) {
        try {
            AssistantEngine.Result result = new AssistantEngine(context).processRecorded(text);
            String messageId = lastAssistantMessageId();
            if (result.requiresConfirmation && result.pendingPlan != null) {
                synchronized (this) {
                    if (!thisRequest.equals(requestId)) return;
                    state = AWAITING_CONFIRMATION;
                    detail = "删除和高影响操作需要你的明确确认";
                    confirmationMessage = safeDetail(result.message);
                    pendingPlan = result.pendingPlan;
                    lastAssistantMessageId = messageId;
                    unreadCount = conversationVisible && appForeground ? 0 : 1;
                    persistLocked();
                }
                dispatch();
                return;
            }
            finishWithMessage(thisRequest, result.success,
                    result.success ? "答案已生成" : safeDetail(result.message), messageId);
        } catch (RuntimeException error) {
            android.util.Log.e("MindAiAssistant", "Assistant request failed", error);
            Models.ConversationMessage message = recordFailure("助理处理失败，请稍后重试。");
            finishWithMessage(thisRequest, false, "本次请求未成功，请稍后重试", message.id);
        }
    }

    private void markResponding(String thisRequest) {
        synchronized (this) {
            if (!thisRequest.equals(requestId) || !THINKING.equals(state)) return;
            state = RESPONDING;
            detail = "正在组织答案";
            persistLocked();
        }
        dispatch();
    }

    private void finish(String thisRequest, boolean success, String finishDetail) {
        finishWithMessage(thisRequest, success, finishDetail, lastAssistantMessageId());
    }

    private void finishWithMessage(String thisRequest, boolean success, String finishDetail, String messageId) {
        boolean notifySystem;
        synchronized (this) {
            if (thisRequest != null && !thisRequest.isEmpty() && !thisRequest.equals(requestId)) return;
            state = success ? COMPLETED : FAILED;
            detail = finishDetail;
            lastAssistantMessageId = messageId == null ? "" : messageId;
            confirmationMessage = "";
            pendingPlan = null;
            unreadCount = conversationVisible && appForeground ? 0 : 1;
            minimized = !conversationVisible;
            notifySystem = success && !appForeground && !conversationVisible && unreadCount > 0;
            persistLocked();
        }
        dispatch();
        if (notifySystem) SummaryNotificationHelper.notifyAssistantReply(context, lastAssistantMessageId);
        scheduleCompletedStateClear();
    }

    private void scheduleCompletedStateClear() {
        final String expectedRequest;
        synchronized (this) {
            if (!conversationVisible || !COMPLETED.equals(state) || unreadCount > 0) return;
            expectedRequest = requestId;
        }
        main.postDelayed(() -> {
            synchronized (AssistantConversationManager.this) {
                if (!expectedRequest.equals(requestId) || !conversationVisible || !COMPLETED.equals(state)) return;
                state = IDLE;
                detail = "";
                minimized = false;
                persistLocked();
            }
            dispatch();
        }, 1_800L);
    }

    private Models.ConversationMessage recordFailure(String text) {
        return db.addConversationMessage("assistant", text, "", "", "error");
    }

    private String lastAssistantMessageId() {
        List<Models.ConversationMessage> messages = db.conversationMessages(200);
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("assistant".equals(messages.get(i).role)) return messages.get(i).id;
        }
        return "";
    }

    private void dispatch() {
        Snapshot snapshot = snapshot();
        main.post(() -> {
            for (Listener listener : listeners) listener.onAssistantStateChanged(snapshot);
        });
    }

    private synchronized void persistLocked() {
        prefs.edit()
                .putString(KEY_REQUEST, requestId == null ? "" : requestId)
                .putString(KEY_STATE, state == null ? IDLE : state)
                .putString(KEY_DETAIL, detail == null ? "" : detail)
                .putString(KEY_LAST_MESSAGE, lastAssistantMessageId == null ? "" : lastAssistantMessageId)
                .putInt(KEY_UNREAD, unreadCount)
                .putBoolean(KEY_MINIMIZED, minimized)
                .putString(KEY_CONFIRMATION, encryptPreference(confirmationMessage))
                .putString(KEY_PENDING_PLAN, encryptPreference(planToJson(pendingPlan)))
                .apply();
    }

    private String encryptPreference(String value) {
        return value == null || value.isEmpty() ? "" : crypto.encrypt(value);
    }

    private String decryptPreference(String value) {
        if (value == null || value.isEmpty()) return "";
        String decrypted = crypto.decrypt(value);
        return decrypted.startsWith("[本地加密数据") ? "" : decrypted;
    }

    private void cancelReplyNotification() {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(SummaryNotificationHelper.ASSISTANT_REPLY_ID);
    }

    private static String safeDetail(String value) {
        return value == null || value.trim().isEmpty() ? "请求未完成，请稍后重试" : value.trim();
    }

    private static String planToJson(Models.AssistantPlan plan) {
        if (plan == null) return "";
        try {
            return new JSONObject()
                    .put("type", plan.type).put("message", plan.message).put("action", plan.action)
                    .put("targetId", plan.targetId).put("title", plan.title).put("description", plan.description)
                    .put("query", plan.query).put("priority", plan.priority).put("category", plan.category)
                    .put("dueAt", plan.dueAt).put("startAt", plan.startAt).put("endAt", plan.endAt)
                    .put("allDay", plan.allDay).put("requiresConfirmation", plan.requiresConfirmation)
                    .put("confidence", plan.confidence).put("actualModel", plan.actualModel).toString();
        } catch (Exception ignored) { return ""; }
    }

    private static Models.AssistantPlan planFromJson(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(encoded);
            Models.AssistantPlan plan = new Models.AssistantPlan();
            plan.type = json.optString("type", "answer"); plan.message = json.optString("message", "");
            plan.action = json.optString("action", "none"); plan.targetId = json.optString("targetId", "");
            plan.title = json.optString("title", ""); plan.description = json.optString("description", "");
            plan.query = json.optString("query", ""); plan.priority = json.optString("priority", "medium");
            plan.category = json.optString("category", "other"); plan.dueAt = json.optLong("dueAt", 0);
            plan.startAt = json.optLong("startAt", 0); plan.endAt = json.optLong("endAt", 0);
            plan.allDay = json.optBoolean("allDay", false);
            plan.requiresConfirmation = json.optBoolean("requiresConfirmation", true);
            plan.confidence = json.optDouble("confidence", 0); plan.actualModel = json.optString("actualModel", "");
            return plan;
        } catch (Exception ignored) { return null; }
    }

    void resetForTests() {
        synchronized (this) {
            requestId = ""; state = IDLE; detail = ""; lastAssistantMessageId = "";
            confirmationMessage = ""; unreadCount = 0; minimized = false;
            conversationVisible = false; appForeground = true; pendingPlan = null;
            prefs.edit().clear().apply();
        }
        cancelReplyNotification();
        dispatch();
    }
}
