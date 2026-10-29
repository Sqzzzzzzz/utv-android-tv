package dev.mark.utvremote;

import android.content.*;
import android.content.pm.*;
import android.os.Looper;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=31, qualifiers="w960dp-h540dp-land-mdpi")
public final class SettingsUpdatesTest {
    private ActivityController<SettingsActivity> controller;
    private SettingsActivity activity;
    private QueueExecutor queue;
    private FakeRepository repository;
    private SharedPreferences prefs;
    @Before public void setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("watch", 0); prefs.edit().clear().commit();
    }
    private void launch(boolean incomingUpdate) throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), SettingsActivity.class).putExtra("downloadUpdate", incomingUpdate);
        controller = Robolectric.buildActivity(SettingsActivity.class, intent); activity = controller.get();
        queue = new QueueExecutor(); repository = new FakeRepository();
        ReflectionHelpers.setField(activity, "network", queue); ReflectionHelpers.setField(activity, "repository", repository);
        controller.setup().visible();
        PackageManager pm = activity.getPackageManager();
        PackageInfo installed = pm.getPackageInfo(activity.getPackageName(), 0);
        installed.versionCode = 18; installed.signingInfo = null; installed.signatures = new Signature[]{new Signature("1234")};
        Shadows.shadowOf(pm).installPackage(installed); Shadows.shadowOf(pm).setCanRequestPackageInstalls(true);
    }
    @After public void cleanup() {
        if (controller != null) { activity.finish(); if (ReflectionHelpers.<Boolean>getField(activity, "resumed")) controller.pause(); controller.stop().destroy(); }
    }
    @Test public void incomingUpdateWaitsForForegroundBeforeDownloadAndInstaller() throws Exception {
        launch(true); assertEquals(1, queue.tasks.size()); assertFalse(activity.getIntent().hasExtra("downloadUpdate"));
        controller.pause(); queue.complete(); assertEquals(0, repository.downloads); assertNoInstaller();
        controller.resume(); assertEquals(1, queue.tasks.size()); controller.pause(); queue.complete();
        assertEquals(1, repository.downloads); assertNoInstaller();
        File apk = ReflectionHelpers.getField(activity, "verifiedFile"); assertTrue(apk.isFile());
        controller.resume(); Shadows.shadowOf(Looper.getMainLooper()).idle(); Intent install = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(((TextView)ReflectionHelpers.getField(activity, "status")).getText().toString(), install); assertEquals(Intent.ACTION_VIEW, install.getAction());
        assertEquals("content", install.getData().getScheme()); assertEquals("application/vnd.android.package-archive", install.getType());
        assertTrue((install.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue("Installer must retain access after Activity exits", apk.isFile());
        controller.pause(); controller.resume(); assertNoInstaller();
    }
    @Test public void ordinaryManualCheckDoesNotDownloadAndBusyControlsAreDisabled() throws Exception {
        launch(false); assertTrue(queue.tasks.isEmpty());
        Button check = field("check"); check.performClick();
        ProgressBar progress = ReflectionHelpers.getField(activity, "progress");
        TextView status = ReflectionHelpers.getField(activity, "status");
        assertEquals(View.VISIBLE, progress.getVisibility()); assertEquals(3, status.getMaxLines());
        assertSame(progress.getParent(), status.getParent()); assertFalse(progress.getParent() instanceof ScrollView);
        for (String name : Arrays.asList("check", "download", "choose", "source", "autoCheck")) assertFalse(field(name).isEnabled());
        queue.complete(); assertEquals(UpdateRepository.source(prefs), repository.checkedSource); assertEquals(0, repository.downloads);
        assertTrue(check.isEnabled()); assertEquals(View.GONE, progress.getVisibility());
        assertEquals(View.VISIBLE, field("download").getVisibility()); assertNoInstaller();
    }
    @Test public void failedDownloadDeletesOnlyItsOwnUniqueFileAndCanRetry() throws Exception {
        launch(false); field("check").performClick(); queue.complete(); repository.fail = true;
        field("download").performClick(); queue.complete(); File first = repository.lastFile;
        assertNotNull(first); assertFalse(first.exists()); assertTrue(field("download").isEnabled());
        field("download").performClick(); queue.complete();
        assertNotEquals(first.getPath(), repository.lastFile.getPath()); assertFalse(repository.lastFile.exists()); assertNoInstaller();
    }
    @Test public void autoCheckToggleAndClearingCustomSourceResetThrottle() throws Exception {
        prefs.edit().putString("updateSource", "https://example.com/release.json").putLong("lastUpdateAttempt", 123).commit();
        launch(false); field("autoCheck").performClick(); assertFalse(prefs.getBoolean("autoCheckUpdates", true));
        field("source").performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        EditText input = findEdit(dialog.getWindow().getDecorView()); assertNotNull(input); input.setText("");
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(UpdateRepository.DEFAULT_SOURCE, UpdateRepository.source(prefs)); assertFalse(prefs.contains("lastUpdateAttempt"));
        assertEquals(View.GONE, field("download").getVisibility());
    }
    @Test public void permissionGrantedReturnRevalidatesAndWaitsForResume() throws Exception {
        openPermissionScreen(); controller.pause();
        Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
        activity.onActivityResult(41, 0, null); assertEquals(1, queue.tasks.size()); queue.complete();
        assertNoInstaller(); controller.resume(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent install = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(statusText(), install); assertEquals(Intent.ACTION_VIEW, install.getAction());
        assertEquals(1, repository.downloads);
    }
    @Test public void permissionDeniedReturnWaitsForExplicitRetry() throws Exception {
        openPermissionScreen(); controller.pause(); activity.onActivityResult(41, 0, null);
        controller.resume(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertNoInstaller(); assertTrue(queue.tasks.isEmpty());
        assertTrue(statusText().contains("Установка не разрешена"));
        field("download").performClick(); assertEquals(1, queue.tasks.size()); queue.complete();
        Intent permission = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(statusText(), permission); assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.getAction());
        assertEquals(1, repository.downloads);
    }
    @Test public void recreationDuringPermissionRestoresFileButRequiresExplicitRevalidatedRetry() throws Exception {
        openPermissionScreen(); File file = ReflectionHelpers.getField(activity, "verifiedFile");
        controller.pause(); Bundle state = new Bundle(); controller.saveInstanceState(state).stop().destroy();
        assertTrue(file.isFile());
        controller = Robolectric.buildActivity(SettingsActivity.class); activity = controller.get();
        queue = new QueueExecutor(); repository = new FakeRepository();
        ReflectionHelpers.setField(activity, "network", queue); ReflectionHelpers.setField(activity, "repository", repository);
        Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
        controller.create(state).start().resume().visible(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(file, ReflectionHelpers.getField(activity, "verifiedFile")); assertTrue(queue.tasks.isEmpty()); assertNoInstaller();
        PackageInfo changed = new PackageInfo(); changed.packageName = activity.getPackageName(); changed.versionCode = 999;
        changed.signatures = new Signature[]{new Signature("5678")};
        Shadows.shadowOf(activity.getPackageManager()).setPackageArchiveInfo(file.getPath(), changed);
        field("download").performClick(); queue.complete(); assertNoInstaller(); assertTrue(statusText().contains("Подпись APK"));
        changed.signatures = new Signature[]{new Signature("1234")};
        Shadows.shadowOf(activity.getPackageManager()).setPackageArchiveInfo(file.getPath(), changed);
        field("download").performClick(); queue.complete();
        Intent install = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(statusText(), install); assertEquals(Intent.ACTION_VIEW, install.getAction()); assertEquals(0, repository.downloads);
    }
    private void openPermissionScreen() throws Exception {
        launch(false); Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
        field("check").performClick(); queue.complete(); field("download").performClick(); queue.complete();
        Intent permission = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(statusText(), permission); assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.getAction());
    }
    private String statusText() { return ((TextView)ReflectionHelpers.getField(activity, "status")).getText().toString(); }
    private Button field(String name) { return ReflectionHelpers.getField(activity, name); }
    private void assertNoInstaller() { assertNull(Shadows.shadowOf(activity).peekNextStartedActivity()); }
    private EditText findEdit(View view) {
        if (view instanceof EditText) return (EditText)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) { EditText found = findEdit(((ViewGroup)view).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private final class FakeRepository extends UpdateRepository {
        String checkedSource; int downloads; boolean fail; File lastFile;
        @Override Release check(String source) { checkedSource = source; return new Release(999, "test", "https://example.com/app.apk", "a".repeat(64), "Проверка"); }
        @Override void download(Release release, File target, Progress progress) throws Exception {
            downloads++; lastFile = target;
            try (OutputStream out = new FileOutputStream(target)) { out.write(new byte[]{1, 2, 3}); }
            progress.onProgress(3, -1);
            if (fail) throw new IOException("Ошибка загрузки");
            PackageInfo candidate = new PackageInfo(); candidate.packageName = activity.getPackageName(); candidate.versionCode = 999;
            candidate.signatures = new Signature[]{new Signature("1234")};
            Shadows.shadowOf(activity.getPackageManager()).setPackageArchiveInfo(target.getPath(), candidate);
        }
    }
    private static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>(); boolean closed;
        void complete() throws Exception { Runnable task = tasks.removeFirst(); Thread thread = new Thread(task); thread.start(); thread.join(); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
        @Override public void execute(Runnable command) { tasks.add(command); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; tasks.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    }
}
