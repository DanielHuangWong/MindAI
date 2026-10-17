package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class CryptoManager {
    private static final String ALIAS = "mindai_local_aes_v1";
    private static final String V2_PREFIX = "v2:";
    private static final String WRAPPED_DATA_KEY = "wrapped_data_key_v2";
    private final SharedPreferences keyPrefs;
    private volatile SecretKey cachedMasterKey;
    private volatile SecretKey cachedDataKey;

    CryptoManager(Context context) {
        keyPrefs = context.getApplicationContext().getSharedPreferences("mindai_crypto", Context.MODE_PRIVATE);
    }

    private SecretKey masterKey() throws Exception {
        SecretKey key = cachedMasterKey;
        if (key != null) return key;
        synchronized (this) {
            key = cachedMasterKey;
            if (key == null) {
                key = loadOrCreateKey();
                cachedMasterKey = key;
            }
        }
        return key;
    }

    private SecretKey dataKey() throws Exception {
        SecretKey key = cachedDataKey;
        if (key != null) return key;
        synchronized (this) {
            key = cachedDataKey;
            if (key != null) return key;
            String wrapped = keyPrefs.getString(WRAPPED_DATA_KEY, "");
            byte[] raw;
            if (wrapped == null || wrapped.isEmpty()) {
                raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                String encoded = encode(encryptBytes(masterKey(), raw));
                if (!keyPrefs.edit().putString(WRAPPED_DATA_KEY, encoded).commit()) {
                    throw new IllegalStateException("无法保存本地数据密钥");
                }
            } else {
                raw = decryptBytes(masterKey(), decode(wrapped));
                if (raw.length != 32) throw new IllegalStateException("本地数据密钥格式异常");
            }
            cachedDataKey = key = new SecretKeySpec(raw, "AES");
        }
        return key;
    }

    private SecretKey loadOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        KeyStore.Entry entry = store.getEntry(ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            return V2_PREFIX + encode(encryptBytes(dataKey(), plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法安全保存本地数据", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            boolean v2 = encoded.startsWith(V2_PREFIX);
            String payload = v2 ? encoded.substring(V2_PREFIX.length()) : encoded;
            SecretKey key = v2 ? dataKey() : masterKey();
            return new String(decryptBytes(key, decode(payload)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[本地加密数据暂时无法读取]";
        }
    }

    private static byte[] encryptBytes(SecretKey key, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain);
        byte[] joined = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(data, 0, joined, iv.length, data.length);
        return joined;
    }

    private static byte[] decryptBytes(SecretKey key, byte[] joined) throws Exception {
        if (joined.length <= 12) throw new IllegalArgumentException("密文格式异常");
        byte[] iv = new byte[12];
        byte[] data = new byte[joined.length - iv.length];
        System.arraycopy(joined, 0, iv, 0, iv.length);
        System.arraycopy(joined, iv.length, data, 0, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(data);
    }

    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private static byte[] decode(String value) { return Base64.decode(value, Base64.NO_WRAP); }
}
