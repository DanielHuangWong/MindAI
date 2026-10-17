package com.mindai.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("SetTextI18n")
public final class MainActivity extends Activity implements AssistantConversationManager.Listener {
    private static final class SummaryPageData {
        Models.SummarySession session;
        Models.NeedToKnow need;
        List<Models.TodoItem> todos = new ArrayList<>();
        List<Models.MemoryItem> memories = new ArrayList<>();
        List<Models.ProfileFact> profile = new ArrayList<>();
    }

    private static final class NotificationSourceTarget {
        Models.NotificationItem item;
        NotificationOpenCoordinator.Resolution resolution;

        NotificationSourceTarget(Models.NotificationItem item,
                                 NotificationOpenCoordinator.Resolution resolution) {
            this.item = item;
            this.resolution = resolution;
        }
    }

    private static final class TodoDetailData {
        Models.TodoItem todo;
        List<NotificationSourceTarget> sources = new ArrayList<>();
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService queryExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private AppDatabase db;
    private SecurePrefs secure;
    private SharedPreferences settings;
    private Runnable backAction;
    private int currentRoot = 0;
    private long selectedDayStart;
    private String notificationState = "all";
    private String notificationPackage = "";
    private String recordState = "confirmed";
    private String todoState = "open";
    private String memoryMode = "memories";
    private String memoryState = "active";
    private String profileState = "active";
    private int notificationVisibleLimit = PAGE_SIZE;
    private int recordVisibleLimit = PAGE_SIZE;
    private int todoVisibleLimit = PAGE_SIZE;
    private int memoryVisibleLimit = PAGE_SIZE;
    private long lastNavClickAt = 0;
    private volatile boolean summaryRunning;
    private volatile boolean summaryMinimized;
    private volatile int summaryPercent;
    private volatile String summaryStep = "";
    private volatile String minimizedSummaryId = "";
    private volatile String minimizedSummaryError = "";
    private int notificationLoadSeq = 0;
    private int todoLoadSeq = 0;
    private int recordLoadSeq = 0;
    private int memoryLoadSeq = 0;
    private int screenSeq = 0;
    private Runnable pendingSearch;
    private volatile boolean destroyed;
    private String pendingMarkdownExport = "";
    private String scheduleMode = "today";
    private String libraryMode = "notifications";
    private volatile boolean briefRefreshRunning;
    private volatile long lastBriefRefreshAt;
    private AssistantConversationManager assistantConversations;
    private boolean assistantChatVisible;
    private int assistantReturnRoot;
    private boolean assistantFocusInputOnOpen;
    private LinearLayout assistantStatusSlot;
    private FrameLayout assistantEntryButtonView;
    private LinearLayout assistantProgressCard;
    private TextView assistantProgressTitleView;
    private TextView assistantProgressDetailView;
    private ProgressBar assistantProgressSpinner;
    private ScrollView assistantChatScroll;
    private String restorableDetailType = "";
    private long restorableNotificationId;
    private String restorableTodoId = "";
    private String notificationReturnTodoId = "";
    private long lastExternalOpenAt;

    private static final int PAGE_SIZE = 30;
    private static final String FIXED_HEADER_TAG = "mindai_fixed_navigation_header";
    private static final int REQ_IMPORT_PROFILE_MD = 7301;
    private static final int REQ_EXPORT_PROFILE_MD = 7302;
    private static final int REQ_CALENDAR_READ = 7303;
    private static final int REQ_POST_NOTIFICATIONS = 7304;
    private static final int REQ_EXPORT_JSON = 7305;
    private static final int REQ_CALENDAR_WRITE = 7306;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.CHINA);
    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("M月d日", Locale.CHINA);

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.BG);
        root.setPadding(0, systemBarDimension("status_bar_height"), 0, 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                int topInset = insets.getSystemWindowInsetTop(); int bottomInset = insets.getSystemWindowInsetBottom();
                view.setPadding(insets.getSystemWindowInsetLeft(), topInset > 0 ? topInset : systemBarDimension("status_bar_height"),
                        insets.getSystemWindowInsetRight(), bottomInset);
            }
            return insets;
        });
        setContentView(root);
        db = MindAiApp.get().db();
        secure = MindAiApp.get().secure();
        settings = getSharedPreferences("mindai_settings", MODE_PRIVATE);
        assistantConversations = MindAiApp.get().assistantConversations();
        assistantConversations.addListener(this);
        selectedDayStart = AppDatabase.todayRange()[0];
        if (handleIntent(getIntent())) return;
        if (savedInstanceState != null && savedInstanceState.getBoolean("assistant_chat_visible", false)) {
            currentRoot = savedInstanceState.getInt("assistant_current_root", 0);
            assistantReturnRoot = savedInstanceState.getInt("assistant_return_root", currentRoot);
            showAssistantChat("", false, false);
            return;
        }
        if (savedInstanceState != null) {
            currentRoot = savedInstanceState.getInt("current_root", currentRoot);
            String detailType = savedInstanceState.getString("restorable_detail_type", "");
            if ("notification".equals(detailType)) {
                long notificationId = savedInstanceState.getLong("restorable_notification_id", 0);
                String returnTodoId = savedInstanceState.getString("notification_return_todo_id", "");
                if (notificationId > 0) { showNotificationDetail(notificationId, returnTodoId); return; }
            } else if ("todo".equals(detailType)) {
                String todoId = savedInstanceState.getString("restorable_todo_id", "");
                if (!todoId.isEmpty()) { showTodoDetail(todoId); return; }
            }
        }
        if (!settings.getBoolean("onboarding_seen", false)) showOnboarding();
        else showHome();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("assistant_chat_visible", assistantChatVisible);
        outState.putInt("assistant_current_root", currentRoot);
        outState.putInt("assistant_return_root", assistantReturnRoot);
        outState.putInt("current_root", currentRoot);
        outState.putString("restorable_detail_type", restorableDetailType);
        outState.putLong("restorable_notification_id", restorableNotificationId);
        outState.putString("restorable_todo_id", restorableTodoId);
        outState.putString("notification_return_todo_id", notificationReturnTodoId);
    }

    private int systemBarDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : getResources().getDimensionPixelSize(id);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_PROFILE_MD) importProfileMarkdown(uri);
        else if (requestCode == REQ_EXPORT_PROFILE_MD) writeProfileMarkdown(uri);
        else if (requestCode == REQ_EXPORT_JSON) writeJsonExport(uri);
    }

    @Override protected void onResume() {
        super.onResume();
        if (settings == null) return;
        assistantConversations.setAppForeground(true);
        if (settings.getBoolean("waiting_for_permission", false) && hasNotificationAccess()) {
            settings.edit().putBoolean("waiting_for_permission", false).putBoolean("onboarding_seen", true).apply();
            showCaptureScope(true);
        }
    }

    @Override protected void onPause() {
        if (assistantConversations != null) assistantConversations.setAppForeground(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        if (assistantConversations != null) {
            assistantConversations.removeListener(this);
            assistantConversations.setConversationVisible(false);
        }
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        queryExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        if (SummaryNotificationHelper.ACTION_OPEN_ASSISTANT.equals(intent.getAction())) {
            assistantReturnRoot = 0;
            showAssistantChat("", false, false);
            return true;
        }
        if (MindAiWidgetUpdater.ACTION_OPEN_ITEM.equals(intent.getAction())) {
            String type = intent.getStringExtra(MindAiWidgetUpdater.EXTRA_ITEM_TYPE);
            String id = intent.getStringExtra(MindAiWidgetUpdater.EXTRA_ITEM_ID);
            String sourceSessionId = intent.getStringExtra(MindAiWidgetUpdater.EXTRA_SOURCE_SESSION_ID);
            if (WidgetSummaryState.KIND_SUMMARY.equals(type)) {
                if (id != null && !id.isEmpty()) showSummaryResult(id); else showHome();
            } else if (WidgetSummaryState.KIND_TODO.equals(type) && id != null && db.todo(id) != null) {
                showTodoDetail(id);
            } else if (WidgetSummaryState.KIND_MEMORY.equals(type) && id != null && db.memory(id) != null) {
                memoryMode = "memories"; showMemoryDetail(id);
            } else if (WidgetSummaryState.KIND_PROFILE.equals(type) && id != null && db.profileFact(id) != null) {
                memoryMode = "profile"; showProfileDetail(id);
            } else if (WidgetSummaryState.KIND_CALENDAR.equals(type) && id != null) {
                Models.CalendarEvent event = null;
                try {
                    event = new CalendarRepository(this).event(Long.parseLong(id),
                            intent.getLongExtra(MindAiWidgetUpdater.EXTRA_EVENT_START, 0));
                } catch (Exception ignored) {}
                if (event != null) showCalendarDetail(event);
                else if (sourceSessionId != null && !sourceSessionId.isEmpty()) showSummaryResult(sourceSessionId);
                else showSchedule();
            } else if (sourceSessionId != null && !sourceSessionId.isEmpty()) {
                showSummaryResult(sourceSessionId);
            } else showHome();
            return true;
        }
        if (SummaryNotificationHelper.ACTION_OPEN_BRIEF.equals(intent.getAction())) {
            String briefId = intent.getStringExtra(SummaryNotificationHelper.EXTRA_BRIEF_ID);
            if (briefId != null && !briefId.isEmpty()) showBriefDetail(briefId); else showHome();
            return true;
        }
        if (!SummaryNotificationHelper.ACTION_OPEN_SUMMARY.equals(intent.getAction())) return false;
        String id = intent.getStringExtra(SummaryNotificationHelper.EXTRA_SUMMARY_ID);
        if (id != null && !id.isEmpty()) showSummaryResult(id);
        else if (settings.getBoolean("onboarding_seen", false)) showHome();
        else showOnboarding();
        return true;
    }

    private void maybeRequestPostNotifications() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 81);
    }

    @Override public void onBackPressed() {
        if (assistantChatVisible) {
            minimizeAssistantChat();
            return;
        }
        if (backAction != null) {
            Runnable action = backAction;
            backAction = null;
            action.run();
        } else super.onBackPressed();
    }

    @Override public void onAssistantStateChanged(AssistantConversationManager.Snapshot snapshot) {
        if (destroyed) return;
        runUi(() -> {
            if (destroyed) return;
            if (assistantChatVisible) showAssistantChat("", false, false);
            else {
                renderAssistantStatusSlot();
                renderAssistantEntryButton();
            }
        });
    }

    private void showOnboarding() {
        backAction = null;
        LinearLayout body = UiKit.vertical(this);
        body.setPadding(dp(24), dp(18), dp(24), dp(20));
        body.addView(UiKit.mono(this, "MINDAI / PRIVATE BY DESIGN", 12, UiKit.SUB));
        body.addView(UiKit.space(this, 18));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_selected);
        mark.setContentDescription("MindAI 图标");
        mark.setScaleType(ImageView.ScaleType.CENTER_CROP);
        body.addView(mark, new LinearLayout.LayoutParams(dp(88), dp(88)));
        body.addView(UiKit.space(this, 20));
        body.addView(UiKit.title(this, "重要信息\n主动出现"));
        body.addView(UiKit.space(this, 14));
        body.addView(UiKit.text(this,
                "通知与日历默认在本机保存。只有你主动使用 AI，或明确开启自动整理后，选定内容才会发送给 OpenRouter 及其模型提供商。",
                16, UiKit.SUB));
        body.addView(UiKit.space(this, 8));
        addPrinciple(body, android.R.drawable.ic_menu_today, "通知", "按日期回看");
        addPrinciple(body, android.R.drawable.ic_menu_info_details, "总结", "提取关键点");
        addPrinciple(body, android.R.drawable.ic_menu_save, "记忆", "可追溯、可编辑");
        body.addView(UiKit.space(this, 6));
        Button grant = UiKit.accentButton(this, "开启通知使用权  [→]");
        grant.setOnClickListener(v -> showNotificationAccessDisclosure());
        body.addView(grant, matchHeight(60));
        TextView skip = UiKit.text(this, "稍后", 14, UiKit.SUB);
        skip.setGravity(Gravity.CENTER); skip.setPadding(0, dp(18), 0, dp(8));
        skip.setOnClickListener(v -> { settings.edit().putBoolean("onboarding_seen", true).apply(); showHome(); });
        body.addView(skip);
        renderScroll(body, -1);
    }

    private void addPrinciple(LinearLayout parent, int icon, String title, String detail) {
        LinearLayout row = UiKit.horizontal(this);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(UiKit.icon(this, icon));
        LinearLayout text = UiKit.vertical(this); text.setPadding(dp(16), 0, 0, 0);
        text.addView(UiKit.text(this, title, 17, UiKit.TEXT));
        text.addView(UiKit.text(this, detail, 13, UiKit.SUB));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private void showNotificationAccessDisclosure() {
        String disclosure = "MindAI 需要“通知使用权”来读取你选择范围内通知的来源 App、标题、正文和时间，并把通知保存在本机，供你按日期查看、搜索和整理。\n\n" +
                "通知可能包含联系人消息、订单、财务、身份验证或其他敏感信息。推荐模式默认只采集通讯、办公、日历和邮件类 App，并默认排除验证码、银行和健康类通知。\n\n" +
                "当你手动使用 AI，或另行开启自动整理时，经过筛选的通知内容以及为判断提供的待办、日程、记忆和画像上下文会通过加密连接发送给 OpenRouter，并可能由其选择的模型提供商处理。不开启 AI 时，通知不会因此上传。\n\n" +
                "此权限不是使用手动待办、日程和本地信息库的必需条件。你可以拒绝，并可随时在“我的 → 数据与隐私”撤回同意或在系统设置关闭通知使用权。";
        new AlertDialog.Builder(this)
                .setTitle("通知数据使用说明")
                .setMessage(disclosure)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    ComplianceSettings.grantNotificationCaptureConsent(this);
                    settings.edit().putBoolean("waiting_for_permission", true).apply();
                    startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                })
                .setNegativeButton("暂不开启", null)
                .show();
    }

    private void showCaptureScope(boolean firstRun) {
        backAction = firstRun ? null : this::showSettings;
        LinearLayout body = pageBody();
        body.addView(backHeader("采集范围", firstRun ? null : this::showSettings));
        body.addView(UiKit.space(this, 26));
        body.addView(UiKit.title(this, "采集范围"));
        body.addView(UiKit.space(this, 28));

        String current = settings.getString("capture_mode", "recommended");
        addScopeChoice(body, "recommended", "推荐", "通讯 / 办公 / 日历 / 邮件", current);
        addScopeChoice(body, "all", "全部", "排除 MindAI", current);
        addScopeChoice(body, "custom", "自定义", "逐个选择", current);

        Button choose = UiKit.outlineButton(this, "应用列表");
        choose.setOnClickListener(v -> showAppPicker());
        body.addView(choose, matchHeight(54));
        body.addView(UiKit.space(this, 22));
        CheckBox excludeSensitive = checkbox("默认不保存验证码、银行和健康类通知",
                settings.getBoolean("exclude_sensitive", true));
        excludeSensitive.setOnCheckedChangeListener((b, checked) ->
                settings.edit().putBoolean("exclude_sensitive", checked).apply());
        body.addView(excludeSensitive);
        CheckBox sensitive = checkbox("对其他敏感数字进行脱敏", settings.getBoolean("redact_sensitive", true));
        sensitive.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("redact_sensitive", checked).apply());
        body.addView(sensitive);
        CheckBox ongoing = checkbox("静默通知", settings.getBoolean("capture_ongoing", false));
        ongoing.setOnCheckedChangeListener((b, checked) -> settings.edit().putBoolean("capture_ongoing", checked).apply());
        body.addView(ongoing);
        body.addView(UiKit.space(this, 28));
        Button done = UiKit.accentButton(this, firstRun ? "完成  [→]" : "保存");
        done.setOnClickListener(v -> { settings.edit().putBoolean("onboarding_seen", true).apply(); if (firstRun) showOpenRouter(true); else showSettings(); });
        body.addView(done, matchHeight(60));
        renderScroll(body, -1);
    }

    private void addScopeChoice(LinearLayout body, String mode, String title, String detail, String current) {
        LinearLayout row = UiKit.vertical(this);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        boolean active = mode.equals(current);
        row.setBackground(UiKit.box(UiKit.BG_ALT, active ? UiKit.ACCENT : UiKit.LINE, dp(16), dp(1)));
        TextView titleView = UiKit.text(this, (active ? "●  " : "○  ") + title, 18, active ? UiKit.ACCENT : UiKit.TEXT);
        row.addView(titleView);
        TextView detailView = UiKit.text(this, detail, 14, UiKit.SUB); detailView.setPadding(dp(28), dp(6), 0, 0);
        row.addView(detailView);
        row.setOnClickListener(v -> { settings.edit().putString("capture_mode", mode).apply(); showCaptureScope(backAction == null); });
        body.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(UiKit.space(this, 12));
    }

    private void showAppPicker() {
        executor.execute(() -> {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> matches = getPackageManager().queryIntentActivities(launcher, 0);
            List<ApplicationInfo> apps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo match : matches) {
                ApplicationInfo info = match.activityInfo.applicationInfo;
                if (!info.packageName.equals(getPackageName()) && seen.add(info.packageName)) apps.add(info);
            }
            apps.sort((a, b) -> appLabel(a).compareToIgnoreCase(appLabel(b)));
            String[] labels = new String[apps.size()];
            boolean[] checked = new boolean[apps.size()];
            Set<String> selected = new HashSet<>(settings.getStringSet("allowed_packages", new HashSet<>()));
            for (int i = 0; i < apps.size(); i++) { labels[i] = appLabel(apps.get(i)); checked[i] = selected.contains(apps.get(i).packageName); }
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("选择采集应用")
                    .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                        String pkg = apps.get(which).packageName;
                        if (isChecked) selected.add(pkg); else selected.remove(pkg);
                    })
                    .setPositiveButton("保存", (d, w) -> settings.edit().putStringSet("allowed_packages", selected).putString("capture_mode", "custom").apply())
                    .setNegativeButton("取消", null).show());
        });
    }

    private void showHome() {
        final int screen = beginScreenLoad();
        currentRoot = 0; backAction = null;
        LinearLayout body = pageBody();
        LinearLayout top = UiKit.horizontal(this);
        TextView pageTitle = UiKit.text(this, "助理", 28, UiKit.TEXT);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        top.addView(pageTitle, new LinearLayout.LayoutParams(0, dp(48), 1));
        top.addView(assistantEntryButton(), new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(UiKit.space(this, 4), new LinearLayout.LayoutParams(dp(4), dp(1)));
        TextView refresh = UiKit.materialIcon(this, summaryRunning ? "hourglass_empty" : "refresh", 24, UiKit.TEXT);
        refresh.setContentDescription(summaryRunning ? "正在整理通知" : "立即整理未总结通知");
        refresh.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(24), dp(1)));
        refresh.setOnClickListener(v -> refreshAssistantHome(true));
        top.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(top);
        body.addView(UiKit.space(this, 28));
        body.addView(UiKit.title(this, "现在需要知道"));
        body.addView(UiKit.space(this, 18));

        LinearLayout briefList = UiKit.vertical(this);
        briefList.addView(loadingBlock("正在整理当前最重要的信息…"));
        body.addView(briefList);
        renderAssistantHome(body);
        queryExecutor.execute(() -> {
            List<Models.AssistantBriefItem> cached = db.activeBriefItems(7);
            runUi(() -> { if (screenIsCurrent(screen)) renderBriefItems(briefList, cached); });
            scheduleBriefRefresh(briefList, screen);
        });
    }

    private void scheduleBriefRefresh(LinearLayout briefList, int screen) {
        long now = SystemClock.elapsedRealtime();
        if (briefRefreshRunning || now - lastBriefRefreshAt < 30_000L) return;
        briefRefreshRunning = true;
        backgroundExecutor.execute(() -> {
            try {
                new AssistantBriefEngine(this).refresh();
                lastBriefRefreshAt = SystemClock.elapsedRealtime();
                List<Models.AssistantBriefItem> refreshed = db.activeBriefItems(7);
                runUi(() -> { if (screenIsCurrent(screen)) renderBriefItems(briefList, refreshed); });
            } catch (Exception ignored) {
            } finally {
                briefRefreshRunning = false;
            }
        });
    }

    private void refreshAssistantHome(boolean manual) {
        if (!manual) return;
        if (summaryRunning || briefRefreshRunning) { toast("正在刷新，请稍候"); return; }
        toast("正在检查新通知…");
        queryExecutor.execute(() -> {
            int pending = db.countUnsummarized();
            if (pending <= 0) {
                briefRefreshRunning = true;
                backgroundExecutor.execute(() -> {
                    try { new AssistantBriefEngine(this).refresh(); lastBriefRefreshAt = SystemClock.elapsedRealtime(); }
                    catch (Exception ignored) {}
                    finally { briefRefreshRunning = false; }
                    runUi(() -> { toast("重要信息已更新"); showHome(); });
                });
                return;
            }
            if (!secure.configured()) {
                runUi(() -> new AlertDialog.Builder(this).setTitle("还没有连接 AI").setMessage("请先在“我的”中连接 OpenRouter。")
                        .setPositiveButton("去连接", (d, w) -> showOpenRouter(false)).setNegativeButton("取消", null).show());
                return;
            }
            if (!ComplianceSettings.hasAiDataConsent(this)) {
                runUi(() -> showAiDataDisclosure(() -> refreshAssistantHome(true)));
                return;
            }
            summaryRunning = true;
            WidgetSummaryState.markRunning(this, pending, "正在整理通知…");
            MindAiWidgetUpdater.updateAll(this);
            runUi(() -> { toast("正在整理全部未总结通知"); showHome(); });
            SummaryEngine engine = new SummaryEngine(db, secure);
            executor.execute(() -> engine.summarize(0, Long.MAX_VALUE, "", new SummaryEngine.Listener() {
                @Override public void onProgress(String step, int percent) { summaryStep = step; summaryPercent = percent; }
                @Override public void onSuccess(Models.SummarySession session) {
                    summaryRunning = false;
                    WidgetSummaryState.markDone(MainActivity.this, session, WidgetSummaryService.mostImportantText(session));
                    MindAiWidgetUpdater.updateAll(MainActivity.this);
                    runUi(() -> { toast("重要信息已更新"); showHome(); });
                }
                @Override public void onError(String message) {
                    summaryRunning = false;
                    WidgetSummaryState.markError(MainActivity.this, message);
                    MindAiWidgetUpdater.updateAll(MainActivity.this);
                    runUi(() -> { toast(message); showHome(); });
                }
            }));
        });
    }

    private void renderBriefItems(LinearLayout parent, List<Models.AssistantBriefItem> items) {
        parent.removeAllViews();
        if (items.isEmpty()) {
            LinearLayout calm = UiKit.vertical(this); calm.setPadding(dp(20), dp(22), dp(20), dp(22));
            calm.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
            TextView check = UiKit.materialIcon(this, "check_circle", 28, UiKit.GREEN);
            check.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); calm.addView(check, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView clear = UiKit.text(this, "目前没有需要立即处理的事情", 17, UiKit.TEXT); clear.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            clear.setPadding(0, dp(10), 0, 0); calm.addView(clear);
            parent.addView(calm); return;
        }
        for (int i = 0; i < items.size(); i++) {
            Models.AssistantBriefItem item = items.get(i);
            if (i == 0) {
                LinearLayout outer = UiKit.horizontal(this);
                outer.setBackground(UiKit.box(UiKit.ACCENT_SOFT, Color.rgb(202, 221, 244), dp(16), dp(1)));
                View accentLine = new View(this); accentLine.setBackgroundColor(UiKit.ACCENT);
                outer.addView(accentLine, new LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT));
                LinearLayout card = UiKit.vertical(this); card.setPadding(dp(16), dp(18), dp(18), dp(16));
                card.setOnClickListener(v -> showBriefDetail(item.id));

                LinearLayout meta = UiKit.horizontal(this);
                TextView icon = UiKit.materialIcon(this, iconForBrief(item.type), 21, UiKit.ACCENT);
                icon.setBackground(UiKit.box(Color.rgb(218, 234, 255), Color.rgb(202, 221, 244), dp(22), dp(1)));
                meta.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout metaCopy = UiKit.vertical(this); metaCopy.setPadding(dp(12), 0, 0, 0);
                TextView kind = UiKit.text(this, urgencyName(item.urgency) + " · " + briefTimeHint(item), 13, urgencyColor(item.urgency));
                kind.setTypeface(Typeface.DEFAULT, Typeface.BOLD); metaCopy.addView(kind);
                TextView sourceTop = UiKit.text(this, "来源 · " + briefSourceName(item), 12, UiKit.SUB); sourceTop.setPadding(0, dp(3), 0, 0); metaCopy.addView(sourceTop);
                meta.addView(metaCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                card.addView(meta);

                TextView title = UiKit.text(this, item.title, 18, UiKit.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                title.setMaxLines(2); title.setPadding(0, dp(14), 0, 0); card.addView(title);
                if (!item.detail.isEmpty()) {
                    TextView d = UiKit.text(this, item.detail, 14, UiKit.SUB); d.setMaxLines(3); d.setPadding(0, dp(10), 0, 0); card.addView(d);
                }
                if (!item.reason.isEmpty() && !item.reason.equals(item.detail)) {
                    TextView reason = UiKit.text(this, "重要原因：" + item.reason, 13, UiKit.TEXT);
                    reason.setMaxLines(2); reason.setPadding(0, dp(12), 0, 0); card.addView(reason);
                }
                View divider = UiKit.line(this); LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)); dividerParams.topMargin = dp(14); card.addView(divider, dividerParams);
                String updated = item.updatedAt > 0 ? " · " + TIME.format(new Date(item.updatedAt)) + " 更新" : "";
                TextView source = UiKit.text(this, "来源：" + briefSourceName(item) + updated, 12, UiKit.SUB); source.setPadding(0, dp(11), 0, 0); card.addView(source);
                LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(16), 0, 0);
                Button open = UiKit.accentButton(this, primaryBriefAction(item)); open.setOnClickListener(v -> showBriefDetail(item.id));
                actions.addView(open, new LinearLayout.LayoutParams(0, dp(46), 1.4f));
                actions.addView(UiKit.space(this, 8), new LinearLayout.LayoutParams(dp(8), dp(1)));
                Button later = UiKit.outlineButton(this, "稍后"); later.setOnClickListener(v -> showSnoozeChoices(item.id));
                actions.addView(later, new LinearLayout.LayoutParams(0, dp(46), .8f)); card.addView(actions);
                outer.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); parent.addView(outer);
            } else {
                if (i == 1) {
                    LinearLayout group = UiKit.vertical(this); group.setId(View.generateViewId());
                    group.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
                    parent.addView(UiKit.space(this, 12)); parent.addView(group);
                }
                LinearLayout group = (LinearLayout) parent.getChildAt(parent.getChildCount() - 1);
                LinearLayout row = UiKit.horizontal(this); row.setPadding(dp(16), dp(15), dp(12), dp(15));
                TextView icon = UiKit.materialIcon(this, iconForBrief(item.type), 21, UiKit.ACCENT);
                icon.setBackground(UiKit.box(UiKit.ACCENT_SOFT, UiKit.ACCENT_SOFT, dp(20), 0)); row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(13), 0, dp(8), 0);
                TextView title = UiKit.text(this, item.title, 16, UiKit.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setMaxLines(1); copy.addView(title);
                TextView detail = UiKit.text(this, item.detail.isEmpty() ? item.reason : item.detail, 13, UiKit.SUB); detail.setMaxLines(1); detail.setPadding(0, dp(4), 0, 0); copy.addView(detail);
                row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(UiKit.materialIcon(this, "chevron_right", 21, UiKit.SUB), new LinearLayout.LayoutParams(dp(30), dp(40)));
                row.setOnClickListener(v -> showBriefDetail(item.id));
                if (i > 1) group.addView(UiKit.line(this)); group.addView(row);
            }
        }
    }

    private String iconForBrief(String type) {
        if ("calendar".equals(type) || "conflict".equals(type)) return "event";
        if ("todo".equals(type) || "overdue".equals(type) || "action".equals(type)) return "check_circle_outline";
        if ("risk".equals(type)) return "warning_amber";
        return "notifications_none";
    }

    private String urgencyName(String urgency) {
        if ("critical".equals(urgency)) return "紧急";
        if ("high".equals(urgency)) return "重要";
        if ("medium".equals(urgency)) return "需关注";
        return "提示";
    }

    private String briefTimeHint(Models.AssistantBriefItem item) {
        long remaining = item.validUntil > System.currentTimeMillis() ? item.validUntil - System.currentTimeMillis() : 0;
        if (remaining > 0 && remaining <= 3_600_000L) return "1 小时内";
        if (remaining > 0 && remaining <= 2 * 3_600_000L) return "2 小时内";
        return briefTypeName(item.type);
    }

    private String briefSourceName(Models.AssistantBriefItem item) {
        if (!item.relatedCalendarKeys.isEmpty() || "calendar".equals(item.type) || "schedule".equals(item.type)) return "日程";
        if (!item.relatedTodoIds.isEmpty() || "todo".equals(item.type) || "overdue".equals(item.type)) return "待办";
        return item.origin.equals("ai") ? "智能整理" : "助理判断";
    }

    private String primaryBriefAction(Models.AssistantBriefItem item) {
        if (!item.relatedTodoIds.isEmpty() || "todo".equals(item.type)) return "查看待办";
        if (!item.relatedCalendarKeys.isEmpty() || "calendar".equals(item.type)) return "查看日程";
        return "查看详情";
    }

    private void showSchedule() {
        if ("todo".equals(scheduleMode)) { showTodos(); return; }
        clearRestorableDetail();
        final int screen = beginScreenLoad();
        currentRoot = 1; backAction = null;
        LinearLayout body = pageBody();
        LinearLayout heading = UiKit.horizontal(this);
        heading.addView(UiKit.title(this, "日程"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = UiKit.materialIcon(this, "add", 30, UiKit.ACCENT); add.setContentDescription("新建日程或待办"); add.setOnClickListener(v -> showCreateMenu());
        heading.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48))); body.addView(heading);
        body.addView(UiKit.space(this, 16)); addScheduleTabs(body, scheduleMode, false); body.addView(UiKit.space(this, 24));
        if ("month".equals(scheduleMode)) renderMonth(body, screen); else renderTodaySchedule(body, screen);
        renderScroll(body, 1);
    }

    private void addScheduleTabs(LinearLayout body, String active, boolean compact) {
        LinearLayout tabs = UiKit.horizontal(this);
        String[] values = {"today", "month", "todo"};
        String[] labels = {"今天", "月历", "待办"};
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            TextView tab = UiKit.chip(this, labels[i], value.equals(active));
            tab.setOnClickListener(v -> { scheduleMode = value; showSchedule(); });
            tabs.addView(tab); if (i < values.length - 1) tabs.addView(spaceWide(8));
        }
        body.addView(tabs);
    }

    private void renderTodaySchedule(LinearLayout body, int screen) {
        LinearLayout date = UiKit.horizontal(this);
        TextView previous = UiKit.materialIcon(this, "chevron_left", 28, UiKit.SUB);
        previous.setOnClickListener(v -> { selectedDayStart -= 86_400_000L; showSchedule(); });
        date.addView(previous, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView selected = UiKit.text(this, new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(new Date(selectedDayStart)), 20, UiKit.TEXT);
        selected.setTypeface(Typeface.DEFAULT, Typeface.BOLD); selected.setGravity(Gravity.CENTER); selected.setOnClickListener(v -> pickDate(selectedDayStart, chosen -> { selectedDayStart = chosen; showSchedule(); }));
        date.addView(selected, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView next = UiKit.materialIcon(this, "chevron_right", 28, UiKit.SUB);
        next.setOnClickListener(v -> { selectedDayStart += 86_400_000L; showSchedule(); }); date.addView(next, new LinearLayout.LayoutParams(dp(44), dp(44)));
        body.addView(date); body.addView(UiKit.space(this, 22));
        if (!hasCalendarRead()) {
            LinearLayout permission = UiKit.vertical(this); permission.setPadding(dp(18), dp(18), dp(18), dp(18));
            permission.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
            permission.addView(UiKit.text(this, "连接本地日历", 18, UiKit.TEXT));
            permission.addView(UiKit.text(this, "MindAI 只在本机读取日程，用于冲突识别和重要提醒。", 13, UiKit.SUB));
            Button grant = UiKit.accentButton(this, "授权读取日历"); grant.setOnClickListener(v -> requestCalendarReadPermission());
            LinearLayout.LayoutParams gp = matchHeight(54); gp.topMargin = dp(18); permission.addView(grant, gp); body.addView(permission);
        } else {
            LinearLayout list = UiKit.vertical(this); list.addView(loadingBlock("正在读取本地日历…")); body.addView(list);
            loadCalendarInto(list, selectedDayStart, selectedDayStart + 86_400_000L, 50, screen);
        }
        body.addView(UiKit.space(this, 24));
        LinearLayout todoHeading = UiKit.horizontal(this);
        todoHeading.addView(UiKit.section(this, "当天待办"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView all = UiKit.text(this, "全部待办  ›", 14, UiKit.SUB); all.setOnClickListener(v -> { scheduleMode = "todo"; showSchedule(); }); todoHeading.addView(all); body.addView(todoHeading);
        LinearLayout dayTodos = UiKit.vertical(this);
        dayTodos.addView(loadingBlock("正在读取当天待办…"));
        body.addView(dayTodos);
        loadTodosForDayInto(dayTodos, selectedDayStart, screen);
    }

    private void renderMonth(LinearLayout body, int screen) {
        Calendar month = Calendar.getInstance(); month.setTimeInMillis(selectedDayStart); month.set(Calendar.DAY_OF_MONTH, 1);
        LinearLayout header = UiKit.horizontal(this);
        TextView prev = UiKit.materialIcon(this, "chevron_left", 28, UiKit.SUB); prev.setOnClickListener(v -> { Calendar c = Calendar.getInstance(); c.setTimeInMillis(selectedDayStart); c.add(Calendar.MONTH, -1); selectedDayStart = dayStart(c.getTimeInMillis()); showSchedule(); });
        header.addView(prev, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = UiKit.text(this, new SimpleDateFormat("yyyy年 M月", Locale.CHINA).format(month.getTime()), 22, UiKit.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setGravity(Gravity.CENTER); header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView next = UiKit.materialIcon(this, "chevron_right", 28, UiKit.SUB); next.setOnClickListener(v -> { Calendar c = Calendar.getInstance(); c.setTimeInMillis(selectedDayStart); c.add(Calendar.MONTH, 1); selectedDayStart = dayStart(c.getTimeInMillis()); showSchedule(); });
        header.addView(next, new LinearLayout.LayoutParams(dp(44), dp(44))); body.addView(header); body.addView(UiKit.space(this, 18));
        GridLayout grid = new GridLayout(this); grid.setColumnCount(7); grid.setUseDefaultMargins(false);
        String[] weekdays = {"一", "二", "三", "四", "五", "六", "日"};
        for (String weekday : weekdays) {
            TextView view = UiKit.mono(this, weekday, 12, UiKit.SUB); view.setGravity(Gravity.CENTER);
            grid.addView(view, gridCell());
        }
        int first = month.get(Calendar.DAY_OF_WEEK); int offset = (first + 5) % 7;
        for (int i = 0; i < offset; i++) grid.addView(new Space(this), gridCell());
        int days = month.getActualMaximum(Calendar.DAY_OF_MONTH);
        for (int day = 1; day <= days; day++) {
            Calendar value = (Calendar) month.clone(); value.set(Calendar.DAY_OF_MONTH, day); long start = dayStart(value.getTimeInMillis());
            boolean selected = start == selectedDayStart; boolean today = start == AppDatabase.todayRange()[0];
            TextView cell = UiKit.text(this, String.valueOf(day), 15, selected ? Color.WHITE : today ? UiKit.ACCENT : UiKit.TEXT); cell.setGravity(Gravity.CENTER);
            if (selected) cell.setBackground(UiKit.box(UiKit.ACCENT, UiKit.ACCENT, dp(24), 0));
            cell.setOnClickListener(v -> { selectedDayStart = start; scheduleMode = "month"; showSchedule(); }); grid.addView(cell, gridCell());
        }
        body.addView(grid); body.addView(UiKit.space(this, 24));
        renderMonthDayContents(body, screen);
    }

    private void renderMonthDayContents(LinearLayout body, int screen) {
        TextView dayTitle = UiKit.text(this, new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(new Date(selectedDayStart)), 20, UiKit.TEXT);
        dayTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); body.addView(dayTitle); body.addView(UiKit.space(this, 12));
        LinearLayout agenda = UiKit.vertical(this); agenda.setPadding(dp(16), dp(4), dp(16), dp(4));
        agenda.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
        body.addView(agenda);
        if (!hasCalendarRead()) {
            TextView grant = UiKit.text(this, "授权本地日历后，可在这里查看当日日程。", 14, UiKit.SUB);
            grant.setPadding(0, dp(16), 0, dp(16)); grant.setOnClickListener(v -> requestCalendarReadPermission()); agenda.addView(grant);
        } else {
            agenda.addView(loadingBlock("正在读取当日日程…"));
            loadCalendarInto(agenda, selectedDayStart, selectedDayStart + 86_400_000L, 50, screen);
        }
        LinearLayout todoBox = UiKit.vertical(this); todoBox.setPadding(dp(16), dp(4), dp(16), dp(4));
        todoBox.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); tp.topMargin = dp(10);
        body.addView(todoBox, tp);
        todoBox.addView(loadingBlock("正在读取当天待办…"));
        loadTodosForDayInto(todoBox, selectedDayStart, screen);
    }

    private GridLayout.LayoutParams gridCell() {
        GridLayout.LayoutParams p = new GridLayout.LayoutParams(); p.width = 0; p.height = dp(52); p.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f); return p;
    }

    private void showLibrary() {
        if ("memory".equals(libraryMode)) { memoryMode = "memories"; showMemoryHub(); return; }
        if ("profile".equals(libraryMode)) { memoryMode = "profile"; showMemoryHub(); return; }
        if ("processing".equals(libraryMode)) { showProcessingLibrary(); return; }
        showNotifications();
    }

    private void addLibraryTabs(LinearLayout body, String active, boolean compact) {
        LinearLayout tabs = UiKit.horizontal(this);
        String[] values = {"notifications", "processing", "memory", "profile"};
        String[] labels = {"通知", "整理", "记忆", "画像"};
        for (int i = 0; i < values.length; i++) {
            String value = values[i]; TextView tab = UiKit.chip(this, labels[i], value.equals(active));
            tab.setPadding(dp(11), dp(9), dp(11), dp(9)); tab.setOnClickListener(v -> { libraryMode = value; showLibrary(); });
            tabs.addView(tab); if (i < values.length - 1) tabs.addView(spaceWide(6));
        }
        body.addView(tabs);
    }

    private void showProcessingLibrary() {
        final int screen = beginScreenLoad();
        currentRoot = 2; backAction = null;
        LinearLayout body = pageBody(); body.addView(UiKit.title(this, "信息库")); body.addView(UiKit.space(this, 16)); addLibraryTabs(body, "processing", false);
        body.addView(UiKit.space(this, 24));
        LinearLayout state = UiKit.vertical(this); state.setPadding(dp(18), dp(16), dp(18), dp(16)); state.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
        state.addView(UiKit.mono(this, settings.getBoolean("auto_processing_running", false) ? "PROCESSING  /  LIVE" : "AUTO  /  " + (settings.getBoolean("auto_processing_enabled", false) ? "ON" : "OFF"), 11, UiKit.ACCENT));
        TextView pendingText = UiKit.text(this, "正在检查待整理通知…", 20, UiKit.TEXT);
        state.addView(pendingText);
        String last = settings.getLong("auto_last_success", 0) == 0 ? "尚无自动整理记录" : "上次成功：" + DATE_TIME.format(new Date(settings.getLong("auto_last_success", 0)));
        state.addView(UiKit.text(this, last, 13, UiKit.SUB)); body.addView(state);
        Button run = UiKit.accentButton(this, "立即整理全部未总结通知"); run.setEnabled(false); run.setOnClickListener(v -> showSummaryScope()); LinearLayout.LayoutParams p = matchHeight(56); p.topMargin = dp(14); body.addView(run, p);
        body.addView(UiKit.space(this, 26)); body.addView(UiKit.section(this, "整理历史"));
        LinearLayout history = UiKit.vertical(this); history.addView(loadingBlock("正在加载整理历史…")); body.addView(history);
        renderScroll(body, 2);
        queryExecutor.execute(() -> {
            int pending = db.countUnsummarized();
            List<Models.SummarySession> sessions = db.sessions();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                pendingText.setText(pending + " 条通知等待整理");
                run.setEnabled(pending > 0);
                run.setVisibility(pending > 0 ? View.VISIBLE : View.GONE);
                history.removeAllViews();
                if (sessions.isEmpty()) history.addView(emptyBlock("还没有整理记录", "收到通知后会按设置频率自动整理，也可以手动触发。"));
                else for (int i = 0; i < Math.min(PAGE_SIZE, sessions.size()); i++) addSessionRow(history, sessions.get(i));
            });
        });
    }

    private void showBriefDetail(String id) {
        final int screen = beginScreenLoad();
        backAction = this::showHome;
        LinearLayout loading = pageBody(); loading.addView(backHeader("重要信息", this::showHome));
        loading.addView(UiKit.space(this, 20)); loading.addView(loadingBlock("正在读取重要信息…")); renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            Models.AssistantBriefItem item = db.briefItem(id);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (item == null) { showHome(); return; }
                renderBriefDetail(item);
            });
        });
    }

    private void renderBriefDetail(Models.AssistantBriefItem item) {
        String id = item.id;
        backAction = this::showHome;
        LinearLayout body = pageBody(); body.addView(backHeader("重要信息", this::showHome)); body.addView(UiKit.space(this, 24));
        body.addView(UiKit.mono(this, briefTypeName(item.type) + "  /  " + item.urgency.toUpperCase(Locale.ROOT), 11, urgencyColor(item.urgency)));
        body.addView(UiKit.space(this, 14)); body.addView(UiKit.title(this, item.title));
        if (!item.detail.isEmpty()) { TextView detail = UiKit.text(this, item.detail, 18, UiKit.TEXT); detail.setPadding(0, dp(18), 0, 0); body.addView(detail); }
        body.addView(UiKit.space(this, 26));
        LinearLayout why = UiKit.vertical(this); why.setPadding(dp(18), dp(16), dp(18), dp(16)); why.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
        why.addView(UiKit.mono(this, "WHY NOW", 11, UiKit.ACCENT)); why.addView(UiKit.space(this, 10)); why.addView(UiKit.text(this, item.reason, 15, UiKit.TEXT));
        if (!item.suggestedAction.isEmpty()) why.addView(UiKit.text(this, "\n建议：" + item.suggestedAction, 14, UiKit.SUB)); body.addView(why);
        body.addView(UiKit.space(this, 24));
        if (!item.relatedTodoIds.isEmpty()) {
            Button todo = UiKit.accentButton(this, "查看相关待办"); todo.setOnClickListener(v -> showTodoDetail(item.relatedTodoIds.split(",")[0])); body.addView(todo, matchHeight(56));
        } else if (!item.relatedCalendarKeys.isEmpty()) {
            Button calendar = UiKit.accentButton(this, "查看相关日程"); calendar.setOnClickListener(v -> { scheduleMode = "today"; if (item.validUntil > 0) selectedDayStart = dayStart(item.validUntil); showSchedule(); }); body.addView(calendar, matchHeight(56));
        } else if (!item.sourceSummaryId.isEmpty()) {
            Button summary = UiKit.accentButton(this, "查看来源整理"); summary.setOnClickListener(v -> showSummaryResult(item.sourceSummaryId)); body.addView(summary, matchHeight(56));
        }
        LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
        Button done = UiKit.outlineButton(this, "已处理"); done.setOnClickListener(v -> runDatabaseAction("正在更新…", () -> db.setBriefStatus(id, "resolved", 0), this::showHome)); actions.addView(done, new LinearLayout.LayoutParams(0, dp(52), 1)); actions.addView(spaceWide(10));
        Button later = UiKit.outlineButton(this, "稍后提醒"); later.setOnClickListener(v -> showSnoozeChoices(id)); actions.addView(later, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(actions);
        LinearLayout feedback = UiKit.horizontal(this); feedback.setPadding(0, dp(24), 0, 0);
        TextView useful = UiKit.text(this, "有用", 13, "useful".equals(item.feedback) ? UiKit.ACCENT : UiKit.SUB); useful.setOnClickListener(v -> runDatabaseAction("正在记录反馈…", () -> db.setBriefFeedback(id, "useful"), () -> showBriefDetail(id)));
        TextView notUseful = UiKit.text(this, "不相关", 13, "not_useful".equals(item.feedback) ? UiKit.RED : UiKit.SUB); notUseful.setGravity(Gravity.CENTER); notUseful.setOnClickListener(v -> runDatabaseAction("正在记录反馈…", () -> { db.setBriefFeedback(id, "not_useful"); db.setBriefStatus(id, "dismissed", 0); }, this::showHome));
        TextView report = UiKit.text(this, "举报 AI 内容", 13, UiKit.RED); report.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        report.setOnClickListener(v -> showAiReportDialog("assistant_brief", item.id,
                item.title + "\n" + item.detail + "\n" + item.reason + "\n" + item.suggestedAction));
        feedback.addView(useful, new LinearLayout.LayoutParams(0, dp(42), 1));
        feedback.addView(notUseful, new LinearLayout.LayoutParams(0, dp(42), 1));
        feedback.addView(report, new LinearLayout.LayoutParams(0, dp(42), 1));
        body.addView(feedback);
        renderScroll(body, -1);
    }

    private void showSnoozeChoices(String id) {
        String[] labels = {"1 小时后", "今晚 20:00", "明天 09:00"};
        new AlertDialog.Builder(this).setTitle("稍后提醒").setItems(labels, (d, which) -> {
            long now = System.currentTimeMillis(); long until;
            if (which == 0) until = now + 3_600_000L;
            else { Calendar c = Calendar.getInstance(); c.setTimeInMillis(now); if (which == 1) { c.set(Calendar.HOUR_OF_DAY, 20); c.set(Calendar.MINUTE, 0); if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1); } else { c.add(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 9); c.set(Calendar.MINUTE, 0); } c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); until = c.getTimeInMillis(); }
            long snoozeUntil = until;
            runDatabaseAction("正在设置提醒…", () -> db.setBriefStatus(id, "snoozed", snoozeUntil), this::showHome);
        }).show();
    }

    private void openAssistantChat(String prefill, boolean sendImmediately, boolean focusInput) {
        if (!assistantChatVisible) assistantReturnRoot = currentRoot;
        assistantFocusInputOnOpen = focusInput;
        showAssistantChat(prefill, sendImmediately, focusInput);
    }

    private void showAssistantChat(String prefill, boolean sendImmediately) {
        openAssistantChat(prefill, sendImmediately, false);
    }

    private void showAssistantChat(String prefill, boolean sendImmediately, boolean focusInput) {
        final int screen = beginScreenLoad();
        assistantChatVisible = true;
        assistantConversations.setConversationVisible(true);
        backAction = this::minimizeAssistantChat;
        AssistantConversationManager.Snapshot snapshot = assistantConversations.snapshot();
        LinearLayout body = pageBody();
        body.addView(assistantChatHeader(snapshot));
        body.addView(UiKit.space(this, 18));
        LinearLayout messagesBox = UiKit.vertical(this);
        messagesBox.addView(loadingBlock("正在加载最近对话…"));
        body.addView(messagesBox);

        LinearLayout composerArea = UiKit.vertical(this); composerArea.setBackgroundColor(UiKit.BG);
        composerArea.addView(UiKit.line(this));
        LinearLayout composerWrap = UiKit.horizontal(this); composerWrap.setPadding(dp(16), dp(9), dp(16), dp(5));
        LinearLayout composer = UiKit.horizontal(this); composer.setPadding(dp(4), 0, dp(5), 0);
        composer.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(28), dp(1)));
        boolean canSend = snapshot.canStartRequest();
        EditText input = new EditText(this); input.setHint(canSend ? "输入查询或操作…" : snapshot.chatTitle() + "…");
        input.setHintTextColor(UiKit.SUB); input.setTextColor(UiKit.TEXT); input.setTextSize(15); input.setSingleLine(true);
        input.setBackgroundColor(Color.TRANSPARENT); input.setPadding(dp(14), 0, dp(8), 0); input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setText(sendImmediately ? "" : prefill == null ? "" : prefill); input.setEnabled(canSend);
        Runnable send = () -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty() && processAssistant(value)) input.setText("");
        };
        input.setOnEditorActionListener((v, actionId, event) -> { if (actionId == EditorInfo.IME_ACTION_SEND) { send.run(); return true; } return false; });
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView sendIcon = UiKit.materialIcon(this, "arrow_upward", 22, Color.WHITE); sendIcon.setContentDescription("发送消息");
        sendIcon.setBackground(UiKit.box(canSend ? UiKit.ACCENT : UiKit.SUB, canSend ? UiKit.ACCENT : UiKit.SUB, dp(22), 0));
        sendIcon.setEnabled(canSend); sendIcon.setAlpha(canSend ? 1f : .55f); sendIcon.setOnClickListener(v -> send.run());
        composer.addView(sendIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        composerWrap.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        composerArea.addView(composerWrap);
        TextView note = UiKit.text(this, "删除操作会先确认 · 日程操作使用系统日历权限", 11, UiKit.SUB);
        note.setPadding(dp(22), 0, dp(22), dp(8)); composerArea.addView(note);
        renderAssistantChatPage(body, composerArea);
        queryExecutor.execute(() -> {
            List<Models.ConversationMessage> messages = db.conversationMessages(50);
            runUi(() -> {
                if (!screenIsCurrent(screen) || !assistantChatVisible) return;
                renderAssistantMessages(messagesBox, messages, assistantConversations.snapshot());
            });
        });
        if ((focusInput || assistantFocusInputOnOpen) && canSend) {
            assistantFocusInputOnOpen = false;
            input.post(() -> {
                input.requestFocus();
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            });
        }
        if (sendImmediately && prefill != null && !prefill.trim().isEmpty()) {
            String message = prefill.trim();
            mainHandler.post(() -> processAssistant(message));
        }
    }

    private LinearLayout assistantChatHeader(AssistantConversationManager.Snapshot snapshot) {
        LinearLayout header = UiKit.horizontal(this); header.setTag(FIXED_HEADER_TAG);
        TextView minimize = UiKit.materialIcon(this, "keyboard_arrow_down", 30, UiKit.TEXT);
        minimize.setContentDescription("最小化对话"); minimize.setOnClickListener(v -> minimizeAssistantChat());
        header.addView(minimize, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout title = UiKit.vertical(this); title.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = UiKit.text(this, "MindAI 助理", 19, UiKit.TEXT); label.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.addView(label);
        String stateText = snapshot.chatTitle().isEmpty() ? "随时可以问我" : snapshot.chatTitle();
        TextView state = UiKit.text(this, stateText, 11, snapshot.running() ? UiKit.ACCENT : UiKit.SUB); state.setPadding(0, dp(2), 0, 0); title.addView(state);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView clear = UiKit.text(this, "清空", 13, UiKit.SUB); clear.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        clear.setEnabled(snapshot.canStartRequest()); clear.setAlpha(snapshot.canStartRequest() ? 1f : .45f);
        clear.setOnClickListener(v -> confirm("清空对话？", "待办、日程、记忆和画像不会被删除。", () -> {
            assistantConversations.clearConversation(); showAssistantChat("", false, false);
        }));
        header.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(52)));
        return header;
    }

    private void renderAssistantMessages(LinearLayout messagesBox, List<Models.ConversationMessage> messages,
                                         AssistantConversationManager.Snapshot snapshot) {
        messagesBox.removeAllViews();
        for (Models.ConversationMessage message : messages) addChatBubble(messagesBox, message);
        if (!AssistantConversationManager.IDLE.equals(snapshot.state)) addAssistantProgress(messagesBox, snapshot);
        if (messages.isEmpty() && AssistantConversationManager.IDLE.equals(snapshot.state)) {
            messagesBox.addView(UiKit.title(this, "我能帮你处理什么？")); messagesBox.addView(UiKit.space(this, 18));
            String[] examples = {"今天最重要的事情是什么？", "明天下午三点提醒我提交周报", "查一下最近关于旅行的记忆"};
            for (String example : examples) {
                TextView chip = UiKit.chip(this, example, false); chip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                chip.setOnClickListener(v -> processAssistant(example)); messagesBox.addView(chip, matchHeight(48)); messagesBox.addView(UiKit.space(this, 8));
            }
        }
        scrollAssistantToBottom();
    }

    private void addChatBubble(LinearLayout body, Models.ConversationMessage message) {
        boolean user = "user".equals(message.role);
        LinearLayout wrap = UiKit.horizontal(this); wrap.setGravity(user ? Gravity.END : Gravity.START); wrap.setPadding(user ? dp(42) : 0, dp(6), user ? 0 : dp(42), dp(6));
        TextView bubble = UiKit.text(this, message.content, 15, user ? Color.WHITE : UiKit.TEXT); bubble.setPadding(dp(15), dp(13), dp(15), dp(13));
        bubble.setBackground(UiKit.box(user ? UiKit.ACCENT : UiKit.BG_ALT, user ? UiKit.ACCENT : UiKit.LINE, dp(18), dp(1)));
        if (user) {
            wrap.addView(bubble);
        } else {
            LinearLayout content = UiKit.vertical(this);
            content.addView(bubble);
            LinearLayout meta = UiKit.horizontal(this); meta.setPadding(dp(8), dp(5), dp(8), 0);
            meta.addView(UiKit.text(this, "AI 生成 · 可能不准确", 10, UiKit.SUB),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView report = UiKit.text(this, "举报", 11, UiKit.RED);
            report.setGravity(Gravity.END);
            report.setContentDescription("举报这条 AI 回复");
            report.setOnClickListener(v -> showAiReportDialog(
                    "assistant_message", message.id, message.content));
            meta.addView(report, new LinearLayout.LayoutParams(dp(48), dp(30)));
            content.addView(meta);
            wrap.addView(content);
        }
        body.addView(wrap);
    }

    private void showAiReportDialog(String contentType, String contentId, String content) {
        new AlertDialog.Builder(this)
                .setTitle("举报 AI 内容")
                .setMessage("请选择原因。举报只包含这段 AI 输出和原因，不会自动附带原始通知；内容可能含敏感信息。")
                .setItems(AiContentReportManager.REASON_LABELS, (dialog, which) ->
                        submitAiReport(contentType, contentId, content,
                                AiContentReportManager.REASON_LABELS[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void submitAiReport(String contentType, String contentId, String content, String reason) {
        toast("正在提交举报…");
        backgroundExecutor.execute(() -> {
            AiContentReportManager.Result result =
                    new AiContentReportManager(this).submit(contentType, contentId, content, reason);
            runUi(() -> {
                if (result.delivered) {
                    toast("举报已提交 · 编号 " + result.reportId.substring(0, 8));
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("举报已安全排队")
                            .setMessage("当前构建尚未连接开发者举报服务，举报已加密保存在本机，未声称已送达。正式发布构建配置 HTTPS 举报端点后会自动重试。\n\n本机编号：" + result.reportId.substring(0, 8))
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    private void addAssistantProgress(LinearLayout body, AssistantConversationManager.Snapshot snapshot) {
        LinearLayout card = UiKit.horizontal(this); card.setPadding(dp(14), dp(12), dp(14), dp(12));
        int border = AssistantConversationManager.FAILED.equals(snapshot.state) ? UiKit.RED : UiKit.LINE;
        card.setBackground(UiKit.box(UiKit.ACCENT_SOFT, border, dp(18), dp(1)));
        ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        if (snapshot.running()) card.addView(spinner, new LinearLayout.LayoutParams(dp(26), dp(26)));
        else {
            String icon = AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state) ? "help_outline"
                    : AssistantConversationManager.FAILED.equals(snapshot.state) ? "error_outline" : "check_circle";
            int color = AssistantConversationManager.FAILED.equals(snapshot.state) ? UiKit.RED
                    : AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state) ? Color.rgb(163, 93, 0) : UiKit.GREEN;
            card.addView(UiKit.materialIcon(this, icon, 25, color), new LinearLayout.LayoutParams(dp(26), dp(26)));
        }
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(12), 0, 0, 0);
        TextView title = UiKit.text(this, snapshot.chatTitle(), 15, UiKit.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); copy.addView(title);
        String detailText = AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state)
                && !snapshot.confirmationMessage.isEmpty() ? snapshot.confirmationMessage : snapshot.detail;
        TextView detail = UiKit.text(this, detailText, 12, UiKit.SUB); detail.setPadding(0, dp(4), 0, 0); copy.addView(detail);
        if (AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state)) {
            LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
            Button confirmAction = UiKit.accentButton(this, "确认"); confirmAction.setOnClickListener(v -> assistantConversations.confirmPendingAction());
            Button cancelAction = UiKit.outlineButton(this, "取消"); cancelAction.setOnClickListener(v -> assistantConversations.cancelPendingAction());
            actions.addView(confirmAction, new LinearLayout.LayoutParams(0, dp(44), 1));
            actions.addView(UiKit.space(this, 8), new LinearLayout.LayoutParams(dp(8), dp(1)));
            actions.addView(cancelAction, new LinearLayout.LayoutParams(0, dp(44), 1));
            copy.addView(actions);
        }
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout wrap = UiKit.horizontal(this); wrap.setPadding(0, dp(8), dp(42), dp(8)); wrap.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(wrap);
        assistantProgressCard = wrap; assistantProgressTitleView = title; assistantProgressDetailView = detail; assistantProgressSpinner = spinner;
    }

    private boolean processAssistant(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (!assistantConversations.snapshot().canStartRequest()) { toast("助理正在处理上一条请求"); return false; }
        if (secure.configured() && !ComplianceSettings.hasAiDataConsent(this)) {
            String pending = text.trim();
            showAiDataDisclosure(() -> processAssistant(pending));
            return false;
        }
        boolean calendarMutation = text.contains("新建日程") || text.contains("添加日程") || text.contains("创建日程")
                || text.contains("安排会议") || text.contains("改到") || text.contains("修改日程")
                || text.contains("删除日程") || text.contains("取消会议");
        if (!hasCalendarWrite() && calendarMutation) {
            requestCalendarWritePermission();
            toast("授权日历后，请再次发送这条请求");
            return false;
        }
        String message = text.trim();
        boolean started = assistantConversations.start(message);
        if (started) showAssistantChat("", false, false);
        return started;
    }

    private void scrollAssistantToBottom() {
        ScrollView scroll = assistantChatScroll;
        if (scroll == null) return;
        mainHandler.post(() -> { if (!destroyed && scroll.isAttachedToWindow()) scroll.fullScroll(View.FOCUS_DOWN); });
    }

    private void loadCalendarInto(LinearLayout list, long start, long end, int limit) {
        loadCalendarInto(list, start, end, limit, screenSeq);
    }

    private void loadCalendarInto(LinearLayout list, long start, long end, int limit, int screen) {
        queryExecutor.execute(() -> {
            List<Models.CalendarEvent> events;
            try { events = new CalendarRepository(this).events(start, end); }
            catch (Exception e) { events = new ArrayList<>(); }
            List<Models.CalendarEvent> result = events;
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                list.removeAllViews();
                if (result.isEmpty()) { list.addView(emptyBlock("没有日程", "这个时间范围内没有读取到本地日程。")); return; }
                for (int i = 0; i < Math.min(limit, result.size()); i++) addCalendarRow(list, result.get(i));
            });
        });
    }

    private void loadTodosForDayInto(LinearLayout list, long start, int screen) {
        queryExecutor.execute(() -> {
            List<Models.TodoItem> todos = db.todosDueBetween(start, start + 86_400_000L, "open", 100);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                list.removeAllViews();
                if (todos.isEmpty()) {
                    list.addView(emptyBlock("当天没有到期待办", "你可以点右上角 +，或直接告诉助理创建。"));
                    return;
                }
                for (Models.TodoItem todo : todos) addTodoRow(list, todo);
            });
        });
    }

    private void addCalendarRow(LinearLayout parent, Models.CalendarEvent event) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(13), 0, dp(13));
        TextView time = UiKit.mono(this, event.allDay ? "全天" : TIME.format(new Date(event.startAt)), 14, event.startAt < System.currentTimeMillis() && event.endAt > System.currentTimeMillis() ? UiKit.ACCENT : UiKit.SUB);
        time.setGravity(Gravity.TOP); row.addView(time, new LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(10), 0, 0, 0); TextView title = UiKit.text(this, event.title.isEmpty() ? "未命名日程" : event.title, 16, UiKit.TEXT); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); copy.addView(title);
        String meta = (event.location.isEmpty() ? event.calendarName : event.location + " · " + event.calendarName); copy.addView(UiKit.text(this, meta, 12, UiKit.SUB)); row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = UiKit.materialIcon(this, "chevron_right", 22, UiKit.SUB); row.addView(arrow, new LinearLayout.LayoutParams(dp(34), dp(38))); row.setOnClickListener(v -> showCalendarDetail(event)); parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showCalendarDetail(Models.CalendarEvent event) {
        backAction = this::showSchedule;
        LinearLayout body = pageBody(); body.addView(backHeader("日程详情", this::showSchedule)); body.addView(UiKit.space(this, 24));
        body.addView(UiKit.mono(this, event.calendarName.toUpperCase(Locale.ROOT), 11, UiKit.SUB)); body.addView(UiKit.space(this, 14)); body.addView(UiKit.title(this, event.title)); body.addView(UiKit.space(this, 22));
        addMeta(body, "开始", DATE_TIME.format(new Date(event.startAt))); addMeta(body, "结束", DATE_TIME.format(new Date(event.endAt))); addMeta(body, "地点", event.location.isEmpty() ? "—" : event.location); addMeta(body, "日历", event.calendarName); addMeta(body, "权限", event.readOnly ? "只读" : "可编辑");
        if (!event.description.isEmpty()) { body.addView(UiKit.space(this, 18)); TextView description = UiKit.text(this, event.description, 15, UiKit.TEXT); description.setPadding(dp(16), dp(15), dp(16), dp(15)); description.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); body.addView(description); }
        if (!event.readOnly && hasCalendarWrite()) {
            body.addView(UiKit.space(this, 24)); Button edit = UiKit.accentButton(this, "编辑日程"); edit.setOnClickListener(v -> editCalendarEvent(event)); body.addView(edit, matchHeight(56));
            TextView delete = UiKit.text(this, "删除日程", 14, UiKit.RED); delete.setGravity(Gravity.CENTER); delete.setPadding(0, dp(22), 0, dp(10)); delete.setOnClickListener(v -> confirm("删除日程？", "将从系统日历中删除。", () -> {
                toast("正在删除日程…");
                backgroundExecutor.execute(() -> {
                    try {
                        new CalendarRepository(this).delete(event.id);
                        new AssistantBriefEngine(this).refresh();
                        runUi(this::showSchedule);
                    } catch (RuntimeException error) {
                        android.util.Log.e("MindAiCalendar", "Calendar delete failed", error);
                        runUi(() -> toast("删除失败，请检查日历权限"));
                    }
                });
            })); body.addView(delete);
        }
        renderScroll(body, -1);
    }

    private void showCreateMenu() {
        new AlertDialog.Builder(this).setTitle("新建").setItems(new String[]{"日程", "待办"}, (d, which) -> { if (which == 0) editCalendarEvent(null); else editTodo(null); }).show();
    }

    private void editCalendarEvent(Models.CalendarEvent existing) {
        if (!hasCalendarWrite()) { requestCalendarWritePermission(); toast("授权后请再次保存日程"); return; }
        LinearLayout form = UiKit.vertical(this); form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText title = editText("日程标题"); EditText description = editText("说明（可选）"); EditText location = editText("地点（可选）");
        long defaultStart = selectedDayStart + 9 * 3_600_000L; final long[] start = {existing == null ? defaultStart : existing.startAt}; final long[] end = {existing == null ? defaultStart + 3_600_000L : existing.endAt};
        if (existing != null) { title.setText(existing.title); description.setText(existing.description); location.setText(existing.location); }
        TextView startView = UiKit.chip(this, "开始  " + DATE_TIME.format(new Date(start[0])), false); TextView endView = UiKit.chip(this, "结束  " + DATE_TIME.format(new Date(end[0])), false);
        startView.setOnClickListener(v -> pickDateTime(start[0], chosen -> { start[0] = chosen; if (end[0] <= start[0]) end[0] = start[0] + 3_600_000L; startView.setText("开始  " + DATE_TIME.format(new Date(start[0]))); endView.setText("结束  " + DATE_TIME.format(new Date(end[0]))); }));
        endView.setOnClickListener(v -> pickDateTime(end[0], chosen -> { end[0] = chosen; endView.setText("结束  " + DATE_TIME.format(new Date(end[0]))); }));
        form.addView(title, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(description, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(location, matchHeight(52)); form.addView(UiKit.space(this, 12)); form.addView(startView, matchHeight(46)); form.addView(UiKit.space(this, 8)); form.addView(endView, matchHeight(46));
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建日程" : "编辑日程").setView(form).setPositiveButton("保存", (d, w) -> {
            String value = title.getText().toString().trim(); if (value.isEmpty()) { toast("请填写日程标题"); return; } if (end[0] <= start[0]) { toast("结束时间需要晚于开始时间"); return; }
            toast("正在保存日程…");
            backgroundExecutor.execute(() -> {
                try {
                    CalendarRepository repo = new CalendarRepository(this);
                    if (existing == null) repo.create(value, description.getText().toString().trim(), location.getText().toString().trim(), start[0], end[0], false, 0);
                    else repo.update(existing.id, value, description.getText().toString().trim(), location.getText().toString().trim(), start[0], end[0], false);
                    new AssistantBriefEngine(this).refresh();
                    runUi(() -> { selectedDayStart = dayStart(start[0]); scheduleMode = "today"; showSchedule(); });
                } catch (RuntimeException error) {
                    android.util.Log.e("MindAiCalendar", "Calendar write failed", error);
                    runUi(() -> toast("保存失败，请检查日历权限"));
                }
            });
        }).setNegativeButton("取消", null).show();
    }

    private void showNotifications() {
        clearRestorableDetail();
        final int screen = beginScreenLoad();
        currentRoot = 2; backAction = null;
        LinearLayout body = pageBody();
        LinearLayout title = UiKit.horizontal(this);
        title.addView(UiKit.title(this, "通知"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout capture = UiKit.vertical(this);
        TextView state = UiKit.text(this, hasNotificationAccess() ? "●  采集正常" : "●  采集停止", 13,
                hasNotificationAccess() ? UiKit.TEXT : UiKit.RED); state.setGravity(Gravity.END);
        capture.addView(state);
        TextView count = UiKit.mono(this, "… 今日", 18, UiKit.SUB); count.setGravity(Gravity.END);
        capture.addView(count); title.addView(capture);
        body.addView(title); body.addView(UiKit.space(this, 16));
        addLibraryTabs(body, "notifications", false);
        body.addView(UiKit.space(this, 20));

        LinearLayout days = UiKit.horizontal(this);
        for (int i = 0; i < 5; i++) {
            Calendar c = Calendar.getInstance(); c.setTimeInMillis(AppDatabase.todayRange()[0]); c.add(Calendar.DAY_OF_MONTH, -i);
            long start = c.getTimeInMillis();
            TextView chip = UiKit.text(this, DAY.format(new Date(start)), 14, start == selectedDayStart ? UiKit.ACCENT : UiKit.SUB);
            chip.setGravity(Gravity.CENTER); chip.setPadding(dp(3), dp(10), dp(3), dp(10));
            chip.setOnClickListener(v -> { selectedDayStart = start; notificationPackage = ""; notificationVisibleLimit = PAGE_SIZE; showNotifications(); });
            days.addView(chip, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        body.addView(days); body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 18));

        TextView chooseDay = UiKit.chip(this, "日期", false);
        chooseDay.setOnClickListener(v -> pickDate(selectedDayStart, chosen -> { selectedDayStart = chosen; notificationPackage = ""; notificationVisibleLimit = PAGE_SIZE; showNotifications(); }));
        body.addView(chooseDay, matchHeight(46)); body.addView(UiKit.space(this, 12));

        EditText search = editText("搜索标题、正文或应用");
        body.addView(search, matchHeight(52)); body.addView(UiKit.space(this, 12));
        LinearLayout filters = UiKit.horizontal(this);
        TextView allChip = UiKit.chip(this, "全部", notificationState.equals("all"));
        TextView newChip = UiKit.chip(this, "未总结", notificationState.equals("unsummarized"));
        TextView doneChip = UiKit.chip(this, "已总结", notificationState.equals("summarized"));
        allChip.setOnClickListener(v -> { notificationState = "all"; notificationVisibleLimit = PAGE_SIZE; showNotifications(); });
        newChip.setOnClickListener(v -> { notificationState = "unsummarized"; notificationVisibleLimit = PAGE_SIZE; showNotifications(); });
        doneChip.setOnClickListener(v -> { notificationState = "summarized"; notificationVisibleLimit = PAGE_SIZE; showNotifications(); });
        filters.addView(allChip); filters.addView(spaceWide(8)); filters.addView(newChip); filters.addView(spaceWide(8)); filters.addView(doneChip);
        TextView apps = UiKit.chip(this, notificationPackage.isEmpty() ? "全部应用⌄" : "已筛选⌄", false);
        apps.setOnClickListener(v -> {
            apps.setText("按应用筛选…");
            apps.setEnabled(false);
            // Commit visible feedback in the current frame before constructing the dialog.
            mainHandler.post(() -> showNotificationAppFilter(apps));
        });
        filters.addView(spaceWeight()); filters.addView(apps); body.addView(filters);
        body.addView(UiKit.space(this, 22));

        LinearLayout list = UiKit.vertical(this); body.addView(list);
        Runnable reload = () -> { notificationVisibleLimit = PAGE_SIZE; fillNotificationList(list, search.getText().toString()); };
        search.addTextChangedListener(watcher(reload));
        fillNotificationList(list, "");
        body.addView(UiKit.space(this, 18));
        Button sum = UiKit.accentButton(this, "正在检查未总结通知…");
        sum.setEnabled(false);
        sum.setOnClickListener(v -> showSummaryScope()); body.addView(sum, matchHeight(58));
        renderScroll(body, 2);
        queryExecutor.execute(() -> {
            int today = db.countToday();
            int pending = db.countUnsummarized();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                count.setText(today + " 今日");
                if (pending > 0) {
                    sum.setText("✦  总结 " + pending + " 条新通知  [→]");
                    sum.setEnabled(true);
                    sum.setVisibility(View.VISIBLE);
                } else sum.setVisibility(View.GONE);
            });
        });
    }

    private void fillNotificationList(LinearLayout list, String query) {
        list.removeAllViews();
        String trimmedQuery = query == null ? "" : query.trim();
        list.addView(loadingBlock("正在加载通知…"));
        final int seq = ++notificationLoadSeq;
        final int screen = screenSeq;
        final long start = selectedDayStart;
        final String state = notificationState;
        final String pkg = notificationPackage;
        int offset = Math.max(0, notificationVisibleLimit - PAGE_SIZE);
        queryExecutor.execute(() -> {
            List<Models.NotificationItem> items = db.notifications(start, start + 86_400_000L, query, state, pkg, offset, PAGE_SIZE + 1);
            runUi(() -> {
                if (seq != notificationLoadSeq || !screenIsCurrent(screen)) return;
                renderNotificationList(list, items, query, offset);
            });
        });
    }

    private void renderNotificationList(LinearLayout list, List<Models.NotificationItem> items, String query, int offset) {
        list.removeAllViews();
        if (items.isEmpty()) { list.addView(emptyBlock("NO NOTIFICATIONS", "")); return; }
        boolean hasMore = items.size() > PAGE_SIZE;
        int visibleCount = Math.min(items.size(), PAGE_SIZE);
        String lastHour = "";
        for (int i = 0; i < visibleCount; i++) {
            Models.NotificationItem item = items.get(i);
            String hour = TIME.format(new Date(item.postedAt)).substring(0, 2) + ":00";
            if (!hour.equals(lastHour)) { TextView h = UiKit.text(this, hour, 15, UiKit.SUB); h.setPadding(0, dp(13), 0, dp(10)); list.addView(h); lastHour = hour; }
            addNotificationRow(list, item);
        }
        addPager(list, offset, hasMore,
                () -> { notificationVisibleLimit = Math.max(PAGE_SIZE, notificationVisibleLimit - PAGE_SIZE); fillNotificationList(list, query); },
                () -> { notificationVisibleLimit += PAGE_SIZE; fillNotificationList(list, query); });
    }

    private void showNotificationAppFilter(TextView anchor) {
        AlertDialog loadingDialog = new AlertDialog.Builder(this)
                .setTitle("按应用筛选")
                .setMessage("正在读取应用列表…")
                .setNegativeButton("取消", null)
                .create();
        loadingDialog.setOnDismissListener(ignored -> {
            if (anchor != null && anchor.isAttachedToWindow()) {
                anchor.setText(notificationPackage.isEmpty() ? "全部应用⌄" : "已筛选⌄");
                anchor.setEnabled(true);
            }
        });
        loadingDialog.show();
        long start = selectedDayStart;
        int screen = screenSeq;
        queryExecutor.execute(() -> {
            List<Models.NotificationItem> items = db.notificationAppsForDay(start, start + 86_400_000L);
            List<String> packages = new ArrayList<>(); List<String> labels = new ArrayList<>();
            packages.add(""); labels.add("全部应用");
            for (Models.NotificationItem n : items) { packages.add(n.packageName); labels.add(n.appName.isEmpty() ? n.packageName : n.appName); }
            runUi(() -> {
                if (!screenIsCurrent(screen)) { loadingDialog.dismiss(); return; }
                if (!loadingDialog.isShowing()) return;
                loadingDialog.dismiss();
                new AlertDialog.Builder(this).setTitle("按应用筛选").setItems(labels.toArray(new String[0]), (d, which) -> {
                    notificationPackage = packages.get(which);
                    notificationVisibleLimit = PAGE_SIZE;
                    // Let the dialog dismiss and acknowledge the tap before rebuilding the page.
                    mainHandler.post(this::showNotifications);
                }).show();
            });
        });
    }

    private void showNotificationDetail(long id) {
        showNotificationDetail(id, "");
    }

    private void showNotificationDetail(long id, String returnTodoId) {
        final int screen = beginScreenLoad();
        restorableDetailType = "notification";
        restorableNotificationId = id;
        restorableTodoId = "";
        notificationReturnTodoId = returnTodoId == null ? "" : returnTodoId;
        Runnable returnAction = notificationDetailReturnAction(notificationReturnTodoId);
        backAction = returnAction;
        LinearLayout loading = pageBody();
        loading.addView(backHeader("通知详情", returnAction));
        loading.addView(UiKit.space(this, 20));
        loading.addView(loadingBlock("正在读取通知详情…"));
        renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            Models.NotificationItem item = db.notification(id);
            List<Models.NotificationItem> versions = item == null ? new ArrayList<>() : db.notificationVersions(item.sourceKey);
            NotificationOpenCoordinator.Resolution resolution = item == null ? null
                    : NotificationOpenCoordinator.resolve(getApplicationContext(), item);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (item == null) { returnAction.run(); return; }
                renderNotificationDetail(item, versions, resolution, returnAction);
            });
        });
    }

    private Runnable notificationDetailReturnAction(String returnTodoId) {
        return () -> {
            if (returnTodoId != null && !returnTodoId.isEmpty()) showTodoDetail(returnTodoId);
            else showNotifications();
        };
    }

    private void renderNotificationDetail(Models.NotificationItem item, List<Models.NotificationItem> versions,
                                          NotificationOpenCoordinator.Resolution resolution,
                                          Runnable returnAction) {
        backAction = returnAction;
        LinearLayout body = pageBody(); body.addView(backHeader("通知详情", returnAction));
        body.addView(UiKit.space(this, 24));
        TextView source = UiKit.mono(this, item.appName.toUpperCase(Locale.ROOT) + "  /  " + TIME.format(new Date(item.postedAt)), 12, UiKit.SUB);
        body.addView(source); body.addView(UiKit.space(this, 18));
        body.addView(UiKit.title(this, item.title.isEmpty() ? "无标题通知" : item.title));
        body.addView(UiKit.space(this, 18));
        TextView content = UiKit.text(this, item.content, 18, UiKit.TEXT);
        content.setPadding(dp(18), dp(18), dp(18), dp(18)); content.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1)));
        body.addView(content);
        addNotificationOpenSection(body, item, resolution);
        body.addView(UiKit.space(this, 28));
        addMeta(body, "来源应用", item.appName + "\n" + item.packageName);
        addMeta(body, "通知时间", DATE_TIME.format(new Date(item.postedAt)));
        addMeta(body, "捕获时间", DATE_TIME.format(new Date(item.capturedAt)));
        addMeta(body, "总结状态", "summarized".equals(item.summaryState) ? "已总结" : "未总结");
        addMeta(body, "隐私", "sensitive".equals(item.sensitivity) ? "敏感" : "普通");
        if (versions.size() > 1) {
            body.addView(UiKit.space(this, 20)); body.addView(UiKit.section(this, "更新历史 · " + versions.size() + " 个版本"));
            for (Models.NotificationItem version : versions) {
                TextView versionView = UiKit.text(this, TIME.format(new Date(version.capturedAt)) + "  " + version.title + "\n" + version.content, 14, UiKit.SUB);
                versionView.setPadding(dp(14), dp(12), dp(14), dp(12)); versionView.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1)));
                body.addView(versionView); body.addView(UiKit.space(this, 8));
            }
        }
        body.addView(UiKit.space(this, 24));
        Button delete = UiKit.outlineButton(this, "删除这条通知"); delete.setTextColor(UiKit.RED);
        delete.setOnClickListener(v -> {
            toast("正在检查关联记录…");
            queryExecutor.execute(() -> {
                int derived = db.recordsUsingNotification(item.id).size();
                runUi(() -> {
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("删除通知？")
                            .setMessage(derived > 0 ? "关联 " + derived + " 条记录。" : "")
                            .setPositiveButton("只删除通知", (d, w) -> runDatabaseAction("正在删除通知…", () -> db.deleteNotification(item.id), this::showNotifications))
                            .setNegativeButton("取消", null);
                    if (derived > 0) dialog.setNeutralButton("连同记录删除", (d, w) -> runDatabaseAction("正在删除关联数据…", () -> { db.deleteRecordsUsingNotification(item.id); db.deleteNotification(item.id); }, this::showNotifications));
                    dialog.show();
                });
            });
        });
        body.addView(delete, matchHeight(54)); renderScroll(body, -1);
    }

    private void addNotificationOpenSection(LinearLayout body, Models.NotificationItem item,
                                            NotificationOpenCoordinator.Resolution resolution) {
        if (body == null || item == null) return;
        body.addView(UiKit.space(this, 24));
        body.addView(UiKit.section(this, "来源操作"));
        if (resolution == null) {
            addSourceStatusCard(body, "暂时无法确认原消息入口，请稍后重试。");
            return;
        }
        if (resolution.exactAvailable() || resolution.appHomeAvailable()) {
            boolean exact = resolution.exactAvailable();
            String label = exact ? "↗  在" + resolution.appName + "中打开"
                    : "打开" + resolution.appName + "首页";
            Button open = exact ? UiKit.accentButton(this, label) : UiKit.outlineButton(this, label);
            open.setSingleLine(true);
            open.setEllipsize(android.text.TextUtils.TruncateAt.END);
            open.setContentDescription(exact
                    ? "在" + resolution.appName + "中打开原通知内容，将离开 MindAI"
                    : "打开" + resolution.appName + "首页，无法定位原消息，将离开 MindAI");
            open.setOnClickListener(v -> openNotificationTarget(item, resolution, open));
            body.addView(open, matchHeight(52));
            TextView helper = UiKit.text(this, exact
                    ? "等同于点击原通知，将离开 MindAI"
                    : resolution.reason, 13, UiKit.SUB);
            helper.setPadding(dp(2), dp(7), dp(2), 0);
            body.addView(helper);
            return;
        }
        String label = resolution.state == NotificationOpenCoordinator.State.APP_MISSING
                ? "来源 App 不可用" : "当前无法打开来源 App";
        Button disabled = UiKit.outlineButton(this, label);
        disabled.setEnabled(false);
        disabled.setTextColor(UiKit.SUB);
        disabled.setBackground(UiKit.box(UiKit.LINE, UiKit.LINE, dp(12), 0));
        disabled.setContentDescription(label + "，" + resolution.reason);
        body.addView(disabled, matchHeight(52));
        addSourceStatusCard(body, resolution.reason);
    }

    private void addSourceStatusCard(LinearLayout body, String message) {
        TextView status = UiKit.text(this, message == null || message.isEmpty()
                ? "系统暂时无法打开该消息。" : message, 13, UiKit.SUB);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(12), dp(1)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        body.addView(status, params);
    }

    private void openNotificationTarget(Models.NotificationItem item,
                                        NotificationOpenCoordinator.Resolution resolution,
                                        Button button) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastExternalOpenAt < 800) return;
        lastExternalOpenAt = now;
        if (button != null) {
            button.setEnabled(false);
            button.setText("正在打开…");
        }
        NotificationOpenCoordinator.Outcome outcome = resolution.exactAvailable()
                ? NotificationOpenCoordinator.sendExact(this, resolution)
                : NotificationOpenCoordinator.openAppHome(this, resolution);
        if (outcome == NotificationOpenCoordinator.Outcome.CANCELED) {
            if (button != null) restoreOpenButton(button, resolution);
            queryExecutor.execute(() -> {
                NotificationOpenCoordinator.Resolution fallback =
                        NotificationOpenCoordinator.resolve(getApplicationContext(), item);
                runUi(() -> {
                    if (!destroyed) showExpiredTargetDialog(item, fallback);
                });
            });
            return;
        }
        if (outcome == NotificationOpenCoordinator.Outcome.SECURITY_REJECTED) {
            if (button != null) restoreOpenButton(button, resolution);
            new AlertDialog.Builder(this)
                    .setTitle("已阻止打开")
                    .setMessage("无法确认这是来源 App 创建的入口。为保护安全，MindAI 没有执行该操作。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        if (outcome == NotificationOpenCoordinator.Outcome.SYSTEM_BLOCKED ||
                outcome == NotificationOpenCoordinator.Outcome.UNAVAILABLE) {
            if (button != null) restoreOpenButton(button, resolution);
            toast("系统暂时无法打开该消息，请稍后重试");
            return;
        }
        if (button != null) mainHandler.postDelayed(() -> {
            if (!destroyed && button.isAttachedToWindow()) restoreOpenButton(button, resolution);
        }, 1_000L);
    }

    private void restoreOpenButton(Button button, NotificationOpenCoordinator.Resolution resolution) {
        if (button == null) return;
        button.setEnabled(true);
        button.setText(resolution.exactAvailable() ? "↗  在" + resolution.appName + "中打开"
                : "打开" + resolution.appName + "首页");
    }

    private void showExpiredTargetDialog(Models.NotificationItem item,
                                         NotificationOpenCoordinator.Resolution fallback) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("原消息入口已失效")
                .setMessage(fallback != null && fallback.appHomeAvailable()
                        ? "无法再定位到对应页面。你仍可打开" + fallback.appName + "首页继续查找。"
                        : "无法再定位到对应页面，来源 App 当前也无法打开。")
                .setNegativeButton("取消", null);
        if (fallback != null && fallback.appHomeAvailable()) {
            dialog.setPositiveButton("打开" + fallback.appName + "首页", (d, which) -> {
                NotificationOpenCoordinator.Outcome outcome =
                        NotificationOpenCoordinator.openAppHome(this, fallback);
                if (outcome != NotificationOpenCoordinator.Outcome.HOME_SENT) {
                    toast("系统暂时无法打开来源 App");
                }
            });
        } else dialog.setPositiveButton("知道了", null);
        dialog.show();
    }

    private void addNotificationRow(LinearLayout parent, Models.NotificationItem item) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(12), 0, dp(12));
        row.addView(UiKit.icon(this, iconFor(item.packageName)));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(10), 0);
        TextView title = UiKit.text(this, item.title.isEmpty() ? "无标题通知" : item.title, 16, UiKit.TEXT); title.setMaxLines(1);
        TextView detail = UiKit.text(this, item.content, 13, UiKit.SUB); detail.setMaxLines(2); detail.setPadding(0, dp(5), 0, 0);
        copy.addView(title); copy.addView(detail); row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout meta = UiKit.vertical(this);
        TextView time = UiKit.text(this, TIME.format(new Date(item.postedAt)) + ("unsummarized".equals(item.summaryState) ? "  ●" : ""), 13,
                "unsummarized".equals(item.summaryState) ? UiKit.RED : UiKit.SUB); time.setGravity(Gravity.END);
        TextView app = UiKit.text(this, item.appName, 12, UiKit.SUB); app.setGravity(Gravity.END); app.setMaxLines(1);
        meta.addView(time); meta.addView(app); row.addView(meta, new LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showNotificationDetail(item.id)); parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showSummaryScope() {
        final int screen = beginScreenLoad();
        final int originRoot = currentRoot;
        Runnable returnToOrigin = () -> {
            if (originRoot == 1) showSchedule();
            else if (originRoot == 2) showLibrary();
            else if (originRoot == 3) showSettings();
            else showHome();
        };
        backAction = returnToOrigin;
        LinearLayout body = pageBody(); body.addView(backHeader("总结新通知", returnToOrigin));
        body.addView(UiKit.space(this, 22));
        body.addView(UiKit.title(this, "总结全部未总结"));
        body.addView(UiKit.space(this, 22));

        LinearLayout scope = UiKit.vertical(this); scope.setPadding(dp(18), dp(18), dp(18), dp(18));
        scope.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.ACCENT, dp(18), dp(1)));
        TextView pendingCount = UiKit.mono(this, "…", 44, UiKit.ACCENT);
        scope.addView(pendingCount);
        scope.addView(UiKit.text(this, "PENDING", 16, UiKit.TEXT));
        body.addView(scope);
        body.addView(UiKit.space(this, 18));

        LinearLayout privacy = UiKit.vertical(this); privacy.setPadding(dp(18), dp(16), dp(18), dp(16));
        privacy.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(18), dp(1)));
        privacy.addView(UiKit.text(this, "AI PIPELINE", 16, UiKit.TEXT));
        privacy.addView(UiKit.text(this, settings.getBoolean("redact_sensitive", true)
                ? "SUMMARY / TODO / MEMORY / PROFILE\nREDACT ON · ZDR " + (secure.zdr() ? "ON" : "OFF")
                : "SUMMARY / TODO / MEMORY / PROFILE\nRAW · ZDR " + (secure.zdr() ? "ON" : "OFF"), 13, UiKit.SUB));
        body.addView(privacy); body.addView(UiKit.space(this, 28));
        Button start = UiKit.accentButton(this, "✦  开始");
        start.setOnClickListener(v -> {
            if (!secure.configured()) {
                new AlertDialog.Builder(this).setTitle("还没有连接 AI")
                        .setMessage("先连接 OpenRouter。")
                        .setPositiveButton("去连接", (d, w) -> showOpenRouter(false)).setNegativeButton("取消", null).show();
                return;
            }
            if (!ComplianceSettings.hasAiDataConsent(this)) {
                showAiDataDisclosure(() -> runSummary(0, Long.MAX_VALUE));
                return;
            }
            runSummary(0, Long.MAX_VALUE);
        });
        body.addView(start, matchHeight(60));
        body.addView(UiKit.space(this, 14));
        Button history = UiKit.outlineButton(this, "历史"); history.setOnClickListener(v -> showSummaryHistory());
        body.addView(history, matchHeight(52)); renderScroll(body, -1);
        queryExecutor.execute(() -> {
            int pending = db.countUnsummarized();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                pendingCount.setText(String.valueOf(pending));
                start.setEnabled(pending > 0);
                if (pending == 0) start.setText("没有待总结通知");
            });
        });
    }

    private void runSummary(long start, long end) {
        summaryRunning = true;
        summaryMinimized = false;
        summaryPercent = 0;
        summaryStep = "固定范围";
        minimizedSummaryId = "";
        minimizedSummaryError = "";
        WidgetSummaryState.markRunning(this, db.countUnsummarized(), "正在整理通知…");
        MindAiWidgetUpdater.updateAll(this);
        backAction = null;
        LinearLayout body = pageBody();
        body.addView(UiKit.mono(this, "MINDAI / ANALYZING", 12, UiKit.SUB));
        body.addView(UiKit.space(this, 70));
        TextView percent = UiKit.mono(this, "00", 72, UiKit.TEXT); body.addView(percent);
        TextView step = UiKit.title(this, "正在固定通知范围"); body.addView(step);
        body.addView(UiKit.space(this, 20));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        body.addView(progress, matchHeight(4));
        body.addView(UiKit.space(this, 24));
        Button minimize = UiKit.outlineButton(this, "最小化");
        minimize.setOnClickListener(v -> {
            summaryMinimized = true;
            toast("总结已最小化");
            showCurrentRoot();
        });
        body.addView(minimize, matchHeight(54));
        renderScroll(body, -1);
        SummaryEngine engine = new SummaryEngine(db, secure);
        executor.execute(() -> engine.summarize(start, end, "", new SummaryEngine.Listener() {
            @Override public void onProgress(String message, int p) {
                summaryStep = message;
                summaryPercent = p;
                runOnUiThread(() -> {
                    if (!summaryMinimized) {
                        percent.setText(String.format(Locale.CHINA, "%02d", p));
                        step.setText(message);
                        progress.setProgress(p, true);
                    }
                });
            }
            @Override public void onSuccess(Models.SummarySession session) {
                summaryRunning = false;
                summaryPercent = 100;
                minimizedSummaryId = session.id;
                WidgetSummaryState.markDone(MainActivity.this, session, WidgetSummaryService.mostImportantText(session));
                MindAiWidgetUpdater.updateAll(MainActivity.this);
                runOnUiThread(() -> {
                    if (summaryMinimized) toast("总结完成");
                    else showSummaryResult(session.id);
                });
            }
            @Override public void onError(String message) {
                summaryRunning = false;
                minimizedSummaryError = message;
                WidgetSummaryState.markError(MainActivity.this, message);
                MindAiWidgetUpdater.updateAll(MainActivity.this);
                runOnUiThread(() -> {
                    if (summaryMinimized) toast(message);
                    else showSummaryError(message);
                });
            }
        }));
    }

    private void showSummaryError(String message) {
        backAction = this::showHome;
        LinearLayout body = pageBody(); body.addView(backHeader("未完成", this::showHome));
        body.addView(UiKit.space(this, 50)); body.addView(UiKit.mono(this, "!", 72, UiKit.RED));
        body.addView(UiKit.title(this, "未总结"));
        body.addView(UiKit.space(this, 16)); body.addView(UiKit.text(this, message, 16, UiKit.SUB));
        body.addView(UiKit.space(this, 28));
        Button retry = UiKit.accentButton(this, "重试"); retry.setOnClickListener(v -> showSummaryScope());
        body.addView(retry, matchHeight(58));
        Button settingsButton = UiKit.outlineButton(this, "OpenRouter"); settingsButton.setOnClickListener(v -> showOpenRouter(false));
        LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(12); body.addView(settingsButton, p);
        renderScroll(body, -1);
    }

    private void showSummaryResult(String id) {
        final int screen = beginScreenLoad();
        backAction = this::showHome;
        LinearLayout loading = pageBody();
        loading.addView(backHeader("总结结果", this::showHome));
        loading.addView(UiKit.space(this, 20));
        loading.addView(loadingBlock("正在读取总结、待办、记忆与画像…"));
        renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            SummaryPageData data = new SummaryPageData();
            data.session = db.session(id);
            if (data.session != null) {
                data.need = db.needToKnowForSession(id);
                data.todos = db.todosForSession(id, 100);
                data.memories = db.memoriesForSession(id, 100);
                data.profile = db.profileFactsForSession(id, 100);
            }
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (data.session == null) { showSummaryHistory(); return; }
                renderSummaryResult(data);
            });
        });
    }

    private void renderSummaryResult(SummaryPageData data) {
        Models.SummarySession session = data.session;
        backAction = this::showHome;
        LinearLayout body = pageBody();
        LinearLayout header = backHeader("总结结果", this::showHome);
        TextView status = UiKit.text(this, session.notificationCount + " 条  ■", 13, UiKit.SUB);
        header.addView(status); body.addView(header);
        body.addView(UiKit.space(this, 30));
        body.addView(UiKit.title(this, "结果"));
        body.addView(UiKit.space(this, 14));
        addNeedToKnowCard(body, data.need, false);
        body.addView(UiKit.space(this, 16));
        TextView overview = UiKit.text(this, session.overview, 17, UiKit.TEXT);
        overview.setPadding(dp(18), dp(16), dp(18), dp(16)); overview.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1)));
        body.addView(overview); body.addView(UiKit.space(this, 20));
        try {
            JSONArray points = new JSONArray(session.keyPointsJson);
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.getJSONObject(i);
                LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(18), 0, dp(18));
                TextView number = UiKit.mono(this, String.format(Locale.CHINA, "%02d", i + 1), 32, UiKit.TEXT);
                row.addView(number, new LinearLayout.LayoutParams(dp(76), ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout copy = UiKit.vertical(this);
                copy.addView(UiKit.text(this, point.optString("title"), 18, UiKit.TEXT));
                TextView detail = UiKit.text(this, point.optString("detail"), 15, UiKit.SUB); detail.setPadding(0, dp(7), 0, dp(7)); copy.addView(detail);
                copy.addView(UiKit.text(this, "SOURCE · " + countIds(point.optString("source_ids")), 13, UiKit.SUB));
                row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                body.addView(row); body.addView(UiKit.line(this));
            }
        } catch (Exception ignored) {}

        List<Models.TodoItem> sessionTodos = data.todos;
        body.addView(UiKit.space(this, 22)); body.addView(UiKit.section(this, "待办"));
        if (sessionTodos.isEmpty()) body.addView(emptyBlock("NO TODO", ""));
        else for (Models.TodoItem todo : sessionTodos) addTodoRow(body, todo);

        List<Models.MemoryItem> sessionMemories = data.memories;
        body.addView(UiKit.space(this, 22)); body.addView(UiKit.section(this, "记忆"));
        if (sessionMemories.isEmpty()) body.addView(emptyBlock("NO MEMORY", ""));
        else for (Models.MemoryItem memory : sessionMemories) addMemoryRow(body, memory);

        List<Models.ProfileFact> sessionProfile = data.profile;
        body.addView(UiKit.space(this, 22)); body.addView(UiKit.section(this, "画像"));
        if (sessionProfile.isEmpty()) body.addView(emptyBlock("NO PROFILE UPDATE", ""));
        else for (Models.ProfileFact fact : sessionProfile) addProfileRow(body, fact);
        Button sources = UiKit.outlineButton(this, "SOURCE · " + session.notificationCount);
        sources.setOnClickListener(v -> { selectedDayStart = dayStart(session.rangeStart); showNotifications(); });
        LinearLayout.LayoutParams sp = matchHeight(54); sp.topMargin = dp(12); body.addView(sources, sp);
        TextView model = UiKit.mono(this, "MODEL / " + session.modelInfo, 10, UiKit.SUB); model.setPadding(0, dp(20), 0, 0); body.addView(model);
        LinearLayout aiSafety = UiKit.horizontal(this); aiSafety.setPadding(0, dp(10), 0, 0);
        aiSafety.addView(UiKit.text(this, "AI 生成 · 可能不准确，请核对来源", 11, UiKit.SUB),
                new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView report = UiKit.text(this, "举报本轮内容", 12, UiKit.RED);
        report.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        report.setOnClickListener(v -> showAiReportDialog("summary_session", session.id,
                session.overview + "\n" + session.keyPointsJson));
        aiSafety.addView(report, new LinearLayout.LayoutParams(dp(112), dp(40)));
        body.addView(aiSafety);
        renderScroll(body, -1);
    }

    private void showSummaryHistory() {
        final int screen = beginScreenLoad();
        backAction = this::showHome;
        LinearLayout body = pageBody(); body.addView(backHeader("总结历史", this::showHome));
        body.addView(UiKit.space(this, 24));
        LinearLayout list = UiKit.vertical(this);
        list.addView(loadingBlock("正在加载总结历史…"));
        body.addView(list);
        renderScroll(body, -1);
        queryExecutor.execute(() -> {
            List<Models.SummarySession> sessions = db.sessions();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                list.removeAllViews();
                if (sessions.isEmpty()) list.addView(emptyBlock("NO HISTORY", ""));
                else {
                    int visible = Math.min(PAGE_SIZE, sessions.size());
                    for (int i = 0; i < visible; i++) addSessionRow(list, sessions.get(i));
                    if (sessions.size() > visible) list.addView(emptyBlock("仅显示最近 " + visible + " 次", "更早结果仍保存在本机。"));
                }
            });
        });
    }

    private void addSessionRow(LinearLayout parent, Models.SummarySession session) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(14), 0, dp(14));
        row.addView(UiKit.icon(this, "failed".equals(session.status) ? android.R.drawable.ic_dialog_alert : android.R.drawable.ic_menu_info_details));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(10), 0);
        String title = "failed".equals(session.status) ? "总结未完成" : (session.overview.isEmpty() ? "通知总结" : session.overview);
        TextView t = UiKit.text(this, title, 16, UiKit.TEXT); t.setMaxLines(2); copy.addView(t);
        copy.addView(UiKit.text(this, session.notificationCount + " 条通知 · " + DATE_TIME.format(new Date(session.createdAt)), 13, UiKit.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = UiKit.text(this, "›", 26, UiKit.SUB); row.addView(arrow);
        row.setOnClickListener(v -> { if ("success".equals(session.status)) showSummaryResult(session.id); else showSummaryError(session.error); });
        parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showTodos() {
        clearRestorableDetail();
        final int screen = beginScreenLoad();
        currentRoot = 1; backAction = null;
        LinearLayout body = pageBody(); body.addView(UiKit.status(this, hasNotificationAccess(), secure.configured()));
        body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 20));
        body.addView(UiKit.title(this, "日程")); body.addView(UiKit.space(this, 16));
        addScheduleTabs(body, "todo", false); body.addView(UiKit.space(this, 20));
        body.addView(UiKit.text(this, "待办事项", 20, UiKit.TEXT)); body.addView(UiKit.space(this, 16));
        LinearLayout tabs = UiKit.horizontal(this);
        TextView open = UiKit.chip(this, "未完成 …", todoState.equals("open"));
        TextView done = UiKit.chip(this, "已完成", todoState.equals("done"));
        TextView archived = UiKit.chip(this, "已归档", todoState.equals("archived"));
        open.setOnClickListener(v -> { todoState = "open"; todoVisibleLimit = PAGE_SIZE; showTodos(); });
        done.setOnClickListener(v -> { todoState = "done"; todoVisibleLimit = PAGE_SIZE; showTodos(); });
        archived.setOnClickListener(v -> { todoState = "archived"; todoVisibleLimit = PAGE_SIZE; showTodos(); });
        tabs.addView(open); tabs.addView(spaceWide(8)); tabs.addView(done); tabs.addView(spaceWide(8)); tabs.addView(archived);
        body.addView(tabs); body.addView(UiKit.space(this, 14));
        EditText search = editText("搜索待办"); body.addView(search, matchHeight(52)); body.addView(UiKit.space(this, 18));
        LinearLayout list = UiKit.vertical(this); body.addView(list);
        Runnable reload = () -> { todoVisibleLimit = PAGE_SIZE; fillTodoList(list, search.getText().toString()); };
        search.addTextChangedListener(watcher(() -> {
            // Invalidate any previous result immediately, then keep the 300 ms query debounce.
            todoLoadSeq++;
            list.removeAllViews();
            list.addView(loadingBlock("正在加载待办…"));
        }, reload));
        fillTodoList(list, "");
        body.addView(UiKit.space(this, 18));
        Button create = UiKit.outlineButton(this, "＋ 新建待办"); create.setOnClickListener(v -> editTodo(null));
        body.addView(create, matchHeight(54));
        renderScroll(body, 1);
        queryExecutor.execute(() -> {
            int count = db.countOpenTodos();
            runUi(() -> { if (screenIsCurrent(screen)) open.setText("未完成 " + count); });
        });
    }

    private void fillTodoList(LinearLayout list, String query) {
        list.removeAllViews();
        list.addView(loadingBlock("正在加载待办…"));
        final int seq = ++todoLoadSeq;
        final int screen = screenSeq;
        final String state = todoState;
        final int offset = Math.max(0, todoVisibleLimit - PAGE_SIZE);
        queryExecutor.execute(() -> {
            List<Models.TodoItem> todos = db.todos(state, query, offset, PAGE_SIZE + 1);
            runUi(() -> {
                if (seq != todoLoadSeq || !screenIsCurrent(screen)) return;
                renderTodoList(list, todos, query, offset);
            });
        });
    }

    private void renderTodoList(LinearLayout list, List<Models.TodoItem> todos, String query, int offset) {
        list.removeAllViews();
        if (todos.isEmpty()) { list.addView(emptyBlock("NO TODO", "")); return; }
        boolean hasMore = todos.size() > PAGE_SIZE;
        int visible = Math.min(todos.size(), PAGE_SIZE);
        for (int i = 0; i < visible; i++) addTodoRow(list, todos.get(i));
        addPager(list, offset, hasMore,
                () -> { todoVisibleLimit = Math.max(PAGE_SIZE, todoVisibleLimit - PAGE_SIZE); fillTodoList(list, query); },
                () -> { todoVisibleLimit += PAGE_SIZE; fillTodoList(list, query); });
    }

    private void addTodoRow(LinearLayout parent, Models.TodoItem todo) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(13), 0, dp(13));
        row.addView(UiKit.icon(this, android.R.drawable.ic_menu_today));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(8), 0);
        TextView title = UiKit.text(this, todo.title, 16, UiKit.TEXT); title.setMaxLines(1); copy.addView(title);
        String detail = todo.description == null || todo.description.isEmpty() ? "—" : todo.description;
        TextView desc = UiKit.text(this, detail, 13, UiKit.SUB); desc.setMaxLines(2); desc.setPadding(0, dp(5), 0, dp(5)); copy.addView(desc);
        copy.addView(UiKit.text(this, todo.generatedByAi ? "AI TODO" : "USER TODO", 12, UiKit.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout meta = UiKit.vertical(this);
        TextView due = UiKit.text(this, todo.dueAt > 0 ? DATE_TIME.format(new Date(todo.dueAt)) : "无截止", 12,
                todo.dueAt > 0 && todo.dueAt < System.currentTimeMillis() && "open".equals(todo.status) ? UiKit.RED : UiKit.SUB);
        due.setGravity(Gravity.END); meta.addView(due);
        TextView pri = UiKit.text(this, priorityName(todo.priority) + " · " + todoStatusName(todo.status), 12,
                "urgent".equals(todo.priority) || "high".equals(todo.priority) ? UiKit.ACCENT : UiKit.SUB);
        pri.setGravity(Gravity.END); pri.setPadding(0, dp(7), 0, 0); meta.addView(pri);
        row.addView(meta, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showTodoDetail(todo.id));
        parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showTodoDetail(String id) {
        final int screen = beginScreenLoad();
        restorableDetailType = "todo";
        restorableTodoId = id == null ? "" : id;
        restorableNotificationId = 0;
        notificationReturnTodoId = "";
        backAction = this::showTodos;
        LinearLayout loading = pageBody(); loading.addView(backHeader("待办详情", this::showTodos));
        loading.addView(UiKit.space(this, 20)); loading.addView(loadingBlock("正在读取待办详情…")); renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            TodoDetailData data = new TodoDetailData();
            data.todo = db.todo(id);
            if (data.todo != null) {
                for (Models.NotificationItem item : db.notificationsByIds(data.todo.sourceNotificationIds, 50)) {
                    data.sources.add(new NotificationSourceTarget(item,
                            NotificationOpenCoordinator.resolve(getApplicationContext(), item)));
                }
            }
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (data.todo == null) { showTodos(); return; }
                renderTodoDetail(data.todo, data.sources);
            });
        });
    }

    private void renderTodoDetail(Models.TodoItem todo, List<NotificationSourceTarget> sources) {
        String id = todo.id;
        backAction = this::showTodos;
        LinearLayout body = pageBody(); body.addView(backHeader("待办详情", this::showTodos));
        body.addView(UiKit.space(this, 24));
        body.addView(UiKit.mono(this, todo.generatedByAi ? "AI TODO  /  AUTO SAVED" : "USER TODO", 11, UiKit.SUB));
        body.addView(UiKit.space(this, 16)); body.addView(UiKit.title(this, todo.title));
        body.addView(UiKit.space(this, 18));
        TextView content = UiKit.text(this, todo.description, 18, UiKit.TEXT); content.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); body.addView(content);
        body.addView(UiKit.space(this, 22));
        addMeta(body, "状态", todoStatusName(todo.status)); addMeta(body, "优先级", priorityName(todo.priority));
        addMeta(body, "截止", todo.dueAt > 0 ? DATE_TIME.format(new Date(todo.dueAt)) : "—");
        addMeta(body, "置信度", Math.round(todo.confidence * 100) + "%");
        addMeta(body, "更新时间", DATE_TIME.format(new Date(todo.updatedAt)));
        addSourceBlock(body, todo.sourceSummaryId, "");
        addTodoNotificationSources(body, todo, sources);
        body.addView(UiKit.space(this, 24));
        Button primary = UiKit.accentButton(this, "done".equals(todo.status) ? "重新打开" : "完成  [→]");
        primary.setOnClickListener(v -> runDatabaseAction("正在更新待办…", () -> db.setTodoStatus(id, "done".equals(todo.status) ? "open" : "done"), this::showTodos));
        body.addView(primary, matchHeight(58));
        LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
        Button edit = UiKit.outlineButton(this, "编辑"); edit.setOnClickListener(v -> editTodo(todo)); actions.addView(edit, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(spaceWide(10));
        Button archive = UiKit.outlineButton(this, "archived".equals(todo.status) ? "恢复" : "归档");
        archive.setOnClickListener(v -> runDatabaseAction("正在更新待办…", () -> db.setTodoStatus(id, "archived".equals(todo.status) ? "open" : "archived"), this::showTodos));
        actions.addView(archive, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(actions);
        TextView delete = UiKit.text(this, "删除待办", 14, UiKit.RED); delete.setGravity(Gravity.CENTER); delete.setPadding(0, dp(22), 0, dp(8));
        delete.setOnClickListener(v -> confirm("删除待办？", "", () -> runDatabaseAction("正在删除待办…", () -> db.deleteTodo(id), this::showTodos))); body.addView(delete);
        renderScroll(body, -1);
    }

    private void addTodoNotificationSources(LinearLayout body, Models.TodoItem todo,
                                            List<NotificationSourceTarget> sources) {
        if (sources == null || sources.isEmpty()) return;
        body.addView(UiKit.space(this, 20));
        body.addView(UiKit.section(this, "来源通知"));
        for (NotificationSourceTarget source : sources) {
            body.addView(todoSourceCard(todo, source));
            body.addView(UiKit.space(this, 8));
        }
        if (sources.size() == 1) {
            NotificationSourceTarget source = sources.get(0);
            addNotificationOpenSection(body, source.item, source.resolution);
            return;
        }
        boolean hasExact = false;
        boolean hasHome = false;
        for (NotificationSourceTarget source : sources) {
            hasExact |= source.resolution != null && source.resolution.exactAvailable();
            hasHome |= source.resolution != null && source.resolution.appHomeAvailable();
        }
        body.addView(UiKit.space(this, 12));
        Button choose = hasExact ? UiKit.accentButton(this, "打开来源消息")
                : UiKit.outlineButton(this, "打开来源消息");
        choose.setContentDescription("选择一条来源通知并打开对应 App");
        choose.setOnClickListener(v -> showNotificationSourcePicker(todo, sources));
        body.addView(choose, matchHeight(52));
        TextView helper = UiKit.text(this, hasExact ? "请选择要处理的具体通知"
                : hasHome ? "原消息入口均已失效，可选择打开来源 App 首页"
                : "当前来源均不可在外部打开，仍可查看通知原文", 13, UiKit.SUB);
        helper.setPadding(dp(2), dp(7), dp(2), 0);
        body.addView(helper);
    }

    private View todoSourceCard(Models.TodoItem todo, NotificationSourceTarget source) {
        LinearLayout card = UiKit.horizontal(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(14), dp(1)));
        card.addView(UiKit.icon(this, iconFor(source.item.packageName)));
        LinearLayout copy = UiKit.vertical(this);
        copy.setPadding(dp(12), 0, 0, 0);
        TextView app = UiKit.text(this, source.item.appName, 13, UiKit.SUB);
        TextView title = UiKit.text(this, source.item.title.isEmpty() ? "无标题通知" : source.item.title,
                15, UiKit.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        TextView meta = UiKit.text(this, DATE_TIME.format(new Date(source.item.postedAt)) + " · " +
                sourceStateName(source.resolution), 12, UiKit.SUB);
        copy.addView(app); copy.addView(title); copy.addView(meta);
        card.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.setContentDescription(source.item.appName + "，" + title.getText() + "，" +
                sourceStateName(source.resolution) + "，点击查看通知详情");
        card.setOnClickListener(v -> showNotificationDetail(source.item.id, todo.id));
        return card;
    }

    private void showNotificationSourcePicker(Models.TodoItem todo,
                                              List<NotificationSourceTarget> sources) {
        List<NotificationSourceTarget> ordered = new ArrayList<>(sources);
        ordered.sort((left, right) -> {
            int state = Integer.compare(sourceStateRank(left.resolution), sourceStateRank(right.resolution));
            return state != 0 ? state : Long.compare(right.item.postedAt, left.item.postedAt);
        });

        AlertDialog dialog = new AlertDialog.Builder(this).create();
        LinearLayout panel = UiKit.vertical(this);
        panel.setPadding(dp(20), dp(16), dp(20), dp(18));
        panel.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.BG_ALT, dp(20), 0));
        LinearLayout heading = UiKit.horizontal(this);
        LinearLayout headingCopy = UiKit.vertical(this);
        TextView title = UiKit.text(this, "选择来源", 20, UiKit.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headingCopy.addView(title);
        headingCopy.addView(UiKit.text(this, "该待办由 " + ordered.size() + " 条通知提炼而来", 13, UiKit.SUB));
        heading.addView(headingCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = UiKit.materialIcon(this, "close", 26, UiKit.SUB);
        close.setContentDescription("关闭来源选择");
        close.setOnClickListener(v -> dialog.dismiss());
        heading.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(heading);
        panel.addView(UiKit.space(this, 10));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = UiKit.vertical(this);
        for (NotificationSourceTarget source : ordered) {
            LinearLayout row = UiKit.horizontal(this);
            row.setPadding(0, dp(10), 0, dp(10));
            row.addView(UiKit.icon(this, iconFor(source.item.packageName)));
            LinearLayout copy = UiKit.vertical(this);
            copy.setPadding(dp(12), 0, dp(8), 0);
            TextView app = UiKit.text(this, source.item.appName, 12, UiKit.SUB);
            TextView sourceTitle = UiKit.text(this, source.item.title.isEmpty() ? "无标题通知" : source.item.title,
                    15, UiKit.TEXT);
            sourceTitle.setMaxLines(2);
            TextView meta = UiKit.text(this, DATE_TIME.format(new Date(source.item.postedAt)) + " · " +
                    sourceStateName(source.resolution), 12,
                    source.resolution != null && source.resolution.exactAvailable() ? UiKit.ACCENT : UiKit.SUB);
            copy.addView(app); copy.addView(sourceTitle); copy.addView(meta);
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(UiKit.materialIcon(this, "chevron_right", 24, UiKit.SUB),
                    new LinearLayout.LayoutParams(dp(32), dp(52)));
            row.setContentDescription(source.item.appName + "，" + sourceTitle.getText() + "，" +
                    sourceStateName(source.resolution));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                if (source.resolution != null && (source.resolution.exactAvailable() ||
                        source.resolution.appHomeAvailable())) {
                    openNotificationTarget(source.item, source.resolution, null);
                } else showNotificationDetail(source.item.id, todo.id);
            });
            list.addView(row);
            list.addView(UiKit.line(this));
        }
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        int maxHeight = Math.min(dp(460), Math.round(getResources().getDisplayMetrics().heightPixels * .62f));
        int contentHeight = Math.min(maxHeight, dp(Math.max(92, ordered.size() * 88)));
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight));
        dialog.setView(panel);
        dialog.setOnShowListener(ignored -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setGravity(Gravity.BOTTOM);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
        });
        dialog.show();
    }

    private static int sourceStateRank(NotificationOpenCoordinator.Resolution resolution) {
        if (resolution != null && resolution.exactAvailable()) return 0;
        if (resolution != null && resolution.appHomeAvailable()) return 1;
        return 2;
    }

    private static String sourceStateName(NotificationOpenCoordinator.Resolution resolution) {
        if (resolution == null) return "暂时不可用";
        if (resolution.exactAvailable()) return "可打开原消息";
        if (resolution.appHomeAvailable()) return "仅打开 App 首页";
        if (resolution.state == NotificationOpenCoordinator.State.BLOCKED) return "已安全阻止";
        return "不可用";
    }

    private void editTodo(Models.TodoItem existing) {
        LinearLayout form = UiKit.vertical(this); form.setPadding(dp(20), dp(10), dp(20), 0);
        EditText title = editText("标题"); EditText desc = editText("说明");
        desc.setMinLines(3); desc.setGravity(Gravity.TOP); desc.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText priority = editText("low / medium / high / urgent");
        final long[] dueAt = {existing == null ? 0 : existing.dueAt};
        TextView due = UiKit.chip(this, dueAt[0] > 0 ? "截止 " + DATE_TIME.format(new Date(dueAt[0])) : "截止日期", false);
        due.setOnClickListener(v -> pickDate(dueAt[0] > 0 ? dueAt[0] : System.currentTimeMillis(), chosen -> {
            dueAt[0] = chosen + 18 * 3_600_000L;
            due.setText("截止 " + DATE_TIME.format(new Date(dueAt[0])));
        }));
        if (existing != null) { title.setText(existing.title); desc.setText(existing.description); priority.setText(existing.priority); }
        form.addView(title, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(desc);
        form.addView(UiKit.space(this, 10)); form.addView(priority, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(due, matchHeight(46));
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建待办" : "编辑待办").setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String savedTitle = title.getText().toString().trim();
                    String savedDescription = desc.getText().toString().trim();
                    String savedPriority = priority.getText().toString().trim();
                    long savedDueAt = dueAt[0];
                    if (savedTitle.isEmpty()) { toast("请填写待办标题"); return; }
                    runDatabaseAction("正在保存待办…", () -> {
                        if (existing == null) db.addManualTodo(savedTitle, savedDescription, savedDueAt, savedPriority);
                        else { existing.title = savedTitle; existing.description = savedDescription; existing.dueAt = savedDueAt; existing.priority = savedPriority; db.updateTodo(existing); }
                    }, this::showTodos);
                }).setNegativeButton("取消", null).show();
    }

    private void showRecords() {
        final int screen = beginScreenLoad();
        currentRoot = 2; backAction = null;
        LinearLayout body = pageBody(); body.addView(UiKit.status(this, hasNotificationAccess(), secure.configured()));
        body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 20));
        body.addView(UiKit.title(this, "重要记录")); body.addView(UiKit.space(this, 20));
        LinearLayout tabs = UiKit.horizontal(this);
        TextView pending = UiKit.chip(this, "旧候选 …", recordState.equals("pending"));
        TextView confirmed = UiKit.chip(this, "已保存", recordState.equals("confirmed"));
        TextView archived = UiKit.chip(this, "已归档", recordState.equals("archived"));
        pending.setOnClickListener(v -> { recordState = "pending"; recordVisibleLimit = PAGE_SIZE; showRecords(); });
        confirmed.setOnClickListener(v -> { recordState = "confirmed"; recordVisibleLimit = PAGE_SIZE; showRecords(); });
        archived.setOnClickListener(v -> { recordState = "archived"; recordVisibleLimit = PAGE_SIZE; showRecords(); });
        tabs.addView(pending); tabs.addView(spaceWide(8)); tabs.addView(confirmed); tabs.addView(spaceWide(8)); tabs.addView(archived);
        body.addView(tabs); body.addView(UiKit.space(this, 14));
        EditText search = editText("搜索重要信息"); body.addView(search, matchHeight(52));
        body.addView(UiKit.space(this, 18));
        LinearLayout list = UiKit.vertical(this); body.addView(list);
        Runnable reload = () -> { recordVisibleLimit = PAGE_SIZE; fillRecordList(list, search.getText().toString()); };
        search.addTextChangedListener(watcher(reload)); fillRecordList(list, "");
        body.addView(UiKit.space(this, 18));
        Button create = UiKit.outlineButton(this, "＋ 新建一条记录"); create.setOnClickListener(v -> editRecord(null));
        body.addView(create, matchHeight(54)); renderScroll(body, 2);
        queryExecutor.execute(() -> {
            int count = db.countPendingRecords();
            runUi(() -> { if (screenIsCurrent(screen)) pending.setText("旧候选 " + count); });
        });
    }

    private void fillRecordList(LinearLayout list, String query) {
        list.removeAllViews();
        list.addView(loadingBlock("正在加载记录…"));
        final int seq = ++recordLoadSeq;
        final int screen = screenSeq;
        final String state = recordState;
        final int offset = Math.max(0, recordVisibleLimit - PAGE_SIZE);
        queryExecutor.execute(() -> {
            List<Models.ImportantRecord> records = db.records(state, query, offset, PAGE_SIZE + 1);
            runUi(() -> {
                if (seq != recordLoadSeq || !screenIsCurrent(screen)) return;
                renderRecordList(list, records, query, offset);
            });
        });
    }

    private void renderRecordList(LinearLayout list, List<Models.ImportantRecord> records, String query, int offset) {
        list.removeAllViews();
        if (records.isEmpty()) { list.addView(emptyBlock("这里暂时是空的", "新版会优先写入待办、记忆和画像；旧记录仅用于兼容。")); return; }
        boolean hasMore = records.size() > PAGE_SIZE;
        int visibleCount = Math.min(records.size(), PAGE_SIZE);
        String category = "";
        for (int i = 0; i < visibleCount; i++) {
            Models.ImportantRecord record = records.get(i);
            if (!record.category.equals(category)) { list.addView(UiKit.section(this, record.category)); category = record.category; }
            addRecordRow(list, record);
        }
        addPager(list, offset, hasMore,
                () -> { recordVisibleLimit = Math.max(PAGE_SIZE, recordVisibleLimit - PAGE_SIZE); fillRecordList(list, query); },
                () -> { recordVisibleLimit += PAGE_SIZE; fillRecordList(list, query); });
    }

    private void addRecordRow(LinearLayout parent, Models.ImportantRecord record) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(13), 0, dp(13));
        row.addView(UiKit.icon(this, recordIcon(record.category)));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(8), 0);
        TextView title = UiKit.text(this, record.title, 16, UiKit.TEXT); title.setMaxLines(1); copy.addView(title);
        TextView detail = UiKit.text(this, record.content, 13, UiKit.SUB); detail.setMaxLines(2); detail.setPadding(0, dp(5), 0, dp(5)); copy.addView(detail);
        copy.addView(UiKit.text(this, record.generatedByAi ? "AI 生成 · 可查看来源" : "用户创建", 12, UiKit.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout meta = UiKit.vertical(this);
        TextView time = UiKit.text(this, DATE_TIME.format(new Date(record.updatedAt)), 12, UiKit.SUB); time.setGravity(Gravity.END); meta.addView(time);
        TextView status = UiKit.text(this, "pending".equals(record.status) ? "●  旧候选" : ("confirmed".equals(record.status) ? "●  已保存" : "●  已归档"), 13,
                "pending".equals(record.status) ? UiKit.ACCENT : UiKit.SUB); status.setGravity(Gravity.END); status.setPadding(0, dp(7), 0, 0); meta.addView(status);
        row.addView(meta, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showRecordDetail(record.id)); parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showRecordDetail(String id) {
        final int screen = beginScreenLoad();
        backAction = this::showRecords;
        LinearLayout loading = pageBody(); loading.addView(backHeader("记录详情", this::showRecords));
        loading.addView(UiKit.space(this, 20)); loading.addView(loadingBlock("正在读取记录详情…")); renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            Models.ImportantRecord record = db.record(id);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (record == null) { showRecords(); return; }
                renderRecordDetail(record);
            });
        });
    }

    private void renderRecordDetail(Models.ImportantRecord record) {
        String id = record.id;
        backAction = this::showRecords;
        LinearLayout body = pageBody(); body.addView(backHeader("记录详情", this::showRecords));
        body.addView(UiKit.space(this, 24));
        body.addView(UiKit.mono(this, record.generatedByAi ? "AI GENERATED  /  AUTO SAVED" : "USER CREATED", 11,
                "pending".equals(record.status) ? UiKit.ACCENT : UiKit.SUB));
        body.addView(UiKit.space(this, 16)); body.addView(UiKit.title(this, record.title));
        body.addView(UiKit.space(this, 18));
        TextView content = UiKit.text(this, record.content, 18, UiKit.TEXT); content.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); body.addView(content);
        body.addView(UiKit.space(this, 22));
        addMeta(body, "类别", record.category); addMeta(body, "重要性", Math.round(record.importance * 100) + "%");
        addMeta(body, "状态", statusName(record.status)); addMeta(body, "更新时间", DATE_TIME.format(new Date(record.updatedAt)));
        body.addView(UiKit.space(this, 20)); body.addView(UiKit.section(this, "来源与依据"));
        Models.SummarySession source = db.session(record.sourceSummaryId);
        if (source != null) {
            TextView summary = UiKit.text(this, source.overview, 15, UiKit.SUB); summary.setPadding(dp(16), dp(14), dp(16), dp(14));
            summary.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); summary.setOnClickListener(v -> showSummaryResult(source.id)); body.addView(summary);
        } else body.addView(UiKit.text(this, record.generatedByAi ? "来源总结已删除" : "这是用户手动创建的记录", 14, UiKit.SUB));
        if (!record.sourceNotificationIds.isEmpty()) {
            Button sourceButton = UiKit.outlineButton(this, "查看原始通知");
            sourceButton.setOnClickListener(v -> { long first = firstId(record.sourceNotificationIds); if (first > 0) showNotificationDetail(first); });
            LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(12); body.addView(sourceButton, p);
        }
        body.addView(UiKit.space(this, 24));
        Button primary = UiKit.accentButton(this, "pending".equals(record.status) ? "标记为已保存  [→]" : "编辑记录  [→]");
        primary.setOnClickListener(v -> { if ("pending".equals(record.status)) runDatabaseAction("正在保存记录…", () -> db.setRecordStatus(id, "confirmed"), () -> showRecordDetail(id)); else editRecord(record); });
        body.addView(primary, matchHeight(58));
        LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
        Button edit = UiKit.outlineButton(this, "编辑"); edit.setOnClickListener(v -> editRecord(record)); actions.addView(edit, new LinearLayout.LayoutParams(0, dp(52), 1));
        actions.addView(spaceWide(10));
        Button archive = UiKit.outlineButton(this, "archived".equals(record.status) ? "恢复" : "归档");
        archive.setOnClickListener(v -> runDatabaseAction("正在更新记录…", () -> db.setRecordStatus(id, "archived".equals(record.status) ? "confirmed" : "archived"), this::showRecords));
        actions.addView(archive, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(actions);
        TextView delete = UiKit.text(this, "删除记录", 14, UiKit.RED); delete.setGravity(Gravity.CENTER); delete.setPadding(0, dp(22), 0, dp(8));
        delete.setOnClickListener(v -> confirm("删除记录？", "原始通知不会被删除；记录会保留在回收状态 7 天。", () -> runDatabaseAction("正在删除记录…", () -> db.deleteRecord(id), this::showRecords))); body.addView(delete);
        renderScroll(body, -1);
    }

    private void editRecord(Models.ImportantRecord existing) {
        LinearLayout form = UiKit.vertical(this); form.setPadding(dp(20), dp(10), dp(20), 0);
        EditText title = editText("记录标题"); EditText content = editText("完整信息");
        content.setMinLines(4); content.setGravity(Gravity.TOP); content.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText category = editText("类别，例如：工作项目");
        if (existing != null) { title.setText(existing.title); content.setText(existing.content); category.setText(existing.category); }
        form.addView(title, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(content); form.addView(UiKit.space(this, 10)); form.addView(category, matchHeight(52));
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建记录" : "编辑记录").setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String savedTitle = title.getText().toString().trim();
                    String savedContent = content.getText().toString().trim();
                    String savedCategory = category.getText().toString().trim();
                    if (savedTitle.isEmpty()) { toast("请填写标题"); return; }
                    runDatabaseAction("正在保存记录…", () -> {
                        if (existing == null) db.addManualRecord(savedTitle, savedContent, savedCategory.isEmpty() ? "其他" : savedCategory);
                        else { existing.title = savedTitle; existing.content = savedContent; existing.category = savedCategory; db.updateRecord(existing); }
                    }, this::showRecords);
                }).setNegativeButton("取消", null).show();
    }

    private void showMemoryHub() {
        final int screen = beginScreenLoad();
        currentRoot = 2; backAction = null;
        LinearLayout body = pageBody(); body.addView(UiKit.status(this, hasNotificationAccess(), secure.configured()));
        body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 20));
        body.addView(UiKit.title(this, "信息库")); body.addView(UiKit.space(this, 16));
        addLibraryTabs(body, memoryMode.equals("profile") ? "profile" : "memory", false); body.addView(UiKit.space(this, 20));
        body.addView(UiKit.text(this, memoryMode.equals("profile") ? "个人画像" : "长期记忆", 20, UiKit.TEXT)); body.addView(UiKit.space(this, 16));

        LinearLayout modeTabs = UiKit.horizontal(this);
        TextView memories = UiKit.chip(this, "记忆库 …", memoryMode.equals("memories"));
        TextView profile = UiKit.chip(this, "用户画像 …", memoryMode.equals("profile"));
        memories.setOnClickListener(v -> { memoryMode = "memories"; memoryVisibleLimit = PAGE_SIZE; showMemoryHub(); });
        profile.setOnClickListener(v -> { memoryMode = "profile"; memoryVisibleLimit = PAGE_SIZE; showMemoryHub(); });
        modeTabs.addView(memories); modeTabs.addView(spaceWide(8)); modeTabs.addView(profile); body.addView(modeTabs);
        body.addView(UiKit.space(this, 14));
        LinearLayout io = UiKit.horizontal(this);
        Button importMd = UiKit.outlineButton(this, "导入 MD");
        importMd.setOnClickListener(v -> chooseProfileMarkdown());
        Button exportMd = UiKit.outlineButton(this, "导出 MD");
        exportMd.setOnClickListener(v -> exportProfileMarkdown());
        io.addView(importMd, new LinearLayout.LayoutParams(0, dp(50), 1));
        io.addView(spaceWide(10));
        io.addView(exportMd, new LinearLayout.LayoutParams(0, dp(50), 1));
        body.addView(io);
        body.addView(UiKit.space(this, 14));

        LinearLayout tabs = UiKit.horizontal(this);
        if (memoryMode.equals("memories")) {
            TextView active = UiKit.chip(this, "活跃", memoryState.equals("active"));
            TextView disputed = UiKit.chip(this, "冲突", memoryState.equals("disputed"));
            TextView archived = UiKit.chip(this, "归档", memoryState.equals("archived"));
            active.setOnClickListener(v -> { memoryState = "active"; showMemoryHub(); });
            disputed.setOnClickListener(v -> { memoryState = "disputed"; showMemoryHub(); });
            archived.setOnClickListener(v -> { memoryState = "archived"; showMemoryHub(); });
            tabs.addView(active); tabs.addView(spaceWide(8)); tabs.addView(disputed); tabs.addView(spaceWide(8)); tabs.addView(archived);
        } else {
            TextView active = UiKit.chip(this, "活跃", profileState.equals("active"));
            TextView disputed = UiKit.chip(this, "冲突", profileState.equals("disputed"));
            TextView archived = UiKit.chip(this, "归档", profileState.equals("archived"));
            active.setOnClickListener(v -> { profileState = "active"; showMemoryHub(); });
            disputed.setOnClickListener(v -> { profileState = "disputed"; showMemoryHub(); });
            archived.setOnClickListener(v -> { profileState = "archived"; showMemoryHub(); });
            tabs.addView(active); tabs.addView(spaceWide(8)); tabs.addView(disputed); tabs.addView(spaceWide(8)); tabs.addView(archived);
        }
        body.addView(tabs); body.addView(UiKit.space(this, 14));
        EditText search = editText(memoryMode.equals("memories") ? "搜索记忆" : "搜索画像"); body.addView(search, matchHeight(52)); body.addView(UiKit.space(this, 18));
        LinearLayout list = UiKit.vertical(this); body.addView(list);
        Runnable reload = () -> { memoryVisibleLimit = PAGE_SIZE; fillMemoryHubList(list, search.getText().toString()); };
        search.addTextChangedListener(watcher(reload)); fillMemoryHubList(list, "");
        body.addView(UiKit.space(this, 18));
        Button create = UiKit.outlineButton(this, memoryMode.equals("memories") ? "＋ 新建记忆" : "＋ 新建画像条目");
        create.setOnClickListener(v -> { if (memoryMode.equals("memories")) editMemory(null); else editProfileFact(null); });
        body.addView(create, matchHeight(54));
        renderScroll(body, 2);
        queryExecutor.execute(() -> {
            int memoryCount = db.countMemories();
            int profileCount = db.countProfileFacts();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                memories.setText("记忆库 " + memoryCount);
                profile.setText("用户画像 " + profileCount);
            });
        });
    }

    private void fillMemoryHubList(LinearLayout list, String query) {
        list.removeAllViews();
        list.addView(loadingBlock(memoryMode.equals("memories") ? "正在加载记忆…" : "正在加载画像…"));
        final int seq = ++memoryLoadSeq;
        final int screen = screenSeq;
        final int offset = Math.max(0, memoryVisibleLimit - PAGE_SIZE);
        final String mode = memoryMode;
        final String state = mode.equals("memories") ? memoryState : profileState;
        queryExecutor.execute(() -> {
            if (mode.equals("memories")) {
                List<Models.MemoryItem> items = db.memories(state, query, offset, PAGE_SIZE + 1);
                runUi(() -> {
                    if (seq != memoryLoadSeq || !screenIsCurrent(screen)) return;
                    renderMemoryList(list, items, query, offset);
                });
            } else {
                List<Models.ProfileFact> items = db.profileFacts(state, query, offset, PAGE_SIZE + 1);
                runUi(() -> {
                    if (seq != memoryLoadSeq || !screenIsCurrent(screen)) return;
                    renderProfileList(list, items, query, offset);
                });
            }
        });
    }

    private void renderMemoryList(LinearLayout list, List<Models.MemoryItem> items, String query, int offset) {
            list.removeAllViews();
            if (items.isEmpty()) { list.addView(emptyBlock("NO MEMORY", "")); return; }
            boolean hasMore = items.size() > PAGE_SIZE;
            int visible = Math.min(items.size(), PAGE_SIZE);
            String category = "";
            for (int i = 0; i < visible; i++) {
                Models.MemoryItem item = items.get(i);
                if (!item.category.equals(category)) { list.addView(UiKit.section(this, memoryCategoryName(item.category))); category = item.category; }
                addMemoryRow(list, item);
            }
            addPager(list, offset, hasMore,
                    () -> { memoryVisibleLimit = Math.max(PAGE_SIZE, memoryVisibleLimit - PAGE_SIZE); fillMemoryHubList(list, query); },
                    () -> { memoryVisibleLimit += PAGE_SIZE; fillMemoryHubList(list, query); });
    }

    private void renderProfileList(LinearLayout list, List<Models.ProfileFact> items, String query, int offset) {
            list.removeAllViews();
            if (items.isEmpty()) { list.addView(emptyBlock("NO PROFILE", "")); return; }
            boolean hasMore = items.size() > PAGE_SIZE;
            int visible = Math.min(items.size(), PAGE_SIZE);
            String section = "";
            for (int i = 0; i < visible; i++) {
                Models.ProfileFact item = items.get(i);
                if (!item.section.equals(section)) { list.addView(UiKit.section(this, item.section)); section = item.section; }
                addProfileRow(list, item);
            }
            addPager(list, offset, hasMore,
                    () -> { memoryVisibleLimit = Math.max(PAGE_SIZE, memoryVisibleLimit - PAGE_SIZE); fillMemoryHubList(list, query); },
                    () -> { memoryVisibleLimit += PAGE_SIZE; fillMemoryHubList(list, query); });
    }

    private void addMemoryRow(LinearLayout parent, Models.MemoryItem memory) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(13), 0, dp(13));
        row.addView(UiKit.icon(this, memoryIcon(memory.category)));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(8), 0);
        TextView title = UiKit.text(this, memory.title.isEmpty() ? firstLine(memory.text) : memory.title, 16, UiKit.TEXT); title.setMaxLines(1); copy.addView(title);
        TextView detail = UiKit.text(this, memory.text, 13, UiKit.SUB); detail.setMaxLines(2); detail.setPadding(0, dp(5), 0, dp(5)); copy.addView(detail);
        copy.addView(UiKit.text(this, memory.generatedByAi ? "AI MEMORY" : "USER MEMORY", 12, UiKit.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout meta = UiKit.vertical(this);
        TextView imp = UiKit.text(this, Math.round(memory.importance * 100) + "%", 12, UiKit.SUB); imp.setGravity(Gravity.END); meta.addView(imp);
        TextView status = UiKit.text(this, memoryStatusName(memory.status), 12, "disputed".equals(memory.status) ? UiKit.ACCENT : UiKit.SUB); status.setGravity(Gravity.END); status.setPadding(0, dp(7), 0, 0); meta.addView(status);
        row.addView(meta, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showMemoryDetail(memory.id));
        parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void addProfileRow(LinearLayout parent, Models.ProfileFact fact) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(13), 0, dp(13));
        row.addView(UiKit.icon(this, android.R.drawable.ic_menu_myplaces));
        LinearLayout copy = UiKit.vertical(this); copy.setPadding(dp(16), 0, dp(8), 0);
        TextView title = UiKit.text(this, fact.title.isEmpty() ? fact.section : fact.title, 16, UiKit.TEXT); title.setMaxLines(1); copy.addView(title);
        TextView detail = UiKit.text(this, fact.content, 13, UiKit.SUB); detail.setMaxLines(2); detail.setPadding(0, dp(5), 0, dp(5)); copy.addView(detail);
        copy.addView(UiKit.text(this, fact.generatedByAi ? "AI PROFILE" : "USER PROFILE", 12, UiKit.SUB));
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView status = UiKit.text(this, profileStatusName(fact.status), 12, "disputed".equals(fact.status) ? UiKit.ACCENT : UiKit.SUB);
        status.setGravity(Gravity.END); row.addView(status, new LinearLayout.LayoutParams(dp(86), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> showProfileDetail(fact.id));
        parent.addView(row); parent.addView(UiKit.line(this));
    }

    private void showMemoryDetail(String id) {
        final int screen = beginScreenLoad();
        backAction = this::showMemoryHub;
        LinearLayout loading = pageBody(); loading.addView(backHeader("记忆详情", this::showMemoryHub));
        loading.addView(UiKit.space(this, 20)); loading.addView(loadingBlock("正在读取记忆详情…")); renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            Models.MemoryItem memory = db.memory(id);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (memory == null) { showMemoryHub(); return; }
                renderMemoryDetail(memory);
            });
        });
    }

    private void renderMemoryDetail(Models.MemoryItem memory) {
        String id = memory.id;
        backAction = this::showMemoryHub;
        LinearLayout body = pageBody(); body.addView(backHeader("记忆详情", this::showMemoryHub));
        body.addView(UiKit.space(this, 24)); body.addView(UiKit.mono(this, memory.generatedByAi ? "AI MEMORY  /  AUTO SAVED" : "USER MEMORY", 11, UiKit.SUB));
        body.addView(UiKit.space(this, 16)); body.addView(UiKit.title(this, memory.title.isEmpty() ? firstLine(memory.text) : memory.title));
        body.addView(UiKit.space(this, 18));
        TextView content = UiKit.text(this, memory.text, 18, UiKit.TEXT); content.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); body.addView(content);
        body.addView(UiKit.space(this, 22));
        addMeta(body, "类别", memoryCategoryName(memory.category)); addMeta(body, "状态", memoryStatusName(memory.status));
        addMeta(body, "重要性", Math.round(memory.importance * 100) + "%"); addMeta(body, "置信度", Math.round(memory.confidence * 100) + "%");
        addMeta(body, "稳定性", memory.stability); addMeta(body, "敏感度", memory.sensitivity);
        addSourceBlock(body, memory.sourceSummaryId, memory.sourceNotificationIds);
        body.addView(UiKit.space(this, 24));
        Button edit = UiKit.accentButton(this, "编辑  [→]"); edit.setOnClickListener(v -> editMemory(memory)); body.addView(edit, matchHeight(58));
        LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
        Button archive = UiKit.outlineButton(this, "archived".equals(memory.status) ? "恢复" : "归档");
        archive.setOnClickListener(v -> runDatabaseAction("正在更新记忆…", () -> db.setMemoryStatus(id, "archived".equals(memory.status) ? "active" : "archived"), this::showMemoryHub));
        actions.addView(archive, new LinearLayout.LayoutParams(0, dp(52), 1)); actions.addView(spaceWide(10));
        Button dispute = UiKit.outlineButton(this, "冲突"); dispute.setOnClickListener(v -> runDatabaseAction("正在更新记忆…", () -> db.setMemoryStatus(id, "disputed"), this::showMemoryHub));
        actions.addView(dispute, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(actions);
        TextView delete = UiKit.text(this, "删除记忆", 14, UiKit.RED); delete.setGravity(Gravity.CENTER); delete.setPadding(0, dp(22), 0, dp(8));
        delete.setOnClickListener(v -> confirm("删除记忆？", "", () -> runDatabaseAction("正在删除记忆…", () -> db.deleteMemory(id), this::showMemoryHub))); body.addView(delete);
        renderScroll(body, -1);
    }

    private void showProfileDetail(String id) {
        final int screen = beginScreenLoad();
        backAction = this::showMemoryHub;
        LinearLayout loading = pageBody(); loading.addView(backHeader("画像详情", this::showMemoryHub));
        loading.addView(UiKit.space(this, 20)); loading.addView(loadingBlock("正在读取画像详情…")); renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            Models.ProfileFact fact = db.profileFact(id);
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (fact == null) { showMemoryHub(); return; }
                renderProfileDetail(fact);
            });
        });
    }

    private void renderProfileDetail(Models.ProfileFact fact) {
        String id = fact.id;
        backAction = this::showMemoryHub;
        LinearLayout body = pageBody(); body.addView(backHeader("画像详情", this::showMemoryHub));
        body.addView(UiKit.space(this, 24)); body.addView(UiKit.mono(this, fact.generatedByAi ? "AI PROFILE  /  USER EDITS WIN" : "USER PROFILE", 11, UiKit.SUB));
        body.addView(UiKit.space(this, 16)); body.addView(UiKit.title(this, fact.title.isEmpty() ? fact.section : fact.title));
        body.addView(UiKit.space(this, 18));
        TextView content = UiKit.text(this, fact.content, 18, UiKit.TEXT); content.setPadding(dp(18), dp(18), dp(18), dp(18));
        content.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); body.addView(content);
        body.addView(UiKit.space(this, 22));
        addMeta(body, "分区", fact.section); addMeta(body, "状态", profileStatusName(fact.status));
        addMeta(body, "置信度", Math.round(fact.confidence * 100) + "%"); addMeta(body, "稳定性", fact.stability);
        addMeta(body, "最近证实", fact.lastSeenAt > 0 ? DATE_TIME.format(new Date(fact.lastSeenAt)) : "未知");
        addSourceBlock(body, fact.sourceSummaryId, fact.sourceNotificationIds);
        body.addView(UiKit.space(this, 24));
        Button edit = UiKit.accentButton(this, "编辑  [→]"); edit.setOnClickListener(v -> editProfileFact(fact)); body.addView(edit, matchHeight(58));
        LinearLayout actions = UiKit.horizontal(this); actions.setPadding(0, dp(12), 0, 0);
        Button archive = UiKit.outlineButton(this, "archived".equals(fact.status) ? "恢复" : "归档");
        archive.setOnClickListener(v -> runDatabaseAction("正在更新画像…", () -> db.setProfileFactStatus(id, "archived".equals(fact.status) ? "active" : "archived"), this::showMemoryHub));
        actions.addView(archive, new LinearLayout.LayoutParams(0, dp(52), 1)); actions.addView(spaceWide(10));
        Button dispute = UiKit.outlineButton(this, "冲突"); dispute.setOnClickListener(v -> runDatabaseAction("正在更新画像…", () -> db.setProfileFactStatus(id, "disputed"), this::showMemoryHub));
        actions.addView(dispute, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(actions);
        TextView delete = UiKit.text(this, "删除画像条目", 14, UiKit.RED); delete.setGravity(Gravity.CENTER); delete.setPadding(0, dp(22), 0, dp(8));
        delete.setOnClickListener(v -> confirm("删除画像？", "", () -> runDatabaseAction("正在删除画像…", () -> db.deleteProfileFact(id), this::showMemoryHub))); body.addView(delete);
        renderScroll(body, -1);
    }

    private void editMemory(Models.MemoryItem existing) {
        LinearLayout form = UiKit.vertical(this); form.setPadding(dp(20), dp(10), dp(20), 0);
        EditText title = editText("标题"); EditText text = editText("内容"); EditText category = editText("project / preference / fact");
        text.setMinLines(4); text.setGravity(Gravity.TOP); text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) { title.setText(existing.title); text.setText(existing.text); category.setText(existing.category); }
        form.addView(title, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(text); form.addView(UiKit.space(this, 10)); form.addView(category, matchHeight(52));
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建记忆" : "编辑记忆").setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String savedTitle = title.getText().toString().trim();
                    String savedText = text.getText().toString().trim();
                    String savedCategory = category.getText().toString().trim();
                    if (savedText.isEmpty()) { toast("请填写记忆内容"); return; }
                    runDatabaseAction("正在保存记忆…", () -> {
                        if (existing == null) db.addManualMemory(savedTitle, savedText, savedCategory);
                        else { existing.title = savedTitle; existing.text = savedText; existing.category = savedCategory; db.updateMemory(existing); }
                    }, () -> { memoryMode = "memories"; showMemoryHub(); });
                }).setNegativeButton("取消", null).show();
    }

    private void editProfileFact(Models.ProfileFact existing) {
        LinearLayout form = UiKit.vertical(this); form.setPadding(dp(20), dp(10), dp(20), 0);
        EditText section = editText("分区"); EditText title = editText("标题"); EditText content = editText("内容");
        content.setMinLines(4); content.setGravity(Gravity.TOP); content.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (existing != null) { section.setText(existing.section); title.setText(existing.title); content.setText(existing.content); }
        form.addView(section, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(title, matchHeight(52)); form.addView(UiKit.space(this, 10)); form.addView(content);
        new AlertDialog.Builder(this).setTitle(existing == null ? "新建画像条目" : "编辑画像条目").setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String savedSection = section.getText().toString().trim();
                    String savedTitle = title.getText().toString().trim();
                    String savedContent = content.getText().toString().trim();
                    if (savedContent.isEmpty()) { toast("请填写画像内容"); return; }
                    runDatabaseAction("正在保存画像…", () -> {
                        if (existing == null) db.addManualProfileFact(savedSection, savedTitle, savedContent);
                        else { existing.section = savedSection; existing.title = savedTitle; existing.content = savedContent; db.updateProfileFact(existing); }
                    }, () -> { memoryMode = "profile"; showMemoryHub(); });
                }).setNegativeButton("取消", null).show();
    }

    private void showSettings() {
        beginScreenLoad();
        currentRoot = 3; backAction = null;
        LinearLayout body = pageBody(); body.addView(UiKit.status(this, hasNotificationAccess(), secure.configured()));
        body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 20)); body.addView(UiKit.title(this, "我的"));
        body.addView(UiKit.space(this, 26)); body.addView(UiKit.text(this, "ASSISTANT", 16, UiKit.SUB));
        LinearLayout automation = UiKit.settingRow(this, android.R.drawable.ic_menu_recent_history, "自动整理", settings.getBoolean("auto_processing_enabled", false) ? intervalName(settings.getInt("auto_interval_minutes", 15)) : "已关闭");
        automation.setOnClickListener(v -> showAutomationSettings()); body.addView(automation); body.addView(UiKit.line(this));
        LinearLayout calendar = UiKit.settingRow(this, android.R.drawable.ic_menu_today, "本地日历", hasCalendarRead() ? "已连接" : "未授权");
        calendar.setOnClickListener(v -> showCalendarSettings()); body.addView(calendar); body.addView(UiKit.line(this));
        LinearLayout router = UiKit.settingRow(this, android.R.drawable.ic_menu_share, "OpenRouter", secure.configured() ? "可用 · FREE" : "未配置");
        router.setOnClickListener(v -> showOpenRouter(false)); body.addView(router);

        body.addView(UiKit.space(this, 24)); body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 18)); body.addView(UiKit.text(this, "CAPTURE", 16, UiKit.SUB));
        LinearLayout scope = UiKit.settingRow(this, android.R.drawable.ic_menu_agenda, "应用范围", captureModeName());
        scope.setOnClickListener(v -> showCaptureScope(false)); body.addView(scope); body.addView(UiKit.line(this));
        LinearLayout silent = UiKit.settingRow(this, android.R.drawable.ic_lock_silent_mode, "静默通知", settings.getBoolean("capture_ongoing", false) ? "ON" : "OFF");
        silent.setOnClickListener(v -> toggleSetting("capture_ongoing", "静默通知", this::showSettings)); body.addView(silent); body.addView(UiKit.line(this));
        LinearLayout sensitive = UiKit.settingRow(this, android.R.drawable.ic_secure, "脱敏", settings.getBoolean("redact_sensitive", true) ? "ON" : "OFF");
        sensitive.setOnClickListener(v -> toggleSetting("redact_sensitive", "脱敏", this::showSettings)); body.addView(sensitive);

        body.addView(UiKit.space(this, 24)); body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 18)); body.addView(UiKit.text(this, "DATA", 16, UiKit.SUB));
        LinearLayout retention = UiKit.settingRow(this, android.R.drawable.ic_menu_recent_history, "保留期限", retentionName());
        retention.setOnClickListener(v -> chooseRetention()); body.addView(retention); body.addView(UiKit.line(this));
        LinearLayout privacy = UiKit.settingRow(this, android.R.drawable.ic_menu_manage, "导出 / 删除", "本地数据");
        privacy.setOnClickListener(v -> showDataPrivacy()); body.addView(privacy);
        body.addView(UiKit.line(this));
        LinearLayout privacyPolicy = UiKit.settingRow(this, android.R.drawable.ic_menu_info_details,
                "隐私政策与数据使用", "查看说明");
        privacyPolicy.setOnClickListener(v -> showPrivacyNotice()); body.addView(privacyPolicy);

        body.addView(UiKit.space(this, 24)); body.addView(UiKit.line(this)); body.addView(UiKit.space(this, 18)); body.addView(UiKit.text(this, "STATUS", 16, UiKit.SUB));
        LinearLayout diagnose = UiKit.settingRow(this, android.R.drawable.ic_menu_info_details, "权限与连接状态", hasNotificationAccess() ? "正常" : "需修复");
        diagnose.setOnClickListener(v -> showDiagnostics()); body.addView(diagnose);
        renderScroll(body, 3);
    }

    private void showAutomationSettings() {
        beginScreenLoad();
        backAction = this::showSettings;
        LinearLayout body = pageBody(); body.addView(backHeader("自动整理", this::showSettings)); body.addView(UiKit.space(this, 24));
        body.addView(UiKit.title(this, "让重要信息主动出现")); body.addView(UiKit.space(this, 18));
        Switch enabled = new Switch(this); enabled.setText("自动整理新通知"); enabled.setTextColor(UiKit.TEXT); enabled.setTextSize(17); enabled.setChecked(settings.getBoolean("auto_processing_enabled", false)); enabled.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); body.addView(enabled, matchHeight(54));
        body.addView(UiKit.text(this, "默认关闭。开启后，系统会在后台把全部未总结通知及必要上下文发送给 OpenRouter 和实际模型提供商。某轮失败时不会标记为已总结。", 13, UiKit.SUB));
        Switch autoCalendar = new Switch(this); autoCalendar.setText("自动写入明确日程"); autoCalendar.setTextColor(UiKit.TEXT); autoCalendar.setTextSize(17);
        autoCalendar.setChecked(settings.getBoolean("auto_calendar_write_enabled", false));
        autoCalendar.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        LinearLayout.LayoutParams acp = matchHeight(54); acp.topMargin = dp(14); body.addView(autoCalendar, acp);
        body.addView(UiKit.text(this, "默认关闭。仅在高置信度、时间完整且无冲突时写入系统日历；关闭时仍会生成待办或提示。", 13, UiKit.SUB));
        Switch reminders = new Switch(this); reminders.setText("高优先级主动提醒"); reminders.setTextColor(UiKit.TEXT); reminders.setTextSize(17); reminders.setChecked(settings.getBoolean("proactive_reminders_enabled", true)); reminders.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); LinearLayout.LayoutParams rp = matchHeight(54); rp.topMargin = dp(14); body.addView(reminders, rp);
        Switch quiet = new Switch(this); quiet.setText("22:00–08:00 免打扰"); quiet.setTextColor(UiKit.TEXT); quiet.setTextSize(17); quiet.setChecked(settings.getBoolean("quiet_hours_enabled", true)); quiet.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); body.addView(quiet, matchHeight(54));
        body.addView(UiKit.space(this, 24)); body.addView(fieldLabel("整理频率"));
        RadioGroup intervals = new RadioGroup(this); intervals.setOrientation(LinearLayout.VERTICAL); int current = settings.getInt("auto_interval_minutes", 15);
        RadioButton every15 = radio("约每 15 分钟（推荐）", current == 15);
        RadioButton every30 = radio("约每 30 分钟", current == 30);
        RadioButton everyHour = radio("约每 1 小时", current == 60);
        RadioButton everyThree = radio("约每 3 小时", current == 180);
        intervals.addView(every15); intervals.addView(every30); intervals.addView(everyHour); intervals.addView(everyThree); body.addView(intervals);
        body.addView(UiKit.space(this, 22));
        Button save = UiKit.accentButton(this, "保存自动化设置"); save.setOnClickListener(v -> {
            int selected = intervals.getCheckedRadioButtonId();
            int chosen = selected == every30.getId() ? 30 : selected == everyHour.getId() ? 60 : selected == everyThree.getId() ? 180 : 15;
            Runnable persist = () -> saveAutomationSettings(enabled.isChecked(), autoCalendar.isChecked(),
                    chosen, reminders.isChecked(), quiet.isChecked());
            if (enabled.isChecked() && !ComplianceSettings.hasAutoProcessingConsent(this)) {
                showAutoProcessingDisclosure(() -> {
                    if (autoCalendar.isChecked() && !ComplianceSettings.hasAutoCalendarWriteConsent(this)) {
                        showAutoCalendarWriteDisclosure(persist);
                    } else persist.run();
                });
            } else if (autoCalendar.isChecked() && !ComplianceSettings.hasAutoCalendarWriteConsent(this)) {
                showAutoCalendarWriteDisclosure(persist);
            } else persist.run();
        }); body.addView(save, matchHeight(58));
        long success = settings.getLong("auto_last_success", 0); long failure = settings.getLong("auto_last_failure", 0); body.addView(UiKit.space(this, 24));
        addMeta(body, "上次成功", success == 0 ? "尚无" : DATE_TIME.format(new Date(success))); addMeta(body, "上次失败", failure == 0 ? "尚无" : DATE_TIME.format(new Date(failure)));
        String error = settings.getString("auto_last_error", ""); if (!error.isEmpty()) addMeta(body, "失败原因", error);
        renderScroll(body, -1);
    }

    private void saveAutomationSettings(boolean enabled, boolean autoCalendar, int intervalMinutes,
                                        boolean reminders, boolean quietHours) {
        settings.edit()
                .putBoolean("auto_processing_enabled", enabled)
                .putBoolean("auto_calendar_write_enabled", enabled && autoCalendar)
                .putInt("auto_interval_minutes", intervalMinutes)
                .putBoolean("proactive_reminders_enabled", reminders)
                .putBoolean("quiet_hours_enabled", quietHours)
                .apply();
        AutoProcessingScheduler.schedule(this);
        toast("自动整理设置已保存");
        showSettings();
    }

    private void showAutoProcessingDisclosure(Runnable accepted) {
        String disclosure = "开启后，MindAI 会按你选择的频率在后台处理全部未总结通知。为生成总结、待办、记忆、画像和重要提醒，经过本地筛选的通知正文，以及必要的近期日历、待办、记忆和画像上下文，会发送给 OpenRouter，并由其实际路由到的模型提供商处理。\n\n" +
                "后台调用会消耗你自己的 OpenRouter 额度。模型提供商可能变化，保留和训练政策也可能不同；ZDR 只会限制为当前标记兼容的端点，不代表所有服务绝对不保留数据。\n\n" +
                "你可以随时关闭自动整理。关闭后仍可手动查看通知、创建待办与日程，失败的批次不会被标记为已总结。";
        new AlertDialog.Builder(this)
                .setTitle("后台 AI 自动整理")
                .setMessage(disclosure)
                .setPositiveButton("同意并开启", (dialog, which) -> {
                    ComplianceSettings.grantAutoProcessingConsent(this);
                    accepted.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showAutoCalendarWriteDisclosure(Runnable accepted) {
        new AlertDialog.Builder(this)
                .setTitle("允许 AI 自动写入日历？")
                .setMessage("开启后，MindAI 可把通知中时间、参与关系和标题都明确，且置信度达到安全阈值的日程写入你选定的系统日历。写入项会标记为 MindAI 自动整理并保留来源；有冲突、时间不完整或无法确认你参与时不会写入。\n\n该功能默认关闭。你仍应核对 AI 生成的日程，并可随时关闭或删除日程。")
                .setPositiveButton("同意自动写入", (dialog, which) -> {
                    ComplianceSettings.grantAutoCalendarWriteConsent(this);
                    accepted.run();
                })
                .setNegativeButton("保持关闭", null)
                .show();
    }

    private void showCalendarSettings() {
        final int screen = beginScreenLoad();
        backAction = this::showSettings;
        LinearLayout body = pageBody(); body.addView(backHeader("本地日历", this::showSettings)); body.addView(UiKit.space(this, 24));
        if (!hasCalendarRead()) {
            body.addView(UiKit.title(this, "连接系统日历")); body.addView(UiKit.space(this, 16)); body.addView(UiKit.text(this, "读取日程用于首页提醒、冲突判断和自然语言查询；当你使用 AI 时，选中的近期日程上下文可能发送给 OpenRouter 和模型提供商。写入权限只在创建、修改或删除日程时另行请求。", 15, UiKit.SUB));
            body.addView(UiKit.space(this, 24)); Button grant = UiKit.accentButton(this, "授权读取日历"); grant.setOnClickListener(v -> requestCalendarReadPermission()); body.addView(grant, matchHeight(58)); renderScroll(body, -1); return;
        }
        body.addView(UiKit.title(this, "已连接的日历")); body.addView(UiKit.space(this, 8)); body.addView(UiKit.text(this, "取消勾选后，该日历不会参与首页提醒和 AI 判断。", 13, UiKit.SUB)); body.addView(UiKit.space(this, 18));
        LinearLayout list = UiKit.vertical(this); list.addView(UiKit.text(this, "正在读取日历列表…", 14, UiKit.SUB)); body.addView(list); renderScroll(body, -1);
        queryExecutor.execute(() -> {
            List<Models.CalendarSource> sources = new CalendarRepository(this).calendars();
            long configuredDefault = settings.getLong("default_calendar_id", 0);
            long candidateDefault = configuredDefault;
            if (candidateDefault == 0) for (Models.CalendarSource source : sources) {
                if (source.visible && source.writable) { candidateDefault = source.id; break; }
            }
            final long resolvedDefault = candidateDefault;
            runUi(() -> {
            if (!screenIsCurrent(screen)) return;
            list.removeAllViews(); Set<String> excluded = new HashSet<>(settings.getStringSet("excluded_calendar_ids", new HashSet<>()));
            if (sources.isEmpty()) list.addView(emptyBlock("没有可用日历", "请先在系统日历中添加账户。"));
            for (Models.CalendarSource source : sources) {
                LinearLayout row = UiKit.horizontal(this);
                CheckBox box = checkbox(source.name + (source.accountName.isEmpty() ? "" : "\n" + source.accountName), !excluded.contains(String.valueOf(source.id)));
                box.setOnCheckedChangeListener((button, checked) -> { if (checked) excluded.remove(String.valueOf(source.id)); else excluded.add(String.valueOf(source.id)); settings.edit().putStringSet("excluded_calendar_ids", new HashSet<>(excluded)).apply(); refreshAssistantHome(false); });
                row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                if (source.writable) {
                    boolean selectedDefault = source.id == resolvedDefault;
                    TextView makeDefault = UiKit.chip(this, selectedDefault ? "默认写入" : "设为默认", selectedDefault);
                    makeDefault.setOnClickListener(v -> { settings.edit().putLong("default_calendar_id", source.id).apply(); showCalendarSettings(); });
                    row.addView(makeDefault);
                }
                list.addView(row); list.addView(UiKit.line(this));
            }
        }); });
    }

    private void showOpenRouter(boolean firstRun) {
        beginScreenLoad();
        backAction = firstRun ? this::showHome : this::showSettings;
        LinearLayout body = pageBody(); body.addView(backHeader("OpenRouter 连接", firstRun ? this::showHome : this::showSettings));
        body.addView(UiKit.space(this, 22));
        LinearLayout connection = UiKit.horizontal(this);
        TextView status = UiKit.text(this, secure.configured() ? "■  已验证" : "■  未配置", 14, secure.configured() ? UiKit.ACCENT : UiKit.RED);
        connection.addView(status); body.addView(connection); body.addView(UiKit.space(this, 20));

        body.addView(fieldLabel("API Key"));
        EditText key = editText(secure.configured() ? "已保存：" + maskKey(secure.keyLabel()) + "（留空可重新验证）" : "sk-or-v1-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        body.addView(key, matchHeight(56)); body.addView(UiKit.text(this, "Android Keystore / LOCAL", 13, UiKit.SUB));
        CheckBox showKey = checkbox("显示正在输入的 Key", false);
        showKey.setOnCheckedChangeListener((b, checked) -> { key.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)); key.setSelection(key.length()); });
        body.addView(showKey); body.addView(UiKit.space(this, 18));

        body.addView(fieldLabel("模型模式"));
        RadioGroup mode = new RadioGroup(this); mode.setOrientation(LinearLayout.VERTICAL);
        String autoLabel = secure.fixedModel().endsWith(":free") ? "自动免费  ·  " + secure.fixedModel() : "自动免费";
        RadioButton auto = radio(autoLabel, !"fixed".equals(secure.mode()));
        RadioButton fixed = radio("固定免费模型  ·  高级", "fixed".equals(secure.mode()));
        mode.addView(auto); mode.addView(fixed); body.addView(mode);
        EditText fixedModel = editText("例如：provider/model:free"); fixedModel.setText(secure.fixedModel());
        LinearLayout.LayoutParams fixedParams = matchHeight(52); fixedParams.topMargin = dp(10); body.addView(fixedModel, fixedParams);
        Button loadModels = UiKit.outlineButton(this, "加载兼容的免费模型列表"); LinearLayout.LayoutParams loadParams = matchHeight(48); loadParams.topMargin = dp(8); body.addView(loadModels, loadParams);
        fixedModel.setVisibility(fixed.isChecked() ? View.VISIBLE : View.GONE);
        loadModels.setVisibility(fixed.isChecked() ? View.VISIBLE : View.GONE);
        mode.setOnCheckedChangeListener((g, checkedId) -> { fixedModel.setVisibility(checkedId == fixed.getId() ? View.VISIBLE : View.GONE); loadModels.setVisibility(checkedId == fixed.getId() ? View.VISIBLE : View.GONE); });
        body.addView(UiKit.space(this, 18));

        Switch zdr = new Switch(this); zdr.setText("Zero Data Retention"); zdr.setTextColor(UiKit.TEXT); zdr.setTextSize(17); zdr.setChecked(secure.validatedAt() > 0 && secure.zdr());
        zdr.setThumbTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); body.addView(zdr, matchHeight(52));
        body.addView(UiKit.text(this, "ZDR 可能减少可用免费模型。", 13, UiKit.SUB));
        body.addView(UiKit.space(this, 20));

        TextView advancedTitle = UiKit.chip(this, "ADVANCED  ›", false); body.addView(advancedTitle, matchHeight(48));
        LinearLayout advanced = UiKit.vertical(this); advanced.setVisibility(View.GONE); advanced.setPadding(0, dp(12), 0, 0);
        advanced.addView(fieldLabel("服务地址"));
        TextView endpoint = UiKit.text(this, OpenRouterClient.API_BASE_URL + "\n发布版固定为 OpenRouter 官方 HTTPS 地址，避免 Key 被发送到未知服务器。", 13, UiKit.SUB);
        endpoint.setPadding(dp(14), dp(12), dp(14), dp(12));
        endpoint.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(14), dp(1)));
        advanced.addView(endpoint);
        TextView openRouterPrivacy = UiKit.text(this, "OpenRouter 隐私政策  ↗", 14, UiKit.ACCENT);
        openRouterPrivacy.setPadding(0, dp(16), 0, dp(10));
        openRouterPrivacy.setOnClickListener(v -> openExternalUrl("https://openrouter.ai/privacy/"));
        advanced.addView(openRouterPrivacy);
        TextView providerPrivacy = UiKit.text(this, "模型提供商日志与保留说明  ↗", 14, UiKit.ACCENT);
        providerPrivacy.setPadding(0, dp(8), 0, dp(10));
        providerPrivacy.setOnClickListener(v -> openExternalUrl("https://openrouter.ai/docs/guides/privacy/provider-logging/"));
        advanced.addView(providerPrivacy);
        TextView openRouterTerms = UiKit.text(this, "OpenRouter 服务条款  ↗", 14, UiKit.ACCENT);
        openRouterTerms.setPadding(0, dp(8), 0, dp(10));
        openRouterTerms.setOnClickListener(v -> openExternalUrl("https://openrouter.ai/terms"));
        advanced.addView(openRouterTerms);
        body.addView(advanced); advancedTitle.setOnClickListener(v -> advanced.setVisibility(advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        loadModels.setOnClickListener(v -> {
            String entered = key.getText().toString().trim();
            OpenRouterClient.Config listConfig = new OpenRouterClient.Config();
            listConfig.apiKey = entered.isEmpty() && secure.configured() ? secure.apiKey() : entered;
            listConfig.baseUrl = OpenRouterClient.API_BASE_URL;
            loadModels.setEnabled(false); loadModels.setText("正在加载…");
            executor.execute(() -> {
                try {
                    List<String> models = new OpenRouterClient().listFreeModels(listConfig);
                    runOnUiThread(() -> { loadModels.setEnabled(true); loadModels.setText("加载兼容的免费模型列表");
                        new AlertDialog.Builder(this).setTitle("选择免费模型").setItems(models.toArray(new String[0]), (d, which) -> { fixedModel.setText(models.get(which)); fixed.setChecked(true); }).show(); });
                } catch (Exception e) { runOnUiThread(() -> { loadModels.setEnabled(true); loadModels.setText("重新加载免费模型"); toast(safeMessage(e)); }); }
            });
        });
        body.addView(UiKit.space(this, 24));

        Button validate = UiKit.accentButton(this, secure.configured() ? "✦  重新验证并保存" : "✦  验证并保存"); body.addView(validate, matchHeight(60));
        body.addView(UiKit.space(this, 24)); body.addView(UiKit.section(this, "连接验证"));
        TextView progress = UiKit.mono(this, "1  本地校验  ┄  2  Key 与额度  ┄  3  免费路由\n4  结构化输出  ┄  5  安全保存", 11, UiKit.SUB);
        body.addView(progress);
        if (secure.configured()) {
            body.addView(UiKit.space(this, 18));
            body.addView(UiKit.text(this, "最近验证：" + DATE_TIME.format(new Date(secure.validatedAt())) +
                    "\n实际模型：" + secure.actualModel() + (secure.keyExpiresAt().isEmpty() ? "" : "\nKey 到期：" + secure.keyExpiresAt()), 13, UiKit.SUB));
            TextView remove = UiKit.text(this, "删除 OpenRouter 配置", 14, UiKit.RED); remove.setPadding(0, dp(22), 0, dp(10));
            remove.setOnClickListener(v -> confirm("删除 AI 配置？", "", () -> { secure.clearOpenRouter(); showOpenRouter(firstRun); })); body.addView(remove);
        }

        Runnable validationAction = () -> {
            String entered = key.getText().toString().trim();
            String apiKey = entered.isEmpty() && secure.configured() ? secure.apiKey() : entered;
            OpenRouterClient.Config config = new OpenRouterClient.Config();
            config.apiKey = apiKey; config.baseUrl = OpenRouterClient.API_BASE_URL;
            config.mode = mode.getCheckedRadioButtonId() == fixed.getId() ? "fixed" : "auto";
            config.fixedModel = fixedModel.getText().toString().trim(); config.zdr = zdr.isChecked();
            config.appName = "MindAI";
            validate.setEnabled(false); validate.setText("验证中…"); progress.setTextColor(UiKit.ACCENT); progress.setText("VERIFYING");
            executor.execute(() -> {
                try {
                    OpenRouterClient.ValidationResult result = new OpenRouterClient().validate(config);
                    secure.saveOpenRouter(config.apiKey, config.baseUrl, config.mode, config.fixedModel, config.zdr,
                            result.actualModel, System.currentTimeMillis(), result.keyLabel, result.expiresAt);
                    runOnUiThread(() -> {
                        String privacyMode = config.zdr ? "ZDR 已开启" : "ZDR 未开启";
                        progress.setText("✓  Key、免费路由与结构化输出可用 · " + privacyMode + "\n实际模型：" + result.actualModel + (result.quotaText.isEmpty() ? "" : "\n" + result.quotaText));
                        validate.setEnabled(true); validate.setText("验证成功  [→]"); status.setText("■  连接可用"); status.setTextColor(UiKit.ACCENT);
                        toast("OpenRouter 连接已安全保存");
                        if (firstRun) validate.setOnClickListener(next -> showHome());
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { progress.setTextColor(UiKit.RED); progress.setText("验证失败\n" + safeMessage(e)); validate.setEnabled(true); validate.setText("重新验证"); });
                }
            });
        };
        validate.setOnClickListener(v -> {
            if (ComplianceSettings.hasAiDataConsent(this)) validationAction.run();
            else showAiDataDisclosure(validationAction);
        });
        renderScroll(body, -1);
    }

    private void showAiDataDisclosure(Runnable accepted) {
        String disclosure = "MindAI 使用你自己的 OpenRouter API Key 调用第三方 AI。使用通知总结、画像导入解析或助理对话时，相关通知正文、你的问题，以及回答所需的待办、近期日程、记忆、画像和重要信息上下文，会通过 HTTPS 发送给 OpenRouter，并由其实际选择的模型提供商处理。\n\n" +
                "OpenRouter 和模型提供商可能记录输入与输出，保留期限和训练政策因端点而异。开启 ZDR 后，MindAI 会要求兼容的零保留端点；没有兼容端点时请求会失败，不会静默降级。免费模型和供应商可能随时变化。\n\n" +
                "API Key 由 Android Keystore 加密并只保存在本机。你可以随时删除 Key 或在“数据与隐私”撤回 AI 数据处理同意；撤回后本地查看、手动待办和信息库仍可使用。";
        new AlertDialog.Builder(this)
                .setTitle("AI 数据处理说明")
                .setMessage(disclosure)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    ComplianceSettings.grantAiDataConsent(this);
                    accepted.run();
                })
                .setNegativeButton("不同意", null)
                .show();
    }

    private void showPrivacyNotice() {
        String reportState = AiContentReportManager.hasEndpoint()
                ? "AI 内容举报会发送到开发者举报服务。"
                : "当前测试构建尚未配置开发者举报服务；举报会加密排队保存在本机。正式提交 Play 前必须配置 HTTPS 举报端点。";
        String notice = "隐私政策与数据使用\n\n" +
                "• 通知、待办、日程索引、记忆、画像和对话主要保存在本机；Android 备份已关闭。\n" +
                "• 只有在你主动使用 AI 或明确开启自动整理后，所需上下文才发送给 OpenRouter 和模型提供商。\n" +
                "• API Key 使用 Android Keystore 加密；生产版只连接 OpenRouter 官方 API 地址。\n" +
                "• 敏感通知默认排除，其余敏感数字默认脱敏。你可以导出或删除本地数据，并撤回通知采集与 AI 处理同意。\n" +
                "• AI 内容可能不准确，不应作为医疗、法律、财务或紧急服务的替代。\n\n" +
                reportState;
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("隐私政策")
                .setMessage(notice)
                .setNegativeButton("关闭", null);
        if (BuildConfig.PRIVACY_POLICY_URL != null
                && BuildConfig.PRIVACY_POLICY_URL.trim().startsWith("https://")) {
            builder.setPositiveButton("查看完整政策", (dialog, which) ->
                    openExternalUrl(BuildConfig.PRIVACY_POLICY_URL.trim()));
        }
        builder.show();
    }

    private void openExternalUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("没有可打开此链接的应用");
        }
    }

    private void showDataPrivacy() {
        final int screen = beginScreenLoad();
        backAction = this::showSettings;
        LinearLayout body = pageBody(); body.addView(backHeader("数据与隐私", this::showSettings));
        body.addView(UiKit.space(this, 24)); body.addView(UiKit.title(this, "数据"));
        body.addView(UiKit.space(this, 26));
        TextView localStats = addMeta(body, "本地保存", "正在统计…");
        addMeta(body, "发送给 AI", settings.getBoolean("auto_processing_enabled", false)
                ? "主动使用或后台自动整理时" : "仅主动使用 AI 时");
        addMeta(body, "原始通知保留", retentionName());
        addMeta(body, "通知采集同意", ComplianceSettings.hasNotificationCaptureConsent(this) ? "已同意" : "未同意");
        addMeta(body, "AI 数据处理同意", ComplianceSettings.hasAiDataConsent(this) ? "已同意" : "未同意");
        AiContentReportManager reportManager = new AiContentReportManager(this);
        addMeta(body, "AI 举报", AiContentReportManager.hasEndpoint()
                ? "已连接开发者服务" : reportManager.pendingCount() + " 条本机排队 · 待配置服务");
        body.addView(UiKit.space(this, 20));
        Button policy = UiKit.outlineButton(this, "查看隐私政策与第三方处理说明");
        policy.setOnClickListener(v -> showPrivacyNotice());
        body.addView(policy, matchHeight(52));
        Button revokeAi = UiKit.outlineButton(this, "撤回 AI 数据处理同意");
        LinearLayout.LayoutParams rap = matchHeight(52); rap.topMargin = dp(10);
        revokeAi.setOnClickListener(v -> confirm("撤回 AI 数据处理同意？",
                "自动整理会关闭；本地数据和 OpenRouter Key 不会自动删除。",
                () -> {
                    ComplianceSettings.revokeAiDataConsent(this);
                    toast("已撤回 AI 数据处理同意");
                    showDataPrivacy();
                }));
        body.addView(revokeAi, rap);
        Button revokeCapture = UiKit.outlineButton(this, "撤回通知采集同意");
        LinearLayout.LayoutParams rcp = matchHeight(52); rcp.topMargin = dp(10);
        revokeCapture.setOnClickListener(v -> confirm("撤回通知采集同意？",
                "MindAI 将停止保存新通知。系统通知使用权需在系统设置中单独关闭；已有本地数据不会自动删除。",
                () -> {
                    ComplianceSettings.revokeNotificationCaptureConsent(this);
                    toast("已停止通知采集");
                    showDataPrivacy();
                }));
        body.addView(revokeCapture, rcp);
        body.addView(UiKit.space(this, 20));
        Button export = UiKit.accentButton(this, "导出 JSON"); export.setOnClickListener(v -> exportData()); body.addView(export, matchHeight(58));
        Button exportMd = UiKit.outlineButton(this, "导出画像 / 记忆 MD");
        exportMd.setOnClickListener(v -> exportProfileMarkdown());
        LinearLayout.LayoutParams mdp = matchHeight(52); mdp.topMargin = dp(10); body.addView(exportMd, mdp);
        Button recycle = UiKit.outlineButton(this, "最近删除的记录 · …");
        recycle.setOnClickListener(v -> showRecycleBin()); LinearLayout.LayoutParams rp = matchHeight(52); rp.topMargin = dp(10); body.addView(recycle, rp);
        body.addView(UiKit.space(this, 28)); body.addView(UiKit.section(this, "DELETE"));
        Button notifications = UiKit.outlineButton(this, "通知"); notifications.setTextColor(UiKit.RED);
        notifications.setOnClickListener(v -> confirm("删除全部通知？", "", () -> runDatabaseAction("正在删除通知…", db::clearNotifications, this::showDataPrivacy))); body.addView(notifications, matchHeight(52));
        Button ai = UiKit.outlineButton(this, "AI 结果"); ai.setTextColor(UiKit.RED); LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(10);
        ai.setOnClickListener(v -> confirm("删除 AI 结果？", "", () -> runDatabaseAction("正在删除 AI 结果…", db::clearAiResults, this::showDataPrivacy))); body.addView(ai, p);
        Button all = UiKit.outlineButton(this, "全部本地数据"); all.setTextColor(UiKit.RED); LinearLayout.LayoutParams p2 = matchHeight(52); p2.topMargin = dp(10);
        all.setOnClickListener(v -> confirm("彻底清除？", "通知、总结、记录、对话、AI 举报队列、授权记录和 OpenRouter 配置都会删除，且不可恢复。", () -> runDatabaseAction("正在清除本地数据…", this::clearAllLocalData, this::showHome))); body.addView(all, p2);
        body.addView(UiKit.space(this, 18));
        renderScroll(body, -1);
        queryExecutor.execute(() -> {
            int today = db.countToday(); int todos = db.countOpenTodos(); int memories = db.countMemories(); int profiles = db.countProfileFacts();
            int trash = db.trashedRecords().size();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                localStats.setText(today + " 条今日通知 · " + todos + " 个待办 · " + memories + " 条记忆 · " + profiles + " 条画像");
                recycle.setText("最近删除的记录 · " + trash);
            });
        });
    }

    private void clearAllLocalData() {
        AutoProcessingScheduler.cancel(this);
        db.clearEverything();
        assistantConversations.clearConversation();
        secure.clearOpenRouter();
        new AiContentReportManager(this).clearLocalReports();
        getSharedPreferences("mindai_processing_changes", MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("mindai_widget_summary", MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("mindai_notification_open_audit", MODE_PRIVATE).edit().clear().apply();
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (notificationManager != null) notificationManager.cancelAll();
        settings.edit().clear().commit();
        ComplianceSettings.migrate(this);
    }

    private void showRecycleBin() {
        toast("正在读取最近删除…");
        int screen = screenSeq;
        queryExecutor.execute(() -> {
            List<Models.ImportantRecord> trash = db.trashedRecords();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                if (trash.isEmpty()) { toast("最近删除中没有记录"); return; }
                String[] titles = new String[trash.size()];
                for (int i = 0; i < trash.size(); i++) titles[i] = trash.get(i).title + " · 点击恢复";
                new AlertDialog.Builder(this).setTitle("最近删除 · 保留 7 天").setItems(titles, (d, which) ->
                        runDatabaseAction("正在恢复记录…", () -> db.restoreRecord(trash.get(which).id), this::showDataPrivacy))
                        .setNegativeButton("关闭", null).show();
            });
        });
    }

    private void showDiagnostics() {
        final int screen = beginScreenLoad();
        backAction = this::showSettings;
        LinearLayout body = pageBody(); body.addView(backHeader("权限与连接状态", this::showSettings)); body.addView(UiKit.space(this, 24));
        body.addView(UiKit.title(this, hasNotificationAccess() ? "系统运行正常" : "通知采集需要修复")); body.addView(UiKit.space(this, 22));
        addMeta(body, "通知使用权", hasNotificationAccess() ? "已开启" : "未开启");
        addMeta(body, "日历读取", hasCalendarRead() ? "已开启" : "未开启");
        addMeta(body, "日历写入", hasCalendarWrite() ? "已开启" : "未开启");
        if (Build.VERSION.SDK_INT >= 33) addMeta(body, "助理提醒", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "已开启" : "未开启");
        TextView lastCapture = addMeta(body, "最后成功捕获", "正在读取…");
        TextView pending = addMeta(body, "待处理通知", "正在读取…");
        TextView lastSummary = addMeta(body, "最后成功总结", "正在读取…");
        addMeta(body, "OpenRouter", secure.configured() ? "已验证 · " + secure.actualModel() : "未配置");
        body.addView(UiKit.space(this, 22));
        Button permission = UiKit.accentButton(this, hasNotificationAccess() ? "检查系统通知权限" : "修复通知使用权");
        permission.setOnClickListener(v -> {
            if (ComplianceSettings.hasNotificationCaptureConsent(this)) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            } else {
                showNotificationAccessDisclosure();
            }
        });
        body.addView(permission, matchHeight(58));
        Button calendar = UiKit.outlineButton(this, "设置日历读取权限"); calendar.setOnClickListener(v -> requestCalendarReadPermission()); LinearLayout.LayoutParams cp = matchHeight(52); cp.topMargin = dp(10); body.addView(calendar, cp);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Button reminders = UiKit.outlineButton(this, "开启助理提醒"); reminders.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIFICATIONS)); LinearLayout.LayoutParams np = matchHeight(52); np.topMargin = dp(10); body.addView(reminders, np);
        }
        Button router = UiKit.outlineButton(this, "重新验证 OpenRouter"); router.setOnClickListener(v -> showOpenRouter(false)); LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(10); body.addView(router, p);
        renderScroll(body, -1);
        queryExecutor.execute(() -> {
            long capturedAt = db.lastCapturedAt(); int pendingCount = db.countUnsummarized(); long summaryAt = db.lastSummaryAt();
            runUi(() -> {
                if (!screenIsCurrent(screen)) return;
                lastCapture.setText(capturedAt == 0 ? "尚无记录" : DATE_TIME.format(new Date(capturedAt)));
                pending.setText(pendingCount + " 条");
                lastSummary.setText(summaryAt == 0 ? "尚无记录" : DATE_TIME.format(new Date(summaryAt)));
            });
        });
    }

    private void renderScroll(LinearLayout body, int navIndex) {
        markAssistantChatHidden();
        root.removeAllViews();
        LinearLayout shell = UiKit.vertical(this); shell.setBackgroundColor(UiKit.BG);
        View fixedHeader = takeFixedHeader(body);
        if (fixedHeader != null) addFixedHeader(shell, fixedHeader);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        addAssistantStatusSlot(shell);
        if (navIndex >= 0) shell.addView(bottomNav(navIndex), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)));
        root.addView(shell, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void renderAssistantChatPage(LinearLayout body, View composer) {
        root.removeAllViews();
        LinearLayout shell = UiKit.vertical(this); shell.setBackgroundColor(UiKit.BG);
        View fixedHeader = takeFixedHeader(body);
        if (fixedHeader != null) addFixedHeader(shell, fixedHeader);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        shell.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(shell, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        assistantChatScroll = scroll;
        scrollAssistantToBottom();
    }

    private View takeFixedHeader(LinearLayout body) {
        if (body == null || body.getChildCount() == 0) return null;
        View first = body.getChildAt(0);
        if (!FIXED_HEADER_TAG.equals(first.getTag())) return null;
        body.removeViewAt(0);
        return first;
    }

    private void addFixedHeader(LinearLayout shell, View header) {
        LinearLayout fixed = UiKit.vertical(this);
        fixed.setPadding(dp(20), dp(4), dp(20), 0);
        fixed.setBackgroundColor(UiKit.BG);
        fixed.setElevation(dp(2));
        fixed.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        fixed.addView(UiKit.line(this));
        shell.addView(fixed, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(61)));
    }

    private void renderAssistantHome(LinearLayout body) {
        markAssistantChatHidden();
        root.removeAllViews();
        LinearLayout shell = UiKit.vertical(this); shell.setBackgroundColor(UiKit.BG);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout composerWrap = UiKit.horizontal(this); composerWrap.setPadding(dp(16), dp(9), dp(16), dp(9));
        composerWrap.setBackgroundColor(UiKit.BG);
        LinearLayout composer = UiKit.horizontal(this); composer.setPadding(dp(4), 0, dp(5), 0);
        composer.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(28), dp(1)));
        EditText input = new EditText(this); input.setHint("问助理，或直接新增日程、待办…"); input.setHintTextColor(UiKit.SUB);
        input.setTextColor(UiKit.TEXT); input.setTextSize(15); input.setSingleLine(true); input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(14), 0, dp(8), 0); input.setFocusable(false); input.setCursorVisible(false);
        input.setContentDescription("打开助理对话并输入"); input.setOnClickListener(v -> openAssistantChat("", false, true));
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
        TextView sendIcon = UiKit.materialIcon(this, "arrow_upward", 22, Color.WHITE); sendIcon.setContentDescription("发送给助理");
        sendIcon.setBackground(UiKit.box(UiKit.ACCENT, UiKit.ACCENT, dp(22), 0));
        sendIcon.setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) openAssistantChat("", false, true);
            else openAssistantChat(value, true, false);
        });
        composer.addView(sendIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        composerWrap.addView(composer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        shell.addView(composerWrap, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74)));
        addAssistantStatusSlot(shell);
        shell.addView(bottomNav(0), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)));
        root.addView(shell, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View assistantEntryButton() {
        FrameLayout button = new FrameLayout(this);
        assistantEntryButtonView = button;
        renderAssistantEntryButton();
        return button;
    }

    private void renderAssistantEntryButton() {
        FrameLayout button = assistantEntryButtonView;
        if (button == null) return;
        button.removeAllViews();
        AssistantConversationManager.Snapshot snapshot = assistantConversations.snapshot();
        button.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(24), dp(1)));
        button.setContentDescription(snapshot.unreadCount > 0
                ? "进入助理对话，" + snapshot.unreadCount + " 条未读回复" : "进入助理对话");
        TextView icon = UiKit.materialIcon(this, "chat_bubble_outline", 22, UiKit.TEXT);
        button.addView(icon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (snapshot.unreadCount > 0) {
            View dot = new View(this); dot.setBackground(UiKit.box(UiKit.ACCENT, UiKit.ACCENT, dp(5), 0));
            FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP | Gravity.END);
            dotParams.topMargin = dp(4); dotParams.rightMargin = dp(4); button.addView(dot, dotParams);
        }
        button.setOnClickListener(v -> openAssistantChat("", false, false));
    }

    private void markAssistantChatHidden() {
        if (!assistantChatVisible) return;
        assistantChatVisible = false;
        assistantProgressCard = null; assistantProgressTitleView = null;
        assistantProgressDetailView = null; assistantProgressSpinner = null; assistantChatScroll = null;
        assistantConversations.setConversationVisible(false);
    }

    private void minimizeAssistantChat() {
        assistantChatVisible = false;
        backAction = null;
        assistantConversations.setConversationVisible(false);
        int target = assistantReturnRoot;
        if (target == 1) showSchedule();
        else if (target == 2) showLibrary();
        else if (target == 3) showSettings();
        else showHome();
    }

    private void addAssistantStatusSlot(LinearLayout shell) {
        LinearLayout slot = UiKit.vertical(this);
        assistantStatusSlot = slot;
        shell.addView(slot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderAssistantStatusSlot();
    }

    private void renderAssistantStatusSlot() {
        LinearLayout slot = assistantStatusSlot;
        if (slot == null || !slot.isAttachedToWindow() && slot.getParent() == null) return;
        slot.removeAllViews();
        AssistantConversationManager.Snapshot snapshot = assistantConversations.snapshot();
        if (!snapshot.showStatusPill()) {
            slot.setVisibility(View.GONE);
            return;
        }
        slot.setVisibility(View.VISIBLE);
        slot.setPadding(dp(16), dp(6), dp(16), dp(6));
        LinearLayout pill = UiKit.horizontal(this); pill.setPadding(dp(13), dp(8), dp(12), dp(8)); pill.setElevation(dp(4));
        int border = AssistantConversationManager.FAILED.equals(snapshot.state) ? UiKit.RED : UiKit.LINE;
        pill.setBackground(UiKit.box(UiKit.BG_ALT, border, dp(16), dp(1)));
        if (snapshot.running()) {
            ProgressBar spinner = new ProgressBar(this); spinner.setIndeterminate(true);
            spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
            pill.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        } else {
            String iconName = AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state) ? "help_outline"
                    : AssistantConversationManager.FAILED.equals(snapshot.state) ? "error_outline" : "check_circle";
            int iconColor = AssistantConversationManager.FAILED.equals(snapshot.state) ? UiKit.RED
                    : AssistantConversationManager.AWAITING_CONFIRMATION.equals(snapshot.state) ? Color.rgb(163, 93, 0) : UiKit.ACCENT;
            pill.addView(UiKit.materialIcon(this, iconName, 23, iconColor), new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        TextView label = UiKit.text(this, snapshot.pillText(), 14, UiKit.TEXT); label.setTypeface(Typeface.DEFAULT, Typeface.BOLD); label.setPadding(dp(10), 0, dp(8), 0);
        pill.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1));
        TextView action = UiKit.text(this, AssistantConversationManager.FAILED.equals(snapshot.state) ? "重试" : "查看", 13, UiKit.ACCENT);
        action.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); pill.addView(action, new LinearLayout.LayoutParams(dp(46), dp(36)));
        pill.setContentDescription(snapshot.pillText() + "，点击返回助理对话");
        pill.setOnClickListener(v -> openAssistantChat("", false, false));
        slot.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
    }

    private LinearLayout bottomNav(int active) {
        LinearLayout nav = UiKit.horizontal(this); nav.setPadding(dp(8), dp(4), dp(8), dp(2));
        nav.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, 0, dp(1)));
        String[] icons = {"auto_awesome", "event", "library_books", "person_outline"};
        String[] labels = {"助理", "日程", "信息库", "我的"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = UiKit.vertical(this); item.setGravity(Gravity.CENTER); item.setPadding(dp(4), dp(3), dp(4), dp(2));
            TextView icon = UiKit.materialIcon(this, icons[i], 24, i == active ? UiKit.ACCENT : UiKit.SUB);
            if (i == active) icon.setBackground(UiKit.box(UiKit.ACCENT_SOFT, UiKit.ACCENT_SOFT, dp(20), 0));
            FrameLayout iconWrap = new FrameLayout(this);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(44), dp(34), Gravity.CENTER);
            iconWrap.addView(icon, iconParams);
            int unread = assistantConversations == null ? 0 : assistantConversations.snapshot().unreadCount;
            if (i == 0 && unread > 0) {
                TextView badge = UiKit.text(this, unread > 9 ? "9+" : String.valueOf(unread), 9, Color.WHITE);
                badge.setGravity(Gravity.CENTER); badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                badge.setBackground(UiKit.box(UiKit.ACCENT, UiKit.ACCENT, dp(9), 0));
                badge.setContentDescription(unread + " 条未读助理回复");
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
                iconWrap.addView(badge, badgeParams);
                item.setContentDescription("助理，" + unread + " 条未读回复");
            }
            item.addView(iconWrap, new LinearLayout.LayoutParams(dp(48), dp(36)));
            TextView label = UiKit.text(this, labels[i], 12, i == active ? UiKit.ACCENT : UiKit.SUB); label.setGravity(Gravity.CENTER); label.setPadding(0, dp(4), 0, 0);
            item.addView(label); item.setEnabled(i != active); item.setOnClickListener(v -> showRoot(index));
            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        return nav;
    }

    private void showRoot(int index) {
        long now = SystemClock.elapsedRealtime();
        if (index == currentRoot && backAction == null) return;
        if (now - lastNavClickAt < 220) return;
        lastNavClickAt = now;
        if (index == 1) todoVisibleLimit = PAGE_SIZE;
        if (index == 2) { notificationVisibleLimit = PAGE_SIZE; memoryVisibleLimit = PAGE_SIZE; }
        if (index == 0) showHome(); else if (index == 1) showSchedule(); else if (index == 2) showLibrary(); else showSettings();
    }

    private LinearLayout pageBody() {
        LinearLayout body = UiKit.vertical(this); body.setPadding(dp(20), dp(18), dp(20), dp(30));
        body.setBackgroundColor(UiKit.BG);
        body.setFocusableInTouchMode(true); body.requestFocus();
        return body;
    }

    private LinearLayout backHeader(String title, Runnable action) {
        LinearLayout header = UiKit.horizontal(this);
        header.setTag(FIXED_HEADER_TAG);
        TextView back = UiKit.materialIcon(this, "arrow_back", 28, UiKit.TEXT); back.setContentDescription("返回");
        if (action != null) back.setOnClickListener(v -> { backAction = null; action.run(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = UiKit.text(this, title, 21, UiKit.TEXT); label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(label, new LinearLayout.LayoutParams(0, dp(52), 1)); return header;
    }

    private TextView emptyBlock(String title, String detail) {
        TextView text = UiKit.text(this, detail == null || detail.isEmpty() ? title : title + "\n" + detail, 15, UiKit.SUB); text.setPadding(dp(18), dp(20), dp(18), dp(20));
        text.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); return text;
    }

    private EditText editText(String hint) {
        EditText input = new EditText(this); input.setHint(hint); input.setHintTextColor(UiKit.SUB); input.setTextColor(UiKit.TEXT);
        input.setTextSize(15); input.setSingleLine(true); input.setPadding(dp(16), 0, dp(16), 0); input.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(14), dp(1)));
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH); return input;
    }

    private TextView fieldLabel(String label) {
        TextView t = UiKit.text(this, label, 15, UiKit.TEXT); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); t.setPadding(0, 0, 0, dp(8)); return t;
    }

    private CheckBox checkbox(String label, boolean checked) {
        CheckBox box = new CheckBox(this); box.setText(label); box.setTextColor(UiKit.TEXT); box.setTextSize(15); box.setChecked(checked);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT)); box.setPadding(0, dp(4), 0, dp(4)); return box;
    }

    private RadioButton radio(String label, boolean checked) {
        RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(label); radio.setTextColor(UiKit.TEXT);
        radio.setTextSize(16); radio.setChecked(checked); radio.setButtonTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        radio.setPadding(0, dp(10), 0, dp(10)); return radio;
    }

    private TextWatcher watcher(Runnable reload) {
        return watcher(null, reload);
    }

    private TextWatcher watcher(Runnable immediateFeedback, Runnable reload) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (immediateFeedback != null) immediateFeedback.run();
                if (pendingSearch != null) mainHandler.removeCallbacks(pendingSearch);
                pendingSearch = reload;
                mainHandler.postDelayed(reload, 300);
            }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private View loadingBlock(String label) {
        LinearLayout row = UiKit.horizontal(this);
        row.setPadding(dp(18), dp(18), dp(18), dp(18));
        row.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1)));
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(UiKit.ACCENT));
        row.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView text = UiKit.text(this, label == null || label.isEmpty() ? "正在加载…" : label, 14, UiKit.SUB);
        text.setPadding(dp(14), 0, 0, 0);
        row.addView(text, new LinearLayout.LayoutParams(0, dp(40), 1));
        return row;
    }

    private int beginScreenLoad() { return ++screenSeq; }

    private boolean screenIsCurrent(int seq) {
        return !destroyed && !isFinishing() && seq == screenSeq;
    }

    private void runUi(Runnable action) {
        if (destroyed) return;
        runOnUiThread(() -> { if (!destroyed && !isFinishing()) action.run(); });
    }

    private TextView addMeta(LinearLayout body, String label, String value) {
        LinearLayout row = UiKit.horizontal(this); row.setPadding(0, dp(11), 0, dp(11));
        TextView l = UiKit.text(this, label, 14, UiKit.SUB); row.addView(l, new LinearLayout.LayoutParams(dp(112), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = UiKit.text(this, value, 14, UiKit.TEXT); v.setGravity(Gravity.END); row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        body.addView(row); body.addView(UiKit.line(this));
        return v;
    }

    private void toggleSetting(String key, String label, Runnable after) {
        boolean current = settings.getBoolean(key, false);
        new AlertDialog.Builder(this).setTitle(label).setMessage(current ? "当前已启用。是否关闭？" : "当前未启用。是否开启？")
                .setPositiveButton(current ? "关闭" : "开启", (d, w) -> { settings.edit().putBoolean(key, !current).apply(); after.run(); })
                .setNegativeButton("取消", null).show();
    }

    private void chooseRetention() {
        String[] labels = {"30 天", "90 天", "永久"}; int[] days = {30, 90, 36500}; int current = settings.getInt("retention_days", 30);
        int selected = current == 90 ? 1 : current >= 36500 ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("原始通知保留期限").setSingleChoiceItems(labels, selected, (d, which) -> {
            settings.edit().putInt("retention_days", days[which]).apply(); d.dismiss();
            runDatabaseAction("正在更新保留期限…", () -> db.applyRetentionPolicy(days[which]), this::showSettings);
        }).show();
    }

    private void exportData() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "MindAI_数据导出_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date()) + ".json");
        try { startActivityForResult(intent, REQ_EXPORT_JSON); }
        catch (Exception e) { toast("无法打开文件保存器"); }
    }

    private void writeJsonExport(Uri uri) {
        final int screen = beginScreenLoad();
        LinearLayout loading = pageBody();
        loading.addView(backHeader("导出数据", this::showDataPrivacy));
        loading.addView(UiKit.space(this, 24));
        loading.addView(loadingBlock("正在流式导出全部本地数据…"));
        renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("无法写入文件");
                db.exportJson(out);
                out.flush();
                runUi(() -> { if (screenIsCurrent(screen)) { toast("数据已导出"); showDataPrivacy(); } });
            } catch (Exception e) {
                runUi(() -> { if (screenIsCurrent(screen)) { toast("导出失败：" + safeMessage(e)); showDataPrivacy(); } });
            }
        });
    }

    private void chooseProfileMarkdown() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/markdown", "text/plain", "application/octet-stream"});
        try { startActivityForResult(intent, REQ_IMPORT_PROFILE_MD); }
        catch (Exception e) { toast("无法打开文件选择器"); }
    }

    private void exportProfileMarkdown() {
        toast("正在准备导出文件");
        queryExecutor.execute(() -> {
            pendingMarkdownExport = db.exportProfileMemoryMarkdown();
            runOnUiThread(this::launchProfileMarkdownExporter);
        });
    }

    private void launchProfileMarkdownExporter() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/markdown");
        intent.putExtra(Intent.EXTRA_TITLE, "MindAI_画像与记忆_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date()) + ".md");
        try { startActivityForResult(intent, REQ_EXPORT_PROFILE_MD); }
        catch (Exception e) {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/markdown");
            share.putExtra(Intent.EXTRA_SUBJECT, "MindAI 画像与记忆");
            share.putExtra(Intent.EXTRA_TEXT, pendingMarkdownExport);
            startActivity(Intent.createChooser(share, "导出 Markdown"));
        }
    }

    private void importProfileMarkdown(Uri uri) {
        final int screen = beginScreenLoad();
        backAction = this::showMemoryHub;
        LinearLayout loading = pageBody();
        loading.addView(backHeader("导入画像与记忆", this::showMemoryHub));
        loading.addView(UiKit.space(this, 24));
        loading.addView(loadingBlock("正在解析并合并 Markdown…"));
        loading.addView(UiKit.space(this, 12));
        loading.addView(UiKit.text(this, "大量画像会分批写入，请保持 App 在前台。", 13, UiKit.SUB));
        renderScroll(loading, -1);
        queryExecutor.execute(() -> {
            try {
                String markdown = readText(uri);
                AppDatabase.MdImportResult result = db.importProfileMarkdown(markdown);
                runUi(() -> {
                    if (!screenIsCurrent(screen)) return;
                    memoryMode = "profile";
                    toast("导入 " + result.profileFacts + " 条画像 / " + result.memories + " 条记忆");
                    showMemoryHub();
                });
            } catch (Exception e) {
                runUi(() -> {
                    if (screenIsCurrent(screen)) {
                        toast("导入失败：" + safeMessage(e));
                        showMemoryHub();
                    }
                });
            }
        });
    }

    private void writeProfileMarkdown(Uri uri) {
        queryExecutor.execute(() -> {
            String markdown = pendingMarkdownExport == null || pendingMarkdownExport.isEmpty()
                    ? db.exportProfileMemoryMarkdown() : pendingMarkdownExport;
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("无法写入文件");
                out.write(markdown.getBytes(StandardCharsets.UTF_8));
                out.flush();
                runOnUiThread(() -> toast("已导出 MD"));
            } catch (Exception e) {
                runOnUiThread(() -> toast("导出失败：" + safeMessage(e)));
            }
        });
    }

    private String readText(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("无法读取文件");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 2_000_000) throw new IllegalStateException("文件过大");
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("确认", (d, w) -> action.run()).setNegativeButton("取消", null).show();
    }

    private void runDatabaseAction(String message, Runnable action, Runnable after) {
        final int screen = beginScreenLoad();
        LinearLayout body = pageBody();
        body.addView(UiKit.space(this, 36));
        body.addView(loadingBlock(message));
        renderScroll(body, -1);
        queryExecutor.execute(() -> {
            try {
                action.run();
                MindAiWidgetUpdater.updateAll(this);
                runUi(() -> { if (screenIsCurrent(screen) && after != null) after.run(); });
            } catch (Exception e) {
                runUi(() -> {
                    if (!screenIsCurrent(screen)) return;
                    toast("操作失败：" + safeMessage(e));
                    if (after != null) after.run();
                });
            }
        });
    }

    private interface DateChosen { void onDate(long dayStart); }

    private void pickDate(long initial, DateChosen callback) {
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(initial);
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar chosen = Calendar.getInstance(); chosen.set(year, month, day, 0, 0, 0); chosen.set(Calendar.MILLISECOND, 0);
            callback.onDate(chosen.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickDateTime(long initial, DateChosen callback) {
        Calendar current = Calendar.getInstance(); current.setTimeInMillis(initial);
        new DatePickerDialog(this, (picker, year, month, day) -> {
            Calendar selected = Calendar.getInstance(); selected.setTimeInMillis(initial); selected.set(year, month, day);
            new TimePickerDialog(this, (time, hour, minute) -> {
                selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute); selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0);
                callback.onDate(selected.getTimeInMillis());
            }, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE), true).show();
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show();
    }

    private boolean hasCalendarRead() {
        return ComplianceSettings.hasCalendarDisclosureConsent(this)
                && checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCalendarWrite() {
        return ComplianceSettings.hasCalendarDisclosureConsent(this)
                && checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCalendarReadPermission() {
        showCalendarDisclosure(() -> requestPermissions(
                new String[]{Manifest.permission.READ_CALENDAR}, REQ_CALENDAR_READ));
    }

    private void requestCalendarWritePermission() {
        showCalendarDisclosure(() -> requestPermissions(
                new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR},
                REQ_CALENDAR_WRITE));
    }

    private void showCalendarDisclosure(Runnable accepted) {
        if (ComplianceSettings.hasCalendarDisclosureConsent(this)) {
            accepted.run();
            return;
        }
        String disclosure = "MindAI 读取你选择的系统日历名称、日程标题、时间、地点和说明，用于本机展示日程、判断时间冲突和回答你的查询。\n\n" +
                "当你手动使用 AI，或明确开启自动整理时，近期且与你问题相关的日程上下文可能通过加密连接发送给 OpenRouter，并由其模型提供商处理。日历不会用于广告，也不会在未授权时读取。\n\n" +
                "读取权限可以拒绝；手动待办、通知浏览和本地信息库仍可使用。写入权限只在你创建、修改或删除日程时请求，AI 自动写入另有独立开关且默认关闭。";
        new AlertDialog.Builder(this)
                .setTitle("日历数据使用说明")
                .setMessage(disclosure)
                .setPositiveButton("同意并继续", (dialog, which) -> {
                    ComplianceSettings.grantCalendarDisclosureConsent(this);
                    accepted.run();
                })
                .setNegativeButton("暂不连接", null)
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CALENDAR_READ) {
            if (hasCalendarRead()) { toast("日历已连接"); refreshAssistantHome(false); if (currentRoot == 1) showSchedule(); else showHome(); }
            else toast("未获得日历权限，其他功能仍可使用");
        } else if (requestCode == REQ_CALENDAR_WRITE) {
            if (hasCalendarWrite()) toast("日历写入权限已开启，请再次执行刚才的操作");
            else toast("未获得日历写入权限，仍可只读查看日程");
        }
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat == null) return false;
        for (String name : flat.split(":")) {
            ComponentName component = ComponentName.unflattenFromString(name);
            if (component != null && getPackageName().equals(component.getPackageName())) return true;
        }
        return false;
    }

    private String appLabel(ApplicationInfo info) {
        try { return getPackageManager().getApplicationLabel(info).toString(); } catch (Exception e) { return info.packageName; }
    }

    private List<Models.ImportantRecord> recordsForSession(String sessionId) {
        List<Models.ImportantRecord> result = new ArrayList<>();
        for (Models.ImportantRecord r : db.records("all", "", 500)) if (sessionId.equals(r.sourceSummaryId)) result.add(r);
        return result;
    }

    private void showCurrentRoot() {
        int index = currentRoot;
        if (index == 1) showSchedule();
        else if (index == 2) showLibrary();
        else if (index == 3) showSettings();
        else showHome();
    }

    private Models.NeedToKnow visibleNeedToKnow() {
        Models.NeedToKnow need = db.latestNeedToKnow();
        if (need == null || need.sourceSummaryId == null || need.sourceSummaryId.isEmpty()) return need;
        boolean hasTodo = false;
        boolean hasOpenTodo = false;
        for (Models.TodoItem todo : db.todos("all", "", 500)) {
            if (!need.sourceSummaryId.equals(todo.sourceSummaryId)) continue;
            hasTodo = true;
            if ("open".equals(todo.status)) { hasOpenTodo = true; break; }
        }
        return hasTodo && !hasOpenTodo ? null : need;
    }

    private void addSummaryMiniStatus(LinearLayout body) {
        if (!summaryRunning && minimizedSummaryId.isEmpty() && minimizedSummaryError.isEmpty()) return;
        LinearLayout card = UiKit.horizontal(this);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(UiKit.box(UiKit.BG_ALT, summaryRunning ? UiKit.ACCENT : (minimizedSummaryError.isEmpty() ? UiKit.LINE : UiKit.RED), dp(16), dp(1)));
        String left = summaryRunning ? "SUMMARY  " + String.format(Locale.CHINA, "%02d", summaryPercent) : (minimizedSummaryError.isEmpty() ? "SUMMARY  DONE" : "SUMMARY  ERROR");
        card.addView(UiKit.mono(this, left, 12, summaryRunning ? UiKit.ACCENT : UiKit.SUB), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView right = UiKit.text(this, summaryRunning ? trimText(summaryStep, 14) : (minimizedSummaryError.isEmpty() ? "查看  ›" : "重试  ›"), 13,
                minimizedSummaryError.isEmpty() ? UiKit.TEXT : UiKit.RED);
        right.setGravity(Gravity.END);
        card.addView(right);
        card.setOnClickListener(v -> {
            if (summaryRunning) showSummaryRunningPanel();
            else if (!minimizedSummaryId.isEmpty()) showSummaryResult(minimizedSummaryId);
            else showSummaryScope();
        });
        body.addView(card);
        body.addView(UiKit.space(this, 14));
    }

    private void showSummaryRunningPanel() {
        backAction = this::showCurrentRoot;
        LinearLayout body = pageBody();
        body.addView(backHeader("总结中", this::showCurrentRoot));
        body.addView(UiKit.space(this, 70));
        TextView percent = UiKit.mono(this, String.format(Locale.CHINA, "%02d", summaryPercent), 72, UiKit.TEXT);
        body.addView(percent);
        body.addView(UiKit.title(this, summaryStep.isEmpty() ? "正在处理" : summaryStep));
        body.addView(UiKit.space(this, 24));
        Button minimize = UiKit.outlineButton(this, "最小化");
        minimize.setOnClickListener(v -> { summaryMinimized = true; showCurrentRoot(); });
        body.addView(minimize, matchHeight(54));
        renderScroll(body, -1);
    }

    private List<Models.TodoItem> todosForSession(String sessionId) {
        List<Models.TodoItem> result = new ArrayList<>();
        for (Models.TodoItem t : db.todos("all", "", 500)) if (sessionId.equals(t.sourceSummaryId)) result.add(t);
        return result;
    }

    private List<Models.MemoryItem> memoriesForSession(String sessionId) {
        List<Models.MemoryItem> result = new ArrayList<>();
        for (Models.MemoryItem m : db.memories("all", "", 500)) if (sessionId.equals(m.sourceSummaryId)) result.add(m);
        return result;
    }

    private List<Models.ProfileFact> profileForSession(String sessionId) {
        List<Models.ProfileFact> result = new ArrayList<>();
        for (Models.ProfileFact p : db.profileFacts("all", "", 500)) if (sessionId.equals(p.sourceSummaryId)) result.add(p);
        return result;
    }

    private void addNeedToKnowCard(LinearLayout body, Models.NeedToKnow need, boolean emptyFallback) {
        if (need == null || "none".equals(need.urgency) || (need.title.isEmpty() && need.detail.isEmpty())) {
            if (emptyFallback) body.addView(emptyBlock("暂无特别需要处理的信息", "完成总结后，MindAI 会结合画像、记忆和待办判断当前最重要提醒。"));
            return;
        }
        LinearLayout card = UiKit.vertical(this);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(UiKit.box(UiKit.BG_ALT, urgencyColor(need.urgency), dp(16), dp(1)));
        card.addView(UiKit.mono(this, "NEED TO KNOW / " + need.urgency.toUpperCase(Locale.ROOT), 11, urgencyColor(need.urgency)));
        card.addView(UiKit.space(this, 10));
        card.addView(UiKit.text(this, need.title, 19, UiKit.TEXT));
        TextView detail = UiKit.text(this, need.detail + (need.reason.isEmpty() ? "" : "\n\n为什么重要：" + need.reason)
                + (need.suggestedAction.isEmpty() ? "" : "\n建议：" + need.suggestedAction), 14, UiKit.SUB);
        detail.setPadding(0, dp(10), 0, 0); card.addView(detail);
        if (!need.sourceSummaryId.isEmpty()) card.setOnClickListener(v -> showSummaryResult(need.sourceSummaryId));
        body.addView(card);
    }

    private void addSourceBlock(LinearLayout body, String summaryId, String notificationIds) {
        body.addView(UiKit.space(this, 20)); body.addView(UiKit.section(this, "来源与依据"));
        Models.SummarySession source = summaryId == null || summaryId.isEmpty() ? null : db.session(summaryId);
        if (source != null) {
            TextView summary = UiKit.text(this, source.overview, 15, UiKit.SUB); summary.setPadding(dp(16), dp(14), dp(16), dp(14));
            summary.setBackground(UiKit.box(UiKit.BG_ALT, UiKit.LINE, dp(16), dp(1))); summary.setOnClickListener(v -> showSummaryResult(source.id)); body.addView(summary);
        } else body.addView(UiKit.text(this, "暂无来源总结，可能是用户手动创建。", 14, UiKit.SUB));
        if (notificationIds != null && !notificationIds.isEmpty()) {
            Button sourceButton = UiKit.outlineButton(this, "查看原始通知");
            sourceButton.setOnClickListener(v -> { long first = firstId(notificationIds); if (first > 0) showNotificationDetail(first); });
            LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(12); body.addView(sourceButton, p);
        }
    }

    private void addLoadMore(LinearLayout list, Runnable action, String label) {
        Button more = UiKit.outlineButton(this, label);
        more.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams p = matchHeight(52); p.topMargin = dp(14); list.addView(more, p);
    }

    private void addPager(LinearLayout list, int offset, boolean hasNext, Runnable previous, Runnable next) {
        if (offset <= 0 && !hasNext) return;
        LinearLayout pager = UiKit.horizontal(this);
        pager.setPadding(0, dp(14), 0, 0);
        Button before = UiKit.outlineButton(this, "上一页");
        before.setEnabled(offset > 0);
        if (offset > 0) before.setOnClickListener(v -> previous.run());
        pager.addView(before, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView page = UiKit.text(this, "第 " + (offset / PAGE_SIZE + 1) + " 页", 13, UiKit.SUB);
        page.setGravity(Gravity.CENTER);
        pager.addView(page, new LinearLayout.LayoutParams(dp(76), dp(50)));
        Button after = UiKit.outlineButton(this, "下一页");
        after.setEnabled(hasNext);
        if (hasNext) after.setOnClickListener(v -> next.run());
        pager.addView(after, new LinearLayout.LayoutParams(0, dp(50), 1));
        list.addView(pager);
    }

    private int iconFor(String pkg) {
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
        if (p.contains("mail") || p.contains("gmail")) return android.R.drawable.ic_dialog_email;
        if (p.contains("calendar")) return android.R.drawable.ic_menu_today;
        if (p.contains("bank") || p.contains("pay")) return android.R.drawable.ic_menu_send;
        if (p.contains("message") || p.contains("wechat") || p.contains("tencent.mm")) return android.R.drawable.ic_dialog_dialer;
        return android.R.drawable.ic_dialog_info;
    }

    private int recordIcon(String category) {
        if (category == null) return android.R.drawable.ic_menu_save;
        if (category.contains("承诺") || category.contains("截止")) return android.R.drawable.ic_menu_today;
        if (category.contains("人物")) return android.R.drawable.ic_menu_myplaces;
        if (category.contains("财务")) return android.R.drawable.ic_menu_send;
        if (category.contains("工作") || category.contains("项目")) return android.R.drawable.ic_menu_agenda;
        return android.R.drawable.ic_menu_save;
    }

    private int memoryIcon(String category) {
        if (category == null) return android.R.drawable.ic_menu_save;
        if (category.contains("relationship")) return android.R.drawable.ic_menu_myplaces;
        if (category.contains("risk")) return android.R.drawable.ic_dialog_alert;
        if (category.contains("asset") || category.contains("financial")) return android.R.drawable.ic_menu_send;
        if (category.contains("project") || category.contains("decision")) return android.R.drawable.ic_menu_agenda;
        if (category.contains("preference")) return android.R.drawable.ic_menu_manage;
        return android.R.drawable.ic_menu_save;
    }

    private String captureModeName() {
        String mode = settings.getString("capture_mode", "recommended");
        return "recommended".equals(mode) ? "推荐模式" : "custom".equals(mode) ? "自定义" : "全部应用";
    }

    private void clearRestorableDetail() {
        restorableDetailType = "";
        restorableNotificationId = 0;
        restorableTodoId = "";
        notificationReturnTodoId = "";
    }

    private String retentionName() { int days = settings.getInt("retention_days", 30); return days >= 36500 ? "永久" : days + " 天"; }
    private static String statusName(String status) { return "pending".equals(status) ? "旧候选" : "confirmed".equals(status) ? "已保存" : "已归档"; }
    private static String todoStatusName(String status) { return "done".equals(status) ? "已完成" : "archived".equals(status) ? "已归档" : "未完成"; }
    private static String priorityName(String priority) {
        if ("urgent".equals(priority)) return "紧急";
        if ("high".equals(priority)) return "高优先级";
        if ("low".equals(priority)) return "低优先级";
        return "中优先级";
    }
    private static String memoryStatusName(String status) { return "disputed".equals(status) ? "存在冲突" : "archived".equals(status) ? "已归档" : "活跃"; }
    private static String profileStatusName(String status) { return "disputed".equals(status) ? "存在冲突" : "stale".equals(status) ? "可能过期" : "archived".equals(status) ? "已归档" : "活跃"; }
    private static String memoryCategoryName(String category) {
        if ("preference".equals(category)) return "偏好";
        if ("fact".equals(category)) return "事实";
        if ("relationship".equals(category)) return "关系";
        if ("decision".equals(category)) return "决策";
        if ("project".equals(category)) return "项目";
        if ("risk".equals(category)) return "风险";
        if ("asset".equals(category)) return "资产";
        if ("temporary".equals(category)) return "临时上下文";
        return "其他";
    }
    private static String briefTypeName(String type) {
        if ("conflict".equals(type)) return "时间冲突";
        if ("schedule".equals(type)) return "近期日程";
        if ("action".equals(type)) return "行动建议";
        if ("risk".equals(type)) return "风险提醒";
        return "重要信息";
    }
    private static String dayGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 6) return "夜深了，先看最重要的事";
        if (hour < 12) return "早上好，这是你现在的重点";
        if (hour < 18) return "下午好，这是你现在的重点";
        return "晚上好，看看还有什么要处理";
    }

    private static String intervalName(int minutes) {
        if (minutes == 15) return "约每 15 分钟";
        if (minutes == 30) return "约每 30 分钟";
        if (minutes == 60) return "约每 1 小时";
        if (minutes == 180) return "约每 3 小时";
        return "约每 " + minutes + " 分钟";
    }
    private static int urgencyColor(String urgency) {
        if ("critical".equals(urgency)) return UiKit.RED;
        if ("high".equals(urgency) || "medium".equals(urgency)) return UiKit.ACCENT;
        return UiKit.LINE;
    }
    private static int countIds(String ids) { return ids == null || ids.isEmpty() ? 0 : ids.split(",").length; }
    private static long firstId(String ids) { try { return Long.parseLong(ids.split(",")[0]); } catch (Exception e) { return -1; } }
    private static long dayStart(long time) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(time); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); return c.getTimeInMillis(); }
    private static String safeMessage(Exception e) { return e.getMessage() == null || e.getMessage().isEmpty() ? "网络或服务暂时不可用，请稍后重试" : e.getMessage(); }
    private static String maskKey(String label) { if (label == null || label.isEmpty()) return "sk-or-••••"; return label.length() <= 8 ? label : label.substring(0, 4) + "••••" + label.substring(label.length() - 3); }
    private static String trimText(String value, int max) {
        if (value == null) return "";
        String v = value.trim();
        return v.length() <= max ? v : v.substring(0, Math.max(0, max - 1)) + "…";
    }
    private static String firstLine(String value) {
        if (value == null || value.trim().isEmpty()) return "未命名";
        String v = value.trim().replace('\n', ' ');
        return v.length() <= 28 ? v : v.substring(0, 28) + "…";
    }
    private int dp(float value) { return UiKit.dp(this, value); }
    private LinearLayout.LayoutParams matchHeight(int height) { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(height)); }
    private Space spaceWide(int width) { Space s = new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return s; }
    private Space spaceWeight() { Space s = new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1)); return s; }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
