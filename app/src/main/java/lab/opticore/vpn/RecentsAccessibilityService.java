package lab.opticore.vpn;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class RecentsAccessibilityService extends AccessibilityService {

    private static volatile RecentsAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger returnGeneration = new AtomicInteger(0);
    private volatile Runnable pendingReturnRunnable;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // OptiCore does not record user content. The service is used only
        // when the user requests a scan/close action from the local panel.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    public static JSONObject status(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", true);
            out.put("enabled", isEnabled(context));
            out.put("connected", instance != null);
            out.put(
                    "note",
                    "Accessibility is used only on explicit user request to inspect visible Recent-app cards and perform swipe-close gestures.");
        } catch (Throwable e) {
            putError(out, e);
        }
        return out;
    }

    public static JSONObject scanOpenApps(Context context) {
        JSONObject out = new JSONObject();

        try {
            boolean enabled = isEnabled(context);
            out.put("accessibility_enabled", enabled);
            out.put("connected", instance != null);

            if (!enabled || instance == null) {
                out.put("ok", false);
                out.put("reason", "accessibility_required");
                out.put("apps", new JSONArray());
                return out;
            }

            RecentsAccessibilityService service = instance;
            service.cancelPendingReturn();

            if (!service.performGlobalActionSync(GLOBAL_ACTION_RECENTS)) {
                out.put("ok", false);
                out.put("reason", "could_not_open_recents");
                out.put("apps", new JSONArray());
                return out;
            }

            sleepQuietly(800L);

            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            JSONArray apps = service.collectRecentCards(context, root);

            // Return explicitly to the OptiCore panel instead of leaving
            // the user in Android Recents or a previous Settings screen.
            service.returnToCallerSoon(260L);

            out.put("ok", true);
            out.put("apps", apps);
            out.put("count", apps.length());
            out.put(
                    "source",
                    "Android Recents accessibility tree; only cards currently exposed by Android are returned.");
            out.put(
                    "note",
                    "This is different from usage history. Android may expose only the currently loaded Recent-app cards. OptiCore returns to the exact screen that launched the scan, so the pending panel request can render its result in the same tab.");

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    public static JSONObject closeSelected(Context context, String targetsJson) {
        JSONObject out = new JSONObject();

        try {
            boolean enabled = isEnabled(context);
            out.put("accessibility_enabled", enabled);
            out.put("connected", instance != null);

            if (!enabled || instance == null) {
                out.put("ok", false);
                out.put("reason", "accessibility_required");
                return out;
            }

            JSONArray targets;
            try {
                targets = new JSONArray(
                        targetsJson == null || targetsJson.trim().isEmpty()
                                ? "[]"
                                : targetsJson);
            } catch (Throwable e) {
                out.put("ok", false);
                out.put("reason", "invalid_targets");
                return out;
            }

            if (targets.length() == 0) {
                out.put("ok", false);
                out.put("reason", "no_apps_selected");
                return out;
            }

            RecentsAccessibilityService service = instance;
            service.cancelPendingReturn();

            if (!service.performGlobalActionSync(GLOBAL_ACTION_RECENTS)) {
                out.put("ok", false);
                out.put("reason", "could_not_open_recents");
                return out;
            }

            sleepQuietly(750L);

            JSONArray closed = new JSONArray();
            JSONArray missing = new JSONArray();
            JSONArray protectedApps = new JSONArray();

            int limit = Math.min(targets.length(), 12);

            for (int i = 0; i < limit; i++) {
                JSONObject target = targets.optJSONObject(i);
                if (target == null) continue;

                String label = target.optString("label", "").trim();
                String pkg = target.optString("package", "").trim();

                if (label.isEmpty()) continue;

                if (isProtectedPackage(context, pkg)) {
                    JSONObject item = new JSONObject();
                    item.put("label", label);
                    item.put("package", pkg);
                    protectedApps.put(item);
                    continue;
                }

                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                AccessibilityNodeInfo node = service.findNodeForLabel(root, label);

                if (node == null) {
                    JSONObject item = new JSONObject();
                    item.put("label", label);
                    item.put("package", pkg);
                    missing.put(item);
                    continue;
                }

                boolean removed = false;
                String method = "";

                if (service.dismissNode(node)) {
                    sleepQuietly(500L);
                    removed = !service.isLabelPresent(label);
                    if (removed) {
                        method = "accessibility_dismiss";
                    }
                }

                if (!removed) {
                    AccessibilityNodeInfo retryRoot =
                            service.getRootInActiveWindow();
                    AccessibilityNodeInfo retryNode =
                            service.findNodeForLabel(
                                    retryRoot,
                                    label);

                    if (retryNode != null) {
                        Rect card =
                                service.findCardBounds(
                                        retryNode);

                        if (service.swipeCardUp(
                                card,
                                false)) {

                            sleepQuietly(700L);
                            removed =
                                    !service.isLabelPresent(
                                            label);

                            if (removed) {
                                method = "swipe_up";
                            }
                        }
                    }
                }

                if (!removed) {
                    AccessibilityNodeInfo retryRoot =
                            service.getRootInActiveWindow();
                    AccessibilityNodeInfo retryNode =
                            service.findNodeForLabel(
                                    retryRoot,
                                    label);

                    if (retryNode != null) {
                        Rect card =
                                service.findCardBounds(
                                        retryNode);

                        if (service.swipeCardUp(
                                card,
                                true)) {

                            sleepQuietly(850L);
                            removed =
                                    !service.isLabelPresent(
                                            label);

                            if (removed) {
                                method = "swipe_up_strong";
                            }
                        }
                    }
                }

                JSONObject item = new JSONObject();
                item.put("label", label);
                item.put("package", pkg);

                if (removed) {
                    item.put("method", method);
                    closed.put(item);
                } else {
                    item.put("reason", "still_present_after_close_attempt");
                    missing.put(item);
                }
            }

            // Return explicitly to the OptiCore panel after the requested
            // swipe-close gestures complete.
            service.returnToCallerSoon(320L);

            out.put("ok", true);
            out.put("requested_count", limit);
            out.put("closed_count", closed.length());
            out.put("closed", closed);
            out.put("not_found_count", missing.length());
            out.put("not_found", missing);
            out.put("protected_count", protectedApps.length());
            out.put("protected", protectedApps);
            out.put(
                    "note",
                    "A close is counted only after OptiCore verifies that the selected Recent-app card is no longer present. It tries Android's accessibility dismiss action first, then normal and strong upward swipe fallbacks. The Recent-app UI can vary by manufacturer.");

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    private JSONArray collectRecentCards(
            Context context,
            AccessibilityNodeInfo root) throws Exception {

        JSONArray out = new JSONArray();
        if (root == null) return out;

        List<AppRef> knownApps = buildKnownApps(context);
        LinkedHashMap<String, AppRef> matches = new LinkedHashMap<>();

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;

        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            AppRef match = matchNode(node, knownApps);
            String nodePackage = node.getPackageName() == null ? "" : node.getPackageName().toString();
            boolean recentsUiNode = nodePackage.equals("com.android.systemui") || nodePackage.toLowerCase(Locale.ROOT).contains("launcher");

            if (match != null && recentsUiNode &&
                    !isProtectedPackage(context, match.packageName) &&
                    !matches.containsKey(match.packageName.isEmpty()
                            ? match.label.toLowerCase(Locale.ROOT)
                            : match.packageName)) {

                matches.put(
                        match.packageName.isEmpty()
                                ? match.label.toLowerCase(Locale.ROOT)
                                : match.packageName,
                        match);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        for (AppRef ref : matches.values()) {
            JSONObject item = new JSONObject();
            item.put("label", ref.label);
            item.put("package", ref.packageName);
            item.put("source", "recents");
            out.put(item);
        }

        return out;
    }

    private List<AppRef> buildKnownApps(Context context) {
        LinkedHashMap<String, AppRef> byPackage = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();

        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            if (installed != null) {
                for (ApplicationInfo info : installed) {
                    if (info == null || info.packageName == null) continue;
                    String label = safeLabel(pm, info);
                    if (label.isEmpty()) continue;
                    byPackage.put(
                            info.packageName,
                            new AppRef(info.packageName, label));
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            UsageStatsManager manager =
                    (UsageStatsManager) context.getSystemService(
                            Context.USAGE_STATS_SERVICE);

            if (manager != null) {
                long end = System.currentTimeMillis();
                long start = end - (24L * 60L * 60L * 1000L);

                List<UsageStats> stats =
                        manager.queryUsageStats(
                                UsageStatsManager.INTERVAL_DAILY,
                                start,
                                end);

                if (stats != null) {
                    for (UsageStats stat : stats) {
                        if (stat == null) continue;
                        String pkg = stat.getPackageName();
                        if (TextUtils.isEmpty(pkg) ||
                                byPackage.containsKey(pkg)) {
                            continue;
                        }

                        try {
                            ApplicationInfo info =
                                    pm.getApplicationInfo(pkg, 0);
                            String label = safeLabel(pm, info);
                            if (!label.isEmpty()) {
                                byPackage.put(
                                        pkg,
                                        new AppRef(pkg, label));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        List<AppRef> out = new ArrayList<>(byPackage.values());

        out.sort(
                (a, b) ->
                        Integer.compare(
                                b.label.length(),
                                a.label.length()));

        return out;
    }

    private static String safeLabel(
            PackageManager pm,
            ApplicationInfo info) {

        try {
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? "" : label.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private AppRef matchNode(
            AccessibilityNodeInfo node,
            List<AppRef> apps) {

        if (node == null) return null;

        String text = node.getText() == null
                ? ""
                : node.getText().toString();

        String desc = node.getContentDescription() == null
                ? ""
                : node.getContentDescription().toString();

        AppRef match = matchText(text, apps);
        if (match != null) return match;

        return matchText(desc, apps);
    }

    private AppRef matchText(
            String value,
            List<AppRef> apps) {

        String candidate = normalize(value);
        if (candidate.isEmpty()) return null;

        for (AppRef ref : apps) {
            String label = normalize(ref.label);

            if (label.length() < 2) continue;

            if (candidate.equals(label)) {

                return ref;
            }
        }

        return null;
    }

    private AccessibilityNodeInfo findNodeForLabel(
            AccessibilityNodeInfo root,
            String targetLabel) {

        if (root == null) return null;

        String wanted = normalize(targetLabel);
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);

        int visited = 0;

        while (!queue.isEmpty() && visited < 900) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;

            String text = normalize(
                    node.getText() == null
                            ? ""
                            : node.getText().toString());

            String desc = normalize(
                    node.getContentDescription() == null
                            ? ""
                            : node.getContentDescription().toString());

            if (matchesLabel(text, wanted) ||
                    matchesLabel(desc, wanted)) {
                return node;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.addLast(child);
            }
        }

        return null;
    }

    private static boolean matchesLabel(
            String candidate,
            String wanted) {

        if (candidate.isEmpty() || wanted.isEmpty()) return false;

        return candidate.equals(wanted);
    }

    private Rect findCardBounds(AccessibilityNodeInfo node) {
        DisplayMetrics metrics = screenMetrics();
        Rect best = new Rect();

        AccessibilityNodeInfo current = node;

        for (int depth = 0;
             current != null && depth < 8;
             depth++) {

            Rect r = new Rect();
            current.getBoundsInScreen(r);

            if (r.width() > metrics.widthPixels * 0.45 &&
                    r.height() > metrics.heightPixels * 0.20 &&
                    r.width() < metrics.widthPixels * 0.99 &&
                    r.height() < metrics.heightPixels * 0.95) {

                return r;
            }

            current = current.getParent();
        }

        if (best.isEmpty()) {
            Rect labelBounds = new Rect();
            node.getBoundsInScreen(labelBounds);

            int cx = labelBounds.centerX();
            if (cx <= 0) cx = metrics.widthPixels / 2;

            int halfWidth = (int) (metrics.widthPixels * 0.38f);
            int top = (int) (metrics.heightPixels * 0.20f);
            int bottom = (int) (metrics.heightPixels * 0.78f);

            best.set(
                    Math.max(0, cx - halfWidth),
                    top,
                    Math.min(metrics.widthPixels, cx + halfWidth),
                    bottom);
        }

        return best;
    }

    private boolean isLabelPresent(String label) {
        AccessibilityNodeInfo root =
                getRootInActiveWindow();

        return findNodeForLabel(
                root,
                label) != null;
    }

    private boolean dismissNode(
            AccessibilityNodeInfo node) {

        AccessibilityNodeInfo current = node;

        for (int depth = 0;
             current != null && depth < 8;
             depth++) {

            if ((current.getActions() &
                    AccessibilityNodeInfo.ACTION_DISMISS) != 0) {

                try {
                    if (current.performAction(
                            AccessibilityNodeInfo.ACTION_DISMISS)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }

            current = current.getParent();
        }

        return false;
    }

    private boolean swipeCardUp(
            Rect card,
            boolean strong) {

        DisplayMetrics metrics = screenMetrics();

        float rawX =
                card == null || card.isEmpty()
                        ? metrics.widthPixels / 2f
                        : card.centerX();

        float startX =
                Math.max(
                        metrics.widthPixels * 0.08f,
                        Math.min(
                                metrics.widthPixels * 0.92f,
                                rawX));

        float startY =
                card == null || card.isEmpty()
                        ? metrics.heightPixels * 0.68f
                        : Math.min(
                                metrics.heightPixels * 0.80f,
                                Math.max(
                                        metrics.heightPixels * 0.42f,
                                        card.top + card.height() * 0.62f));

        float endY =
                strong
                        ? metrics.heightPixels * 0.02f
                        : Math.max(
                                metrics.heightPixels * 0.08f,
                                startY - metrics.heightPixels * 0.58f);

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(startX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0L,
                        strong ? 220L : 340L);

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                boolean accepted =
                        dispatchGesture(
                                gesture,
                                new GestureResultCallback() {
                                    @Override
                                    public void onCompleted(
                                            GestureDescription gestureDescription) {
                                        completed.set(true);
                                        latch.countDown();
                                    }

                                    @Override
                                    public void onCancelled(
                                            GestureDescription gestureDescription) {
                                        completed.set(false);
                                        latch.countDown();
                                    }
                                },
                                null);

                if (!accepted) {
                    completed.set(false);
                    latch.countDown();
                }
            } catch (Throwable e) {
                completed.set(false);
                latch.countDown();
            }
        });

        try {
            latch.await(1600L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return completed.get();
    }

    private boolean performGlobalActionSync(int action) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                accepted.set(performGlobalAction(action));
            } catch (Throwable ignored) {
                accepted.set(false);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(1000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return accepted.get();
    }

    private DisplayMetrics screenMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();

        try {
            WindowManager wm =
                    (WindowManager) getSystemService(
                            Context.WINDOW_SERVICE);

            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(metrics);
            }
        } catch (Throwable ignored) {
        }

        if (metrics.widthPixels <= 0) {
            metrics.widthPixels = getResources()
                    .getDisplayMetrics()
                    .widthPixels;
        }

        if (metrics.heightPixels <= 0) {
            metrics.heightPixels = getResources()
                    .getDisplayMetrics()
                    .heightPixels;
        }

        return metrics;
    }

    private void cancelPendingReturn() {
        returnGeneration.incrementAndGet();

        Runnable pending = pendingReturnRunnable;
        if (pending != null) {
            mainHandler.removeCallbacks(pending);
            pendingReturnRunnable = null;
        }
    }

    private void returnToCallerSoon(long delayMs) {
        cancelPendingReturn();
        final int generation = returnGeneration.get();

        Runnable action = () -> {
            if (returnGeneration.get() != generation) {
                return;
            }

            pendingReturnRunnable = null;

            try {
                // Return to the exact activity/tab that invoked the local API.
                // Do not launch a new browser intent because Samsung/Chrome may
                // restore a different tab.
                performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Throwable ignored) {
            }
        };

        pendingReturnRunnable = action;
        mainHandler.postDelayed(action, Math.max(0L, delayMs));
    }

    private static boolean isEnabled(Context context) {
        try {
            String enabled =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabled == null) return false;

            String full =
                    context.getPackageName() +
                    "/" +
                    RecentsAccessibilityService.class.getName();

            String shortName =
                    context.getPackageName() +
                    "/.RecentsAccessibilityService";

            String lower =
                    enabled.toLowerCase(Locale.ROOT);

            return lower.contains(
                            full.toLowerCase(Locale.ROOT)) ||
                    lower.contains(
                            shortName.toLowerCase(Locale.ROOT));

        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean isProtectedPackage(
            Context context,
            String pkg) {

        if (TextUtils.isEmpty(pkg)) return false;

        if (pkg.equals(context.getPackageName()) ||
                pkg.equals("android") ||
                pkg.equals("com.android.systemui") ||
                pkg.equals("com.android.chrome") ||
                pkg.equals("com.sec.android.app.sbrowser")) {
            return true;
        }

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);

            String launcher =
                    context.getPackageManager()
                            .resolveActivity(
                                    home,
                                    PackageManager.MATCH_DEFAULT_ONLY)
                            .activityInfo
                            .packageName;

            if (pkg.equals(launcher)) return true;
        } catch (Throwable ignored) {
        }

        try {
            String ime =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD);

            if (ime != null && ime.contains("/")) {
                String keyboard =
                        ime.substring(
                                0,
                                ime.indexOf('/'));

                if (pkg.equals(keyboard)) return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static String normalize(String value) {
        if (value == null) return "";

        return value
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void putError(
            JSONObject out,
            Throwable e) {

        try {
            out.put("ok", false);
            out.put(
                    "reason",
                    e.getClass().getSimpleName());

            out.put(
                    "message",
                    e.getMessage() == null
                            ? ""
                            : e.getMessage());
        } catch (Throwable ignored) {
        }
    }

    private static final class AppRef {
        final String packageName;
        final String label;

        AppRef(String packageName, String label) {
            this.packageName =
                    packageName == null
                            ? ""
                            : packageName;

            this.label =
                    label == null
                            ? ""
                            : label;
        }
    }
}
