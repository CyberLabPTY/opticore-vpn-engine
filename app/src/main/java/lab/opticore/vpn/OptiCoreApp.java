package lab.opticore.vpn;

import android.app.Application;
import android.content.Intent;
import android.os.Build;

import com.wireguard.android.backend.GoBackend;

public final class OptiCoreApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        LocalStatusServer.start(this);
        LocalPanelServer.start(this);

        GoBackend.setAlwaysOnCallback(() -> {
            Intent i = new Intent(this, EngineService.class);
            i.setAction(EngineService.ACTION_ALWAYS_ON);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        });
    }
}
