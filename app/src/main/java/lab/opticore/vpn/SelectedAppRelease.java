package lab.opticore.vpn;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

public final class SelectedAppRelease {

    private SelectedAppRelease() {}

    public static JSONObject run(Context context, String packagesCsv) {
        JSONObject out = new JSONObject();

        try {
            ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (am == null) {
                out.put("ok", false);
                out.put("reason", "activity_manager_unavailable");
                return out;
            }

            if (Build.VERSION.SDK_INT >= 34) {
                out.put("ok", false);
                out.put("blocked", true);
                out.put("reason", "android_14_plus_other_app_process_control_blocked");
                out.put("android_sdk", Build.VERSION.SDK_INT);
                return out;
            }

            Set<String> selected = new LinkedHashSet<>();

            if (packagesCsv != null) {
                for (String raw : packagesCsv.split(",")) {
                    if (selected.size() >= 20) break;
                    String pkg = raw == null ? "" : raw.trim();
                    if (!pkg.isEmpty()) selected.add(pkg);
                }
            }

            if (selected.isEmpty()) {
                out.put("ok", false);
                out.put("reason", "no_packages_selected");
                return out;
            }

            Set<String> protectedPackages = protectedPackages(context);
            JSONArray attempted = new JSONArray();
            JSONArray skipped = new JSONArray();

            ActivityManager.MemoryInfo before = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(before);

            for (String pkg : selected) {
                JSONObject skip = new JSONObject();
                skip.put("package", pkg);

                if (protectedPackages.contains(pkg)) {
                    skip.put("reason", "protected");
                    skipped.put(skip);
                    continue;
                }

                if (!isUserApp(context, pkg)) {
                    skip.put("reason", "not_user_app");
                    skipped.put(skip);
                    continue;
                }

                try {
                    am.killBackgroundProcesses(pkg);
                    attempted.put(pkg);
                } catch (Throwable e) {
                    skip.put("reason", "android_rejected");
                    skipped.put(skip);
                }
            }

            try {
                Thread.sleep(900L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            ActivityManager.MemoryInfo after = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(after);

            double beforeMb = before.availMem / 1048576.0;
            double afterMb = after.availMem / 1048576.0;

            out.put("ok", true);
            out.put("requested_count", selected.size());
            out.put("attempted_count", attempted.length());
            out.put("attempted_packages", attempted);
            out.put("skipped_count", skipped.length());
            out.put("skipped", skipped);
            out.put("ram_before_mb", Math.round(beforeMb * 10.0) / 10.0);
            out.put("ram_after_mb", Math.round(afterMb * 10.0) / 10.0);
            out.put("measured_delta_mb", Math.round((afterMb - beforeMb) * 10.0) / 10.0);
            out.put(
                    "note",
                    "OptiCore requests release only for selected user apps. Android retains final control over process lifetime.");

        } catch (Throwable e) {
            try {
                out.put("ok", false);
                out.put("reason", e.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
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

    private static Set<String> protectedPackages(Context context) {
        LinkedHashSet<String> out = new LinkedHashSet<>();

        out.add(context.getPackageName());
        out.add("android");
        out.add("com.android.systemui");

        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            String pkg =
                    context.getPackageManager()
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
}
