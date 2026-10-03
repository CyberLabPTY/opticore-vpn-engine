package lab.opticore.vpn;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

public final class ShizukuEngine {

    private static final int REQUEST_CODE = 9050;

    private static volatile boolean initialized = false;
    private static volatile boolean binderAlive = false;
    private static volatile boolean permissionGranted = false;

    private static volatile int serverUid = -1;
    private static volatile int serverVersion = -1;

    private static volatile String selinuxContext = "";
    private static volatile String state = "NO_BINDER";
    private static volatile String detail = "Shizuku no iniciado";

    private ShizukuEngine() {
    }

    private static final Shizuku.OnBinderReceivedListener
            BINDER_RECEIVED = ShizukuEngine::refresh;

    private static final Shizuku.OnBinderDeadListener
            BINDER_DEAD = () -> {

        binderAlive = false;
        permissionGranted = false;
        serverUid = -1;
        serverVersion = -1;
        selinuxContext = "";

        state = "NO_BINDER";
        detail = "Servidor Shizuku detenido";
    };

    private static final Shizuku.OnRequestPermissionResultListener
            PERMISSION_RESULT = (requestCode, result) -> {

        if (requestCode != REQUEST_CODE) {
            return;
        }

        permissionGranted =
                result == PackageManager.PERMISSION_GRANTED;

        refresh();
    };

    public static synchronized void init() {

        if (initialized) {
            refresh();
            return;
        }

        initialized = true;

        try {
            Shizuku.addBinderReceivedListenerSticky(
                    BINDER_RECEIVED);

            Shizuku.addBinderDeadListener(
                    BINDER_DEAD);

            Shizuku.addRequestPermissionResultListener(
                    PERMISSION_RESULT);

        } catch (Throwable e) {

            state = "INIT_ERROR";

            detail =
                    e.getClass().getSimpleName()
                            + ": "
                            + safe(e.getMessage());
        }

        refresh();
    }

    public static void refresh() {

        try {

            if (!Shizuku.pingBinder()) {

                binderAlive = false;
                permissionGranted = false;

                serverUid = -1;
                serverVersion = -1;

                selinuxContext = "";

                state = "NO_BINDER";
                detail = "Shizuku no esta iniciado";

                return;
            }

            binderAlive = true;

            serverVersion =
                    Shizuku.getVersion();

            serverUid =
                    Shizuku.getUid();

            if (Shizuku.isPreV11()) {

                permissionGranted = false;

                state = "UNSUPPORTED";

                detail =
                        "Servidor Shizuku demasiado antiguo";

                return;
            }

            permissionGranted =
                    Shizuku.checkSelfPermission()
                            == PackageManager.PERMISSION_GRANTED;

            if (!permissionGranted) {

                state = "PERMISSION_REQUIRED";

                detail =
                        "Shizuku activo; falta autorizar OptiCore";

                return;
            }

            try {
                selinuxContext =
                        safe(
                                Shizuku.getSELinuxContext()
                        );
            } catch (Throwable ignored) {
                selinuxContext = "";
            }

            if (serverUid == 2000) {

                state = "SHIZUKU_ADB_READY";

                detail =
                        "Motor ADB/shell disponible";

            } else if (serverUid == 0) {

                /*
                 * OptiCore 0.5 permanece estrictamente NO ROOT.
                 */
                state = "ROOT_BACKEND_REJECTED";

                detail =
                        "Backend root detectado; OptiCore no lo utilizara";

            } else {

                state = "SHIZUKU_READY";

                detail =
                        "Shizuku autorizado; UID="
                                + serverUid;
            }

        } catch (Throwable e) {

            binderAlive = false;
            permissionGranted = false;

            state = "ERROR";

            detail =
                    e.getClass().getSimpleName()
                            + ": "
                            + safe(e.getMessage());
        }
    }

    public static String requestPermission() {

        init();

        try {

            if (!Shizuku.pingBinder()) {

                refresh();

                return "Shizuku no esta iniciado";
            }

            if (Shizuku.isPreV11()) {

                refresh();

                return "Servidor Shizuku no compatible";
            }

            if (Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED) {

                refresh();

                return "OptiCore ya esta autorizado";
            }

            if (Shizuku.shouldShowRequestPermissionRationale()) {

                state = "PERMISSION_DENIED";

                detail =
                        "Permiso rechazado anteriormente";

                return "Autoriza OptiCore desde Shizuku";
            }

            Shizuku.requestPermission(
                    REQUEST_CODE);

            state = "PERMISSION_REQUESTED";

            detail =
                    "Esperando autorizacion";

            return "Solicitud enviada a Shizuku";

        } catch (Throwable e) {

            state = "ERROR";

            detail =
                    e.getClass().getSimpleName()
                            + ": "
                            + safe(e.getMessage());

            return "Error solicitando permiso Shizuku";
        }
    }

    public static boolean isShellReady() {

        refresh();

        return binderAlive
                && permissionGranted
                && serverUid == 2000;
    }

    public static boolean isBinderAlive() {
        return binderAlive;
    }

    public static boolean isPermissionGranted() {
        return permissionGranted;
    }

    public static int getServerUid() {
        return serverUid;
    }

    public static int getServerVersion() {
        return serverVersion;
    }

    public static String getSELinuxContext() {
        return selinuxContext;
    }

    public static String getState() {
        return state;
    }

    public static String getDetail() {
        return detail;
    }

    public static String summary() {

        refresh();

        StringBuilder out =
                new StringBuilder();

        out.append("Estado: ")
                .append(state);

        out.append("\nBinder: ")
                .append(
                        binderAlive
                                ? "CONECTADO"
                                : "NO DISPONIBLE");

        out.append("\nPermiso: ")
                .append(
                        permissionGranted
                                ? "AUTORIZADO"
                                : "NO AUTORIZADO");

        if (serverUid >= 0) {

            out.append("\nUID servidor: ")
                    .append(serverUid);
        }

        if (serverVersion >= 0) {

            out.append("\nAPI servidor: ")
                    .append(serverVersion);
        }

        if (!selinuxContext.isEmpty()) {

            out.append("\nSELinux: ")
                    .append(selinuxContext);
        }

        if (!detail.isEmpty()) {

            out.append("\nMotor: ")
                    .append(detail);
        }

        return out.toString();
    }

    private static String safe(String value) {

        return value == null
                ? ""
                : value;
    }
}
