package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class SecurePrefs {
    private final SharedPreferences prefs;
    private final CryptoManager crypto;

    SecurePrefs(Context context, CryptoManager crypto) {
        this.crypto = crypto;
        this.prefs = context.getSharedPreferences("mindai_secure", Context.MODE_PRIVATE);
    }

    public void saveOpenRouter(String apiKey, String baseUrl, String mode, String fixedModel,
                               boolean zdr, String actualModel, long validatedAt, String keyLabel, String keyExpiresAt) {
        prefs.edit()
                .putString("api_key", crypto.encrypt(apiKey))
                .putString("base_url", OpenRouterClient.API_BASE_URL)
                .putString("mode", mode)
                .putString("fixed_model", fixedModel == null ? "" : fixedModel)
                .putBoolean("zdr", zdr)
                .putString("actual_model", actualModel == null ? "" : actualModel)
                .putLong("validated_at", validatedAt)
                .putString("key_label", keyLabel == null ? "" : keyLabel)
                .putString("key_expires_at", keyExpiresAt == null ? "" : keyExpiresAt)
                .apply();
    }

    public String apiKey() { return crypto.decrypt(prefs.getString("api_key", "")); }
    public String baseUrl() { return OpenRouterClient.API_BASE_URL; }
    public String mode() { return prefs.getString("mode", "auto"); }
    public String fixedModel() { return prefs.getString("fixed_model", ""); }
    public boolean zdr() { return prefs.getBoolean("zdr", false); }
    public String actualModel() { return prefs.getString("actual_model", ""); }
    public long validatedAt() { return prefs.getLong("validated_at", 0); }
    public String keyLabel() { return prefs.getString("key_label", ""); }
    public String keyExpiresAt() { return prefs.getString("key_expires_at", ""); }
    /** Fast status check for UI rendering; the encrypted key does not need to be decrypted here. */
    public boolean configured() {
        String encrypted = prefs.getString("api_key", "");
        return encrypted != null && !encrypted.isEmpty() && validatedAt() > 0;
    }
    public void updateActualModel(String model) {
        SharedPreferences.Editor editor = prefs.edit().putString("actual_model", model == null ? "" : model);
        if (model != null && model.trim().endsWith(":free")) editor.putString("fixed_model", model.trim());
        editor.apply();
    }
    public void clearOpenRouter() { prefs.edit().clear().apply(); }
}
