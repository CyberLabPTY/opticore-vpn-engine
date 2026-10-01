package lab.opticore.vpn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        LocalPanelServer.start(context);

        Intent service = new Intent(
                context,
                EngineService.class);

        service.setAction(
                Prefs.autoReconnect(context)
                        ? EngineService.ACTION_CONNECT
                        : EngineService.ACTION_PANEL);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
