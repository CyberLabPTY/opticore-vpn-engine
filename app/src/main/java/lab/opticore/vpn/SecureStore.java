package lab.opticore.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String PREFS = "opticore_secure";
    private static final String KEY_ALIAS = "opticore_wireguard_key";
    private static final String IV = "config_iv";
    private static final String DATA = "config_data";

    private final Context context;

    public SecureStore(Context context) {
        this.context = context.getApplicationContext();
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);

        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry)
                    ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");

        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT |
                KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());

        return generator.generateKey();
    }

    public void saveConfig(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());

        byte[] encrypted = cipher.doFinal(
                value.getBytes(StandardCharsets.UTF_8));

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(IV, Base64.encodeToString(
                        cipher.getIV(), Base64.NO_WRAP))
                .putString(DATA, Base64.encodeToString(
                        encrypted, Base64.NO_WRAP))
                .apply();
    }

    public String loadConfig() throws Exception {
        SharedPreferences p = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        String iv64 = p.getString(IV, null);
        String data64 = p.getString(DATA, null);

        if (iv64 == null || data64 == null) {
            return "";
        }

        byte[] iv = Base64.decode(iv64, Base64.NO_WRAP);
        byte[] data = Base64.decode(data64, Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(128, iv));

        return new String(
                cipher.doFinal(data),
                StandardCharsets.UTF_8);
    }

    public boolean hasConfig() {
        return context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE)
                .contains(DATA);
    }
}
