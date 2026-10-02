package lab.opticore.vpn;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SmartBoostEngine {

    private static final double PRESSURE_CRITICAL = 18.0;
    private static final double PRESSURE_TIGHT = 32.0;
    private static final double HEALTHY_RAM = 38.0;

    private SmartBoostEngine() {}

    public static JSONObject scan(Context context) {
        JSONObject out = new JSONObject();

        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            Memory memory = readMemory(am);
            JSONArray candidates = new JSONArray();
            Set<String> running = runningUserPackages(context, am, false);

            for (String pkg : running) {
                JSONObject item = new JSONObject();
                item.put("package", pkg);
                item.put("label", appLabel(context, pkg));
                candidates.put(item);
            }

            out.put("ok", true);
            out.put("android_sdk", Build.VERSION.SDK_INT);
            out.put("ram_total_mb", round1(memory.totalBytes / 1048576.0));
            out.put("ram_available_mb", round1(memory.availableBytes / 1048576.0));
            out.put("ram_available_percent", round1(memory.availablePercent));
            out.put("ram_threshold_mb", round1(memory.thresholdBytes / 1048576.0));
            out.put("ram_low", memory.lowMemory);
            out.put("pressure", pressureLevel(memory));
            out.put("recommended_mode", recommendedMode(memory));
            out.put("candidate_count", candidates.length());
            out.put("candidates", candidates);
            out.put("usage_access", hasUsageAccess(context));
            out.put(
                    "other_app_process_control",
                    Build.VERSION.SDK_INT >= 34
                            ? "blocked_by_android_14_plus"
                            : "legacy_android_capability");
            out.put(
                    "measurement",
                    "ActivityManager.MemoryInfo before/after; no estimated freed RAM is reported.");

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    public static JSONObject boost(Context context, boolean aggressive) {
        JSONObject out = new JSONObject();

        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (am == null) {
                out.put("ok", false);
                out.put("reason", "activity_manager_unavailable");
                return out;
            }

            Memory before = readMemory(am);

            if (Build.VERSION.SDK_INT >= 34) {
                out.put("ok", false);
                out.put("blocked", true);
                out.put("reason", "android_14_plus_other_app_process_kill_blocked");
                out.put("android_sdk", Build.VERSION.SDK_INT);
                out.put("ram_before_mb", round1(before.availableBytes / 1048576.0));
                return out;
            }

            if (!aggressive &&
                    !before.lowMemory &&
                    before.availablePercent >= HEALTHY_RAM) {

                out.put("ok", true);
                out.put("engine", "smart_fluency_v037");
                out.put("mode", "balanced");
                out.put("smart_skip", true);
                out.put("pass_count", 0);
                out.put("attempted_count", 0);
                out.put("attempted_packages", new JSONArray());
                out.put("ram_before_mb", round1(before.availableBytes / 1048576.0));
                out.put("ram_after_mb", round1(before.availableBytes / 1048576.0));
                out.put("ram_before_percent", round1(before.availablePercent));
                out.put("ram_after_percent", round1(before.availablePercent));
                out.put("measured_delta_mb", 0.0);
                out.put("measured_freed_mb", 0.0);
                out.put("ram_low_before", before.lowMemory);
                out.put("ram_low_after", before.lowMemory);
                out.put("pressure_before", pressureLevel(before));
                out.put("pressure_after", pressureLevel(before));
                out.put("second_pass_used", false);
                out.put(
                        "note",
                        "RAM suficiente. OptiCore evita cerrar procesos innecesariamente para conservar fluidez.");
                return out;
            }

            LinkedHashSet<String> allAttempted = new LinkedHashSet<>();
            int passCount = 0;

            Set<String> first =
                    runningUserPackages(context, am, aggressive);

            int firstAttempts =
                    releasePackages(
                            am,
                            first,
                            aggressive ? 14 : 8,
                            allAttempted);

            if (firstAttempts > 0) {
                passCount++;
            }

            sleepQuietly(900L);

            Memory middle = readMemory(am);

            boolean secondPassNeeded =
                    middle.lowMemory ||
                    middle.availablePercent < 25.0;

            if (secondPassNeeded) {
                Set<String> second =
                        runningUserPackages(context, am, true);

                int secondAttempts =
                        releasePackages(
                                am,
                                second,
                                10,
                                allAttempted);

                if (secondAttempts > 0) {
                    passCount++;
                }

                sleepQuietly(900L);
            }

            try {
                System.gc();
            } catch (Throwable ignored) {
            }

            sleepQuietly(500L);

            Memory after = readMemory(am);
            long delta = after.availableBytes - before.availableBytes;

            JSONArray attempted = new JSONArray();

            for (String pkg : allAttempted) {
                attempted.put(pkg);
            }

            out.put("ok", true);
            out.put("engine", "smart_fluency_v037");
            out.put("mode", aggressive ? "aggressive" : "balanced");
            out.put("android_sdk", Build.VERSION.SDK_INT);
            out.put("smart_skip", false);
            out.put("pass_count", passCount);
            out.put("attempted_count", attempted.length());
            out.put("attempted_packages", attempted);
            out.put("ram_before_mb", round1(before.availableBytes / 1048576.0));
            out.put("ram_after_mb", round1(after.availableBytes / 1048576.0));
            out.put("ram_before_percent", round1(before.availablePercent));
            out.put("ram_after_percent", round1(after.availablePercent));
            out.put("measured_delta_mb", round1(delta / 1048576.0));
            boolean canAttributeFreed = attempted.length() > 0;
            out.put(
                    "measured_freed_mb",
                    canAttributeFreed
                            ? round1(Math.max(0L, delta) / 1048576.0)
                            : 0.0);
            out.put("measurement_attributed", canAttributeFreed);
            out.put("no_safe_candidates", attempted.length() == 0);
            out.put("ram_low_before", before.lowMemory);
            out.put("ram_low_after", after.lowMemory);
            out.put("pressure_before", pressureLevel(before));
            out.put("pressure_after", pressureLevel(after));
            out.put("second_pass_used", secondPassNeeded);
            out.put(
                    "note",
                    attempted.length() > 0
                            ? "OptiCore prioriza procesos de usuario en segundo plano, mide la RAM real antes y despues y solo atribuye memoria liberada cuando intento liberar procesos."
                            : "No se encontraron procesos de usuario seguros para liberar. La variacion de RAM observada pertenece al sistema y no se atribuye a OptiCore.");

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    public static JSONObject batteryDiagnostics(Context context) {
        JSONObject out = new JSONObject();

        try {
            Intent battery =
                    context.registerReceiver(
                            null,
                            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

            int level = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);

            int scale = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            int temperature = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);

            int voltage = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);

            int status = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);

            int plugged = battery == null
                    ? -1
                    : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

            PowerManager pm =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);

            double percent =
                    level >= 0 && scale > 0
                            ? (level * 100.0 / scale)
                            : -1.0;

            out.put("ok", true);
            out.put("level_percent", percent < 0 ? JSONObject.NULL : round1(percent));
            out.put("temperature_c", temperature < 0 ? JSONObject.NULL : round1(temperature / 10.0));
            out.put("voltage_mv", voltage < 0 ? JSONObject.NULL : voltage);
            out.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            out.put("plugged", plugged != 0);
            out.put("power_save_mode", pm != null && pm.isPowerSaveMode());
            out.put("android_sdk", Build.VERSION.SDK_INT);
            out.put(
                    "recommendation",
                    "OptiCore reports measured battery state. It does not claim that killing apps always saves battery.");

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    public static JSONObject recentUsage(Context context) {
        JSONObject out = new JSONObject();

        try {
            JSONArray items = new JSONArray();
            boolean access = hasUsageAccess(context);

            out.put("ok", true);
            out.put("usage_access", access);

            if (!access) {
                out.put("apps", items);
                out.put("settings_action", Settings.ACTION_USAGE_ACCESS_SETTINGS);
                return out;
            }

            UsageStatsManager manager =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);

            if (manager == null) {
                out.put("apps", items);
                return out;
            }

            long end = System.currentTimeMillis();
            long start = end - (6L * 60L * 60L * 1000L);

            List<UsageStats> stats =
                    manager.queryUsageStats(
                            UsageStatsManager.INTERVAL_DAILY,
                            start,
                            end);

            if (stats == null) {
                stats = Collections.emptyList();
            }

            List<UsageStats> copy = new ArrayList<>(stats);
            copy.sort((a, b) -> Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed()));

            int added = 0;

            for (UsageStats stat : copy) {
                if (added >= 20) break;

                String pkg = stat.getPackageName();
                if (!isUserApp(context, pkg)) continue;
                if (pkg.equals(context.getPackageName())) continue;

                JSONObject item = new JSONObject();
                item.put("package", pkg);
                item.put("label", appLabel(context, pkg));
                item.put("last_used_ms", stat.getLastTimeUsed());
                item.put("foreground_ms", stat.getTotalTimeInForeground());
                items.put(item);
                added++;
            }

            out.put("apps", items);

        } catch (Throwable e) {
            putError(out, e);
        }

        return out;
    }

    private static int releasePackages(
            ActivityManager am,
            Set<String> packages,
            int limit,
            Set<String> allAttempted) {

        int attempts = 0;

        if (am == null || packages == null || packages.isEmpty()) {
            return attempts;
        }

        for (String pkg : packages) {
            if (attempts >= limit) {
                break;
            }

            if (pkg == null || pkg.isEmpty() || allAttempted.contains(pkg)) {
                continue;
            }

            try {
                am.killBackgroundProcesses(pkg);
                allAttempted.add(pkg);
                attempts++;
            } catch (Throwable ignored) {
            }

            sleepQuietly(60L);
        }

        return attempts;
    }

    private static String pressureLevel(Memory memory) {
        if (memory == null) {
            return "unknown";
        }

        if (memory.lowMemory || memory.availablePercent < PRESSURE_CRITICAL) {
            return "critical";
        }

        if (memory.availablePercent < PRESSURE_TIGHT) {
            return "tight";
        }

        return "comfortable";
    }

    private static String recommendedMode(Memory memory) {
        String pressure = pressureLevel(memory);

        if ("critical".equals(pressure)) {
            return "aggressive";
        }

        if ("tight".equals(pressure)) {
            return "balanced";
        }

        return "none";
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<String> runningUserPackages(
            Context context,
            ActivityManager am,
            boolean aggressive) {

        LinkedHashSet<String> out = new LinkedHashSet<>();

        if (am == null) return out;

        List<ActivityManager.RunningAppProcessInfo> processes =
                am.getRunningAppProcesses();

        if (processes == null) return out;

        Set<String> protectedPackages = protectedPackages(context);

        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process == null || process.pkgList == null) continue;

            int minimumImportance =
                    aggressive
                            ? ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
                            : ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED;

            if (process.importance < minimumImportance) continue;

            for (String pkg : process.pkgList) {
                if (pkg == null || pkg.isEmpty()) continue;
                if (protectedPackages.contains(pkg)) continue;
                if (!isUserApp(context, pkg)) continue;
                out.add(pkg);
            }
        }

        return out;
    }

    private static Set<String> protectedPackages(Context context) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        out.add(context.getPackageName());
        out.add("android");
        out.add("com.android.systemui");
        out.add("com.android.chrome");
        out.add("com.sec.android.app.sbrowser");
        out.add("com.android.phone");
        out.add("com.samsung.android.incallui");

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            String pkg = context.getPackageManager()
                    .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                    .activityInfo.packageName;

            if (pkg != null) out.add(pkg);
        } catch (Throwable ignored) {
        }

        try {
            String ime =
                    Settings.Secure.getString(
                            context.getContentResolver(),
                            Settings.Secure.DEFAULT_INPUT_METHOD);

            if (ime != null && ime.contains("/")) {
                out.add(ime.substring(0, ime.indexOf('/')));
            }
        } catch (Throwable ignored) {
        }

        return out;
    }

    private static boolean isUserApp(Context context, String pkg) {
        try {
            ApplicationInfo info =
                    context.getPackageManager().getApplicationInfo(pkg, 0);

            boolean system =
                    (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            boolean updatedSystem =
                    (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;

            return !system && !updatedSystem;

        } catch (Throwable e) {
            return false;
        }
    }

    private static String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (Throwable e) {
            return pkg;
        }
    }

    private static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager appOps =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

            if (appOps == null) return false;

            int mode =
                    appOps.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            Process.myUid(),
                            context.getPackageName());

            return mode == AppOpsManager.MODE_ALLOWED;

        } catch (Throwable e) {
            return false;
        }
    }

    private static Memory readMemory(ActivityManager am) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();

        if (am != null) {
            am.getMemoryInfo(info);
        }

        Memory out = new Memory();
        out.totalBytes = Math.max(0L, info.totalMem);
        out.availableBytes = Math.max(0L, info.availMem);
        out.thresholdBytes = Math.max(0L, info.threshold);
        out.lowMemory = info.lowMemory;
        out.availablePercent =
                out.totalBytes > 0
                        ? out.availableBytes * 100.0 / out.totalBytes
                        : 0.0;

        return out;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void putError(JSONObject out, Throwable e) {
        try {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName());
            out.put("message", e.getMessage() == null ? "" : e.getMessage());
        } catch (Throwable ignored) {
        }
    }

    private static final class Memory {
        long totalBytes;
        long availableBytes;
        long thresholdBytes;
        double availablePercent;
        boolean lowMemory;
    }
}
