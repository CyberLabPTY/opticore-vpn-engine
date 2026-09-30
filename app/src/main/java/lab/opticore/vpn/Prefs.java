package lab.opticore.vpn;

import android.content.Context;

public final class Prefs {
    private static final String FILE = "opticore_settings";
    private static final String AUTO = "auto_reconnect";

    private Prefs() {}

    public static boolean autoReconnect(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(AUTO, false);
    }

    public static void setAutoReconnect(Context c, boolean value) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AUTO, value)
                .apply();
    }
}
