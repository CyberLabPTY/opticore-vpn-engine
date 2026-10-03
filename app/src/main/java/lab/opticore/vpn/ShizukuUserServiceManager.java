package lab.opticore.vpn;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

public final class ShizukuUserServiceManager {

    private static volatile
    IOptiCoreShellService service;

    private static volatile
    boolean connected = false;

    private static volatile
    int remoteUid = -1;

    private static volatile
    int remotePid = -1;

    private static volatile
    String state = "DISCONNECTED";

    private static volatile
    String detail =
            "UserService no conectado";

    private ShizukuUserServiceManager() {
    }

    private static final
    Shizuku.UserServiceArgs SERVICE_ARGS =
            new Shizuku.UserServiceArgs(
                    new ComponentName(
                            "lab.opticore.vpn",
                            OptiCoreShellService.class.getName()
                    )
            )
                    .daemon(false)
                    .processNameSuffix(
                            "opticore_shell"
                    )
                    .debuggable(false)
                    .version(44);

    private static final
    ServiceConnection CONNECTION =
            new ServiceConnection() {

        @Override
        public void onServiceConnected(
                ComponentName name,
                IBinder binder) {

            try {

                if (binder == null
                        || !binder.pingBinder()) {

                    reset(
                            "INVALID_BINDER",
                            "Binder UserService invalido"
                    );

                    return;
                }

                service =
                        IOptiCoreShellService.Stub
                                .asInterface(binder);

                if (service == null) {

                    reset(
                            "NO_INTERFACE",
                            "No se pudo obtener interfaz"
                    );

                    return;
                }

                remoteUid =
                        service.getUid();

                remotePid =
                        service.getPid();

                if (remoteUid != 2000) {

                    connected = false;

                    state =
                            "UID_REJECTED";

                    detail =
                            "UID remoto="
                                    + remoteUid
                                    + "; OptiCore solo acepta shell UID 2000";

                    return;
                }

                connected = true;

                state =
                        "SHELL_SERVICE_READY";

                detail =
                        service.getIdentity();

            } catch (Throwable e) {

                reset(
                        "SERVICE_ERROR",
                        safeError(e)
                );
            }
        }

        @Override
        public void onServiceDisconnected(
                ComponentName name) {

            reset(
                    "DISCONNECTED",
                    "UserService desconectado"
            );
        }
    };

    public static synchronized String connect() {

        try {

            if (!ShizukuEngine.isShellReady()) {

                reset(
                        "SHIZUKU_NOT_READY",
                        "Shizuku ADB/shell no disponible"
                );

                return detail;
            }

            state =
                    "CONNECTING";

            detail =
                    "Conectando UserService";

            Shizuku.bindUserService(
                    SERVICE_ARGS,
                    CONNECTION
            );

            return "Conexion solicitada";

        } catch (Throwable e) {

            reset(
                    "BIND_ERROR",
                    safeError(e)
            );

            return detail;
        }
    }

    public static synchronized void disconnect() {

        try {

            Shizuku.unbindUserService(
                    SERVICE_ARGS,
                    CONNECTION,
                    true
            );

        } catch (Throwable ignored) {
        }

        reset(
                "DISCONNECTED",
                "UserService desconectado"
        );
    }

    public static boolean isConnected() {

        return connected
                && service != null
                && remoteUid == 2000;
    }

    public static int getRemoteUid() {
        return remoteUid;
    }

    public static int getRemotePid() {
        return remotePid;
    }

    public static String getState() {
        return state;
    }

    public static String getDetail() {
        return detail;
    }

    public static String identity() {

        try {

            if (!isConnected()) {
                return "UserService no conectado";
            }

            return service.getIdentity();

        } catch (Throwable e) {

            return safeError(e);
        }
    }

    public static String memoryInfo() {

        try {

            if (!isConnected()) {
                return "UserService no conectado";
            }

            return service.readMemInfo();

        } catch (Throwable e) {

            return safeError(e);
        }
    }

    public static String networkInfo() {

        try {

            if (!isConnected()) {
                return "UserService no conectado";
            }

            return service.readNetworkInfo();

        } catch (Throwable e) {

            return safeError(e);
        }
    }

    public static String summary() {

        return "Estado: "
                + state
                + "\nUID remoto: "
                + remoteUid
                + "\nPID remoto: "
                + remotePid
                + "\nDetalle: "
                + detail;
    }

    private static void reset(
            String newState,
            String newDetail) {

        service = null;
        connected = false;

        remoteUid = -1;
        remotePid = -1;

        state = newState;
        detail = newDetail;
    }

    private static String safeError(
            Throwable e) {

        if (e == null) {
            return "";
        }

        String message =
                e.getMessage();

        return e.getClass()
                .getSimpleName()
                + ": "
                + (
                message == null
                        ? ""
                        : message
        );
    }
}
