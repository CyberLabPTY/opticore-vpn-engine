package lab.opticore.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class WebBridgeAuth {

    public static final String PUBLIC_ORIGIN =
            "https://cyberlabpty.github.io";

    public static final String PUBLIC_SITE =
            "https://cyberlabpty.github.io/opticore-vpn-engine/";

    private static final String PREFS =
            "opticore_web_bridge_v1";

    private static final String KEY_TOKEN =
            "token";

    private static final String KEY_EXPIRES =
            "expires_at";

    private static final long TTL_MS =
            24L * 60L * 60L * 1000L;

    private WebBridgeAuth() {}

    public static boolean approve(
            Context context,
            String token,
            String origin) {

        if (context == null ||
                !PUBLIC_ORIGIN.equals(origin) ||
                !isTokenFormatValid(token)) {
            return false;
        }

        long expiresAt =
                System.currentTimeMillis() +
                        TTL_MS;

        prefs(context)
                .edit()
                .putString(
                        KEY_TOKEN,
                        token.toLowerCase(Locale.US))
                .putLong(
                        KEY_EXPIRES,
                        expiresAt)
                .apply();

        return true;
    }

    public static boolean isValid(
            Context context,
            String token,
            String origin) {

        if (context == null ||
                !PUBLIC_ORIGIN.equals(origin) ||
                !isTokenFormatValid(token)) {
            return false;
        }

        SharedPreferences prefs =
                prefs(context);

        long expiresAt =
                prefs.getLong(
                        KEY_EXPIRES,
                        0L);

        if (expiresAt <=
                System.currentTimeMillis()) {

            clear(context);
            return false;
        }

        String stored =
                prefs.getString(
                        KEY_TOKEN,
                        "");

        if (!isTokenFormatValid(stored)) {
            return false;
        }

        byte[] expected =
                stored
                        .toLowerCase(Locale.US)
                        .getBytes(
                                StandardCharsets.UTF_8);

        byte[] provided =
                token
                        .toLowerCase(Locale.US)
                        .getBytes(
                                StandardCharsets.UTF_8);

        return MessageDigest.isEqual(
                expected,
                provided);
    }

    public static long expiresAt(
            Context context) {

        if (context == null) {
            return 0L;
        }

        return prefs(context)
                .getLong(
                        KEY_EXPIRES,
                        0L);
    }

    public static void clear(
            Context context) {

        if (context == null) {
            return;
        }

        prefs(context)
                .edit()
                .remove(KEY_TOKEN)
                .remove(KEY_EXPIRES)
                .apply();
    }

    private static boolean isTokenFormatValid(
            String token) {

        return token != null &&
                token.matches(
                        "[A-Fa-f0-9]{64}");
    }

    private static SharedPreferences prefs(
            Context context) {

        return context
                .getApplicationContext()
                .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE);
    }
}
