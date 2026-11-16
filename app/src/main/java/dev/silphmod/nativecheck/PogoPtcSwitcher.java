package dev.silphmod.nativecheck;

import android.net.Uri;
import android.util.Base64;
import dev.rootcatch.root.RootShell;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public final class PogoPtcSwitcher {
    private static final String POGO_PKG = "com.nianticlabs.pokemongo";
    private static final String PREFS_XML = "/data/user/0/" + POGO_PKG + "/shared_prefs/" + POGO_PKG + ".v2.playerprefs.xml";
    private static final String KEY_GAME_SECRET = "253aaf16-a9ee-4df9-b3ca-0b53a29510ab";
    private static final String KEY_PTC_TOKEN_BLOB = "6844ef50-2b2a-11ee-be56-0242ac120002";
    private static final String KEY_AUTH_PROVIDER = "60b01025-c1ea-422c-9b0e-d70bf489de7f";
    private static final String PTC_PROVIDER_TAG = "gu9809JRHwOgNjkOFdR5sg==";
    private static final String SECRETS_DIR = "/data/user/0/" + POGO_PKG + "/files/nia-client-secrets-manager";

    private PogoPtcSwitcher() {}

    public static void switchAndLaunch(PtcAccountVault.Account account) throws Exception {
        if (account == null || account.accessToken == null || account.accessToken.isEmpty() || account.refreshToken == null || account.refreshToken.isEmpty()) {
            throw new IllegalArgumentException("Account needs refreshed access and refresh tokens before switching");
        }

        try (RootShell root = new RootShell()) {
            RootShell.Result stopRes = root.execute("am force-stop " + POGO_PKG, 10_000);
            if (stopRes.code != 0) {
                throw new IllegalStateException("Failed to stop Pokemon GO: " + stopRes.output);
            }

            RootShell.Result readRes = root.execute(
                "test -f '" + PREFS_XML + "' && base64 '" + PREFS_XML + "' | tr -d '\\r\\n' || echo MISSING_PREFS",
                10_000
            );
            String b64 = readRes.output == null ? "" : readRes.output.trim();
            if (b64.isEmpty() || b64.contains("MISSING_PREFS")) {
                throw new IllegalStateException("Open Pokemon GO once before switching accounts (playerprefs.xml not found)");
            }

            byte[] originalXml = Base64.decode(b64, Base64.DEFAULT);
            Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(originalXml));
            Map<String, Element> entries = indexStringEntries(doc);

            byte[] aesKey = readExistingAesKey(entries.get(KEY_GAME_SECRET));
            String encodedAesKey = null;
            if (aesKey == null) {
                aesKey = new byte[32];
                new SecureRandom().nextBytes(aesKey);
                encodedAesKey = Uri.encode(Base64.encodeToString(aesKey, Base64.NO_WRAP));
                setStringEntry(doc, entries, KEY_GAME_SECRET, encodedAesKey);
            }

            byte[] proto = encodeTokenProtobuf(account.accessToken, account.refreshToken, account.expiresAt);
            String encryptedTokenBlob = encryptAesCbcUrlEncoded(aesKey, proto);

            setStringEntry(doc, entries, KEY_PTC_TOKEN_BLOB, encryptedTokenBlob);
            setStringEntry(doc, entries, KEY_AUTH_PROVIDER, PTC_PROVIDER_TAG);

            byte[] updatedXml = serializeXml(doc);
            verifyRoundtrip(updatedXml, encryptedTokenBlob, encodedAesKey);

            String updatedB64 = Base64.encodeToString(updatedXml, Base64.NO_WRAP);
            String script = "set -e; "
                + "DIR='/data/user/0/" + POGO_PKG + "/shared_prefs'; "
                + "OWNER=$(stat -c %u:%g /data/user/0/" + POGO_PKG + "); "
                + "CTX=$(ls -Zd \"$DIR\" | awk '{print $1}'); "
                + "TMP='" + PREFS_XML + ".tmp'; "
                + "printf '%s' '" + updatedB64 + "' | base64 -d > \"$TMP\"; "
                + "chown \"$OWNER\" \"$TMP\"; "
                + "chmod 660 \"$TMP\"; "
                + "chcon \"$CTX\" \"$TMP\" 2>/dev/null || restorecon \"$TMP\" 2>/dev/null || true; "
                + "mv \"$TMP\" '" + PREFS_XML + "'; "
                + "rm -f '" + PREFS_XML + ".bak' "
                + "'" + SECRETS_DIR + "/271858660' "
                + "'" + SECRETS_DIR + "/977556447' "
                + "'" + SECRETS_DIR + "/837661298' "
                + "'/data/user/0/" + POGO_PKG + "/shared_prefs/com.google.android.gms.signin.xml' "
                + "'/data/user/0/" + POGO_PKG + "/shared_prefs/com.google.android.gms.signin.xml.bak'; "
                + "ACT=$(cmd package resolve-activity --brief " + POGO_PKG + " 2>/dev/null | tail -n 1); "
                + "if [ -n \"$ACT\" ] && echo \"$ACT\" | grep -q '/'; then "
                + "  am start -n \"$ACT\" >/dev/null; "
                + "else "
                + "  monkey -p " + POGO_PKG + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; "
                + "fi; "
                + "echo SWITCHED_OK";

            RootShell.Result writeRes = root.execute(script, 15_000);
            if (writeRes.code != 0 || !writeRes.output.contains("SWITCHED_OK")) {
                throw new IllegalStateException("Root switch script failed: " + writeRes.output);
            }
        }
    }

    private static Map<String, Element> indexStringEntries(Document doc) {
        Map<String, Element> map = new HashMap<>();
        NodeList nodes = doc.getElementsByTagName("string");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element) {
                Element el = (Element) n;
                String name = el.getAttribute("name");
                if (name != null && !name.isEmpty()) map.put(name, el);
            }
        }
        return map;
    }

    private static void setStringEntry(Document doc, Map<String, Element> entries, String key, String value) {
        Element el = entries.get(key);
        if (el == null) {
            el = doc.createElement("string");
            el.setAttribute("name", key);
            doc.getDocumentElement().appendChild(el);
            entries.put(key, el);
        }
        el.setTextContent(value);
    }

    private static byte[] readExistingAesKey(Element el) {
        if (el == null) return null;
        try {
            String raw = el.getTextContent();
            if (raw == null || raw.trim().isEmpty()) return null;
            byte[] decoded = Base64.decode(Uri.decode(raw.trim()), Base64.DEFAULT);
            return decoded.length == 32 ? decoded : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static byte[] encodeTokenProtobuf(String accessToken, String refreshToken, long expiresAtMs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeProtoString(out, 1, accessToken);
        writeProtoString(out, 2, refreshToken);
        if (expiresAtMs > 0) {
            out.write((3 << 3) | 0);
            writeVarint(out, expiresAtMs);
        }
        return out.toByteArray();
    }

    private static void writeProtoString(ByteArrayOutputStream out, int fieldNumber, String val) throws Exception {
        if (val == null || val.isEmpty()) return;
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        out.write((fieldNumber << 3) | 2);
        writeVarint(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0L) {
            out.write(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static String encryptAesCbcUrlEncoded(byte[] aesKey, byte[] plaintext) throws Exception {
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] combined = new byte[16 + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, 16);
        System.arraycopy(encrypted, 0, combined, 16, encrypted.length);
        return Uri.encode(Base64.encodeToString(combined, Base64.NO_WRAP));
    }

    private static byte[] serializeXml(Document doc) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        tf.setOutputProperty(OutputKeys.INDENT, "no");
        tf.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private static void verifyRoundtrip(byte[] xmlBytes, String expectedBlob, String expectedKey) throws Exception {
        Document check = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(new ByteArrayInputStream(xmlBytes));
        Map<String, Element> map = indexStringEntries(check);
        Element blobEl = map.get(KEY_PTC_TOKEN_BLOB);
        Element provEl = map.get(KEY_AUTH_PROVIDER);
        if (blobEl == null || !expectedBlob.equals(blobEl.getTextContent())) {
            throw new IllegalStateException("XML roundtrip failed for token key");
        }
        if (provEl == null || !PTC_PROVIDER_TAG.equals(provEl.getTextContent())) {
            throw new IllegalStateException("XML roundtrip failed for provider key");
        }
        if (expectedKey != null) {
            Element keyEl = map.get(KEY_GAME_SECRET);
            if (keyEl == null || !expectedKey.equals(keyEl.getTextContent())) {
                throw new IllegalStateException("XML roundtrip failed for AES key");
            }
        }
    }
}
