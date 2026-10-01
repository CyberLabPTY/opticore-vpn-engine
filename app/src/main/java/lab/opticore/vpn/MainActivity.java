package lab.opticore.vpn;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {

    private static final int VPN_REQUEST = 41;
    private static final int STORAGE_REQUEST = 42;
    private SecureStore secureStore;
    private EditText config;
    private TextView status;
    private TextView connectionInfo;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Mantiene el host local en primer plano incluso con WireGuard apagado.
        startEngine(EngineService.ACTION_PANEL);

        secureStore = new SecureStore(this);
        buildUi();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Dashboard.refresh(this, secureStore, connectionInfo);
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(7,19,29));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(22),dp(18),dp(28));
        scroll.addView(root);

        TextView title = label("OptiCore VPN Engine",24,Color.WHITE);
        root.addView(title);

        TextView subtitle = label(
                "WireGuard · IP Masking · Protección Android",
                14,Color.rgb(150,185,202));
        subtitle.setPadding(0,dp(4),0,dp(16));
        root.addView(subtitle);

        status = label("",14,Color.rgb(100,225,185));
        status.setPadding(0,0,0,dp(12));
        root.addView(status);
        connectionInfo = label("Estado de red: comprobando...", 13, Color.rgb(150, 185, 202));
        connectionInfo.setPadding(dp(12), dp(10), dp(12), dp(10));
        connectionInfo.setBackgroundColor(Color.rgb(10, 28, 41));
        root.addView(connectionInfo);

        Button authorize = button("Autorizar VPN en Android");
        authorize.setOnClickListener(v -> authorizeVpn());
        root.addView(authorize);

        TextView configTitle = label(
                "Configuración WireGuard",
                14,Color.WHITE);
        configTitle.setPadding(0,dp(18),0,dp(6));
        root.addView(configTitle);

        config = new EditText(this);
        config.setTextColor(Color.WHITE);
        config.setHintTextColor(Color.rgb(105,135,150));
        config.setBackgroundColor(Color.rgb(11,31,45));
        config.setPadding(dp(12),dp(12),dp(12),dp(12));
        config.setGravity(Gravity.TOP);
        config.setMinLines(9);
        config.setTextSize(13);
        config.setInputType(
                InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        config.setHint(
                "[Interface]\nPrivateKey = ...\nAddress = ...\n\n" +
                "[Peer]\nPublicKey = ...\nEndpoint = ...");

        root.addView(config,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        try {
            String saved = secureStore.loadConfig();
            if (!saved.isEmpty()) config.setText(saved);
        } catch (Exception ignored) {}

        Button generate = button("Generar identidad WireGuard");
        generate.setOnClickListener(v -> generateIdentity());
        root.addView(generate);

        Button save = button("Guardar configuración cifrada");
        save.setOnClickListener(v -> saveConfig());
        root.addView(save);

        Button connect = button("Conectar protección");
        connect.setOnClickListener(v -> {
            startEngine(EngineService.ACTION_CONNECT);
            status.postDelayed(() -> { refreshStatus(); Dashboard.refresh(this, secureStore, connectionInfo); }, 1800);
            Toast.makeText(this,
                    "Solicitud de conexión enviada",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(connect);

        Button disconnect = button("Desconectar VPN");
        disconnect.setOnClickListener(v -> {
            startEngine(EngineService.ACTION_DISCONNECT);
            status.postDelayed(() -> { refreshStatus(); Dashboard.refresh(this, secureStore, connectionInfo); }, 700);
            Toast.makeText(this,
                    "Solicitud de desconexión enviada",
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(disconnect);
        Button refresh = button("Actualizar estado / IP");
        refresh.setOnClickListener(v -> { refreshStatus(); Dashboard.refresh(this, secureStore, connectionInfo); });
        root.addView(refresh);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            Button storage = button("Autorizar análisis de caché");
            storage.setOnClickListener(v -> requestStorageAccess());
            root.addView(storage);
        }

        Button usage = button("Autorizar acceso de uso");
        usage.setOnClickListener(v -> openUsageAccess());
        root.addView(usage);

        Button panel = button("Abrir panel OptiCore");
        panel.setOnClickListener(v -> openPanel());
        root.addView(panel);

        Switch auto = new Switch(this);
        auto.setText("Reconectar automáticamente");
        auto.setTextColor(Color.WHITE);
        auto.setChecked(Prefs.autoReconnect(this));
        auto.setPadding(0,dp(12),0,dp(6));
        auto.setOnCheckedChangeListener((b,value) ->
                Prefs.setAutoReconnect(this,value));
        root.addView(auto);

        Button settings = button("Abrir ajustes VPN / Always-on");
        settings.setOnClickListener(v -> openVpnSettings());
        root.addView(settings);

        TextView note = label(
                "La configuración queda cifrada con Android Keystore. " +
                "No compartas tu PrivateKey.",
                12,Color.rgb(135,160,174));
        note.setPadding(0,dp(16),0,0);
        root.addView(note);

        setContentView(scroll);
    }

    private void authorizeVpn() {
        Intent request = VpnService.prepare(this);
        if (request == null) {
            Toast.makeText(this,"VPN ya autorizada",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        } else {
            startActivityForResult(request,VPN_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode == VPN_REQUEST) {
            Toast.makeText(this,
                    resultCode == RESULT_OK
                            ? "VPN autorizada"
                            : "Autorización cancelada",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        }
    }

    private void generateIdentity() {
        try {
            KeyPair keyPair = new KeyPair();
            String privateKey = keyPair.getPrivateKey().toBase64();
            String publicKey = keyPair.getPublicKey().toBase64();

            String template =
                    "# ClientPublicKey = " + publicKey + "\n" +
                    "[Interface]\n" +
                    "PrivateKey = " + privateKey + "\n" +
                    "Address = 10.66.66.2/32\n" +
                    "DNS = 1.1.1.1\n\n" +
                    "[Peer]\n" +
                    "PublicKey = REEMPLAZAR_CLAVE_PUBLICA_SERVIDOR\n" +
                    "AllowedIPs = 0.0.0.0/0\n" +
                    "Endpoint = REEMPLAZAR_IP_SERVIDOR:51820\n" +
                    "PersistentKeepalive = 25";

            config.setText(template);

            Toast.makeText(this,
                    "Identidad WireGuard creada localmente",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudo generar la identidad WireGuard",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void saveConfig() {
        String value = config.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this,
                    "No hay configuración para guardar",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            Config.parse(new ByteArrayInputStream(
                    value.getBytes(StandardCharsets.UTF_8)));
            secureStore.saveConfig(value);
            Toast.makeText(this,
                    "Configuración válida y cifrada",
                    Toast.LENGTH_SHORT).show();
            refreshStatus();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Configuración WireGuard inválida",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startEngine(String action) {
        Intent i = new Intent(this,EngineService.class);
        i.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            Toast.makeText(
                    this,
                    "Android protege /Android/data en esta versión",
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean read = checkSelfPermission(
                Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        boolean write = checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;

        if (read && write) {
            Toast.makeText(
                    this,
                    "Acceso de caché ya autorizado",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        requestPermissions(
                new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                STORAGE_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults);

        if (requestCode == STORAGE_REQUEST) {
            boolean granted =
                    checkSelfPermission(
                            Manifest.permission.READ_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            == PackageManager.PERMISSION_GRANTED;

            Toast.makeText(
                    this,
                    granted
                            ? "Análisis de caché autorizado"
                            : "Permiso de caché no concedido",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openUsageAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudieron abrir los ajustes de acceso de uso",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openPanel() {
        try {
            startActivity(
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("http://127.0.0.1:8766/")));
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo abrir el panel OptiCore",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudieron abrir los ajustes VPN",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshStatus() {
        boolean permission = VpnService.prepare(this) == null;
        boolean configured = secureStore.hasConfig();

        String text =
                "Permiso VPN: " +
                (permission ? "AUTORIZADO" : "PENDIENTE") +
                "\nConfiguración: " +
                (configured ? "GUARDADA" : "PENDIENTE") +
                "\nEstado: " +
                (EngineService.isConnected()
                        ? "PROTECCIÓN ACTIVA"
                        : "DESCONECTADA");

        String error = EngineService.getLastError();
        if (error != null && !error.isEmpty()) {
            text += "\nMotor: " + error;
        }

        status.setText(text);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams p =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,dp(50));
        p.topMargin = dp(8);
        b.setLayoutParams(p);
        return b;
    }

    private TextView label(String text,int size,int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        return v;
    }

    private int dp(int value) {
        return (int)(value *
                getResources().getDisplayMetrics().density + 0.5f);
    }
}
