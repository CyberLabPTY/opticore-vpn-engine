package lab.opticore.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.android.backend.Statistics;
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class EngineService extends Service {

    public static final String ACTION_CONNECT =
            "lab.opticore.vpn.CONNECT";
    public static final String ACTION_DISCONNECT =
            "lab.opticore.vpn.DISCONNECT";
    public static final String ACTION_ALWAYS_ON =
            "lab.opticore.vpn.ALWAYS_ON";
    public static final String ACTION_PANEL =
            "lab.opticore.vpn.PANEL";

    private static final String CHANNEL =
            "opticore_vpn_engine";
    private static final int NOTIFICATION_ID = 2601;

    private static volatile boolean connected = false;
    private static volatile long connectedSince = 0L;
    private static volatile String lastError = "";
    /* Q-FLOW: telemetria real del tunel WireGuard */
    private static volatile long telemetryRxBytes = 0L;
    private static volatile long telemetryTxBytes = 0L;
    private static volatile double telemetryRxBps = 0.0;
    private static volatile double telemetryTxBps = 0.0;
    private static volatile double telemetryRxEwmaBps = 0.0;
    private static volatile double telemetryTxEwmaBps = 0.0;
    private static volatile double telemetryBurstZ = 0.0;
    private static volatile boolean telemetryBurst = false;
    private static volatile long telemetrySampleMs = 0L;
    private static volatile long telemetrySamples = 0L;

    private final ScheduledExecutorService telemetryWorker =
            Executors.newSingleThreadScheduledExecutor();

    private long prevTelemetryRx = 0L;
    private long prevTelemetryTx = 0L;
    private long prevTelemetryMs = 0L;
    private double rxEwma = 0.0;
    private double txEwma = 0.0;

    /* Welford: media y varianza online del throughput agregado */
    private long throughputN = 0L;
    private double throughputMean = 0.0;
    private double throughputM2 = 0.0;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    private GoBackend backend;
    private ManagedTunnel tunnel;
    private SecureStore secureStore;

    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();
        startForeground(
                NOTIFICATION_ID,
                notification("Motor VPN preparado"));

        secureStore = new SecureStore(this);
        tunnel = new ManagedTunnel("opticore");

        try {
            backend = new GoBackend(this);
            lastError = "";
        } catch (Throwable e) {
            lastError = "Backend: " + safe(e.getMessage());
        }

        telemetryWorker.scheduleAtFixedRate(
                this::sampleTelemetry,
                0L,
                1L,
                TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_CONNECT.equals(action)) {
            connect();
        } else if (ACTION_DISCONNECT.equals(action)) {
            disconnect();
        } else if (ACTION_ALWAYS_ON.equals(action)) {
            connect();
        } else if (ACTION_PANEL.equals(action)) {
            // Keep the foreground process alive for the embedded local panel.
        } else if (Prefs.autoReconnect(this)) {
            connect();
        }

        return START_STICKY;
    }

    private void connect() {
        worker.execute(() -> {
            try {
                if (backend == null) {
                    connected = false;
                connectedSince = 0L;
                    lastError = "Backend WireGuard no disponible";
                    return;
                }

                if (!secureStore.hasConfig()) {
                    connected = false;
                connectedSince = 0L;
                    lastError = "No hay configuración WireGuard guardada";
                    return;
                }

                if (VpnService.prepare(this) != null) {
                    connected = false;
                connectedSince = 0L;
                    lastError = "VPN_PERMISSION_REQUIRED";
                    return;
                }

                String text = secureStore.loadConfig();

                Config config = Config.parse(
                        new ByteArrayInputStream(
                                text.getBytes(StandardCharsets.UTF_8)));

                Tunnel.State state = backend.setState(
                        tunnel,
                        Tunnel.State.UP,
                        config);

                connected = state == Tunnel.State.UP;
                if (connected && connectedSince == 0L) connectedSince = System.currentTimeMillis();
                if (!connected) connectedSince = 0L;
                lastError = connected ? "" :
                        "El túnel no quedó activo";

                updateNotification(connected
                        ? "VPN protegida activa"
                        : "VPN no conectada");

            } catch (Throwable e) {
                connected = false;
                connectedSince = 0L;
                lastError = e.getClass().getSimpleName() +
                        ": " + safe(e.getMessage());
                updateNotification("Error de conexión VPN");
            }
        });
    }

    private void disconnect() {
        worker.execute(() -> {
            try {
                if (backend != null) {
                    backend.setState(
                            tunnel,
                            Tunnel.State.DOWN,
                            null);
                }

                connected = false;
                connectedSince = 0L;
                lastError = "";
                updateNotification("VPN desconectada");

            } catch (Throwable e) {
                lastError = e.getClass().getSimpleName() +
                        ": " + safe(e.getMessage());
            }
        });
    }

    private void sampleTelemetry() {
        try {
            if (!connected || backend == null || tunnel == null) {
                resetTelemetry();
                return;
            }

            Statistics stats = backend.getStatistics(tunnel);
            long now = System.currentTimeMillis();
            long rx = Math.max(0L, stats.totalRx());
            long tx = Math.max(0L, stats.totalTx());

            telemetryRxBytes = rx;
            telemetryTxBytes = tx;

            if (prevTelemetryMs > 0L
                    && now > prevTelemetryMs
                    && rx >= prevTelemetryRx
                    && tx >= prevTelemetryTx) {

                long dtMs = now - prevTelemetryMs;
                double dtSeconds = dtMs / 1000.0;

                double rawRx = (rx - prevTelemetryRx) / dtSeconds;
                double rawTx = (tx - prevTelemetryTx) / dtSeconds;

                telemetryRxBps = Math.max(0.0, rawRx);
                telemetryTxBps = Math.max(0.0, rawTx);

                /* EWMA continuo dependiente del tiempo, tau = 4 s. */
                double alpha = 1.0 - Math.exp(-(double) dtMs / 4000.0);

                if (telemetrySamples == 0L) {
                    rxEwma = telemetryRxBps;
                    txEwma = telemetryTxBps;
                } else {
                    rxEwma = alpha * telemetryRxBps
                            + (1.0 - alpha) * rxEwma;
                    txEwma = alpha * telemetryTxBps
                            + (1.0 - alpha) * txEwma;
                }

                telemetryRxEwmaBps = Math.max(0.0, rxEwma);
                telemetryTxEwmaBps = Math.max(0.0, txEwma);

                double throughput = telemetryRxBps + telemetryTxBps;

                /*
                 * Deteccion estadistica de rafagas:
                 * z-score contra media/desviacion previas,
                 * actualizadas online con Welford.
                 */
                double z = 0.0;

                if (throughputN > 1L) {
                    double variance = throughputM2 / (throughputN - 1L);
                    if (variance > 0.0) {
                        double sd = Math.sqrt(variance);
                        z = (throughput - throughputMean) / sd;
                    }
                }

                telemetryBurstZ = Double.isFinite(z) ? z : 0.0;
                telemetryBurst = throughputN >= 10L
                        && telemetryBurstZ >= 2.5;

                throughputN++;
                double delta = throughput - throughputMean;
                throughputMean += delta / throughputN;
                double delta2 = throughput - throughputMean;
                throughputM2 += delta * delta2;

                telemetrySamples++;
            } else {
                telemetryRxBps = 0.0;
                telemetryTxBps = 0.0;
                telemetryRxEwmaBps = 0.0;
                telemetryTxEwmaBps = 0.0;
                telemetryBurstZ = 0.0;
                telemetryBurst = false;
                telemetrySamples = 0L;
                rxEwma = 0.0;
                txEwma = 0.0;
                throughputN = 0L;
                throughputMean = 0.0;
                throughputM2 = 0.0;
            }

            prevTelemetryRx = rx;
            prevTelemetryTx = tx;
            prevTelemetryMs = now;
            telemetrySampleMs = now;

        } catch (Throwable ignored) {
            telemetryRxBps = 0.0;
            telemetryTxBps = 0.0;
            telemetryBurst = false;
            telemetryBurstZ = 0.0;
            telemetrySampleMs = System.currentTimeMillis();
        }
    }

    private void resetTelemetry() {
        telemetryRxBytes = 0L;
        telemetryTxBytes = 0L;
        telemetryRxBps = 0.0;
        telemetryTxBps = 0.0;
        telemetryRxEwmaBps = 0.0;
        telemetryTxEwmaBps = 0.0;
        telemetryBurstZ = 0.0;
        telemetryBurst = false;
        telemetrySampleMs = System.currentTimeMillis();
        telemetrySamples = 0L;

        prevTelemetryRx = 0L;
        prevTelemetryTx = 0L;
        prevTelemetryMs = 0L;
        rxEwma = 0.0;
        txEwma = 0.0;
        throughputN = 0L;
        throughputMean = 0.0;
        throughputM2 = 0.0;
    }

    public static long getTelemetryRxBytes() {
        return telemetryRxBytes;
    }

    public static long getTelemetryTxBytes() {
        return telemetryTxBytes;
    }

    public static double getTelemetryRxBps() {
        return telemetryRxBps;
    }

    public static double getTelemetryTxBps() {
        return telemetryTxBps;
    }

    public static double getTelemetryRxEwmaBps() {
        return telemetryRxEwmaBps;
    }

    public static double getTelemetryTxEwmaBps() {
        return telemetryTxEwmaBps;
    }

    public static double getTelemetryBurstZ() {
        return telemetryBurstZ;
    }

    public static boolean isTelemetryBurst() {
        return telemetryBurst;
    }

    public static long getTelemetrySampleMs() {
        return telemetrySampleMs;
    }

    public static long getTelemetrySamples() {
        return telemetrySamples;
    }

    public static boolean isConnected() {
        return connected;
    }

    public static long getConnectedSince() { return connectedSince; }
    public static String getLastError() {
        return lastError;
    }

    @Override
    public void onDestroy() {
        telemetryWorker.shutdownNow();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL,
                    "OptiCore VPN Engine",
                    NotificationManager.IMPORTANCE_LOW);

            c.setDescription(
                    "Motor local de protección VPN");

            getSystemService(NotificationManager.class)
                    .createNotificationChannel(c);
        }
    }

    private Notification notification(String message) {
        Intent open = new Intent(this, MainActivity.class);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, flags);

        Notification.Builder b;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }

        return b
                .setContentTitle("OptiCore VPN Engine")
                .setContentText(message)
                .setSmallIcon(R.drawable.ic_vpn)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String message) {
        NotificationManager n =
                (NotificationManager)
                        getSystemService(NOTIFICATION_SERVICE);

        n.notify(NOTIFICATION_ID, notification(message));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
