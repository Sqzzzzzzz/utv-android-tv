package dev.mark.utvremote;

import org.jsoup.Jsoup;
import org.jsoup.HttpStatusException;
import org.jsoup.nodes.Document;
import java.io.*;
import java.net.*;
import java.util.zip.GZIPInputStream;

/** Fetch HTML through Android's native HTTPS stack; jsoup only parses the response. */
final class SiteHttp {
    private static final int MAX_HTML = 3 * 1024 * 1024;
    static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    static boolean allowed(URL url) {
        return "https".equals(url.getProtocol()) && ("www.u-tv.ru".equals(url.getHost()) || "u-tv.ru".equals(url.getHost()) || "cdn.media1.ru".equals(url.getHost()))
                && url.getUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443);
    }
    static Document fetch(URL original) throws IOException {
        URL url = original;
        for (int redirects = 0; redirects < 6; redirects++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена.");
            if (!allowed(url)) throw new IOException("Сайт перенаправил запрос на неподдерживаемый адрес.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            try {
                connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
                connection.setUseCaches(false); connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5");
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("Referer", "https://www.u-tv.ru/");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Сайт вернул перенаправление без адреса.");
                    url = new URL(url, location); continue;
                }
                if (status != 200) throw new HttpStatusException("Сервер вернул HTTP " + status, status, url.toString());
                try (InputStream raw = connection.getInputStream();
                     InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                     ByteArrayOutputStream body = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384]; int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Загрузка отменена.");
                        if (body.size() + length > MAX_HTML) throw new ResponseTooLargeException();
                        body.write(buffer, 0, length);
                    }
                    return Jsoup.parse(new ByteArrayInputStream(body.toByteArray()), null, original.toString());
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Сайт вернул слишком много перенаправлений.");
    }
    static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException() { super("Ответ сайта превышает допустимый размер."); }
    }
}
