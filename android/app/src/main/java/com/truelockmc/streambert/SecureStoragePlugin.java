package com.truelockmc.streambert;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

// Small Keystore-backed key/value store. Ciphertext + IV live in private
// SharedPreferences; the AES key never leaves the Android Keystore.
@CapacitorPlugin(name = "SecureStorage")
public class SecureStoragePlugin extends Plugin {
    private static final String PREFS = "streambert_secure";
    private static final String KEY_ALIAS = "streambert_secure_key";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(
            new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        );
        return kg.generateKey();
    }

    @PluginMethod
    public void get(PluginCall call) {
        String name = call.getString("key");
        if (name == null) {
            call.resolve(new JSObject().put("value", null));
            return;
        }
        try {
            String stored = prefs().getString(name, null);
            if (stored == null) {
                call.resolve(new JSObject().put("value", null));
                return;
            }
            String[] parts = stored.split(":", 2);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP))
            );
            String value = new String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                "UTF-8"
            );
            call.resolve(new JSObject().put("value", value));
        } catch (Exception e) {
            call.resolve(new JSObject().put("value", null));
        }
    }

    @PluginMethod
    public void set(PluginCall call) {
        String name = call.getString("key");
        String value = call.getString("value");
        if (name == null) {
            call.reject("Missing key");
            return;
        }
        if (value == null || value.isEmpty()) {
            prefs().edit().remove(name).apply();
            call.resolve();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(value.getBytes("UTF-8"));
            String stored =
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) +
                ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            prefs().edit().putString(name, stored).apply();
            call.resolve();
        } catch (Exception e) {
            call.reject("Could not encrypt value", e);
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        String name = call.getString("key");
        if (name == null) {
            call.reject("Missing key");
            return;
        }
        prefs().edit().remove(name).apply();
        call.resolve();
    }
}
