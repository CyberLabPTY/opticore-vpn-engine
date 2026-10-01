package lab.opticore.vpn;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class LocalStatusServer {

    private static final int PORT = 8767;
    private static final String ALLOWED_ORIGIN = "http://127.0.0.1:8766";
    private static volatile boolean started = false;
    private static Context appContext;

    private LocalStatusServer() {}

    public static synchronized void start(Context context) {
        if (started) return;

        appContext = context.getApplicationContext();
        started = true;

        Thread thread = new Thread(LocalStatusServer::runServer, "OptiCore-LocalStatus");
        thread.setDaemon(true);
        thread.start();
    }

    private static void runServer() {
        /*
         * El servidor de estado no debe morir por un cambio de red,
         * un reinicio temporal del proceso o un puerto ocupado durante
         * unos segundos. Se mantiene ligado únicamente a loopback.
         */
        while (started) {
            try (ServerSocket server = new ServerSocket()) {
                server.setReuseAddress(true);
                server.bind(
                        new InetSocketAddress(
                                InetAddress.getByName("127.0.0.1"),
                                PORT),
                        8);

                while (started) {
                    Socket socket = server.accept();
                    handle(socket);
                }

            } catch (Exception ignored) {
                sleepQuietly(1500L);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(3000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            String request = reader.readLine();
            String origin = "";
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break;

                if (line.regionMatches(true, 0, "Origin:", 0, 7)) {
                    origin = line.substring(7).trim();
                }
            }

            if (request == null) return;

            if (!origin.isEmpty() && !ALLOWED_ORIGIN.equals(origin)) {
                send(socket, 403,
                        "{\"ok\":false,\"error\":\"origin_not_allowed\"}",
                        "application/json; charset=utf-8");
                return;
            }

            if (request.startsWith("OPTIONS ")) {
                send(socket, 204, "", "text/plain");
                return;
            }

            if (request.startsWith("GET /status")) {
                send(socket, 200, buildStatusJson(),
                        "application/json; charset=utf-8");
                return;
            }

            if (request.startsWith("GET /ping")) {
                send(socket, 200,
                        "{\"ok\":true,\"service\":\"OptiCore\"}",
                        "application/json; charset=utf-8");
                return;
            }

            send(socket, 404,
                    "{\"ok\":false,\"error\":\"not_found\"}",
                    "application/json; charset=utf-8");

        } catch (Exception ignored) {

        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    static String buildStatusJson() {
        boolean connected = EngineService.isConnected();
        long since = EngineService.getConnectedSince();
        long seconds = 0L;

        if (connected && since > 0L) {
            seconds = Math.max(
                    0L,
                    (System.currentTimeMillis() - since) / 1000L
            );
        }

        String endpoint = "--";
        boolean hasConfig = false;

        try {
            SecureStore store = new SecureStore(appContext);
            hasConfig = store.hasConfig();

            if (hasConfig) {
                endpoint = endpointFromConfig(store.loadConfig());
            }
        } catch (Exception ignored) {}

        String error = EngineService.getLastError();
        if (error == null) error = "";

        return "{"
                + "\"ok\":true,"
                + "\"app\":\"OptiCore VPN Engine\","
                + "\"version\":\"" + esc(versionName()) + "\","
                + "\"connected\":" + connected + ","
                + "\"has_config\":" + hasConfig + ","
                + "\"endpoint\":\"" + esc(endpoint) + "\","
                + "\"connected_since\":" + since + ","
                + "\"connected_seconds\":" + seconds + ","
                + "\"rx_bytes\":" + EngineService.getTelemetryRxBytes() + ","
                + "\"tx_bytes\":" + EngineService.getTelemetryTxBytes() + ","
                + "\"rx_bps\":" + EngineService.getTelemetryRxBps() + ","
                + "\"tx_bps\":" + EngineService.getTelemetryTxBps() + ","
                + "\"rx_ewma_bps\":" + EngineService.getTelemetryRxEwmaBps() + ","
                + "\"tx_ewma_bps\":" + EngineService.getTelemetryTxEwmaBps() + ","
                + "\"burst\":" + EngineService.isTelemetryBurst() + ","
                + "\"burst_z\":" + EngineService.getTelemetryBurstZ() + ","
                + "\"telemetry_sample_ms\":" + EngineService.getTelemetrySampleMs() + ","
                + "\"telemetry_samples\":" + EngineService.getTelemetrySamples() + ","
                + "\"telemetry_source\":\"wireguard_backend\","
                + "\"telemetry_model\":\"delta+time_ewma+welford_zscore\","
                + "\"last_error\":\"" + esc(error) + "\""
                + "}";
    }

    private static String versionName() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0)
                    .versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String endpointFromConfig(String config) {
        if (config == null || config.isEmpty()) return "--";

        String[] lines = config.split("\\r?\\n");

        for (String raw : lines) {
            String line = raw.trim();

            if (line.regionMatches(true, 0, "Endpoint", 0, 8)) {
                int eq = line.indexOf("=");

                if (eq >= 0 && eq + 1 < line.length()) {
                    String value = line.substring(eq + 1).trim();
                    if (!value.isEmpty()) return value;
                }
            }
        }

        return "--";
    }

    private static void send(
            Socket socket,
            int status,
            String body,
            String type
    ) throws Exception {

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

        String statusText =
                status == 200 ? "OK" :
                status == 204 ? "No Content" :
                status == 403 ? "Forbidden" :
                "Not Found";

        String headers =
                "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: " + ALLOWED_ORIGIN + "\r\n"
                + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.UTF_8));

        if (status != 204) {
            out.write(bytes);
        }

        out.flush();
    }

    private static String esc(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
