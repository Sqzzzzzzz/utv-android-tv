package dev.mark.utvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import org.jsoup.HttpStatusException;
import java.net.*;
import javax.net.ssl.SSLException;

final class NetworkDiagnostics {
    static String code(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof HttpStatusException) return "HTTP_" + ((HttpStatusException) e).getStatusCode();
            if (e instanceof SSLException) return "HTTPS_TLS";
            if (e instanceof UnknownHostException) return "DNS";
            if (e instanceof SocketTimeoutException) return "TIMEOUT";
            if (e instanceof ConnectException) return "CONNECT";
            if (e instanceof SiteHttp.ResponseTooLargeException) return "RESPONSE_SIZE";
            if (e instanceof CatalogRepository.CatalogException) return "CATALOG_FORMAT";
            if (e instanceof org.jsoup.select.Selector.SelectorParseException) return "CATALOG_PARSER";
        }
        return "LOAD_ERROR";
    }
    static String summary(Throwable error) {
        String code = code(error);
        if (code.equals("HTTPS_TLS")) return "Не удалось установить защищённое соединение с сайтом. Подробности — в настройках.";
        if (code.equals("DNS")) return "Телевизор не смог определить адрес сайта. Подробности — в настройках.";
        if (code.equals("TIMEOUT")) return "Сайт не ответил вовремя. Подробности — в настройках.";
        if (code.equals("HTTP_403") || code.equals("HTTP_451")) return "Сайт ограничил доступ к запросу. Подробности — в настройках.";
        if (code.startsWith("HTTP_5")) return "Ошибка сервера сайта. Можно повторить позже.";
        if (code.startsWith("CATALOG_")) return "Сайт ответил, но список выпусков не удалось разобрать. Подробности — в настройках.";
        return "Не удалось получить данные сайта. Подробности — в настройках.";
    }
    static void save(Context context, String stage, Throwable error) {
        String version = "";
        try { version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (Exception ignored) { }
        StringBuilder details = new StringBuilder(stage).append("\nКод: ").append(code(error))
                .append("\nПриложение: ").append(version).append("\nAndroid: ").append(Build.VERSION.RELEASE)
                .append(" / API ").append(Build.VERSION.SDK_INT).append("\nВремя ТВ: ")
                .append(new java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(new java.util.Date())).append("\n");
        int depth = 0;
        for (Throwable e = error; e != null && depth++ < 4; e = e.getCause()) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            details.append(e.getClass().getSimpleName()).append(": ").append(message.substring(0, Math.min(220, message.length()))).append("\n");
        }
        context.getSharedPreferences("watch", Context.MODE_PRIVATE).edit().putString("lastNetworkError", details.toString()).apply();
    }
    static void show(Activity activity) {
        String details = activity.getSharedPreferences("watch", Context.MODE_PRIVATE).getString("lastNetworkError", "Ошибок загрузки пока не записано.");
        new AlertDialog.Builder(activity).setTitle("Последняя ошибка загрузки").setMessage(details).setPositiveButton("Закрыть", null).show();
    }
}
