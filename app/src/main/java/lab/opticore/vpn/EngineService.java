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
import com.wireguard.config.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class EngineService extends Service {

    public static final String ACTION_CONNECT =
            "lab.opticore.vpn.CONNECT";
    public static final String ACTION_DISCONNECT =
            "lab.opticore.vpn.DISCONNECT";
    public static final String ACTION_ALWAYS_ON =
            "lab.opticore.vpn.ALWAYS_ON";

    private static final String CHANNEL =
            "opticore_vpn_engine";
    private static final int NOTIFICATION_ID = 2601;

    private static volatile boolean connected = false;
    private static volatile long connectedSince = 0L;
    private static volatile String lastError = "";

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

    public static boolean isConnected() {
        return connected;
    }

    public static long getConnectedSince() { return connectedSince; }
    public static String getLastError() {
        return lastError;
    }

    @Override
    public void onDestroy() {
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
