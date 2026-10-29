package dev.mark.utvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** Checks only while the home screen is visible. No background installation or playback prompts. */
final class UpdatePrompt {
    static final long INTERVAL = 24 * 60 * 60 * 1000L;
    private final Activity activity;
    private final ExecutorService network;
    private final UpdateRepository repository;
    private final Runnable beforeDialog;
    private final SharedPreferences prefs;
    private Future<?> pending;
    private AlertDialog dialog;
    private boolean active, checking;
    private int generation;

    UpdatePrompt(Activity activity, ExecutorService network, UpdateRepository repository, Runnable beforeDialog) {
        this.activity = activity; this.network = network; this.repository = repository; this.beforeDialog = beforeDialog;
        prefs = activity.getSharedPreferences("watch", Activity.MODE_PRIVATE);
    }
    static boolean due(SharedPreferences prefs, long now) {
        long last = prefs.getLong("lastUpdateAttempt", 0);
        return prefs.getBoolean("autoCheckUpdates", true) && (last == 0 || now < last || now - last >= INTERVAL);
    }
    void resume(long now) {
        active = true;
        if (checking || dialog != null || !due(prefs, now)) return;
        prefs.edit().putLong("lastUpdateAttempt", now).apply();
        final String source = UpdateRepository.source(prefs);
        final int token = ++generation;
        checking = true;
        pending = network.submit(() -> {
            UpdateRepository.Release found = null;
            try { found = repository.check(source); } catch (Exception ignored) { /* Manual check reports errors. */ }
            final UpdateRepository.Release result = found;
            activity.runOnUiThread(() -> {
                if (token != generation) return;
                checking = false; pending = null;
                if (!active || activity.isFinishing() || activity.isDestroyed() || result == null
                        || !prefs.getBoolean("autoCheckUpdates", true) || !source.equals(UpdateRepository.source(prefs))) return;
                try {
                    PackageInfo installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                    long current = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
                    if (result.code <= current) return;
                } catch (Exception ignored) { return; }
                beforeDialog.run();
                dialog = new AlertDialog.Builder(activity).setTitle("Доступна версия " + result.name)
                        .setMessage(result.notes.isEmpty() ? "Скачать и установить обновление «Ю»?" : result.notes)
                        .setPositiveButton("Обновить", (d, which) -> activity.startActivity(new Intent(activity, SettingsActivity.class)
                                .putExtra("downloadUpdate", true)))
                        .setNegativeButton("Позже", null).create();
                dialog.setOnDismissListener(d -> dialog = null);
                dialog.show();
            });
        });
    }
    void pause() {
        active = false; generation++; checking = false;
        if (pending != null) { pending.cancel(true); pending = null; }
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }
}
