package lab.opticore.vpn;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LocalPanelServer {

    private static final int PORT = 8766;
    private static final String ALLOWED_ORIGIN = "http://127.0.0.1:8766";
    private static final String PANEL_BUNDLE = "panel-bundle.zip";

    private static final ExecutorService CLIENTS = Executors.newCachedThreadPool();
    private static final Map<String, byte[]> ASSETS =
            Collections.synchronizedMap(new HashMap<>());

    private static volatile boolean started = false;
    private static volatile Context appContext;
    private static volatile String lastDnsJson = "";
    private static volatile String lastCacheJson = "";
    private static volatile String lastResourceJson = "";
    private static volatile String lastCleanJson = "";

    private LocalPanelServer() {}

    public static synchronized void start(Context context) {
        if (started) return;

        appContext = context.getApplicationContext();
        loadAssets();
        started = true;

        Thread serverThread = new Thread(
                LocalPanelServer::runLoop,
                "OptiCore-Panel-8766");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private static void loadAssets() {
        ASSETS.clear();

        try {
            StringBuilder encoded =
                    new StringBuilder();

            for (int i = 0; i < 6; i++) {
                String name =
                        String.format(
                                Locale.US,
                                "panel-bundle.b64.%02d",
                                i);

                try (InputStream in =
                             appContext
                                     .getAssets()
                                     .open(name);
                     ByteArrayOutputStream out =
                             new ByteArrayOutputStream()) {

                    byte[] buffer =
                            new byte[8192];

                    int read;

                    while ((read =
                            in.read(buffer)) != -1) {

                        out.write(
                                buffer,
                                0,
                                read);
                    }

                    encoded.append(
                            out.toString(
                                    StandardCharsets.UTF_8.name()));
                }
            }

            byte[] zipBytes =
                    Base64.decode(
                            encoded.toString(),
                            Base64.DEFAULT);

            try (ZipInputStream zip =
                         new ZipInputStream(
                                 new ByteArrayInputStream(
                                         zipBytes))) {

                ZipEntry entry;
                byte[] buffer =
                        new byte[8192];

                while ((entry =
                        zip.getNextEntry()) != null) {

                    if (entry.isDirectory()) {
                        continue;
                    }

                    String name =
                            entry.getName();

                    if (name.startsWith("/") ||
                            name.contains("..")) {
                        continue;
                    }

                    ByteArrayOutputStream out =
                            new ByteArrayOutputStream();

                    int read;

                    while ((read =
                            zip.read(buffer)) != -1) {

                        out.write(
                                buffer,
                                0,
                                read);
                    }

                    ASSETS.put(
                            "/" + name,
                            out.toByteArray());

                    zip.closeEntry();
                }
            }

        } catch (Throwable ignored) {
        }
    }

    private static void runLoop() {
        while (started) {

            try (ServerSocket server =
                         new ServerSocket()) {

                server.setReuseAddress(true);

                server.bind(
                        new InetSocketAddress(
                                InetAddress.getByName(
                                        "127.0.0.1"),
                                PORT),
                        16);

                while (started) {
                    final Socket socket =
                            server.accept();

                    CLIENTS.execute(
                            () -> handle(socket));
                }

            } catch (Throwable ignored) {
                sleepQuietly(2000L);
            }
        }
    }

    private static void handle(Socket socket) {
        try {
            socket.setSoTimeout(15000);

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    socket.getInputStream(),
                                    StandardCharsets.UTF_8));

            String requestLine =
                    reader.readLine();

            if (requestLine == null ||
                    requestLine.isEmpty()) {
                return;
            }

            String[] initialParts =
                    requestLine.split(" ");

            String initialMethod =
                    initialParts.length > 0
                            ? initialParts[0]
                            : "";

            String initialTarget =
                    initialParts.length > 1
                            ? initialParts[1]
                            : "";

            String initialPath =
                    initialTarget;

            String initialQuery = "";

            int initialQ =
                    initialTarget.indexOf('?');

            if (initialQ >= 0) {
                initialPath =
                        initialTarget.substring(
                                0,
                                initialQ);

                initialQuery =
                        initialQ + 1 <
                                initialTarget.length()
                                ? initialTarget.substring(
                                        initialQ + 1)
                                : "";
            }

            try {
                initialPath =
                        URLDecoder.decode(
                                initialPath,
                                "UTF-8");
            } catch (Throwable ignored) {
            }

            boolean bridgeStatusRequest =
                    "/api/bridge-status"
                            .equals(
                                    initialPath);

            boolean bridgeDnsRequest =
                    "/api/bridge-dns"
                            .equals(
                                    initialPath);

            boolean bridgeBootstrapRequest =
                    "/api/bridge-bootstrap"
                            .equals(
                                    initialPath);

            boolean bridgeRequest =
                    bridgeStatusRequest ||
                            bridgeDnsRequest ||
                            bridgeBootstrapRequest;

            String origin = "";
            String bridgeToken = "";
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) break;

                if (line.regionMatches(
                        true,
                        0,
                        "Origin:",
                        0,
                        7)) {
                    origin =
                            line.substring(7).trim();

                } else if (line.regionMatches(
                        true,
                        0,
                        "X-OptiCore-Bridge:",
                        0,
                        18)) {

                    bridgeToken =
                            line.substring(18)
                                    .trim();
                }
            }

            if (bridgeRequest) {

                if (!WebBridgeAuth
                        .PUBLIC_ORIGIN
                        .equals(origin)) {

                    sendBridgeJson(
                            socket,
                            403,
                            jsonError(
                                    "origin_not_allowed"));

                    return;
                }

                if ("OPTIONS".equals(
                        initialMethod)) {

                    sendBridge(
                            socket,
                            204,
                            "text/plain; charset=utf-8",
                            new byte[0]);

                    return;
                }

                if (!"GET".equals(
                        initialMethod)) {

                    sendBridgeJson(
                            socket,
                            405,
                            jsonError(
                                    "method_not_allowed"));

                    return;
                }

                if (bridgeBootstrapRequest) {

                    String autoToken =
                            WebBridgeAuth.autoToken(
                                    appContext,
                                    origin);

                    if (autoToken == null ||
                            autoToken.isEmpty()) {

                        sendBridgeJson(
                                socket,
                                500,
                                jsonError(
                                        "bridge_bootstrap_failed"));

                        return;
                    }

                    JSONObject bootstrap =
                            new JSONObject();

                    bootstrap.put(
                            "ok",
                            true);

                    bootstrap.put(
                            "bridge_auto_link",
                            true);

                    bootstrap.put(
                            "bridge_read_only",
                            true);

                    bootstrap.put(
                            "bridge_persistent",
                            true);

                    bootstrap.put(
                            "token",
                            autoToken);

                    bootstrap.put(
                            "device_id",
                            WebBridgeAuth.deviceId(
                                    appContext));

                    bootstrap.put(
                            "version",
                            appVersion());

                    sendBridgeJson(
                            socket,
                            200,
                            bootstrap.toString());

                    return;
                }

                if (!WebBridgeAuth.isValid(
                        appContext,
                        bridgeToken,
                        origin)) {

                    sendBridgeJson(
                            socket,
                            403,
                            jsonError(
                                    "bridge_not_paired"));

                    return;
                }

                if (bridgeDnsRequest) {
                    sendBridgeJson(
                            socket,
                            200,
                            buildDnsAnalysisJson());

                    return;
                }

                Map<String, String>
                        bridgeQuery =
                        parseQuery(
                                initialQuery);

                boolean detailed =
                        "1".equals(
                                bridgeQuery
                                        .get(
                                                "detail"));

                sendBridgeJson(
                        socket,
                        200,
                        buildBridgeStatusJson(
                                detailed));

                return;
            }

            if (!origin.isEmpty() &&
                    !ALLOWED_ORIGIN.equals(origin)) {

                sendJson(
                        socket,
                        403,
                        jsonError(
                                "origin_not_allowed"));

                return;
            }

            String[] parts =
                    requestLine.split(" ");

            if (parts.length < 2) {
                sendJson(
                        socket,
                        400,
                        jsonError("bad_request"));
                return;
            }

            String method = parts[0];
            String target = parts[1];

            if ("OPTIONS".equals(method)) {
                send(
                        socket,
                        204,
                        "text/plain; charset=utf-8",
                        new byte[0]);
                return;
            }

            if (!"GET".equals(method) &&
                    !"POST".equals(method)) {
                sendJson(
                        socket,
                        405,
                        jsonError(
                                "method_not_allowed"));
                return;
            }

            String path = target;
            String query = "";

            int q = target.indexOf('?');

            if (q >= 0) {
                path = target.substring(0, q);

                query =
                        q + 1 < target.length()
                                ? target.substring(q + 1)
                                : "";
            }

            path =
                    URLDecoder.decode(
                            path,
                            "UTF-8");

            if ("/".equals(path)) {
                path = "/index.html";
            }

            if ("POST".equals(method) &&
                    !"/api/vpn-connect".equals(path) &&
                    !"/api/vpn-disconnect".equals(path) &&
                    !"/api/boost-run".equals(path) &&
                    !"/api/release-selected".equals(path) &&
                    !"/api/scan-open-apps".equals(path) &&
                    !"/api/close-recent-apps".equals(path) &&
                    !"/api/open-battery-settings".equals(path) &&
                    !"/api/open-usage-settings".equals(path) &&
                    !"/api/open-accessibility-settings".equals(path)) {
                sendJson(
                        socket,
                        405,
                        jsonError(
                                "method_not_allowed"));
                return;
            }

            switch (path) {

                case "/api/boost-scan":
                    sendJson(
                            socket,
                            200,
                            SmartBoostEngine.scan(appContext).toString());
                    return;

                case "/api/boost-run":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError(
                                        "method_not_allowed"));
                        return;
                    }

                    Map<String, String> boostQuery =
                            parseQuery(query);

                    boolean aggressive =
                            "aggressive".equalsIgnoreCase(
                                    boostQuery.get("mode"));

                    sendJson(
                            socket,
                            200,
                            SmartBoostEngine
                                    .boost(
                                            appContext,
                                            aggressive)
                                    .toString());
                    return;

                case "/api/release-selected":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError(
                                        "method_not_allowed"));
                        return;
                    }

                    Map<String, String> selectedQuery =
                            parseQuery(query);

                    sendJson(
                            socket,
                            200,
                            SelectedAppRelease
                                    .run(
                                            appContext,
                                            selectedQuery.get("packages"))
                                    .toString());
                    return;

                case "/api/battery-diagnostics":
                    sendJson(
                            socket,
                            200,
                            SmartBoostEngine
                                    .batteryDiagnostics(
                                            appContext)
                                    .toString());
                    return;

                case "/api/open-battery-settings":
                    openSettings(
                            Settings.ACTION_BATTERY_SAVER_SETTINGS);

                    sendJson(
                            socket,
                            200,
                            "{\"ok\":true,\"action\":\"battery_settings\"}");
                    return;

                case "/api/open-usage-settings":
                    openSettings(
                            Settings.ACTION_USAGE_ACCESS_SETTINGS);

                    sendJson(
                            socket,
                            200,
                            "{\"ok\":true,\"action\":\"usage_settings\"}");
                    return;

                case "/api/recent-usage":
                    sendJson(
                            socket,
                            200,
                            SmartBoostEngine
                                    .recentUsage(
                                            appContext)
                                    .toString());
                    return;

                case "/api/accessibility-status":
                    sendJson(
                            socket,
                            200,
                            RecentsAccessibilityService
                                    .status(appContext)
                                    .toString());
                    return;

                case "/api/open-accessibility-settings":
                    openSettings(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS);

                    sendJson(
                            socket,
                            200,
                            "{\"ok\":true,\"action\":\"accessibility_settings\"}");
                    return;

                case "/api/scan-open-apps":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError("method_not_allowed"));
                        return;
                    }

                    sendJson(
                            socket,
                            200,
                            RecentsAccessibilityService
                                    .scanOpenApps(appContext)
                                    .toString());
                    return;

                case "/api/close-recent-apps":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError("method_not_allowed"));
                        return;
                    }

                    Map<String, String> closeQuery =
                            parseQuery(query);

                    sendJson(
                            socket,
                            200,
                            RecentsAccessibilityService
                                    .closeSelected(
                                            appContext,
                                            closeQuery.get("targets"))
                                    .toString());
                    return;


                case "/api/vpn-status":
                    sendJson(
                            socket,
                            200,
                            LocalStatusServer.buildStatusJson());
                    return;

                case "/api/vpn-connect":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError(
                                        "method_not_allowed"));
                        return;
                    }

                    startEngineAction(
                            EngineService.ACTION_CONNECT);

                    sendJson(
                            socket,
                            200,
                            "{\"ok\":true,\"action\":\"connect\"}");
                    return;

                case "/api/vpn-disconnect":
                    if (!"POST".equals(method)) {
                        sendJson(
                                socket,
                                405,
                                jsonError(
                                        "method_not_allowed"));
                        return;
                    }

                    startEngineAction(
                            EngineService.ACTION_DISCONNECT);

                    sendJson(
                            socket,
                            200,
                            "{\"ok\":true,\"action\":\"disconnect\"}");
                    return;

                case "/api/refresh":
                    sendJson(
                            socket,
                            200,
                            buildDeviceStatusJson());
                    return;

                case "/data/device-status.json":
                    if (query.startsWith("health=")) {
                        sendJson(
                                socket,
                                200,
                                "{\"ok\":true,\"engine\":\"opticore-embedded\",\"version\":\"" + appVersion() + "\"}");
                    } else {
                        sendJson(
                                socket,
                                200,
                                buildDeviceStatusJson());
                    }
                    return;

                case "/api/dns":
                    lastDnsJson =
                            buildDnsAnalysisJson();

                    sendJson(
                            socket,
                            200,
                            lastDnsJson);
                    return;

                case "/data/dns-analysis.json":
                    if (lastDnsJson.isEmpty()) {
                        lastDnsJson =
                                buildDnsAnalysisJson();
                    }

                    sendJson(
                            socket,
                            200,
                            lastDnsJson);
                    return;

                case "/api/cache-scan":
                    lastCacheJson =
                            buildCacheInventoryJson();

                    sendJson(
                            socket,
                            200,
                            lastCacheJson);
                    return;

                case "/data/cache-inventory.json":
                    if (lastCacheJson.isEmpty()) {
                        lastCacheJson =
                                buildCacheInventoryJson();
                    }

                    sendJson(
                            socket,
                            200,
                            lastCacheJson);
                    return;

                case "/api/cache-clean":
                    lastCleanJson =
                            cleanSelectedCaches(
                                    parseQuery(query));

                    sendJson(
                            socket,
                            200,
                            lastCleanJson);
                    return;

                case "/data/cache-clean-result.json":
                    if (lastCleanJson.isEmpty()) {
                        lastCleanJson =
                                jsonError(
                                        "no_clean_result");
                    }

                    sendJson(
                            socket,
                            200,
                            lastCleanJson);
                    return;

                case "/api/resources":
                    lastResourceJson =
                            buildResourceAnalysisJson();

                    sendJson(
                            socket,
                            200,
                            lastResourceJson);
                    return;

                case "/data/resource-analysis.json":
                    if (lastResourceJson.isEmpty()) {
                        lastResourceJson =
                                buildResourceAnalysisJson();
                    }

                    sendJson(
                            socket,
                            200,
                            lastResourceJson);
                    return;

                case "/favicon.ico":
                    send(
                            socket,
                            204,
                            "image/x-icon",
                            new byte[0]);
                    return;

                default:
                    byte[] asset =
                            ASSETS.get(path);

                    if (asset == null) {
                        send(
                                socket,
                                404,
                                "text/plain; charset=utf-8",
                                "Not Found"
                                        .getBytes(
                                                StandardCharsets.UTF_8));
                        return;
                    }

                    send(
                            socket,
                            200,
                            mime(path),
                            asset);
            }

        } catch (Throwable ignored) {

        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void openSettings(String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appContext.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static String buildBridgeStatusJson(
            boolean detailed) {

        try {
            JSONObject out;

            if (detailed) {
                out =
                        new JSONObject(
                                buildDeviceStatusJson());

            } else {
                MemorySnapshot memory =
                        readMemory();

                out =
                        new JSONObject();

                out.put(
                        "engine_version",
                        appVersion() +
                                "-bridge");

                out.put(
                        "updated_at",
                        nowText());

                out.put(
                        "model",
                        Build.MODEL == null
                                ? "Android"
                                : Build.MODEL);

                out.put(
                        "android",
                        Build.VERSION.RELEASE == null
                                ? ""
                                : Build.VERSION.RELEASE);

                out.put(
                        "ram_available_percent",
                        round1(
                                memory
                                        .ramAvailablePercent));

                out.put(
                        "ram_state",
                        ramState(
                                memory
                                        .ramAvailablePercent));

                out.put(
                        "swap_used_percent",
                        round1(
                                memory
                                        .swapUsedPercent));

                out.put(
                        "swap_state",
                        swapState(
                                memory
                                        .swapUsedPercent));

                out.put(
                        "cpu_efficiency_current_mhz",
                        readCpuMhz(
                                0,
                                false));

                out.put(
                        "cpu_performance_current_mhz",
                        readCpuMhz(
                                4,
                                false));

                out.put(
                        "cpu_performance_max_mhz",
                        readCpuMhz(
                                4,
                                true));

                out.put(
                        "gpu_current_mhz",
                        readHzMhz(
                                "/sys/class/kgsl/kgsl-3d0/gpuclk"));

                out.put(
                        "gpu_max_mhz",
                        readHzMhz(
                                "/sys/class/kgsl/kgsl-3d0/max_gpuclk"));

                double battery =
                        batteryTempC();

                if (battery >= 0.0) {
                    out.put(
                            "battery_celsius",
                            round1(
                                    battery));
                } else {
                    out.put(
                            "battery_celsius",
                            JSONObject.NULL);
                }

                out.put(
                        "network_interface",
                        activeInterfaceName());
            }

            JSONObject vpn =
                    new JSONObject(
                            LocalStatusServer
                                    .buildStatusJson());

            out.put(
                    "ok",
                    true);

            out.put(
                    "version",
                    appVersion());

            out.put(
                    "bridge",
                    true);

            out.put(
                    "bridge_read_only",
                    true);

            out.put(
                    "bridge_persistent",
                    true);

            out.put(
                    "bridge_auto_link",
                    true);

            out.put(
                    "device_id",
                    WebBridgeAuth.deviceId(
                            appContext));

            out.put(
                    "paired_clients",
                    WebBridgeAuth
                            .authorizedClientCount(
                                    appContext));

            out.put(
                    "bridge_expires_at",
                    0L);

            out.put(
                    "vpn_connected",
                    vpn.optBoolean(
                            "connected",
                            false));

            out.put(
                    "vpn_connected_seconds",
                    vpn.optLong(
                            "connected_seconds",
                            0L));

            out.put(
                    "vpn_rx_bytes",
                    vpn.optLong(
                            "rx_bytes",
                            0L));

            out.put(
                    "vpn_tx_bytes",
                    vpn.optLong(
                            "tx_bytes",
                            0L));

            out.put(
                    "vpn_rx_bps",
                    vpn.optDouble(
                            "rx_bps",
                            0.0));

            out.put(
                    "vpn_tx_bps",
                    vpn.optDouble(
                            "tx_bps",
                            0.0));

            out.put(
                    "vpn_rx_ewma_bps",
                    vpn.optDouble(
                            "rx_ewma_bps",
                            0.0));

            out.put(
                    "vpn_tx_ewma_bps",
                    vpn.optDouble(
                            "tx_ewma_bps",
                            0.0));

            out.put(
                    "vpn_burst",
                    vpn.optBoolean(
                            "burst",
                            false));

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "bridge_status_failed");
        }
    }

    private static String buildDeviceStatusJson() {
        try {
            MemorySnapshot memory =
                    readMemory();

            CacheSnapshot cache =
                    scanCaches(false);

            JSONObject out =
                    new JSONObject();

            out.put(
                    "engine_version",
                    appVersion() + "-embedded");

            out.put(
                    "updated_at",
                    nowText());

            out.put(
                    "model",
                    Build.MODEL == null
                            ? "Android"
                            : Build.MODEL);

            out.put(
                    "android",
                    Build.VERSION.RELEASE == null
                            ? ""
                            : Build.VERSION.RELEASE);

            out.put(
                    "ram_available_percent",
                    round1(
                            memory.ramAvailablePercent));

            out.put(
                    "ram_state",
                    ramState(
                            memory.ramAvailablePercent));

            out.put(
                    "swap_used_percent",
                    round1(
                            memory.swapUsedPercent));

            out.put(
                    "swap_state",
                    swapState(
                            memory.swapUsedPercent));

            out.put(
                    "cpu_efficiency_current_mhz",
                    readCpuMhz(
                            0,
                            false));

            out.put(
                    "cpu_performance_current_mhz",
                    readCpuMhz(
                            4,
                            false));

            out.put(
                    "cpu_performance_max_mhz",
                    readCpuMhz(
                            4,
                            true));

            out.put(
                    "gpu_current_mhz",
                    readHzMhz(
                            "/sys/class/kgsl/kgsl-3d0/gpuclk"));

            out.put(
                    "gpu_max_mhz",
                    readHzMhz(
                            "/sys/class/kgsl/kgsl-3d0/max_gpuclk"));

            out.put(
                    "gpu_throttling",
                    readLongFile(
                            "/sys/class/kgsl/kgsl-3d0/throttling",
                            0L));

            out.put(
                    "battery_celsius",
                    formatOne(
                            batteryTempC()));

            out.put(
                    "cache_visible_mb",
                    round1(
                            cache.totalMb));

            out.put(
                    "cache_state",
                    cacheState(
                            cache.totalMb));

            out.put(
                    "network_interface",
                    activeInterfaceName());

            PingResult cf =
                    pingHost(
                            "1.1.1.1",
                            3);

            PingResult q9 =
                    pingHost(
                            "9.9.9.9",
                            3);

            PingResult gg =
                    pingHost(
                            "8.8.8.8",
                            3);

            out.put(
                    "cloudflare_ping_ms",
                    cf.available
                            ? formatThree(
                                    cf.avgMs)
                            : "N/D");

            out.put(
                    "quad9_ping_ms",
                    q9.available
                            ? formatThree(
                                    q9.avgMs)
                            : "N/D");

            out.put(
                    "google_ping_ms",
                    gg.available
                            ? formatThree(
                                    gg.avgMs)
                            : "N/D");

            double cfDoh =
                    measureDoh(
                            "https://cloudflare-dns.com/dns-query?name=example.com&type=A",
                            true);

            double ggDoh =
                    measureDoh(
                            "https://dns.google/resolve?name=example.com&type=A",
                            false);

            out.put(
                    "cloudflare_doh_seconds",
                    cfDoh >= 0.0
                            ? formatSix(
                                    cfDoh / 1000.0)
                            : "N/D");

            out.put(
                    "google_doh_seconds",
                    ggDoh >= 0.0
                            ? formatSix(
                                    ggDoh / 1000.0)
                            : "N/D");

            PackageManager pm =
                    appContext
                            .getPackageManager();

            out.put(
                    "camera_manual_sensor",
                    boolInt(
                            pm.hasSystemFeature(
                                    "android.hardware.camera.capability.manual_sensor")));

            out.put(
                    "camera_manual_processing",
                    boolInt(
                            pm.hasSystemFeature(
                                    "android.hardware.camera.capability.manual_post_processing")));

            out.put(
                    "camera_raw",
                    boolInt(
                            pm.hasSystemFeature(
                                    "android.hardware.camera.capability.raw")));

            out.put(
                    "camera_full",
                    boolInt(
                            pm.hasSystemFeature(
                                    "android.hardware.camera.level.full")));

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "device_status_failed");
        }
    }

    private static String buildDnsAnalysisJson() {
        try {
            PingResult cf =
                    pingHost(
                            "1.1.1.1",
                            7);

            PingResult q9 =
                    pingHost(
                            "9.9.9.9",
                            7);

            PingResult gg =
                    pingHost(
                            "8.8.8.8",
                            7);

            PingResult cd =
                    pingHost(
                            "76.76.2.41",
                            7);

            int cfScore =
                    score(cf);

            int q9Score =
                    score(q9);

            int ggScore =
                    score(gg);

            int cdScore =
                    score(cd);

            String best =
                    "Cloudflare";

            int bestScore =
                    cfScore;

            if (q9Score < bestScore) {
                best =
                        "Quad9";

                bestScore =
                        q9Score;
            }

            if (ggScore < bestScore) {
                best =
                        "Google";

                bestScore =
                        ggScore;
            }

            if (cdScore < bestScore) {
                best =
                        "Control D · HaGeZi Pro";

                bestScore =
                        cdScore;
            }

            int cfDoh =
                    averageDoh(true);

            int ggDoh =
                    averageDoh(false);

            int cdDoh =
                    averageControlDDoh();

            String dohCandidate =
                    "Cloudflare";

            int dohMs =
                    cfDoh;

            if (ggDoh < dohMs) {
                dohCandidate =
                        "Google";

                dohMs =
                        ggDoh;
            }

            if (cdDoh < dohMs) {
                dohCandidate =
                        "Control D · HaGeZi Pro";

                dohMs =
                        cdDoh;
            }

            JSONObject out =
                    new JSONObject();

            out.put(
                    "ok",
                    true);

            out.put(
                    "engine",
                    "dns-embedded-1.2");

            out.put(
                    "updated_at",
                    nowText());

            out.put(
                    "network_interface",
                    activeInterfaceName());

            out.put(
                    "current_dns",
                    buildCurrentDnsInfoJson());

            out.put(
                    "cloudflare",
                    pingJson(
                            cf,
                            cfScore));

            out.put(
                    "quad9",
                    pingJson(
                            q9,
                            q9Score));

            out.put(
                    "google",
                    pingJson(
                            gg,
                            ggScore));

            JSONObject controlD =
                    pingJson(
                            cd,
                            cdScore);

            controlD.put(
                    "profile",
                    "HaGeZi Pro");

            controlD.put(
                    "legacy_ipv4",
                    "76.76.2.41");

            controlD.put(
                    "dot_hostname",
                    "x-hagezi-pro.freedns.controld.com");

            controlD.put(
                    "doh_url",
                    "https://freedns.controld.com/x-hagezi-pro");

            controlD.put(
                    "doh_average_ms",
                    cdDoh);

            out.put(
                    "controld_hagezi_pro",
                    controlD);

            out.put(
                    "latency_stability_candidate",
                    best);

            out.put(
                    "latency_stability_candidate_score",
                    bestScore);

            out.put(
                    "cloudflare_doh_average_ms",
                    cfDoh);

            out.put(
                    "google_doh_average_ms",
                    ggDoh);

            out.put(
                    "controld_hagezi_pro_doh_average_ms",
                    cdDoh);

            out.put(
                    "doh_candidate",
                    dohCandidate);

            out.put(
                    "doh_candidate_ms",
                    dohMs);

            out.put(
                    "dns_changed",
                    false);

            out.put(
                    "note",
                    "La selección es orientativa y se basa en esta muestra local. OptiCore no cambia el DNS automáticamente.");

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "dns_analysis_failed");
        }
    }

    private static JSONObject buildCurrentDnsInfoJson()
            throws Exception {

        JSONObject out =
                new JSONObject();

        JSONArray servers =
                new JSONArray();

        out.put(
                "available",
                false);

        out.put(
                "interface",
                "N/D");

        out.put(
                "servers",
                servers);

        out.put(
                "private_dns_active",
                false);

        out.put(
                "private_dns_mode",
                "off");

        out.put(
                "private_dns_server_name",
                JSONObject.NULL);

        try {
            ConnectivityManager cm =
                    (ConnectivityManager)
                            appContext
                                    .getSystemService(
                                            Context.CONNECTIVITY_SERVICE);

            if (cm == null) {
                out.put(
                        "error",
                        "connectivity_manager_unavailable");

                return out;
            }

            Network active =
                    cm.getActiveNetwork();

            if (active == null) {
                out.put(
                        "error",
                        "active_network_unavailable");

                return out;
            }

            LinkProperties props =
                    cm.getLinkProperties(
                            active);

            if (props == null) {
                out.put(
                        "error",
                        "link_properties_unavailable");

                return out;
            }

            String iface =
                    props.getInterfaceName();

            if (iface != null &&
                    !iface.trim().isEmpty()) {

                out.put(
                        "interface",
                        iface.trim());
            }

            for (InetAddress address :
                    props.getDnsServers()) {

                if (address == null) {
                    continue;
                }

                String host =
                        address.getHostAddress();

                if (host != null &&
                        !host.trim().isEmpty()) {

                    servers.put(
                            host.trim());
                }
            }

            boolean privateDnsActive =
                    false;

            String privateDnsServerName =
                    null;

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P) {

                privateDnsActive =
                        props.isPrivateDnsActive();

                privateDnsServerName =
                        props.getPrivateDnsServerName();
            }

            out.put(
                    "private_dns_active",
                    privateDnsActive);

            boolean strict =
                    privateDnsActive &&
                            privateDnsServerName != null &&
                            !privateDnsServerName
                                    .trim()
                                    .isEmpty();

            out.put(
                    "private_dns_mode",
                    privateDnsActive
                            ? strict
                            ? "strict"
                            : "opportunistic"
                            : "off");

            if (strict) {
                out.put(
                        "private_dns_server_name",
                        privateDnsServerName
                                .trim());
            }

            out.put(
                    "available",
                    servers.length() > 0);

            return out;

        } catch (Throwable e) {
            out.put(
                    "error",
                    "current_dns_unavailable");

            return out;
        }
    }

    private static JSONObject pingJson(
            PingResult r,
            int score)
            throws Exception {

        JSONObject o =
                new JSONObject();

        o.put(
                "avg_ms",
                formatThree(
                        r.avgMs));

        o.put(
                "variation_ms",
                formatThree(
                        r.variationMs));

        o.put(
                "loss_percent",
                round1(
                        r.lossPercent));

        o.put(
                "score",
                score);

        return o;
    }

    private static String buildCacheInventoryJson() {
        try {
            CacheSnapshot snapshot =
                    scanCaches(true);

            JSONObject out =
                    new JSONObject();

            out.put(
                    "engine",
                    "cache-embedded-1.0");

            out.put(
                    "updated_at",
                    nowText());

            out.put(
                    "total_visible_mb",
                    round1(
                            snapshot.totalMb));

            out.put(
                    "accessible_cache_count",
                    snapshot.count);

            out.put(
                    "scan_only",
                    true);

            out.put(
                    "deleted",
                    false);

            if (snapshot.accessError != null) {
                out.put(
                        "access_error",
                        snapshot.accessError);
            }

            JSONArray entries =
                    new JSONArray();

            for (CacheEntry e :
                    snapshot.entries) {

                JSONObject item =
                        new JSONObject();

                item.put(
                        "package",
                        e.packageName);

                item.put(
                        "size_mb",
                        round1(
                                e.sizeMb));

                entries.put(item);
            }

            out.put(
                    "entries",
                    entries);

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "cache_scan_failed");
        }
    }

    private static String cleanSelectedCaches(
            Map<String, String> params) {

        try {
            String token =
                    value(
                            params,
                            "token");

            String mode =
                    value(
                            params,
                            "mode");

            String packages =
                    value(
                            params,
                            "packages");

            if (!"CACHE-CLEAN-2026".equals(token)) {
                return jsonError(
                        "confirmation_required");
            }

            if (!("intelligent".equals(mode) ||
                    "manual".equals(mode))) {

                return jsonError(
                        "invalid_mode");
            }

            if (packages.isEmpty()) {
                return jsonError(
                        "no_packages");
            }

            if (!canWriteExternalStorage()) {
                return jsonError(
                        storageAccessError());
            }

            JSONArray results =
                    new JSONArray();

            double totalFreed = 0.0;
            int cleaned = 0;
            int skipped = 0;

            String[] requested =
                    packages.split(",");

            for (String pkgRaw :
                    requested) {

                String pkg =
                        pkgRaw.trim();

                JSONObject result =
                        new JSONObject();

                result.put(
                        "package",
                        pkg);

                if (!pkg.matches(
                        "[A-Za-z0-9._-]+")) {

                    putCleanResult(
                            result,
                            0.0,
                            0.0,
                            0.0,
                            "invalid_package");

                    skipped++;
                    results.put(result);
                    continue;
                }

                if (isBlockedSystemPackage(pkg)) {

                    putCleanResult(
                            result,
                            0.0,
                            0.0,
                            0.0,
                            "blocked_system");

                    skipped++;
                    results.put(result);
                    continue;
                }

                if ("intelligent".equals(mode) &&
                        isProtectedAutoPackage(pkg)) {

                    putCleanResult(
                            result,
                            0.0,
                            0.0,
                            0.0,
                            "protected_auto");

                    skipped++;
                    results.put(result);
                    continue;
                }

                File cache =
                        externalCacheDirFor(pkg);

                if (cache == null ||
                        !cache.isDirectory()) {

                    putCleanResult(
                            result,
                            0.0,
                            0.0,
                            0.0,
                            "missing");

                    skipped++;
                    results.put(result);
                    continue;
                }

                long beforeBytes =
                        safeDirBytes(
                                cache,
                                cache.getCanonicalPath());

                if (beforeBytes <= 0L) {

                    putCleanResult(
                            result,
                            0.0,
                            0.0,
                            0.0,
                            "empty");

                    skipped++;
                    results.put(result);
                    continue;
                }

                String root =
                        cache.getCanonicalPath();

                File[] children =
                        cache.listFiles();

                if (children != null) {
                    for (File child :
                            children) {

                        deleteInsideRoot(
                                child,
                                root);
                    }
                }

                long afterBytes =
                        safeDirBytes(
                                cache,
                                root);

                long freedBytes =
                        Math.max(
                                0L,
                                beforeBytes -
                                        afterBytes);

                double beforeMb =
                        bytesToMb(
                                beforeBytes);

                double afterMb =
                        bytesToMb(
                                afterBytes);

                double freedMb =
                        bytesToMb(
                                freedBytes);

                totalFreed += freedMb;
                cleaned++;

                putCleanResult(
                        result,
                        beforeMb,
                        afterMb,
                        freedMb,
                        afterBytes == 0L
                                ? "cleaned"
                                : "partial");

                results.put(result);
            }

            JSONObject out =
                    new JSONObject();

            out.put(
                    "ok",
                    true);

            out.put(
                    "engine",
                    "cache-clean-embedded-1.0");

            out.put(
                    "updated_at",
                    nowText());

            out.put(
                    "mode",
                    mode);

            out.put(
                    "freed_mb",
                    round1(
                            totalFreed));

            out.put(
                    "cleaned_count",
                    cleaned);

            out.put(
                    "skipped_count",
                    skipped);

            out.put(
                    "results",
                    results);

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "cache_clean_failed");
        }
    }

    private static void putCleanResult(
            JSONObject result,
            double before,
            double after,
            double freed,
            String status)
            throws Exception {

        result.put(
                "before_mb",
                round1(before));

        result.put(
                "after_mb",
                round1(after));

        result.put(
                "freed_mb",
                round1(freed));

        result.put(
                "status",
                status);
    }

    private static String buildResourceAnalysisJson() {
        try {
            MemorySnapshot memory =
                    readMemory();

            double ramTotalMb =
                    memory.ramTotalKb /
                            1024.0;

            double ramAvailableMb =
                    memory.ramAvailableKb /
                            1024.0;

            double swapUsedMb =
                    memory.swapUsedKb /
                            1024.0;

            int activeCores =
                    Runtime
                            .getRuntime()
                            .availableProcessors();

            int cpuAvgMhz =
                    averageCpuMhz(
                            false);

            int cpuMaxAvgMhz =
                    averageCpuMhz(
                            true);

            int gpuCurrent =
                    readHzMhz(
                            "/sys/class/kgsl/kgsl-3d0/gpuclk");

            int gpuMax =
                    readHzMhz(
                            "/sys/class/kgsl/kgsl-3d0/max_gpuclk");

            double gpuRatio =
                    gpuMax > 0
                            ? gpuCurrent *
                            100.0 /
                            gpuMax
                            : 0.0;

            GpuBusy busy =
                    readGpuBusy();

            double battery =
                    batteryTempC();

            String memoryLevel =
                    memory.ramAvailablePercent <
                            15.0
                            ? "alta"
                            : memory.ramAvailablePercent <
                            30.0
                            ? "moderada"
                            : "normal";

            String swapLevel =
                    memory.swapUsedPercent >=
                            90.0
                            ? "alta"
                            : memory.swapUsedPercent >=
                            70.0
                            ? "elevada"
                            : "normal";

            String thermal =
                    battery < 0.0
                            ? "unavailable"
                            : battery >= 42.0
                            ? "alta"
                            : battery >= 39.0
                            ? "templada"
                            : "normal";

            String overall =
                    "estable";

            String recommendation =
                    "Las mediciones disponibles no justifican una intervención agresiva. Android administra RAM y frecuencias dinámicamente.";

            if ("moderada".equals(
                    memoryLevel)) {

                overall =
                        "vigilar";

                recommendation =
                        "Memoria disponible moderada. La ocupación de ZRAM se informa por separado y no implica por sí sola saturación. Si notas lentitud, cierra aplicaciones pesadas que no estés usando.";
            }

            if ("alta".equals(
                    memoryLevel)) {

                overall =
                        "atencion";

                recommendation =
                        "Presión de memoria alta. Reduce aplicaciones en segundo plano y deja que Android recupere memoria sin forzar procesos del sistema.";
            }

            if ("alta".equals(
                    thermal)) {

                overall =
                        "atencion";

                recommendation =
                        "La temperatura de batería es elevada. Reduce temporalmente tareas pesadas y permite que el dispositivo se enfríe.";
            }

            JSONObject out =
                    new JSONObject();

            out.put(
                    "engine",
                    "resources-embedded-1.0");

            out.put(
                    "updated_at",
                    nowText());

            out.put(
                    "measurement_only",
                    true);

            out.put(
                    "ram_total_mb",
                    round1(
                            ramTotalMb));

            out.put(
                    "ram_available_mb",
                    round1(
                            ramAvailableMb));

            out.put(
                    "ram_available_percent",
                    round1(
                            memory.ramAvailablePercent));

            out.put(
                    "swap_used_mb",
                    round1(
                            swapUsedMb));

            out.put(
                    "swap_used_percent",
                    round1(
                            memory.swapUsedPercent));

            out.put(
                    "memory_pressure",
                    memoryLevel);

            out.put(
                    "memory_pressure_is_estimate",
                    true);

            out.put(
                    "memory_pressure_basis",
                    "MemAvailable");

            out.put(
                    "swap_occupancy_state",
                    swapLevel);

            out.put(
                    "swap_is_direct_pressure_metric",
                    false);

            out.put(
                    "cpu_load_supported",
                    false);

            out.put(
                    "cpu_load_percent",
                    JSONObject.NULL);

            out.put(
                    "cpu_load_state",
                    "unavailable");

            out.put(
                    "cpu_active_cores",
                    activeCores);

            out.put(
                    "cpu_avg_mhz",
                    cpuAvgMhz);

            out.put(
                    "cpu_max_avg_mhz",
                    cpuMaxAvgMhz);

            out.put(
                    "cpu_frequency_is_utilization",
                    false);

            out.put(
                    "gpu_current_mhz",
                    gpuCurrent);

            out.put(
                    "gpu_max_mhz",
                    gpuMax);

            out.put(
                    "gpu_clock_ratio_percent",
                    round1(
                            gpuRatio));

            out.put(
                    "gpu_clock_ratio_is_utilization",
                    false);

            out.put(
                    "gpu_busy_supported",
                    busy.supported);

            out.put(
                    "gpu_busy_sample_percent",
                    round1(
                            busy.percent));

            out.put(
                    "battery_temp_supported",
                    battery >= 0.0);

            if (battery >= 0.0) {
                out.put(
                        "battery_temp_c",
                        round1(
                                battery));
            } else {
                out.put(
                        "battery_temp_c",
                        JSONObject.NULL);
            }

            out.put(
                    "thermal_state",
                    thermal);

            out.put(
                    "overall_state",
                    overall);

            out.put(
                    "recommendation",
                    recommendation);

            out.put(
                    "can_force_ram_cleanup",
                    false);

            out.put(
                    "can_change_cpu_governor",
                    false);

            out.put(
                    "can_force_gpu_frequency",
                    false);

            return out.toString();

        } catch (Throwable e) {
            return jsonError(
                    "resource_analysis_failed");
        }
    }

    private static CacheSnapshot scanCaches(
            boolean includeEntries) {

        CacheSnapshot snapshot =
                new CacheSnapshot();

        if (!canReadExternalStorage()) {
            snapshot.accessError =
                    storageAccessError();

            return snapshot;
        }

        try {
            File root =
                    new File(
                            Environment
                                    .getExternalStorageDirectory(),
                            "Android/data");

            File[] packages =
                    root.listFiles();

            if (packages == null) {
                snapshot.accessError =
                        "storage_unavailable";

                return snapshot;
            }

            List<CacheEntry> entries =
                    new ArrayList<>();

            long totalBytes = 0L;
            int count = 0;

            for (File pkgDir :
                    packages) {

                if (pkgDir == null ||
                        !pkgDir.isDirectory()) {
                    continue;
                }

                File cache =
                        new File(
                                pkgDir,
                                "cache");

                if (!cache.isDirectory()) {
                    continue;
                }

                String cacheRoot =
                        cache.getCanonicalPath();

                long bytes =
                        safeDirBytes(
                                cache,
                                cacheRoot);

                if (bytes <= 0L) {
                    continue;
                }

                totalBytes += bytes;
                count++;

                if (includeEntries) {
                    entries.add(
                            new CacheEntry(
                                    pkgDir.getName(),
                                    bytesToMb(
                                            bytes)));
                }
            }

            if (includeEntries) {
                entries.sort(
                        (a, b) ->
                                Double.compare(
                                        b.sizeMb,
                                        a.sizeMb));

                if (entries.size() > 20) {
                    entries =
                            new ArrayList<>(
                                    entries.subList(
                                            0,
                                            20));
                }
            }

            snapshot.totalMb =
                    bytesToMb(
                            totalBytes);

            snapshot.count =
                    count;

            snapshot.entries =
                    entries;

        } catch (Throwable e) {
            snapshot.accessError =
                    "storage_scan_failed";
        }

        return snapshot;
    }

    private static boolean canReadExternalStorage() {
        if (Build.VERSION.SDK_INT >
                Build.VERSION_CODES.P) {
            return false;
        }

        return appContext
                .checkSelfPermission(
                        Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean canWriteExternalStorage() {
        if (Build.VERSION.SDK_INT >
                Build.VERSION_CODES.P) {
            return false;
        }

        return appContext
                .checkSelfPermission(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static String storageAccessError() {
        return Build.VERSION.SDK_INT >
                Build.VERSION_CODES.P
                ? "android_storage_protected"
                : "permission_required";
    }

    private static File externalCacheDirFor(
            String packageName)
            throws Exception {

        File root =
                new File(
                        Environment
                                .getExternalStorageDirectory(),
                        "Android/data");

        File cache =
                new File(
                        new File(
                                root,
                                packageName),
                        "cache");

        String rootCanonical =
                root.getCanonicalPath();

        String cacheCanonical =
                cache.getCanonicalPath();

        String expectedPrefix =
                rootCanonical +
                        File.separator +
                        packageName +
                        File.separator;

        if (!cacheCanonical.startsWith(
                expectedPrefix) ||
                !cacheCanonical.endsWith(
                        File.separator +
                                "cache")) {

            return null;
        }

        return cache;
    }

    private static boolean isBlockedSystemPackage(
            String pkg) {

        return pkg.startsWith(
                "com.sec.")
                || pkg.startsWith(
                "com.samsung.")
                || "com.android.systemui"
                .equals(pkg)
                || "com.google.android.gms"
                .equals(pkg)
                || "com.google.android.gsf"
                .equals(pkg);
    }

    private static boolean isProtectedAutoPackage(
            String pkg) {

        return "org.telegram.messenger"
                .equals(pkg)
                || "com.whatsapp"
                .equals(pkg)
                || "com.pixonic.wwr"
                .equals(pkg);
    }

    private static void deleteInsideRoot(
            File file,
            String rootCanonical) {

        try {
            String canonical =
                    file.getCanonicalPath();

            if (!canonical.startsWith(
                    rootCanonical +
                            File.separator)) {
                return;
            }

            if (file.isDirectory()) {
                File[] children =
                        file.listFiles();

                if (children != null) {
                    for (File child :
                            children) {

                        deleteInsideRoot(
                                child,
                                rootCanonical);
                    }
                }
            }

            file.delete();

        } catch (Throwable ignored) {
        }
    }

    private static long safeDirBytes(
            File file,
            String rootCanonical) {

        try {
            String canonical =
                    file.getCanonicalPath();

            if (!canonical.equals(
                    rootCanonical) &&
                    !canonical.startsWith(
                            rootCanonical +
                                    File.separator)) {

                return 0L;
            }

            if (file.isFile()) {
                return Math.max(
                        0L,
                        file.length());
            }

            if (!file.isDirectory()) {
                return 0L;
            }

            long total = 0L;

            File[] children =
                    file.listFiles();

            if (children != null) {
                for (File child :
                        children) {

                    total +=
                            safeDirBytes(
                                    child,
                                    rootCanonical);
                }
            }

            return total;

        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static MemorySnapshot readMemory() {
        MemorySnapshot m =
                new MemorySnapshot();

        Map<String, Long> mem =
                readProcMemInfo();

        m.ramTotalKb =
                valueLong(
                        mem,
                        "MemTotal",
                        1L);

        m.ramAvailableKb =
                valueLong(
                        mem,
                        "MemAvailable",
                        0L);

        m.swapTotalKb =
                valueLong(
                        mem,
                        "SwapTotal",
                        0L);

        long swapFree =
                valueLong(
                        mem,
                        "SwapFree",
                        0L);

        m.swapUsedKb =
                Math.max(
                        0L,
                        m.swapTotalKb -
                                swapFree);

        m.ramAvailablePercent =
                m.ramTotalKb > 0L
                        ? m.ramAvailableKb *
                        100.0 /
                        m.ramTotalKb
                        : 0.0;

        m.swapUsedPercent =
                m.swapTotalKb > 0L
                        ? m.swapUsedKb *
                        100.0 /
                        m.swapTotalKb
                        : 0.0;

        return m;
    }

    private static Map<String, Long> readProcMemInfo() {
        Map<String, Long> out =
                new HashMap<>();

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(
                                     "/proc/meminfo"))) {

            String line;

            while ((line =
                    reader.readLine()) != null) {

                int colon =
                        line.indexOf(':');

                if (colon <= 0) continue;

                String key =
                        line.substring(
                                0,
                                colon)
                                .trim();

                String rest =
                        line.substring(
                                colon + 1)
                                .trim();

                String[] parts =
                        rest.split("\s+");

                if (parts.length > 0) {
                    try {
                        out.put(
                                key,
                                Long.parseLong(
                                        parts[0]));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

        } catch (Throwable ignored) {
        }

        return out;
    }

    private static int readCpuMhz(
            int cpu,
            boolean max) {

        String file =
                "/sys/devices/system/cpu/cpu" +
                        cpu +
                        "/cpufreq/" +
                        (max
                                ? "cpuinfo_max_freq"
                                : "scaling_cur_freq");

        return (int) (
                readLongFile(
                        file,
                        0L) /
                        1000L);
    }

    private static int averageCpuMhz(
            boolean max) {

        long total = 0L;
        int count = 0;

        int cores =
                Math.max(
                        1,
                        Runtime
                                .getRuntime()
                                .availableProcessors());

        for (int i = 0;
             i < cores;
             i++) {

            int mhz =
                    readCpuMhz(
                            i,
                            max);

            if (mhz > 0) {
                total += mhz;
                count++;
            }
        }

        return count > 0
                ? (int) (
                total /
                        count)
                : 0;
    }

    private static int readHzMhz(
            String path) {

        long value =
                readLongFile(
                        path,
                        0L);

        return value > 0L
                ? (int) (
                value /
                        1_000_000L)
                : 0;
    }

    private static long readLongFile(
            String path,
            long fallback) {

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(
                                     path))) {

            String line =
                    reader.readLine();

            if (line == null) {
                return fallback;
            }

            line =
                    line.trim()
                            .split("\s+")[0];

            return Long.parseLong(
                    line);

        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static GpuBusy readGpuBusy() {
        GpuBusy result =
                new GpuBusy();

        try (BufferedReader reader =
                     new BufferedReader(
                             new FileReader(
                                     "/sys/class/kgsl/kgsl-3d0/gpubusy"))) {

            String line =
                    reader.readLine();

            if (line == null) {
                return result;
            }

            String[] p =
                    line.trim()
                            .split("\s+");

            if (p.length >= 2) {
                double busy =
                        Double.parseDouble(
                                p[0]);

                double total =
                        Double.parseDouble(
                                p[1]);

                if (total > 0.0) {
                    result.supported =
                            true;

                    result.percent =
                            Math.max(
                                    0.0,
                                    Math.min(
                                            100.0,
                                            busy *
                                                    100.0 /
                                                    total));
                }
            }

        } catch (Throwable ignored) {
        }

        return result;
    }

    private static double batteryTempC() {
        try {
            Intent battery =
                    appContext
                            .registerReceiver(
                                    null,
                                    new IntentFilter(
                                            Intent.ACTION_BATTERY_CHANGED));

            if (battery == null) {
                return -1.0;
            }

            int raw =
                    battery.getIntExtra(
                            BatteryManager.EXTRA_TEMPERATURE,
                            Integer.MIN_VALUE);

            if (raw ==
                    Integer.MIN_VALUE) {
                return -1.0;
            }

            return raw / 10.0;

        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    private static String activeInterfaceName() {
        /*
         * Usa la red ACTIVA de Android en lugar de elegir el primer tun*
         * encontrado. Así un túnel antiguo no hace que el panel reporte
         * TUN0 cuando la ruta real está en Wi-Fi o datos móviles.
         */
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(
                            Context.CONNECTIVITY_SERVICE);

            if (cm != null) {
                Network active = cm.getActiveNetwork();

                if (active != null) {
                    LinkProperties props =
                            cm.getLinkProperties(active);

                    if (props != null) {
                        String activeName =
                                props.getInterfaceName();

                        if (activeName != null &&
                                !activeName.trim().isEmpty()) {
                            return activeName;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        /*
         * Fallback para Android/ROMs que no exponen LinkProperties.
         * Un tun* solo gana prioridad cuando EngineService confirma
         * que WireGuard está realmente conectado.
         */
        try {
            Enumeration<NetworkInterface> all =
                    NetworkInterface.getNetworkInterfaces();

            String physical = "desconocida";
            String tunnel = null;

            while (all != null && all.hasMoreElements()) {
                NetworkInterface ni = all.nextElement();

                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }

                String name = ni.getName();

                if (name == null) {
                    continue;
                }

                if (name.startsWith("tun")) {
                    if (tunnel == null) {
                        tunnel = name;
                    }

                    if (EngineService.isConnected()) {
                        return name;
                    }

                    continue;
                }

                if (physical.equals("desconocida") &&
                        (name.startsWith("wlan") ||
                         name.startsWith("rmnet") ||
                         name.startsWith("eth"))) {
                    physical = name;
                }
            }

            if (!physical.equals("desconocida")) {
                return physical;
            }

            if (EngineService.isConnected() && tunnel != null) {
                return tunnel;
            }

            return "desconocida";

        } catch (Throwable ignored) {
            return EngineService.isConnected()
                    ? "tun0"
                    : "desconocida";
        }
    }

    private static PingResult pingHost(
            String host,
            int count) {

        PingResult result =
                new PingResult();

        StringBuilder text =
                new StringBuilder();

        Process process = null;

        try {
            process =
                    new ProcessBuilder(
                            "/system/bin/ping",
                            "-c",
                            String.valueOf(
                                    count),
                            "-W",
                            "2",
                            host)
                            .redirectErrorStream(
                                    true)
                            .start();

            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         process
                                                 .getInputStream(),
                                         StandardCharsets.UTF_8))) {

                String line;

                while ((line =
                        reader.readLine()) != null) {

                    text.append(
                            line)
                            .append('\n');
                }
            }

            process.waitFor();

            Matcher stats =
                    Pattern.compile(
                            "=\s*([0-9.]+)/([0-9.]+)/([0-9.]+)/([0-9.]+)")
                            .matcher(text);

            if (stats.find()) {
                result.avgMs =
                        parseDouble(
                                stats.group(2),
                                9999.0);

                result.variationMs =
                        parseDouble(
                                stats.group(4),
                                9999.0);

                result.available =
                        true;
            }

            Matcher loss =
                    Pattern.compile(
                            "([0-9.]+)%\s*packet loss")
                            .matcher(text);

            if (loss.find()) {
                result.lossPercent =
                        parseDouble(
                                loss.group(1),
                                100.0);
            } else {
                result.lossPercent =
                        result.available
                                ? 0.0
                                : 100.0;
            }

        } catch (Throwable ignored) {
            result.avgMs =
                    9999.0;

            result.variationMs =
                    9999.0;

            result.lossPercent =
                    100.0;

            result.available =
                    false;

        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        return result;
    }

    private static int score(
            PingResult r) {

        if (!r.available) {
            return 999999;
        }

        return (int) Math.round(
                r.avgMs +
                        r.variationMs *
                                2.0 +
                        r.lossPercent *
                                5.0);
    }

    private static int averageDoh(
            boolean cloudflare) {

        String[] names = {
                "example.com",
                "google.com",
                "wikipedia.org"
        };

        long total = 0L;
        int count = 0;

        for (String name :
                names) {

            String url =
                    cloudflare
                            ? "https://cloudflare-dns.com/dns-query?name=" +
                            name +
                            "&type=A"
                            : "https://dns.google/resolve?name=" +
                            name +
                            "&type=A";

            double ms =
                    measureDoh(
                            url,
                            cloudflare);

            if (ms >= 0.0) {
                total +=
                        Math.round(ms);

                count++;
            }
        }

        return count > 0
                ? (int) (
                total /
                        count)
                : 9999;
    }

    private static int averageControlDDoh() {

        String[] names = {
                "example.com",
                "google.com",
                "wikipedia.org"
        };

        long total = 0L;
        int count = 0;

        for (String name :
                names) {

            double ms =
                    measureDohWire(
                            "https://freedns.controld.com/x-hagezi-pro",
                            name);

            if (ms >= 0.0) {
                total +=
                        Math.round(ms);

                count++;
            }
        }

        return count > 0
                ? (int) (
                total /
                        count)
                : 9999;
    }

    private static double measureDohWire(
            String endpoint,
            String host) {

        HttpURLConnection connection =
                null;

        long start =
                System.nanoTime();

        try {
            byte[] query =
                    buildDnsQuery(
                            host);

            String dns =
                    Base64.encodeToString(
                            query,
                            Base64.URL_SAFE |
                                    Base64.NO_WRAP |
                                    Base64.NO_PADDING);

            String separator =
                    endpoint.contains("?")
                            ? "&"
                            : "?";

            URL url =
                    new URL(
                            endpoint +
                                    separator +
                                    "dns=" +
                                    dns);

            connection =
                    (HttpURLConnection)
                            url.openConnection();

            connection.setConnectTimeout(
                    6000);

            connection.setReadTimeout(
                    6000);

            connection.setRequestMethod(
                    "GET");

            connection.setRequestProperty(
                    "User-Agent",
                    "OptiCore/" + appVersion());

            connection.setRequestProperty(
                    "Accept",
                    "application/dns-message");

            int code =
                    connection
                            .getResponseCode();

            InputStream in =
                    code >= 200 &&
                            code < 300
                            ? connection
                            .getInputStream()
                            : connection
                            .getErrorStream();

            if (in != null) {
                byte[] buffer =
                        new byte[1024];

                while (in.read(buffer) !=
                        -1) {
                }

                in.close();
            }

            if (code < 200 ||
                    code >= 300) {
                return -1.0;
            }

            return (
                    System.nanoTime() -
                            start) /
                    1_000_000.0;

        } catch (Throwable ignored) {
            return -1.0;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] buildDnsQuery(
            String host)
            throws Exception {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(0x4f);
        out.write(0x43);

        out.write(0x01);
        out.write(0x00);

        out.write(0x00);
        out.write(0x01);

        out.write(0x00);
        out.write(0x00);

        out.write(0x00);
        out.write(0x00);

        out.write(0x00);
        out.write(0x00);

        String[] labels =
                host.split("\\.");

        for (String label :
                labels) {

            byte[] bytes =
                    label.getBytes(
                            StandardCharsets.US_ASCII);

            if (bytes.length == 0 ||
                    bytes.length > 63) {
                throw new IllegalArgumentException(
                        "invalid_dns_label");
            }

            out.write(
                    bytes.length);

            out.write(
                    bytes);
        }

        out.write(0x00);

        out.write(0x00);
        out.write(0x01);

        out.write(0x00);
        out.write(0x01);

        return out.toByteArray();
    }

    private static double measureDoh(
            String urlText,
            boolean cloudflare) {

        HttpURLConnection connection =
                null;

        long start =
                System.nanoTime();

        try {
            connection =
                    (HttpURLConnection)
                            new URL(
                                    urlText)
                                    .openConnection();

            connection.setConnectTimeout(
                    6000);

            connection.setReadTimeout(
                    6000);

            connection.setRequestMethod(
                    "GET");

            connection.setRequestProperty(
                    "User-Agent",
                    "OptiCore/" + appVersion());

            if (cloudflare) {
                connection.setRequestProperty(
                        "Accept",
                        "application/dns-json");
            }

            int code =
                    connection
                            .getResponseCode();

            InputStream in =
                    code >= 200 &&
                            code < 400
                            ? connection
                            .getInputStream()
                            : connection
                            .getErrorStream();

            if (in != null) {
                byte[] buffer =
                        new byte[1024];

                while (in.read(buffer) !=
                        -1) {
                }

                in.close();
            }

            if (code < 200 ||
                    code >= 400) {
                return -1.0;
            }

            return (
                    System.nanoTime() -
                            start) /
                    1_000_000.0;

        } catch (Throwable ignored) {
            return -1.0;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Map<String, String> parseQuery(
            String query) {

        Map<String, String> out =
                new HashMap<>();

        if (query == null ||
                query.isEmpty()) {
            return out;
        }

        String[] pairs =
                query.split("&");

        for (String pair :
                pairs) {

            int eq =
                    pair.indexOf('=');

            String key =
                    eq >= 0
                            ? pair.substring(
                            0,
                            eq)
                            : pair;

            String value =
                    eq >= 0
                            ? pair.substring(
                            eq + 1)
                            : "";

            try {
                key =
                        URLDecoder.decode(
                                key,
                                "UTF-8");

                value =
                        URLDecoder.decode(
                                value,
                                "UTF-8");

            } catch (Throwable ignored) {
            }

            out.put(
                    key,
                    value);
        }

        return out;
    }

    private static String value(
            Map<String, String> map,
            String key) {

        String v =
                map.get(key);

        return v == null
                ? ""
                : v;
    }

    private static void sendBridgeJson(
            Socket socket,
            int status,
            String json)
            throws Exception {

        sendBridge(
                socket,
                status,
                "application/json; charset=utf-8",
                json.getBytes(
                        StandardCharsets.UTF_8));
    }

    private static void sendBridge(
            Socket socket,
            int status,
            String type,
            byte[] body)
            throws Exception {

        String statusText;

        switch (status) {
            case 200:
                statusText = "OK";
                break;

            case 204:
                statusText = "No Content";
                break;

            case 400:
                statusText = "Bad Request";
                break;

            case 403:
                statusText = "Forbidden";
                break;

            case 404:
                statusText = "Not Found";
                break;

            case 405:
                statusText = "Method Not Allowed";
                break;

            default:
                statusText = "Error";
                break;
        }

        byte[] bytes =
                body == null
                        ? new byte[0]
                        : body;

        String headers =
                "HTTP/1.1 " +
                        status +
                        " " +
                        statusText +
                        "\r\n" +
                        "Content-Type: " +
                        type +
                        "\r\n" +
                        "Content-Length: " +
                        bytes.length +
                        "\r\n" +
                        "Access-Control-Allow-Origin: " +
                        WebBridgeAuth.PUBLIC_ORIGIN +
                        "\r\n" +
                        "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: X-OptiCore-Bridge, Content-Type\r\n" +
                        "Access-Control-Allow-Private-Network: true\r\n" +
                        "Access-Control-Max-Age: 600\r\n" +
                        "Vary: Origin, Access-Control-Request-Private-Network\r\n" +
                        "Cross-Origin-Resource-Policy: cross-origin\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n";

        OutputStream out =
                socket.getOutputStream();

        out.write(
                headers.getBytes(
                        StandardCharsets.UTF_8));

        if (status != 204 &&
                bytes.length > 0) {

            out.write(bytes);
        }

        out.flush();
    }

    private static void sendJson(
            Socket socket,
            int status,
            String json)
            throws Exception {

        send(
                socket,
                status,
                "application/json; charset=utf-8",
                json.getBytes(
                        StandardCharsets.UTF_8));
    }

    private static void send(
            Socket socket,
            int status,
            String type,
            byte[] body)
            throws Exception {

        String statusText;

        switch (status) {
            case 200:
                statusText = "OK";
                break;

            case 204:
                statusText = "No Content";
                break;

            case 400:
                statusText = "Bad Request";
                break;

            case 403:
                statusText = "Forbidden";
                break;

            case 404:
                statusText = "Not Found";
                break;

            case 405:
                statusText = "Method Not Allowed";
                break;

            default:
                statusText = "Error";
                break;
        }

        byte[] bytes =
                body == null
                        ? new byte[0]
                        : body;

        String headers =
                "HTTP/1.1 " +
                        status +
                        " " +
                        statusText +
                        "\r\n" +
                        "Content-Type: " +
                        type +
                        "\r\n" +
                        "Content-Length: " +
                        bytes.length +
                        "\r\n" +
                        "Access-Control-Allow-Origin: " +
                        ALLOWED_ORIGIN +
                        "\r\n" +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "X-Content-Type-Options: nosniff\r\n" +
                        "Cache-Control: no-store\r\n" +
                        "Connection: close\r\n\r\n";

        OutputStream out =
                socket.getOutputStream();

        out.write(
                headers.getBytes(
                        StandardCharsets.UTF_8));

        if (status != 204 &&
                bytes.length > 0) {

            out.write(bytes);
        }

        out.flush();
    }

    private static String mime(
            String path) {

        if (path.endsWith(
                ".html")) {
            return "text/html; charset=utf-8";
        }

        if (path.endsWith(
                ".css")) {
            return "text/css; charset=utf-8";
        }

        if (path.endsWith(
                ".js")) {
            return "application/javascript; charset=utf-8";
        }

        if (path.endsWith(
                ".webmanifest")) {
            return "application/manifest+json; charset=utf-8";
        }

        if (path.endsWith(
                ".svg")) {
            return "image/svg+xml; charset=utf-8";
        }

        if (path.endsWith(
                ".png")) {
            return "image/png";
        }

        if (path.endsWith(
                ".json")) {
            return "application/json; charset=utf-8";
        }

        return "application/octet-stream";
    }

    private static String jsonError(
            String error) {

        try {
            JSONObject o =
                    new JSONObject();

            o.put(
                    "ok",
                    false);

            o.put(
                    "error",
                    error == null
                            ? "unknown"
                            : error);

            return o.toString();

        } catch (Throwable ignored) {
            return "{\"ok\":false,\"error\":\"unknown\"}";
        }
    }

    private static void startEngineAction(
            String action) {

        Intent intent =
                new Intent(
                        appContext,
                        EngineService.class);

        intent.setAction(
                action);

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            appContext.startForegroundService(
                    intent);

        } else {
            appContext.startService(
                    intent);
        }
    }

    private static String appVersion() {
        try {
            return appContext
                    .getPackageManager()
                    .getPackageInfo(
                            appContext.getPackageName(),
                            0)
                    .versionName;
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String nowText() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.US);

        return f.format(
                new java.util.Date());
    }

    private static String ramState(
            double pct) {

        if (pct >= 25.0) {
            return "NORMAL";
        }

        if (pct >= 15.0) {
            return "MODERADA";
        }

        return "PRESION_ALTA";
    }

    private static String swapState(
            double pct) {

        if (pct >= 80.0) {
            return "ALTO";
        }

        if (pct >= 50.0) {
            return "MODERADO";
        }

        return "BAJO";
    }

    private static String cacheState(
            double mb) {

        if (mb >= 500.0) {
            return "ALTO";
        }

        if (mb >= 100.0) {
            return "MODERADO";
        }

        return "BAJO";
    }

    private static int boolInt(
            boolean value) {

        return value
                ? 1
                : 0;
    }

    private static double bytesToMb(
            long bytes) {

        return bytes /
                (1024.0 *
                        1024.0);
    }

    private static double round1(
            double value) {

        return Math.round(
                value *
                        10.0) /
                10.0;
    }

    private static String formatOne(
            double value) {

        if (value < 0.0) {
            return "N/D";
        }

        return String.format(
                Locale.US,
                "%.1f",
                value);
    }

    private static String formatThree(
            double value) {

        return String.format(
                Locale.US,
                "%.3f",
                Math.max(
                        0.0,
                        value));
    }

    private static String formatSix(
            double value) {

        return String.format(
                Locale.US,
                "%.6f",
                Math.max(
                        0.0,
                        value));
    }

    private static double parseDouble(
            String value,
            double fallback) {

        try {
            return Double.parseDouble(
                    value);

        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long valueLong(
            Map<String, Long> map,
            String key,
            long fallback) {

        Long v =
                map.get(key);

        return v == null
                ? fallback
                : v;
    }

    private static void sleepQuietly(
            long ms) {

        try {
            Thread.sleep(ms);

        } catch (InterruptedException e) {
            Thread.currentThread()
                    .interrupt();
        }
    }

    private static final class MemorySnapshot {
        long ramTotalKb;
        long ramAvailableKb;
        long swapTotalKb;
        long swapUsedKb;
        double ramAvailablePercent;
        double swapUsedPercent;
    }

    private static final class CacheEntry {
        final String packageName;
        final double sizeMb;

        CacheEntry(
                String packageName,
                double sizeMb) {

            this.packageName =
                    packageName;

            this.sizeMb =
                    sizeMb;
        }
    }

    private static final class CacheSnapshot {
        double totalMb = 0.0;
        int count = 0;
        String accessError = null;

        List<CacheEntry> entries =
                new ArrayList<>();
    }

    private static final class PingResult {
        boolean available = false;
        double avgMs = 9999.0;
        double variationMs = 9999.0;
        double lossPercent = 100.0;
    }

    private static final class GpuBusy {
        boolean supported = false;
        double percent = 0.0;
    }
}
