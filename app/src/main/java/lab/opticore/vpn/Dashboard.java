package lab.opticore.vpn;

import android.app.Activity;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class Dashboard {
    private Dashboard() {}

    public static void refresh(
            Activity activity,
            SecureStore secureStore,
            TextView target) {

        String endpoint = "--";
        try {
            if (secureStore.hasConfig()) {
                endpoint = endpointFromConfig(
                        secureStore.loadConfig());
            }
        } catch (Exception ignored) {}

        final String server =
                endpoint == null || endpoint.isEmpty()
                        ? "--" : endpoint;

        if (!EngineService.isConnected()) {
            target.setText(
                    "Servidor: " + server +
                    "\nIP pública: --" +
                    "\nPaís de salida: --" +
                    "\nTiempo conectado: --");
            return;
        }

        target.setText(
                "Servidor: " + server +
                "\nIP pública: consultando..." +
                "\nPaís de salida: consultando..." +
                "\nTiempo conectado: " +
                formatDuration(
                        EngineService.getConnectedSince()));

        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://ipapi.co/json/");
                connection =
                        (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty(
                        "User-Agent",
                        "OptiCore-VPN-Engine/0.1");

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    showUnavailable(activity, target, server);
                    return;
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                connection.getInputStream(),
                                StandardCharsets.UTF_8));

                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();

                JSONObject json =
                        new JSONObject(body.toString());

                String ip = json.optString("ip", "--");
                String country =
                        json.optString("country_name", "--");
                String city = json.optString("city", "");

                String location =
                        city == null || city.isEmpty()
                                ? country
                                : country + " · " + city;

                activity.runOnUiThread(() ->
                        target.setText(
                                "Servidor: " + server +
                                "\nIP pública: " + ip +
                                "\nPaís de salida: " + location +
                                "\nTiempo conectado: " +
                                formatDuration(
                                        EngineService.getConnectedSince())));

            } catch (Throwable e) {
                showUnavailable(activity, target, server);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        worker.setName("OptiCore-Dashboard");
        worker.start();
    }

    private static void showUnavailable(
            Activity activity,
            TextView target,
            String server) {
        activity.runOnUiThread(() ->
                target.setText(
                        "Servidor: " + server +
                        "\nIP pública: No disponible" +
                        "\nPaís de salida: No disponible" +
                        "\nTiempo conectado: " +
                        formatDuration(
                                EngineService.getConnectedSince())));
    }

    private static String endpointFromConfig(String config) {
        if (config == null || config.isEmpty()) return "";

        String[] lines = config.split("\\r?\\n");
        for (String line : lines) {
            String value = line.trim();
            if (value.regionMatches(
                    true, 0,
                    "Endpoint", 0,
                    "Endpoint".length())) {
                int equals = value.indexOf('=');
                if (equals >= 0 &&
                        equals + 1 < value.length()) {
                    return value.substring(
                            equals + 1).trim();
                }
            }
        }
        return "";
    }

    private static String formatDuration(long since) {
        if (since <= 0L) return "recién conectada";

        long seconds = Math.max(
                0L,
                (System.currentTimeMillis() - since) / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;

        if (hours > 0L) {
            return hours + "h " +
                    minutes + "m " +
                    secs + "s";
        }
        if (minutes > 0L) {
            return minutes + "m " +
                    secs + "s";
        }
        return secs + "s";
    }
}
