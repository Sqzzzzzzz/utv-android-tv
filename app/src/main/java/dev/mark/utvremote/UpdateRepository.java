package dev.mark.utvremote;

import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** HTTPS release manifest and bounded downloads; package/signature checks happen before install. */
final class UpdateRepository {
    static final int MAX_APK = 50 * 1024 * 1024;
    static final class Release {
        final int code;
        final String name, url, sha256, notes;
        Release(int code, String name, String url, String sha256, String notes) {
            this.code = code; this.name = name; this.url = url; this.sha256 = sha256; this.notes = notes;
        }
    }
    static boolean https(String value) {
        try {
            URI u = URI.create(value);
            return "https".equals(u.getScheme()) && u.getHost() != null && u.getUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443) && u.getFragment() == null;
        } catch (Exception e) { return false; }
    }
    static Release parse(String text) throws IOException {
        try {
            JSONObject json = new JSONObject(text);
            int code = json.getInt("versionCode"); String name = json.getString("versionName");
            String url = json.getString("apkUrl"), sha = json.getString("sha256").toLowerCase(Locale.ROOT);
            if (code <= 0 || name.trim().isEmpty() || name.length() > 80 || !https(url) || !sha.matches("[0-9a-f]{64}")) throw new IOException("Некорректные сведения об обновлении.");
            return new Release(code, name, url, sha, json.optString("notes").substring(0, Math.min(3000, json.optString("notes").length())));
        } catch (org.json.JSONException ex) { throw new IOException("Источник не выдал сведения об обновлении.", ex); }
    }
    private HttpURLConnection connect(String url) throws IOException {
        for (int redirects = 0; redirects < 6; redirects++) {
            if (!https(url)) throw new IOException("Источник обновления должен использовать HTTPS.");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "UTVRemote-update/0.2");
            int status;
            try { status = c.getResponseCode(); } catch (IOException ex) { c.disconnect(); throw ex; }
            if (status == 200) return c;
            String target = c.getHeaderField("Location"); c.disconnect();
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && target != null) {
                url = new URL(new URL(url), target).toString(); continue;
            }
            throw new IOException("Сервер обновлений недоступен (" + status + ").");
        }
        throw new IOException("Слишком много перенаправлений источника обновления.");
    }
    Release check(String url) throws IOException {
        HttpURLConnection c = connect(url);
        try (InputStream input = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(input, out, 65536); return parse(out.toString(StandardCharsets.UTF_8.name()));
        } finally { c.disconnect(); }
    }
    void download(Release release, File target) throws Exception {
        HttpURLConnection c = connect(release.url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) { copy(in, out, MAX_APK); }
        catch (Exception ex) { target.delete(); throw ex; }
        finally { c.disconnect(); }
        if (!digest(target).equals(release.sha256)) { target.delete(); throw new IOException("Файл обновления повреждён. Загрузи его повторно."); }
    }
    static void copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[16384]; long total = 0; int length;
        while ((length = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена.");
            total += length; if (total > limit) throw new IOException("Файл превышает допустимый размер.");
            out.write(buffer, 0, length);
        }
        if (total == 0) throw new IOException("Получен пустой файл.");
    }
    static String digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[16384]; int length;
            while ((length = in.read(bytes)) != -1) hash.update(bytes, 0, length);
        }
        StringBuilder result = new StringBuilder(); for (byte b : hash.digest()) result.append(String.format(Locale.ROOT, "%02x", b));
        return result.toString();
    }
}
