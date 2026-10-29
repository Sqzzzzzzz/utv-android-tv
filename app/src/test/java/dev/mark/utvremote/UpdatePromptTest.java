package dev.mark.utvremote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=31)
public final class UpdatePromptTest {
    private ActivityController<Activity> host;
    private SharedPreferences prefs;
    private QueueExecutor network;
    private FakeRepository repository;
    private UpdatePrompt prompt;
    private int cancelledPresses;
    private static final long NOW = 1_000_000_000L;
    @Before public void setup() {
        host = Robolectric.buildActivity(Activity.class).setup();
        prefs = host.get().getSharedPreferences("watch",0); prefs.edit().clear().commit();
        network = new QueueExecutor(); repository = new FakeRepository();
        prompt = new UpdatePrompt(host.get(),network,repository,()->cancelledPresses++);
    }
    @After public void cleanup() { prompt.pause(); network.shutdownNow(); host.pause().stop().destroy(); }
    private void complete() throws Exception {
        Thread thread = new Thread(network.queue.remove()); thread.start(); thread.join();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void checkOncePerDayAndLaterDoesNotInstall() throws Exception {
        prompt.resume(NOW); prompt.resume(NOW); assertEquals(1,network.queue.size()); complete();
        assertEquals(UpdateRepository.DEFAULT_SOURCE,repository.source); assertEquals(1,cancelledPresses);
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); Shadows.shadowOf(Looper.getMainLooper()).idle(); assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
        prompt.pause(); prompt.resume(NOW+1000); assertTrue(network.queue.isEmpty());
        prompt.resume(NOW+UpdatePrompt.INTERVAL); assertEquals(1,network.queue.size());
    }
    @Test public void resultAfterLeavingHomeCannotOpenDialog() throws Exception {
        prompt.resume(NOW); Thread thread=new Thread(network.queue.remove());thread.start();thread.join();
        prompt.pause(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0,cancelledPresses); assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
    }
    @Test public void updateButtonStartsInternalConfirmedDownload() throws Exception {
        prompt.resume(NOW);complete();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Intent intent=Shadows.shadowOf(host.get()).getNextStartedActivity();
        assertEquals(SettingsActivity.class.getName(),intent.getComponent().getClassName());assertTrue(intent.getBooleanExtra("downloadUpdate",false));
    }
    @Test public void customSourceAndDisableDuringRequestSuppressStalePrompt() throws Exception {
        prefs.edit().putString("updateSource","https://example.com/release.json").commit();
        prompt.resume(NOW);prefs.edit().putBoolean("autoCheckUpdates",false).commit();complete();
        assertEquals("https://example.com/release.json",repository.source);assertEquals(0,cancelledPresses);
        assertFalse(UpdatePrompt.due(prefs,NOW+UpdatePrompt.INTERVAL));
        prefs.edit().putBoolean("autoCheckUpdates",true).putLong("lastUpdateAttempt",NOW+100).commit();
        assertTrue(UpdatePrompt.due(prefs,NOW));
        prefs.edit().putString("updateSource","  ").commit();assertEquals(UpdateRepository.DEFAULT_SOURCE,UpdateRepository.source(prefs));
    }
    @Test public void changingSourceDuringRequestSuppressesOldOffer() throws Exception {
        prompt.resume(NOW);prefs.edit().putString("updateSource","https://example.com/other.json").commit();complete();
        assertEquals(0,cancelledPresses);
    }
    @Test public void noNewerReleaseAndNetworkErrorsAreSilent() throws Exception {
        repository.version=1;prompt.resume(NOW);complete();assertEquals(0,cancelledPresses);
        prompt.pause();repository.failure=true;prompt.resume(NOW+UpdatePrompt.INTERVAL);complete();
        assertEquals(0,cancelledPresses);assertNull(Shadows.shadowOf(host.get()).getNextStartedActivity());
    }
    @Test public void progressSupportsUnknownLengthAndRejectsTruncation() throws Exception {
        List<Long> seen=new ArrayList<>();ByteArrayOutputStream out=new ByteArrayOutputStream();
        UpdateRepository.copy(new ByteArrayInputStream(new byte[12000]),out,15000,-1,(n,total)->{assertEquals(-1,total);seen.add(n);});
        assertEquals(Long.valueOf(0),seen.get(0));assertEquals(Long.valueOf(12000),seen.get(seen.size()-1));
        try {UpdateRepository.copy(new ByteArrayInputStream(new byte[10]),new ByteArrayOutputStream(),20,15,null);fail();}catch(IOException expected){}
    }
    private static final class FakeRepository extends UpdateRepository {
        String source; int version=999; boolean failure;
        @Override Release check(String value) throws IOException {source=value;if(failure)throw new IOException("offline");return new Release(version,"9.9","https://example.com/a.apk","a".repeat(64),"Исправления");}
    }
    private static final class QueueExecutor extends AbstractExecutorService {
        final Queue<Runnable> queue=new ArrayDeque<>();boolean closed;
        @Override public void execute(Runnable task){queue.add(task);}
        @Override public void shutdown(){closed=true;}
        @Override public List<Runnable> shutdownNow(){closed=true;queue.clear();return Collections.emptyList();}
        @Override public boolean isShutdown(){return closed;}
        @Override public boolean isTerminated(){return closed;}
        @Override public boolean awaitTermination(long n,TimeUnit unit){return closed;}
    }
}
