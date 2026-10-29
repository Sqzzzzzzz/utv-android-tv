package dev.mark.utvremote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

final class PosterLoader {
    private static final int MAX_IMAGE = 8 * 1024 * 1024;
    private static final long MAX_DISK = 64L * 1024 * 1024, MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private final ThreadPoolExecutor workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private volatile boolean closed;
    private volatile int batch;
    // Changing seasons cancels queued requests and prevents obsolete results reaching the UI.
    void newBatch() { ++batch; workers.getQueue().clear(); }
    void load(String url, String page, ImageView view) {
        String key = page + "|" + url;
        view.setTag(key);
        if (url.isEmpty() || closed) return;
        Bitmap existing = cache.get(key);
        if (existing != null) { view.setImageBitmap(existing); return; }
        int request = batch;
        File directory = new File(view.getContext().getCacheDir(), "original-posters");
        File disk = cacheFile(directory, key);
        workers.execute(() -> {
            if (stale(request)) return;
            Bitmap saved = readCache(disk);
            if (saved != null) { publish(key, saved, view, request); return; }
            try {
                Bitmap fallback = decode(download(url));
                if (fallback != null) main.post(() -> {
                    if (!stale(request) && key.equals(view.getTag())) view.setImageBitmap(fallback);
                });
            } catch (Exception ignored) { }
            // Queue upgrades after the initial thumbnails, so the catalogue stays responsive.
            if (!stale(request)) {
                try { workers.execute(() -> upgrade(page, key, disk, view, request)); }
                catch (java.util.concurrent.RejectedExecutionException ignored) { }
            }
        });
    }
    private void upgrade(String page, String key, File disk, ImageView view, int request) {
        if (stale(request)) return;
        try {
            String source = new CatalogRepository().resolvePoster(page);
            if (stale(request)) return;
            byte[] data = download(source);
            Bitmap original = decode(data);
            if (original == null || original.getWidth() < 960 || original.getHeight() < 540 || stale(request)) return;
            publish(key, original, view, request);
            writeCache(disk, data);
        } catch (Exception ignored) { /* Keep the thumbnail if the original is unavailable. */ }
    }
    private boolean stale(int request) { return closed || request != batch || Thread.currentThread().isInterrupted(); }
    private void publish(String key, Bitmap image, ImageView view, int request) {
        if (stale(request)) return;
        // Keep the detail needed by a TV card without retaining full posters for every episode.
        int width = Math.min(image.getWidth(), Math.max(640, view.getResources().getDisplayMetrics().widthPixels / 3));
        Bitmap display = width == image.getWidth() ? image : Bitmap.createScaledBitmap(image, width,
                Math.max(1, Math.round(image.getHeight() * width / (float) image.getWidth())), true);
        cache.put(key, display);
        main.post(() -> { if (!stale(request) && key.equals(view.getTag())) view.setImageBitmap(display); });
    }
    static boolean allowed(URL source) {
        return "https".equals(source.getProtocol()) && source.getUserInfo() == null
                && (source.getPort() == -1 || source.getPort() == 443)
                && ("img.u-tv.ru".equals(source.getHost()) || CatalogRepository.isOriginalPoster(source.toString()));
    }
    private byte[] download(String url) throws IOException {
        URL source = new URL(url);
        for (int redirects = 0; redirects < 4; redirects++) {
            if (!allowed(source)) throw new IOException("Unsupported poster address");
            HttpURLConnection connection = (HttpURLConnection) source.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", SiteHttp.USER_AGENT);
                connection.setRequestProperty("Referer", "https://www.u-tv.ru/");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing redirect");
                    source = new URL(source, location); continue;
                }
                if (status != 200 || connection.getContentLengthLong() > MAX_IMAGE) throw new IOException("Poster unavailable");
                try (InputStream stream = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                        if (bytes.size() + count > MAX_IMAGE) throw new IOException("Poster too large");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many redirects");
    }
    static Bitmap decode(byte[] data) {
        BitmapFactory.Options size = new BitmapFactory.Options(); size.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, size);
        if (size.outWidth <= 0 || size.outHeight <= 0 || (long)size.outWidth * size.outHeight > 32_000_000) return null;
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (size.outWidth / options.inSampleSize > 1920 || size.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
        return BitmapFactory.decodeByteArray(data, 0, data.length, options);
    }
    static File cacheFile(File directory, String key) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte b : hash) name.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return new File(directory, name + ".img");
        } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
    private Bitmap readCache(File file) {
        if (!file.isFile() || System.currentTimeMillis() - file.lastModified() > MAX_AGE || file.length() > MAX_IMAGE) return null;
        try { return decode(java.nio.file.Files.readAllBytes(file.toPath())); }
        catch (IOException ignored) { return null; }
    }
    private synchronized void writeCache(File file, byte[] data) {
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        File pending = new File(directory, file.getName() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(pending)) { stream.write(data); }
            if (!pending.renameTo(file)) pending.delete();
            File[] files = directory.listFiles((dir, name) -> name.endsWith(".img"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long total = 0; for (File item : files) total += item.length();
            for (File item : files) {
                long length = item.length();
                if (total > MAX_DISK && item.delete()) total -= length;
            }
        } catch (IOException ignored) { pending.delete(); }
    }
    void close() { closed = true; workers.shutdownNow(); main.removeCallbacksAndMessages(null); cache.evictAll(); }
}
