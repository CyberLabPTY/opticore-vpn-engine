package lab.opticore.vpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class WebBridgeAuth {

    public static final String PUBLIC_ORIGIN =
            "https://cyberlabpty.github.io";

    public static final String PUBLIC_SITE =
            "https://cyberlabpty.github.io/opticore-vpn-engine/";

    /*
     * Mismo archivo de preferencias de 0.4.2 para migrar
     * automáticamente el enlace ya existente.
     */
    private static final String PREFS =
            "opticore_web_bridge_v1";

    private static final String LEGACY_KEY_TOKEN =
            "token";

    private static final String LEGACY_KEY_EXPIRES =
            "expires_at";

    private static final String KEY_TOKEN_HASHES =
            "authorized_token_hashes_v2";

    private static final String KEY_DEVICE_ID =
            "device_id_v2";

    private static final String KEY_AUTO_TOKEN =
            "auto_bridge_token_v3";

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

        migrateLegacy(context);

        String hash =
                hashToken(token);

        if (!isHashFormatValid(hash)) {
            return false;
        }

        SharedPreferences prefs =
                prefs(context);

        Set<String> authorized =
                new HashSet<>(
                        prefs.getStringSet(
                                KEY_TOKEN_HASHES,
                                new HashSet<>()));

        /*
         * No reemplaza enlaces anteriores. Esto permite que
         * navegador y PWA del mismo teléfono permanezcan
         * autorizados a la vez.
         */
        authorized.add(hash);

        prefs.edit()
                .putStringSet(
                        KEY_TOKEN_HASHES,
                        new HashSet<>(authorized))
                .remove(LEGACY_KEY_EXPIRES)
                .apply();

        deviceId(context);

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

        migrateLegacy(context);

        String providedHash =
                hashToken(token);

        if (!isHashFormatValid(providedHash)) {
            return false;
        }

        Set<String> authorized =
                prefs(context)
                        .getStringSet(
                                KEY_TOKEN_HASHES,
                                new HashSet<>());

        byte[] provided =
                providedHash.getBytes(
                        StandardCharsets.UTF_8);

        for (String stored : authorized) {

            if (!isHashFormatValid(stored)) {
                continue;
            }

            byte[] expected =
                    stored.getBytes(
                            StandardCharsets.UTF_8);

            if (MessageDigest.isEqual(
                    expected,
                    provided)) {
                return true;
            }
        }

        return false;
    }

    /*
     * Identificador aleatorio de esta instalación.
     * No usa IMEI, IMSI, número telefónico, MAC ni Android ID.
     */
    public static String deviceId(
            Context context) {

        if (context == null) {
            return "";
        }

        SharedPreferences prefs =
                prefs(context);

        String current =
                prefs.getString(
                        KEY_DEVICE_ID,
                        "");

        if (isDeviceIdValid(current)) {
            return current;
        }

        String random =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .toUpperCase(Locale.US);

        String created =
                "OC-" +
                        random.substring(
                                0,
                                12);

        prefs.edit()
                .putString(
                        KEY_DEVICE_ID,
                        created)
                .apply();

        return created;
    }

    public static String autoToken(
            Context context,
            String origin) {

        if (context == null ||
                !PUBLIC_ORIGIN.equals(origin)) {
            return "";
        }

        SharedPreferences prefs =
                prefs(context);

        String current =
                prefs.getString(
                        KEY_AUTO_TOKEN,
                        "");

        if (isTokenFormatValid(current)) {
            approve(
                    context,
                    current,
                    origin);

            return current
                    .toLowerCase(
                            Locale.US);
        }

        try {
            byte[] bytes =
                    new byte[32];

            new SecureRandom()
                    .nextBytes(bytes);

            StringBuilder token =
                    new StringBuilder(64);

            for (byte value : bytes) {
                token.append(
                        String.format(
                                Locale.US,
                                "%02x",
                                value & 0xff));
            }

            String created =
                    token.toString();

            if (!isTokenFormatValid(created)) {
                return "";
            }

            prefs.edit()
                    .putString(
                            KEY_AUTO_TOKEN,
                            created)
                    .apply();

            approve(
                    context,
                    created,
                    origin);

            return created;

        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int authorizedClientCount(
            Context context) {

        if (context == null) {
            return 0;
        }

        migrateLegacy(context);

        return prefs(context)
                .getStringSet(
                        KEY_TOKEN_HASHES,
                        new HashSet<>())
                .size();
    }

    /*
     * Compatibilidad con el código 0.4.2.
     * Cero indica que el enlace ya no caduca por tiempo.
     */
    public static long expiresAt(
            Context context) {

        return 0L;
    }

    /*
     * Desvincula clientes autorizados pero conserva el
     * ID propio de esta instalación.
     */
    public static void clear(
            Context context) {

        if (context == null) {
            return;
        }

        prefs(context)
                .edit()
                .remove(KEY_TOKEN_HASHES)
                .remove(KEY_AUTO_TOKEN)
                .remove(LEGACY_KEY_TOKEN)
                .remove(LEGACY_KEY_EXPIRES)
                .apply();
    }

    private static void migrateLegacy(
            Context context) {

        if (context == null) {
            return;
        }

        SharedPreferences prefs =
                prefs(context);

        String legacy =
                prefs.getString(
                        LEGACY_KEY_TOKEN,
                        "");

        if (!isTokenFormatValid(legacy)) {

            if (prefs.contains(
                    LEGACY_KEY_EXPIRES)) {

                prefs.edit()
                        .remove(
                                LEGACY_KEY_EXPIRES)
                        .apply();
            }

            return;
        }

        String hash =
                hashToken(legacy);

        if (!isHashFormatValid(hash)) {
            return;
        }

        Set<String> authorized =
                new HashSet<>(
                        prefs.getStringSet(
                                KEY_TOKEN_HASHES,
                                new HashSet<>()));

        authorized.add(hash);

        prefs.edit()
                .putStringSet(
                        KEY_TOKEN_HASHES,
                        new HashSet<>(authorized))
                .remove(LEGACY_KEY_TOKEN)
                .remove(LEGACY_KEY_EXPIRES)
                .apply();
    }

    private static String hashToken(
            String token) {

        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256");

            byte[] bytes =
                    digest.digest(
                            token
                                    .toLowerCase(
                                            Locale.US)
                                    .getBytes(
                                            StandardCharsets.UTF_8));

            StringBuilder out =
                    new StringBuilder(
                            bytes.length * 2);

            for (byte value : bytes) {
                out.append(
                        String.format(
                                Locale.US,
                                "%02x",
                                value & 0xff));
            }

            return out.toString();

        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isTokenFormatValid(
            String token) {

        return token != null &&
                token.matches(
                        "[A-Fa-f0-9]{64}");
    }

    private static boolean isHashFormatValid(
            String hash) {

        return hash != null &&
                hash.matches(
                        "[a-f0-9]{64}");
    }

    private static boolean isDeviceIdValid(
            String id) {

        return id != null &&
                id.matches(
                        "OC-[A-F0-9]{12}");
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
