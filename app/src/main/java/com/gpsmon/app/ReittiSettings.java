package com.gpsmon.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class ReittiSettings {
    static final String DEFAULT_URL = "";
    private static final String KEY = "GPSmon.reitti.v1";
    private ReittiSettings() {}
    static final class Config {
        final String endpoint, token, target;
        final boolean enabled;
        Config(String endpoint, String token, String target, boolean enabled) throws IOException {
            try {
                String normalized = endpoint.trim();
                if (normalized.isEmpty()) {
                    if (enabled) throw new IOException("Enter your Reitti ingestion URL before enabling uploads.");
                } else {
                    URI uri = new URI(normalized);
                    if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                            || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                            || uri.getRawQuery() != null || uri.getPort() == 0 || uri.getPort() > 65535
                            || uri.getPath().isEmpty()) throw new IOException("Enter the full HTTP or HTTPS ingestion URL without credentials or a query string.");
                    normalized = uri.toASCIIString();
                }
                if (!token.matches("[!-~]*"))
                    throw new IOException("The token must contain visible ASCII characters without spaces or line breaks.");
                if (enabled && token.isBlank()) throw new IOException("Enter your Reitti integration token.");
                this.endpoint = normalized;
                this.token = token;
                this.target = target;
                this.enabled = enabled;
            } catch (java.net.URISyntaxException error) { throw new IOException("Enter a valid ingestion URL."); }
        }
    }
    static SharedPreferences preferences(Context context) { return context.getSharedPreferences("reitti", Context.MODE_PRIVATE); }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    static String target(Context context) { return preferences(context).getString("target", ""); }
    static Config load(Context context) throws IOException {
        SharedPreferences p = preferences(context);
        return new Config(p.getString("endpoint", DEFAULT_URL), decrypt(p.getString("token", "")), target(context), enabled(context));
    }
    static void save(Context context, String endpoint, String replacementToken, boolean enabled) throws IOException {
        Config old;
        try { old = load(context); }
        catch (IOException error) {
            if (replacementToken.isEmpty() && enabled) throw error;
            old = new Config(DEFAULT_URL, "", "", false);
        }
        String token = replacementToken.isEmpty() ? old.token : replacementToken;
        Config validated = new Config(endpoint, token, old.target, enabled);
        boolean sameDestination = old.endpoint.equals(validated.endpoint) && old.token.equals(validated.token) && !old.target.isEmpty();
        String target = sameDestination ? old.target : UUID.randomUUID().toString();
        if (!preferences(context).edit().putString("endpoint", validated.endpoint).putString("token", encrypt(token))
                .putString("target", target).putBoolean("enabled", enabled).putString("problem", "").commit()) throw new IOException("Connection settings could not be saved.");
    }
    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static String encrypt(String token) throws IOException {
        if (token.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (GeneralSecurityException error) { throw new IOException("The token could not be stored securely."); }
    }
    private static String decrypt(String encrypted) throws IOException {
        if (encrypted.isEmpty()) return "";
        try {
            String[] parts = encrypted.split(":", -1);
            if (parts.length != 2) throw new IOException("Re-enter your Reitti token.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) { throw new IOException("Re-enter your Reitti token."); }
    }
}
