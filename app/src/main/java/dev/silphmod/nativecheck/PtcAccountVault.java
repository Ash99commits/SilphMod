package dev.silphmod.nativecheck;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class PtcAccountVault {
    private static final String PREFS_NAME = "ptc-vault";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_INDEX = "active_index";
    private static final String KEY_AUTO_ROTATE = "auto_rotate";
    private static final String KEY_LAUNCH_WAIT_SEC = "launch_wait_sec";
    private static final String KEY_WEBHOOK_URL = "discord_webhook_url";

    private static final String PTC_CLIENT_ID = "pokemon-go";
    private static final String PTC_REDIRECT_URI = "https://www.pokemongolive.com/dl?app=pokemongo&dl_action=OPEN_LOGIN";
    private static final String PTC_TOKEN_URL = "https://access.pokemon.com/oauth2/token";
    private static final String PTC_ME_URL = "https://me.pokemon.com/me";
    private static final String PTC_AUTH_URL = "https://access.pokemon.com/oauth2/auth";
    private static final String PTC_SCOPE = "openid offline email dob pokemon_go member_id username";

    public static final class Account {
        public String id;
        public String username;
        public String displayName;
        public String refreshToken;
        public String accessToken;
        public long expiresAt;
        public long lastSwitchedAt;
        public boolean valid;

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                .put("id", id)
                .put("username", username)
                .put("displayName", displayName)
                .put("refreshToken", refreshToken)
                .put("accessToken", accessToken)
                .put("expiresAt", expiresAt)
                .put("lastSwitchedAt", lastSwitchedAt)
                .put("valid", valid);
        }

        public static Account fromJson(JSONObject obj) {
            Account a = new Account();
            a.id = obj.optString("id", "ptc-" + System.currentTimeMillis());
            a.username = obj.optString("username", "").trim();
            a.displayName = obj.optString("displayName", a.username).trim();
            JSONObject tokens = obj.optJSONObject("ptcTokens");
            if (tokens != null) {
                a.refreshToken = tokens.optString("refreshToken", obj.optString("refreshToken", "")).trim();
                a.accessToken = tokens.optString("accessToken", obj.optString("accessToken", "")).trim();
                a.expiresAt = tokens.optLong("expiresAt", obj.optLong("expiresAt", 0));
            } else {
                a.refreshToken = obj.optString("refreshToken", obj.optString("refresh_token", "")).trim();
                a.accessToken = obj.optString("accessToken", obj.optString("access_token", "")).trim();
                a.expiresAt = obj.optLong("expiresAt", 0);
            }
            a.lastSwitchedAt = obj.optLong("lastSwitchedAt", 0);
            a.valid = !a.refreshToken.isEmpty() && obj.optBoolean("valid", true);
            return a;
        }
    }

    public static final class PkceSession {
        public final String state;
        public final String verifier;
        public final String authUrl;

        PkceSession(String state, String verifier, String authUrl) {
            this.state = state;
            this.verifier = verifier;
            this.authUrl = authUrl;
        }
    }

    private final SharedPreferences prefs;

    public PtcAccountVault(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public synchronized List<Account> list() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                Account a = Account.fromJson(arr.getJSONObject(i));
                if (!a.refreshToken.isEmpty()) out.add(a);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized void saveList(List<Account> accounts) {
        JSONArray arr = new JSONArray();
        for (Account a : accounts) {
            try {
                arr.put(a.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply();
    }

    public synchronized int activeIndex() {
        List<Account> all = list();
        if (all.isEmpty()) return -1;
        int idx = prefs.getInt(KEY_INDEX, 0);
        return (idx >= 0 && idx < all.size()) ? idx : 0;
    }

    public synchronized void setActiveIndex(int index) {
        prefs.edit().putInt(KEY_INDEX, index).apply();
    }

    public synchronized Account activeAccount() {
        List<Account> all = list();
        if (all.isEmpty()) return null;
        int idx = activeIndex();
        return idx >= 0 && idx < all.size() ? all.get(idx) : all.get(0);
    }

    public synchronized Account advanceNextAccount() {
        List<Account> all = list();
        if (all.isEmpty()) return null;
        int cur = activeIndex();
        for (int step = 1; step <= all.size(); step++) {
            int candidateIdx = (cur + step) % all.size();
            Account candidate = all.get(candidateIdx);
            if (candidate.valid && !candidate.refreshToken.isEmpty()) {
                setActiveIndex(candidateIdx);
                return candidate;
            }
        }
        return null;
    }

    public boolean isAutoRotateEnabled() {
        return prefs.getBoolean(KEY_AUTO_ROTATE, false);
    }

    public void setAutoRotateEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_ROTATE, enabled).apply();
    }

    public int getLaunchWaitSeconds() {
        return Math.max(8, Math.min(60, prefs.getInt(KEY_LAUNCH_WAIT_SEC, 15)));
    }

    public void setLaunchWaitSeconds(int seconds) {
        prefs.edit().putInt(KEY_LAUNCH_WAIT_SEC, Math.max(8, Math.min(60, seconds))).apply();
    }

    public String getWebhookUrl() {
        return prefs.getString(KEY_WEBHOOK_URL, "").trim();
    }

    public void setWebhookUrl(String url) {
        prefs.edit().putString(KEY_WEBHOOK_URL, url == null ? "" : url.trim()).apply();
    }

    public int importText(String rawText) throws Exception {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return 0;

        List<Account> current = list();
        int added = 0;

        if (text.startsWith("{") || text.startsWith("[")) {
            JSONArray array;
            if (text.startsWith("{")) {
                JSONObject root = new JSONObject(text);
                array = root.optJSONArray("accounts");
                if (array == null) {
                    array = new JSONArray().put(root);
                }
            } else {
                array = new JSONArray(text);
            }
            for (int i = 0; i < array.length(); i++) {
                Account parsed = Account.fromJson(array.getJSONObject(i));
                if (parsed.refreshToken.isEmpty()) continue;
                upsert(current, parsed);
                added++;
            }
        } else {
            String[] lines = text.split("\\r?\\n");
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                String nick = "";
                String tok = line;
                int comma = line.indexOf(',');
                int colon = line.indexOf(':');
                int sep = comma > 0 ? comma : colon;
                if (sep > 0) {
                    nick = line.substring(0, sep).trim();
                    tok = line.substring(sep + 1).trim();
                }
                if (tok.length() < 12) continue;
                Account a = new Account();
                a.id = "ptc-" + System.currentTimeMillis() + "-" + added;
                a.username = nick;
                a.displayName = nick.isEmpty() ? ("PTC #" + (current.size() + 1)) : nick;
                a.refreshToken = tok;
                a.accessToken = "";
                a.expiresAt = 0;
                a.valid = true;
                upsert(current, a);
                added++;
            }
        }

        if (added > 0) saveList(current);
        return added;
    }

    private void upsert(List<Account> list, Account incoming) {
        for (int i = 0; i < list.size(); i++) {
            Account existing = list.get(i);
            boolean sameUser = !incoming.username.isEmpty()
                && existing.username.equalsIgnoreCase(incoming.username);
            boolean sameTok = existing.refreshToken.equals(incoming.refreshToken);
            if (sameUser || sameTok) {
                existing.refreshToken = incoming.refreshToken;
                if (!incoming.accessToken.isEmpty()) existing.accessToken = incoming.accessToken;
                if (incoming.expiresAt > 0) existing.expiresAt = incoming.expiresAt;
                if (!incoming.username.isEmpty()) existing.username = incoming.username;
                if (!incoming.displayName.isEmpty()) existing.displayName = incoming.displayName;
                existing.valid = true;
                return;
            }
        }
        list.add(incoming);
    }

    public static PkceSession createPkceSession() throws Exception {
        SecureRandom rng = new SecureRandom();
        byte[] stateBytes = new byte[24];
        byte[] verifierBytes = new byte[32];
        rng.nextBytes(stateBytes);
        rng.nextBytes(verifierBytes);
        String state = Base64.encodeToString(stateBytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        String verifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        String challenge = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

        String url = PTC_AUTH_URL
            + "?state=" + URLEncoder.encode(state, "UTF-8")
            + "&scope=" + URLEncoder.encode(PTC_SCOPE, "UTF-8")
            + "&redirect_uri=" + URLEncoder.encode(PTC_REDIRECT_URI, "UTF-8")
            + "&client_id=" + PTC_CLIENT_ID
            + "&response_type=code&prompt=login"
            + "&code_challenge=" + URLEncoder.encode(challenge, "UTF-8")
            + "&code_challenge_method=S256";
        return new PkceSession(state, verifier, url);
    }

    public synchronized Account exchangeAndSaveCode(String rawInput, String verifier) throws Exception {
        String code = extractCode(rawInput);
        if (code == null || code.isEmpty()) throw new IllegalArgumentException("Missing ?code= in callback URL");
        String body = "grant_type=authorization_code"
            + "&client_id=" + PTC_CLIENT_ID
            + "&code=" + URLEncoder.encode(code, "UTF-8")
            + "&code_verifier=" + URLEncoder.encode(verifier, "UTF-8")
            + "&redirect_uri=" + URLEncoder.encode(PTC_REDIRECT_URI, "UTF-8");
        Account acc = callTokenEndpoint(body, new Account());
        List<Account> all = list();
        upsert(all, acc);
        saveList(all);
        return acc;
    }

    public synchronized Account refreshAccount(Account target) throws Exception {
        if (target == null || target.refreshToken == null || target.refreshToken.isEmpty()) {
            throw new IllegalArgumentException("Account has no PTC refresh token");
        }
        String body = "grant_type=refresh_token"
            + "&client_id=" + PTC_CLIENT_ID
            + "&refresh_token=" + URLEncoder.encode(target.refreshToken, "UTF-8");
        try {
            Account refreshed = callTokenEndpoint(body, target);
            refreshed.lastSwitchedAt = System.currentTimeMillis();
            refreshed.valid = true;
            List<Account> all = list();
            upsert(all, refreshed);
            saveList(all);
            return refreshed;
        } catch (Exception err) {
            target.valid = false;
            List<Account> all = list();
            for (Account a : all) {
                if (a.id.equals(target.id)) a.valid = false;
            }
            saveList(all);
            throw err;
        }
    }

    private static String extractCode(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.contains("://") || trimmed.contains("code=")) {
            Uri uri = Uri.parse(trimmed);
            String c = uri.getQueryParameter("code");
            if (c != null && !c.trim().isEmpty()) return c.trim();
        }
        return trimmed.contains(" ") ? null : trimmed;
    }

    private static Account callTokenEndpoint(String formBody, Account base) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(PTC_TOKEN_URL).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int status = conn.getResponseCode();
        String respText = readStream(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();

        JSONObject json = new JSONObject(respText.isEmpty() ? "{}" : respText);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(json.optString("error_description", json.optString("error", "HTTP " + status)));
        }
        String access = json.optString("access_token", "").trim();
        String refresh = json.optString("refresh_token", base.refreshToken == null ? "" : base.refreshToken).trim();
        long expiresIn = Math.max(60L, json.optLong("expires_in", 3600L));
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new IllegalStateException("Incomplete token response from access.pokemon.com");
        }

        String username = fetchUsername(access);
        Account out = new Account();
        out.id = (base.id != null && !base.id.isEmpty()) ? base.id : ("ptc-" + System.currentTimeMillis());
        out.username = !username.isEmpty() ? username : (base.username == null ? "" : base.username);
        out.displayName = !out.username.isEmpty() ? out.username : (base.displayName == null ? "PTC Account" : base.displayName);
        out.accessToken = access;
        out.refreshToken = refresh;
        out.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        out.lastSwitchedAt = base.lastSwitchedAt;
        out.valid = true;
        return out;
    }

    private static String fetchUsername(String accessToken) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(PTC_ME_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            if (conn.getResponseCode() == 200) {
                JSONObject me = new JSONObject(readStream(conn.getInputStream()));
                conn.disconnect();
                return me.optString("username", "").trim();
            }
            conn.disconnect();
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[2048];
            int n;
            while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
