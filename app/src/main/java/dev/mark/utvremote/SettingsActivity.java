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
    private SharedPreferences prefs;
    private LinearLayout content;
    private TextView status;
    private Button quality, autoNext, delay, check, download, choose, source;
    private UpdateRepository.Release release;
    private boolean busy;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TvUi.fullscreen(this); prefs = getSharedPreferences("watch", MODE_PRIVATE);
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
        source = action("Источник обновлений", this::editSource);
        check = action("Проверить обновления", this::checkUpdates);
        download = action("Скачать и установить", this::downloadUpdate); download.setVisibility(View.GONE);
        choose = action("Установить обновление из APK", () -> {
            try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/vnd.android.package-archive")
                    .addCategory(Intent.CATEGORY_OPENABLE), PICK_APK); }
            catch (ActivityNotFoundException e) { status.setText("На телевизоре нет выбора файлов. Открой APK через файловый менеджер."); }
        });
        status = note("");
        action("Вернуться", this::finish);
        TextView footer = TvUi.text(this, "Стрелки — выбор    OK — изменить    Назад — выйти из настроек", 12, TvUi.MUTED);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(26))); setContentView(root);
        updateLabels(); quality.requestFocus();
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
        source.setText(prefs.getString("updateSource", "").isEmpty() ? "Источник обновлений · не указан" : "Изменить источник обновлений");
        if (prefs.getString("updateSource", "").isEmpty()) status.setText("Источник релизов пока не подключён. Можно выбрать новый APK с флешки или из загрузок.");
    }
    private void editSource() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(prefs.getString("updateSource", "")); input.setHint("https://…/release.json");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Адрес источника обновлений").setView(input)
                .setPositiveButton("Сохранить", null).setNegativeButton("Назад", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (!value.isEmpty() && !UpdateRepository.https(value)) { input.setError("Нужен HTTPS-адрес"); return; }
            prefs.edit().putString("updateSource", value).apply(); release = null; download.setVisibility(View.GONE);
            status.setText(""); updateLabels(); dialog.dismiss();
        })); dialog.show();
    }
    private void checkUpdates() {
        String url = prefs.getString("updateSource", "");
        if (url.isEmpty()) { status.setText("Сначала укажи адрес источника релизов или выбери APK."); return; }
        runTask("Проверяем обновления…", () -> {
            UpdateRepository.Release found = new UpdateRepository().check(url); long current = code(installed());
            runOnUiThread(() -> {
                if (isDestroyed()) return; release = found;
                boolean newer = found.code > current; download.setVisibility(newer ? View.VISIBLE : View.GONE);
                status.setText(newer ? "Доступна версия " + found.name + "\n" + found.notes : "Новых версий нет. Установлена актуальная версия.");
            });
        });
    }
    private File pending() throws IOException {
        File dir = new File(getCacheDir(), "updates"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось подготовить обновление.");
        return new File(dir, "pending.apk");
    }
    private void downloadUpdate() {
        UpdateRepository.Release found = release; if (found == null) return;
        runTask("Загружаем обновление…", () -> {
            File file = pending(); new UpdateRepository().download(found, file);
            validate(file, found.code); runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) installValidated(); });
        });
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
    private void installValidated() {
        try {
            if (!getPackageManager().canRequestPackageInstalls()) {
                status.setText("Разреши установку обновлений для «Ю», затем вернись назад.");
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), ALLOW_INSTALL); return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", pending());
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); status.setText("Подтверди обновление в системном окне Android.");
        } catch (Exception e) { status.setText("Не удалось открыть установщик. APK можно установить через файловый менеджер."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData(); runTask("Проверяем APK…", () -> {
                File file = pending();
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException("Не удалось открыть файл."); UpdateRepository.copy(in, out, UpdateRepository.MAX_APK);
                }
                validate(file, 0); runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) installValidated(); });
            });
        } else if (request == ALLOW_INSTALL) {
            if (getPackageManager().canRequestPackageInstalls()) runTask("Проверяем APK…", () -> {
                validate(pending(), 0); runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) installValidated(); });
            }); else status.setText("Установка не разрешена. Разрешение можно выдать при следующей попытке.");
        }
    }
    private interface Task { void run() throws Exception; }
    private void runTask(String text, Task task) {
        if (busy) return; busy = true; status.setText(text); setBusy(true);
        network.execute(() -> {
            try { task.run(); }
            catch (Exception ex) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(ex.getMessage() == null ? "Операция не выполнена. Можно повторить." : ex.getMessage()); }); }
            finally { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; setBusy(false); } }); }
        });
    }
    private void setBusy(boolean value) {
        check.setEnabled(!value); choose.setEnabled(!value); download.setEnabled(!value); source.setEnabled(!value);
    }
    @Override protected void onResume() { super.onResume(); TvUi.fullscreen(this); }
    @Override protected void onDestroy() { network.shutdownNow(); super.onDestroy(); }
}
