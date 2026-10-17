package com.mindai.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic safety layer for notification summarization.
 *
 * The model still performs semantic understanding, but obvious promotion and
 * unverified social engagement copy never reaches it. Every structured model
 * result is checked again against the cited notification ids before commit.
 */
final class NotificationValueGate {
    private NotificationValueGate() {}

    private static final Pattern EXPLICIT_PROMOTION = Pattern.compile(
            "广告|推广|赞助|品牌合作|商业合作|限时|限量|福利|优惠|优惠券|领券|折扣|抽奖|上新|种草|" +
                    "猜你喜欢|为你推荐|今日精选|热门推荐|专属推荐|好物|爆款|同款|神价|秒杀|低至|仅需|" +
                    "开抢|红包|新人礼|免费领|立即购买|立即下单|直播间|正在直播|开播了|冲榜|涨粉|薯条推广");
    private static final Pattern ENGAGEMENT_BAIT = Pattern.compile(
            "你有.{0,8}(新消息|私信)|收到.{0,8}(消息|私信)|有人.{0,8}(关注|点赞|赞了|评论|回复|@|提到)|" +
                    "想认识你|可能认识的人|附近的人|悄悄关注|戳了你|等你回复|快来聊|来聊聊|未读私信|" +
                    "互动消息|新粉丝|访问了你|看过你|对你感兴趣|给你发来.{0,4}(消息|私信)");
    private static final Pattern RECOMMENDATION = Pattern.compile(
            "推荐给你|你可能喜欢|你可能感兴趣|适合你|为你精选|发现更多|快来看看|点击查看|打开看看|" +
                    "不容错过|别错过|刷到|热搜|热榜|同城热门|关注的人都在看");
    private static final Pattern CALL_TO_ACTION = Pattern.compile(
            "点击|点开|打开|查看|快来|马上|立即|参与|领取|围观|去看看|来看看|关注我|进主页|戳这里");
    private static final Pattern DISPOSABLE_NOISE = Pattern.compile(
            "验证码|校验码|动态码|一次性密码|登录代码|verification code|one[- ]?time password|otp",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSACTIONAL = Pattern.compile(
            "订单|快递|物流|配送|签收|退款|支付|扣款|账单|还款|续费|预约|挂号|航班|车次|酒店|门票|" +
                    "会议|评审|面试|截止|到期|服务变更|账号异常|异地登录|安全提醒");
    private static final Pattern SPECIFIC_DETAIL = Pattern.compile(
            "订单号|运单号|流水号|编号|¥|￥|\\d{2,}|今天|明天|后天|本周|下周|周[一二三四五六日天]|" +
                    "星期[一二三四五六日天]|\\d{1,2}[:：]\\d{2}|\\d{1,2}[月/-]\\d{1,2}");
    private static final Pattern EXPLICIT_USER_ACTION = Pattern.compile(
            "请|麻烦|需要你|请你|能否|可以帮|记得|务必|别忘|回复|确认|提交|发送|参加|到场|准备|" +
                    "完成|联系|付款|转账|取件|签收|处理|跟进|反馈|审批|查收|选择|决定");
    private static final Pattern GENERIC_SOCIAL_TITLE = Pattern.compile(
            "^(消息|私信|新消息|新的私信|互动消息|系统消息|系统通知|通知|发现|推荐|热门|猜你喜欢|" +
                    "小红书|微博|抖音|快手|哔哩哔哩|b站|知乎|instagram|facebook|threads|社交消息|官方消息)$",
            Pattern.CASE_INSENSITIVE);
    static final class Decision {
        final String classification;
        final String sourceTrust;
        final String writeScope;
        final String reason;
        final boolean includeInModel;
        final boolean allowInsight;
        final boolean allowActionWrite;
        final boolean allowIdentityWrite;

        Decision(String classification, String sourceTrust, String writeScope, String reason,
                 boolean includeInModel, boolean allowInsight,
                 boolean allowActionWrite, boolean allowIdentityWrite) {
            this.classification = classification;
            this.sourceTrust = sourceTrust;
            this.writeScope = writeScope;
            this.reason = reason;
            this.includeInModel = includeInModel;
            this.allowInsight = allowInsight;
            this.allowActionWrite = allowActionWrite;
            this.allowIdentityWrite = allowIdentityWrite;
        }
    }

    static final class Screening {
        final List<Models.NotificationItem> modelInput = new ArrayList<>();
        final Map<Long, Decision> decisions = new HashMap<>();
        int ignoredCount;
    }

    static Screening screen(List<Models.NotificationItem> notifications) {
        Screening result = new Screening();
        if (notifications == null) return result;
        for (Models.NotificationItem item : notifications) {
            Decision decision = evaluate(item);
            result.decisions.put(item.id, decision);
            if (decision.includeInModel) result.modelInput.add(item);
            else result.ignoredCount++;
        }
        return result;
    }

    static Decision evaluate(Models.NotificationItem item) {
        String packageName = lower(item == null ? "" : item.packageName);
        String app = clean(item == null ? "" : item.appName);
        String title = clean(item == null ? "" : item.title);
        String content = clean(item == null ? "" : item.content);
        String text = title + "\n" + content;
        boolean social = isSocialSource(packageName, app);
        boolean messaging = isMessagingSource(packageName, app);
        boolean promotion = EXPLICIT_PROMOTION.matcher(text).find()
                || RECOMMENDATION.matcher(text).find();
        boolean engagementBait = ENGAGEMENT_BAIT.matcher(text).find();
        boolean disposableNoise = DISPOSABLE_NOISE.matcher(text).find();
        boolean senderEvidence = hasConcreteSender(title, app);
        boolean explicitAction = EXPLICIT_USER_ACTION.matcher(content).find();
        boolean transactional = TRANSACTIONAL.matcher(text).find()
                && SPECIFIC_DETAIL.matcher(text).find();

        if (disposableNoise) {
            return ignore("one_time_noise", "low", "none", "验证码或一次性登录信息");
        }
        if (promotion) {
            return ignore("promotion", social ? "untrusted_social" : "commercial", "none",
                    social ? "社媒推广、内容推荐或互动营销" : "明确营销或内容推荐");
        }
        if (social) {
            if (transactional) {
                return new Decision("user_specific_transaction", "untrusted_social", "action_only",
                        "社媒来源，但包含可核验的用户交易或服务细节", true, true,
                        explicitAction, false);
            }
            if (senderEvidence && explicitAction && !CALL_TO_ACTION.matcher(content).find()) {
                return new Decision("social_direct_action", "untrusted_social", "action_only",
                        "存在具体发送者和明确面向用户的行动请求", true, true, true, false);
            }
            if (engagementBait) {
                return ignore("engagement_bait", "untrusted_social", "none",
                        "社媒互动诱导或无法确认发送者的伪私信文案");
            }
            return ignore("social_unverified", "untrusted_social", "none",
                    "社媒内容缺少可核验的本人关联、明确行动或交易细节");
        }
        if (!messaging && engagementBait
                && (!senderEvidence || CALL_TO_ACTION.matcher(content).find())) {
            return ignore("engagement_bait", "unknown", "none",
                    "非通讯来源的消息式互动诱导");
        }
        return new Decision(transactional ? "user_specific_transaction" : "candidate_value",
                messaging ? "direct_messaging" : "standard", "full",
                transactional ? "包含可核验的用户交易、日程或安全细节" : "交由模型进行价值判断",
                true, true, true, true);
    }

    private static Decision ignore(String classification, String trust, String scope, String reason) {
        return new Decision(classification, trust, scope, reason,
                false, false, false, false);
    }

    static Models.SummaryOutput emptyOutput(int ignoredCount) {
        Models.SummaryOutput output = new Models.SummaryOutput();
        output.overview = ignoredCount > 0
                ? "本轮没有需要关注的新信息，低价值推送已自动忽略。"
                : "本轮没有需要关注的新信息。";
        output.actualModel = "";
        output.filteredNotificationCount = Math.max(0, ignoredCount);
        return output;
    }

    static void sanitize(Models.SummaryOutput output, Screening screening) {
        if (output == null || screening == null) return;
        output.filteredNotificationCount = screening.ignoredCount;

        for (Iterator<Models.KeyPoint> it = output.keyPoints.iterator(); it.hasNext();) {
            Models.KeyPoint item = it.next();
            item.sourceIds = allowedSources(item.sourceIds, screening, Scope.INSIGHT);
            if (item.sourceIds.isEmpty()) it.remove();
        }
        for (Iterator<Models.ImportantRecord> it = output.records.iterator(); it.hasNext();) {
            Models.ImportantRecord item = it.next();
            item.sourceNotificationIds = allowedSources(item.sourceNotificationIds, screening, Scope.IDENTITY);
            if (item.sourceNotificationIds.isEmpty()) it.remove();
        }
        for (Iterator<Models.TodoItem> it = output.todos.iterator(); it.hasNext();) {
            Models.TodoItem item = it.next();
            item.sourceNotificationIds = allowedSources(item.sourceNotificationIds, screening, Scope.ACTION);
            if (item.sourceNotificationIds.isEmpty()) it.remove();
        }
        for (Iterator<Models.CalendarCandidate> it = output.calendarCandidates.iterator(); it.hasNext();) {
            Models.CalendarCandidate item = it.next();
            item.sourceNotificationIds = allowedSources(item.sourceNotificationIds, screening, Scope.ACTION);
            if (item.sourceNotificationIds.isEmpty()) it.remove();
        }
        for (Iterator<Models.MemoryItem> it = output.memories.iterator(); it.hasNext();) {
            Models.MemoryItem item = it.next();
            item.sourceNotificationIds = allowedSources(item.sourceNotificationIds, screening, Scope.IDENTITY);
            if (item.sourceNotificationIds.isEmpty()) it.remove();
        }
        for (Iterator<Models.ProfileFact> it = output.profileUpdates.iterator(); it.hasNext();) {
            Models.ProfileFact item = it.next();
            item.sourceNotificationIds = allowedSources(item.sourceNotificationIds, screening, Scope.IDENTITY);
            if (item.sourceNotificationIds.isEmpty()) it.remove();
        }
        if (output.needToKnow != null) {
            output.needToKnow.sourceNotificationIds = allowedSources(
                    output.needToKnow.sourceNotificationIds, screening, Scope.INSIGHT);
            if (output.needToKnow.sourceNotificationIds.isEmpty()) output.needToKnow = null;
        }
    }

    private enum Scope { INSIGHT, ACTION, IDENTITY }

    private static String allowedSources(String sourceIds, Screening screening, Scope scope) {
        if (sourceIds == null || sourceIds.trim().isEmpty()) return "";
        Set<Long> allowed = new LinkedHashSet<>();
        for (String raw : sourceIds.split(",")) {
            try {
                long id = Long.parseLong(raw.trim());
                Decision decision = screening.decisions.get(id);
                if (decision == null) continue;
                boolean permitted = scope == Scope.ACTION ? decision.allowActionWrite
                        : scope == Scope.IDENTITY ? decision.allowIdentityWrite
                        : decision.allowInsight;
                if (permitted) allowed.add(id);
            } catch (NumberFormatException ignored) {}
        }
        StringBuilder result = new StringBuilder();
        for (Long id : allowed) {
            if (result.length() > 0) result.append(',');
            result.append(id);
        }
        return result.toString();
    }

    private static boolean hasConcreteSender(String title, String app) {
        String candidate = title.trim();
        if (candidate.isEmpty() || candidate.length() > 30) return false;
        if (GENERIC_SOCIAL_TITLE.matcher(candidate).matches()) return false;
        if (ENGAGEMENT_BAIT.matcher(candidate).find() || RECOMMENDATION.matcher(candidate).find()) return false;
        if (!app.isEmpty() && candidate.equalsIgnoreCase(app)) return false;
        if (candidate.matches(".*\\d+\\s*条.*")) return false;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isLetter(candidate.charAt(i))) return true;
        }
        return false;
    }

    private static boolean isSocialSource(String packageName, String appName) {
        return packageName.startsWith("com.sina.weibo")
                || packageName.startsWith("com.xingin.xhs")
                || packageName.startsWith("com.ss.android.ugc.aweme")
                || packageName.startsWith("com.smile.gifmaker")
                || packageName.startsWith("tv.danmaku.bili")
                || packageName.startsWith("com.zhihu.android")
                || packageName.startsWith("com.instagram.android")
                || packageName.startsWith("com.instagram.barcelona")
                || packageName.startsWith("com.twitter.android")
                || packageName.startsWith("com.facebook.katana")
                || containsAny(lower(appName), "微博", "小红书", "抖音", "快手", "哔哩哔哩", "b站", "知乎",
                "instagram", "facebook", "threads");
    }

    private static boolean isMessagingSource(String packageName, String appName) {
        return packageName.startsWith("com.tencent.mm")
                || packageName.startsWith("com.tencent.mobileqq")
                || packageName.startsWith("com.google.android.apps.messaging")
                || packageName.startsWith("com.android.mms")
                || packageName.startsWith("com.whatsapp")
                || packageName.startsWith("org.telegram.messenger")
                || packageName.startsWith("com.facebook.orca")
                || packageName.startsWith("com.alibaba.android.rimet")
                || packageName.startsWith("com.ss.android.lark")
                || packageName.startsWith("com.slack")
                || containsAny(lower(appName), "微信", "企业微信", "短信", "messages", "whatsapp", "telegram",
                "钉钉", "飞书", "slack", "teams");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String lower(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }
}
