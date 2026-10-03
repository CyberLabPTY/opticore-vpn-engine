package lab.opticore.vpn;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class ShizukuControlActivity
        extends Activity {

    private TextView status;
    private TextView output;

    @Override
    protected void onCreate(
            Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        ShizukuEngine.init();

        buildUi();
        refreshState();
    }

    @Override
    protected void onResume() {

        super.onResume();

        ShizukuEngine.refresh();
        refreshState();
    }

    private void buildUi() {

        ScrollView scroll =
                new ScrollView(this);

        scroll.setBackgroundColor(
                Color.rgb(7,19,29)
        );

        LinearLayout root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        root.setPadding(
                dp(18),
                dp(22),
                dp(18),
                dp(28)
        );

        scroll.addView(root);

        TextView title =
                label(
                        "OptiCore Shizuku Engine",
                        23,
                        Color.WHITE
                );

        root.addView(title);

        TextView subtitle =
                label(
                        "Modo avanzado sin root · ADB shell UID 2000",
                        13,
                        Color.rgb(150,185,202)
                );

        subtitle.setPadding(
                0,
                dp(5),
                0,
                dp(15)
        );

        root.addView(subtitle);

        status =
                label(
                        "",
                        13,
                        Color.rgb(105,225,185)
                );

        status.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
        );

        status.setBackgroundColor(
                Color.rgb(10,28,41)
        );

        root.addView(status);

        Button refresh =
                button(
                        "Comprobar Shizuku"
                );

        refresh.setOnClickListener(v -> {

            ShizukuEngine.refresh();
            refreshState();
        });

        root.addView(refresh);

        Button permission =
                button(
                        "Autorizar OptiCore en Shizuku"
                );

        permission.setOnClickListener(v -> {

            String result =
                    ShizukuEngine
                            .requestPermission();

            Toast.makeText(
                    this,
                    result,
                    Toast.LENGTH_LONG
            ).show();

            status.postDelayed(
                    this::refreshState,
                    800L
            );
        });

        root.addView(permission);

        Button connect =
                button(
                        "Conectar motor shell"
                );

        connect.setOnClickListener(v -> {

            String result =
                    ShizukuUserServiceManager
                            .connect();

            Toast.makeText(
                    this,
                    result,
                    Toast.LENGTH_LONG
            ).show();

            status.postDelayed(
                    this::refreshState,
                    1200L
            );
        });

        root.addView(connect);

        Button identity =
                button(
                        "Verificar UID / PID"
                );

        identity.setOnClickListener(v -> {

            output.setText(
                    ShizukuUserServiceManager
                            .identity()
            );

            refreshState();
        });

        root.addView(identity);

        Button ram =
                button(
                        "Diagnóstico avanzado RAM"
                );

        ram.setOnClickListener(v -> {

            output.setText(
                    ShizukuUserServiceManager
                            .memoryInfo()
            );

            refreshState();
        });

        root.addView(ram);

        Button network =
                button(
                        "Diagnóstico avanzado de red"
                );

        network.setOnClickListener(v -> {

            output.setText(
                    ShizukuUserServiceManager
                            .networkInfo()
            );

            refreshState();
        });

        root.addView(network);

        Button disconnect =
                button(
                        "Desconectar motor shell"
                );

        disconnect.setOnClickListener(v -> {

            ShizukuUserServiceManager
                    .disconnect();

            refreshState();

            output.setText(
                    "UserService desconectado"
            );
        });

        root.addView(disconnect);

        TextView outputTitle =
                label(
                        "Resultado",
                        15,
                        Color.WHITE
                );

        outputTitle.setPadding(
                0,
                dp(20),
                0,
                dp(7)
        );

        root.addView(outputTitle);

        output =
                label(
                        "Sin diagnóstico ejecutado.",
                        12,
                        Color.rgb(180,200,210)
                );

        output.setTextIsSelectable(true);

        output.setPadding(
                dp(12),
                dp(12),
                dp(12),
                dp(12)
        );

        output.setBackgroundColor(
                Color.rgb(10,28,41)
        );

        root.addView(
                output,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                )
        );

        TextView warning =
                label(
                        "OptiCore solo habilita el motor avanzado cuando "
                                + "el servicio remoto reporta UID 2000. "
                                + "UID 0/root es rechazado.",
                        12,
                        Color.rgb(135,160,174)
                );

        warning.setPadding(
                0,
                dp(18),
                0,
                0
        );

        root.addView(warning);

        setContentView(scroll);
    }

    private void refreshState() {

        ShizukuEngine.refresh();

        String text =
                "=== SHIZUKU ===\n"
                        + ShizukuEngine.summary()
                        + "\n\n=== USERSERVICE ===\n"
                        + ShizukuUserServiceManager.summary();

        status.setText(text);
    }

    private Button button(
            String text) {

        Button button =
                new Button(this);

        button.setText(text);
        button.setAllCaps(false);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(50)
                );

        params.topMargin =
                dp(8);

        button.setLayoutParams(
                params
        );

        return button;
    }

    private TextView label(
            String text,
            int size,
            int color) {

        TextView view =
                new TextView(this);

        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);

        return view;
    }

    private int dp(
            int value) {

        return (int) (
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
                        + 0.5f
        );
    }
}
