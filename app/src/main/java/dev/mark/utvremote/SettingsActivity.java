package dev.mark.utvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** TV settings and explicit, user-confirmed APK updates. */
public final class SettingsActivity extends Activity {
    private static final int PICK_APK = 40, ALLOW_INSTALL = 41;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private UpdateRepository repository = new UpdateRepository();
    private SharedPreferences prefs;
    private LinearLayout content;
    private TextView status;
    private ProgressBar progress;
    private Button quality, autoNext, delay, check, download, choose, source, autoCheck;
    private UpdateRepository.Release release;
    private File verifiedFile;
    private int verifiedCode;
    private boolean busy, resumed, destroyed, checkWhenResumed, downloadWhenResumed, installWhenResumed, installerOpened;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TvUi.fullscreen(this); prefs = getSharedPreferences("watch", MODE_PRIVATE);
        if (state != null) {
            String name = state.getString("verifiedUpdate", "");
            if (name.matches("pending-[0-9a-f-]{36}\\.apk")) {
                File file = new File(new File(getCacheDir(), "updates"), name);
                if (file.isFile()) { verifiedFile = file; verifiedCode = state.getInt("verifiedCode"); installerOpened = state.getBoolean("installerOpened"); }
            }
        }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(TvUi.BG);
        root.setPadding(dp(40), dp(20), dp(40), dp(12));
        TextView title = TvUi.text(this, "Настройки", 30, TvUi.TEXT); TvUi.bold(title); root.addView(title);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        quality = action("", () -> {
            int[] values = {0, 1080, 720, 480}; String[] names = {"Авто", "1080p", "720p", "480p"};
            int selected = 0; for (int i = 0; i < values.length; i++) if (values[i] == prefs.getInt("quality", 0)) selected = i;
            new AlertDialog.Builder(this).setTitle("Предпочитаемое качество")
                    .setSingleChoiceItems(names, selected, (d, i) -> { prefs.edit().putInt("quality", values[i]).apply(); updateLabels(); d.dismiss(); })
                    .setNegativeButton("Назад", null).show();
        });
        note("Если выбранного качества нет, используется ближайшее доступное. Во время просмотра: кнопка «Качество» или Menu на пульте.");
        autoNext = action("", () -> { prefs.edit().putBoolean("autoNext", !prefs.getBoolean("autoNext", false)).apply(); updateLabels(); });
        delay = action("", () -> {
            int[] seconds = {5, 10, 15, 30}; String[] names = {"5 секунд", "10 секунд", "15 секунд", "30 секунд"};
            new AlertDialog.Builder(this).setTitle("Пауза перед следующей серией").setItems(names, (d, i) -> {
                prefs.edit().putInt("nextDelay", seconds[i]).apply(); updateLabels(); }).show();
        });
        note("Обратный отсчёт можно отменить кнопкой или любым нажатием пульта. При выходе из просмотра автопереход отменяется.");
        try { note("Установлена версия " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); }
        catch (PackageManager.NameNotFoundException ignored) { }
        action("Диагностика загрузки", () -> NetworkDiagnostics.show(this));
        autoCheck = action("", () -> { prefs.edit().putBoolean("autoCheckUpdates", !prefs.getBoolean("autoCheckUpdates", true)).apply(); updateLabels(); });
        note("Проверяем новую версию на главном экране не чаще раза в сутки. Загрузка начинается по кнопке, установка — после подтверждения Android.");
        source = action("Источник обновлений", this::editSource);
        check = action("Проверить обновления", () -> checkUpdates(false));
        download = action("Скачать и установить", this::downloadUpdate); download.setVisibility(verifiedFile == null ? View.GONE : View.VISIBLE);
        choose = action("Установить обновление из APK", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive")
                    .addCategory(Intent.CATEGORY_OPENABLE), PICK_APK); }
            catch (ActivityNotFoundException e) { status.setText("На телевизоре нет выбора файлов. Открой APK через файловый менеджер."); }
        });
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000); progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        status = TvUi.text(this, "", 13, TvUi.MUTED); status.setMaxLines(3);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setPadding(dp(6), dp(7), dp(6), dp(5));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        action("Вернуться", this::finish);
        TextView footer = TvUi.text(this, "Стрелки — выбор    OK — изменить    Назад — выйти из настроек", 12, TvUi.MUTED);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(26))); setContentView(root);
        updateLabels(); quality.requestFocus();
        if (getIntent().getBooleanExtra("downloadUpdate", false)) {
            getIntent().removeExtra("downloadUpdate"); checkWhenResumed = true; check.requestFocus();
        }
    }
    private int dp(int value) { return TvUi.dp(this, value); }
    private Button action(String text, Runnable run) {
        Button b = TvUi.button(this, text); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); b.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44)); lp.topMargin = dp(8); content.addView(b, lp); return b;
    }
    private TextView note(String text) {
        TextView v = TvUi.text(this, text, 13, TvUi.MUTED); v.setPadding(dp(6), dp(7), dp(6), dp(5)); content.addView(v); return v;
    }
    private void updateLabels() {
        quality.setText("Предпочитаемое качество · " + VideoQuality.label(prefs.getInt("quality", 0)));
        autoNext.setText("Следующая серия · " + (prefs.getBoolean("autoNext", false) ? "автоматически" : "по кнопке"));
        delay.setText("Обратный отсчёт · " + prefs.getInt("nextDelay", 10) + " с");
        autoCheck.setText("Автопроверка обновлений · " + (prefs.getBoolean("autoCheckUpdates", true) ? "включена" : "выключена"));
        source.setText(prefs.getString("updateSource", "").trim().isEmpty() ? "Источник обновлений · GitHub" : "Изменить источник обновлений");
        download.setText(verifiedFile == null ? "Скачать и установить" : "Установить проверенный APK");
    }
    private void editSource() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString("updateSource", "")); input.setHint(UpdateRepository.source(prefs));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Адрес источника обновлений").setView(input)
                .setPositiveButton("Сохранить", null).setNegativeButton("Назад", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty() && !UpdateRepository.https(value)) { input.setError("Нужен HTTPS-адрес"); return; }
            prefs.edit().putString("updateSource", value).remove("lastUpdateAttempt").apply(); release = null; downloadWhenResumed = false;
            clearVerified(); download.setVisibility(View.GONE); status.setText(""); updateLabels(); dialog.dismiss();
        })); dialog.show();
    }
    private void checkUpdates(boolean downloadAfterCheck) {
        String url = UpdateRepository.source(prefs);
        runTask("Проверяем обновления…", () -> {
            UpdateRepository.Release found = repository.check(url); long current = code(installed());
            runOnUiThread(() -> {
                if (!alive()) return;
                release = found; clearVerified(); updateLabels();
                boolean newer = found.code > current; download.setVisibility(newer ? View.VISIBLE : View.GONE);
                status.setText(newer ? "Доступна версия " + found.name + "\n" + found.notes : "Новых версий нет. Установлена актуальная версия.");
                downloadWhenResumed = newer && downloadAfterCheck;
            });
        });
    }
    private File pending() throws IOException {
        File dir = new File(getCacheDir(), "updates"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось подготовить обновление.");
        // Each worker owns its file; a cancelled Activity cannot corrupt another download.
        return new File(dir, "pending-" + UUID.randomUUID() + ".apk");
    }
    private void downloadUpdate() {
        if (busy) return;
        if (verifiedFile != null) { recheckAndInstall(); return; }
        UpdateRepository.Release found = release; if (found == null) return;
        if (!resumed) { downloadWhenResumed = true; return; }
        runTask("Загружаем обновление…", () -> {
            File file = pending(); boolean ready = false;
            try {
                repository.download(found, file, this::showProgress);
                runOnUiThread(() -> { if (alive()) status.setText("Проверяем целостность и подпись APK…"); });
                validate(file, found.code); offerInstall(file, found.code); ready = true;
            } finally { if (!ready) file.delete(); }
        });
    }
    private void showProgress(long bytes, long total) {
        runOnUiThread(() -> {
            if (!alive() || !busy) return;
            progress.setIndeterminate(total <= 0);
            if (total > 0) progress.setProgress((int)Math.min(1000, bytes * 1000 / total));
            String size = String.format(Locale.getDefault(), "%.1f", bytes / 1048576.0);
            status.setText(total > 0 ? "Загружаем обновление · " + (bytes * 100 / total) + "% · " + size + " / "
                    + String.format(Locale.getDefault(), "%.1f", total / 1048576.0) + " МБ" : "Загружаем обновление · " + size + " МБ");
        });
    }
    private void offerInstall(File file, int expectedCode) {
        runOnUiThread(() -> {
            if (!alive()) { file.delete(); return; }
            clearVerified(); verifiedFile = file; verifiedCode = expectedCode; installerOpened = false; installWhenResumed = true;
            updateLabels(); download.setVisibility(View.VISIBLE); status.setText("Обновление проверено. Установка начнётся при возвращении в настройки.");
        });
    }
    private void clearVerified() {
        if (verifiedFile != null && !installerOpened) verifiedFile.delete();
        verifiedFile = null; verifiedCode = 0; installWhenResumed = false; installerOpened = false;
    }
    private PackageInfo installed() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
    }
    private long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    void validate(File file, int expectedCode) throws Exception {
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = getPackageManager().getPackageArchiveInfo(file.getPath(), flags), current = installed();
        if (candidate == null || !getPackageName().equals(candidate.packageName) || code(candidate) <= code(current)
                || (expectedCode > 0 && code(candidate) != expectedCode)) throw new IOException("Нужен APK более новой версии «Ю».");
        Signature[] oldSign = Build.VERSION.SDK_INT >= 28 && current.signingInfo != null ? current.signingInfo.getApkContentsSigners() : current.signatures;
        Signature[] newSign = Build.VERSION.SDK_INT >= 28 && candidate.signingInfo != null ? candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
        if (oldSign == null || oldSign.length == 0 || newSign == null || !new HashSet<>(Arrays.asList(oldSign)).equals(new HashSet<>(Arrays.asList(newSign))))
            throw new IOException("Подпись APK не совпадает с установленным приложением.");
    }
    private void recheckAndInstall() {
        File file = verifiedFile; int expectedCode = verifiedCode;
        if (file == null) return;
        runTask("Проверяем APK…", () -> { validate(file, expectedCode); runOnUiThread(() -> { if (alive()) installWhenResumed = true; }); });
    }
    private void installValidated() {
        if (!resumed || !alive()) { installWhenResumed = true; return; }
        try {
            File file = verifiedFile;
            if (file == null || !file.isFile()) throw new IOException("Проверенный APK не найден. Загрузи его повторно.");
            if (!getPackageManager().canRequestPackageInstalls()) {
                status.setText("Разреши установку обновлений для «Ю», затем вернись назад.");
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), ALLOW_INSTALL); return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", file);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); installerOpened = true;
            status.setText("Подтверди обновление в системном окне Android.");
        } catch (Exception e) { status.setText("Не удалось открыть установщик. Можно повторить установку или выбрать APK заново."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData(); runTask("Проверяем APK…", () -> {
                File file = pending(); boolean ready = false;
                try {
                    try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                        if (in == null) throw new IOException("Не удалось открыть файл."); UpdateRepository.copy(in, out, UpdateRepository.MAX_APK);
                    }
                    validate(file, 0); offerInstall(file, 0); ready = true;
                } finally { if (!ready) file.delete(); }
            });
        } else if (request == ALLOW_INSTALL) {
            if (getPackageManager().canRequestPackageInstalls()) recheckAndInstall();
            else status.setText("Установка не разрешена. Можно повторить установку и выдать разрешение.");
        }
    }
    private interface Task { void run() throws Exception; }
    private boolean alive() { return !destroyed && !isDestroyed() && !isFinishing(); }
    private void runTask(String text, Task task) {
        if (busy || !alive()) return; busy = true; status.setText(text); setBusy(true); progress.setIndeterminate(true); progress.setVisibility(View.VISIBLE);
        network.execute(() -> {
            try { task.run(); }
            catch (Exception ex) { runOnUiThread(() -> {
                if (alive()) { downloadWhenResumed = false; installWhenResumed = false;
                    status.setText(ex.getMessage() == null ? "Операция не выполнена. Можно повторить." : ex.getMessage()); }
            }); }
            finally { runOnUiThread(() -> { if (alive()) { busy = false; setBusy(false); progress.setVisibility(View.GONE); runDeferred(); } }); }
        });
    }
    private void setBusy(boolean value) {
        check.setEnabled(!value); choose.setEnabled(!value); download.setEnabled(!value); source.setEnabled(!value); autoCheck.setEnabled(!value);
    }
    private void runDeferred() {
        if (!resumed || busy || !alive()) return;
        if (checkWhenResumed) { checkWhenResumed = false; checkUpdates(true); }
        else if (downloadWhenResumed) { downloadWhenResumed = false; downloadUpdate(); }
        else if (installWhenResumed) { installWhenResumed = false; installValidated(); }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; TvUi.fullscreen(this); runDeferred(); }
    @Override protected void onPause() { resumed = false; super.onPause(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (verifiedFile != null) { state.putString("verifiedUpdate", verifiedFile.getName()); state.putInt("verifiedCode", verifiedCode); state.putBoolean("installerOpened", installerOpened); }
    }
    @Override protected void onDestroy() { destroyed = true; network.shutdownNow(); if (isFinishing()) clearVerified(); super.onDestroy(); }
}
