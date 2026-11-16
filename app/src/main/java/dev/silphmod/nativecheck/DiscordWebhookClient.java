package dev.silphmod.nativecheck;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class DiscordWebhookClient {
    private DiscordWebhookClient() {}

    public static void postAsync(String webhookUrl, String title, String description, int colorHex) {
        if (webhookUrl == null || !webhookUrl.trim().startsWith("https://discord.com/api/webhooks/")) {
            return;
        }
        final String target = webhookUrl.trim();
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject embed = new JSONObject()
                    .put("title", title)
                    .put("description", description)
                    .put("color", colorHex);
                JSONObject payload = new JSONObject()
                    .put("username", "SilphMod AutoRaid")
                    .put("embeds", new JSONArray().put(embed));

                conn = (HttpURLConnection) new URL(target).openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
                conn.getResponseCode();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "silph-discord-webhook").start();
    }
}
