package com.mindai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;

@RunWith(AndroidJUnit4.class)
public final class CryptoMigrationInstrumentedTest {
    @Test public void testEnvelopeEncryptionAndLegacyMigration() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CryptoManager crypto = new CryptoManager(context);
        String v2 = crypto.encrypt("新的快速加密数据");
        assertTrue(v2.startsWith("v2:"));
        assertEquals("新的快速加密数据", new CryptoManager(context).decrypt(v2));

        String legacy = legacyEncrypt(crypto, "旧版数据仍可读取");
        assertEquals("旧版数据仍可读取", crypto.decrypt(legacy));

        AppDatabase db = MindAiApp.get().db();
        db.clearEverything();
        String id = db.addManualTodo("临时标题", "说明", 0, "medium");
        ContentValues values = new ContentValues();
        values.put("title", legacy);
        db.getWritableDatabase().update("todos", values, "id=?", new String[]{id});
        assertEquals("旧版数据仍可读取", db.todo(id).title);
        assertTrue(db.migrateLegacyEncryption(20) >= 1);
        try (Cursor c = db.getReadableDatabase().query("todos", new String[]{"title"}, "id=?",
                new String[]{id}, null, null, null)) {
            assertTrue(c.moveToFirst());
            assertTrue(c.getString(0).startsWith("v2:"));
        }
        assertEquals("旧版数据仍可读取", db.todo(id).title);
    }

    private static String legacyEncrypt(CryptoManager crypto, String plain) throws Exception {
        Method method = CryptoManager.class.getDeclaredMethod("masterKey");
        method.setAccessible(true);
        SecretKey key = (SecretKey) method.invoke(crypto);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] joined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, joined, 0, iv.length);
        System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
        return Base64.encodeToString(joined, Base64.NO_WRAP);
    }
}
