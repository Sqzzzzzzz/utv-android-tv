package dev.mark.utvremote;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.media3.ui.PlayerView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-mdpi")
public final class PlaybackActivityTest {
    private static final String URL = "https://www.u-tv.ru/shows/mama-v-16/episodes/1900566/";
    private SharedPreferences prefs() {
        return RuntimeEnvironment.getApplication().getSharedPreferences("watch", Context.MODE_PRIVATE);
    }
    private ActivityController<PlaybackActivity> create(Bundle state) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), PlaybackActivity.class).putExtra("url", URL);
        ActivityController<PlaybackActivity> controller = Robolectric.buildActivity(PlaybackActivity.class, intent);
        ReflectionHelpers.setField(controller.get(), "network", new OfflineExecutor());
        return controller.create(state);
    }
    /** Exercise lifecycle decisions without a codec, network or extra mock dependency. */
    private static final class TestPlayer {
        int state = Player.STATE_READY;
        long position = 185000, duration = 3600000;
        boolean playing = true;
        int seeks;
        ExoPlaybackException error;
        androidx.media3.common.TrackSelectionParameters parameters = new androidx.media3.common.TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build();
        final ExoPlayer player = (ExoPlayer) Proxy.newProxyInstance(ExoPlayer.class.getClassLoader(),
                new Class<?>[]{ExoPlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getCurrentTracks": return androidx.media3.common.Tracks.EMPTY;
                        case "getTrackSelectionParameters": return parameters;
                        case "setTrackSelectionParameters": parameters = (androidx.media3.common.TrackSelectionParameters) args[0]; return null;
                        case "getPlaybackState": return state;
                        case "getCurrentPosition": return position;
                        case "getDuration": return duration;
                        case "getPlayWhenReady": return playing;
                        case "getPlayerError": return error;
                        case "pause": playing = false; return null;
                        case "play": playing = true; return null;
                        case "setPlayWhenReady": playing = (boolean) args[0]; return null;
                        case "seekTo": position = (long) args[0]; seeks++; return null;
                        case "release": return null;
                        case "toString": return "TestPlayer";
                    }
                    throw new AssertionError("Unexpected player method: " + method.getName());
                });
    }
    @Test public void savesSeekPositionWhileBuffering() {
        prefs().edit().clear().putLong("position:" + URL, 180000).commit();
        ActivityController<PlaybackActivity> controller = create(null);
        try {
            TestPlayer player = new TestPlayer(); player.state = Player.STATE_BUFFERING;
            ReflectionHelpers.setField(controller.get(), "player", player.player);
            ReflectionHelpers.callInstanceMethod(controller.get(), "savePosition");
            assertEquals(185000, prefs().getLong("position:" + URL, -1));
        } finally { controller.stop().destroy(); }
    }
    @Test public void retryRetainsTheLatestPositionBeforeReleasingThePlayer() {
        prefs().edit().clear().putLong("position:" + URL, 180000).commit();
        ActivityController<PlaybackActivity> controller = create(null);
        try {
            TestPlayer player = new TestPlayer();
            ReflectionHelpers.setField(controller.get(), "player", player.player);
            ReflectionHelpers.callInstanceMethod(controller.get(), "startPlayback");
            assertEquals(185000, (long) ReflectionHelpers.getField(controller.get(), "resumePosition"));
            assertEquals(185000, prefs().getLong("position:" + URL, -1));
        } finally { controller.stop().destroy(); }
    }
    @Test public void networkErrorAfterRewindingKeepsTheExactPositionAndPause() {
        prefs().edit().clear().putLong("position:" + URL, 240000).commit();
        ActivityController<PlaybackActivity> controller = create(null);
        try {
            TestPlayer player = new TestPlayer(); player.state = Player.STATE_IDLE;
            player.position = 30000; player.playing = false;
            player.error = ExoPlaybackException.createForSource(new java.io.IOException("Offline"),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
            ReflectionHelpers.setField(controller.get(), "player", player.player);
            ReflectionHelpers.callInstanceMethod(controller.get(), "showError", ReflectionHelpers.ClassParameter.from(String.class, "Нет сети"));
            assertEquals(30000, prefs().getLong("position:" + URL, -1));
            assertEquals(30000, (long) ReflectionHelpers.getField(controller.get(), "resumePosition"));
            assertFalse((boolean) ReflectionHelpers.getField(controller.get(), "playWhenReady"));
        } finally { controller.stop().destroy(); }
    }
    @Test public void pausedStateSurvivesActivityRecreation() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null);
        Bundle saved = new Bundle();
        try {
            TestPlayer player = new TestPlayer(); player.playing = false;
            ReflectionHelpers.setField(controller.get(), "player", player.player);
            controller.saveInstanceState(saved);
        } finally { controller.stop().destroy(); }
        ActivityController<PlaybackActivity> restored = create(saved);
        try {
            assertFalse((boolean) ReflectionHelpers.getField(restored.get(), "playWhenReady"));
            assertEquals(185000, (long) ReflectionHelpers.getField(restored.get(), "resumePosition"));
        } finally { restored.stop().destroy(); }
    }
    @Test public void pausingInTheLastSecondsDoesNotMarkTheEpisodeAsCompleted() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null);
        try {
            TestPlayer player = new TestPlayer(); player.position = player.duration - 1000;
            ReflectionHelpers.setField(controller.get(), "player", player.player);
            ReflectionHelpers.callInstanceMethod(controller.get(), "savePosition");
            assertEquals(player.position, prefs().getLong("position:" + URL, -1));
        } finally { controller.stop().destroy(); }
    }
    @Test public void completionSurvivesHomeAndReplayStartsFromZero() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null).start().resume().visible();
        try {
            PlaybackActivity activity = controller.get();
            OfflineExecutor network = ReflectionHelpers.getField(activity, "network");
            TestPlayer player = new TestPlayer(); player.state = Player.STATE_ENDED; player.position = player.duration;
            ReflectionHelpers.setField(activity, "player", player.player);
            ReflectionHelpers.callInstanceMethod(activity, "handlePlaybackState", ReflectionHelpers.ClassParameter.from(int.class, Player.STATE_ENDED));
            LinearLayout end = ReflectionHelpers.getField(activity, "endPanel");
            assertEquals(View.VISIBLE, end.getVisibility()); assertEquals(0, prefs().getLong("position:" + URL, -1));
            int before = network.requests;
            controller.pause().stop().start().resume();
            assertEquals(before, network.requests); assertEquals(View.VISIBLE, end.getVisibility());
            ((Button) end.getChildAt(1)).performClick();
            assertEquals(before + 1, network.requests); assertEquals(View.GONE, end.getVisibility());
            assertEquals(0, (long) ReflectionHelpers.getField(activity, "resumePosition"));
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void completedStateSurvivesActivityRecreationWithoutStartingAnotherStream() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null);
        Bundle saved = new Bundle();
        try {
            ReflectionHelpers.callInstanceMethod(controller.get(), "handlePlaybackState", ReflectionHelpers.ClassParameter.from(int.class, Player.STATE_ENDED));
            controller.saveInstanceState(saved);
        } finally { controller.stop().destroy(); }
        ActivityController<PlaybackActivity> restored = create(saved).start().resume();
        try {
            OfflineExecutor network = ReflectionHelpers.getField(restored.get(), "network");
            assertEquals(0, network.requests);
            LinearLayout end = ReflectionHelpers.getField(restored.get(), "endPanel");
            assertEquals(View.VISIBLE, end.getVisibility());
        } finally { restored.pause().stop().destroy(); }
    }
    @Test public void loadingAndErrorDialogsKeepRemoteFocusInsideThePanel() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null).start().resume().visible();
        try {
            PlaybackActivity activity = controller.get();
            LinearLayout panel = ReflectionHelpers.getField(activity, "messagePanel");
            PlayerView video = ReflectionHelpers.getField(activity, "playerView");
            Button back = (Button) panel.getChildAt(2);
            assertFalse(video.getUseController());
            android.widget.TextView hint = ReflectionHelpers.getField(activity, "hint");
            assertTrue(hint.getText().toString().contains("нажать кнопку"));
            assertSame(back, back.focusSearch(View.FOCUS_UP));
            assertSame(back, back.focusSearch(View.FOCUS_DOWN));
            ReflectionHelpers.callInstanceMethod(activity, "showError", ReflectionHelpers.ClassParameter.from(String.class, "Нет сети"));
            Button retry = ReflectionHelpers.getField(activity, "retry");
            assertTrue(hint.getText().toString().contains("нажать кнопку"));
            assertSame(retry, retry.focusSearch(View.FOCUS_UP));
            assertSame(back, retry.focusSearch(View.FOCUS_DOWN));
            assertSame(retry, back.focusSearch(View.FOCUS_UP));
            assertSame(back, back.focusSearch(View.FOCUS_DOWN));
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void screenStaysAwakeOnlyWhileWatchingOrLoadingInTheForeground() {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> controller = create(null).start().resume();
        try {
            PlaybackActivity activity = controller.get();
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            LinearLayout panel = ReflectionHelpers.getField(activity, "messagePanel"); panel.setVisibility(View.GONE);
            TestPlayer player = new TestPlayer(); player.playing = false;
            ReflectionHelpers.setField(activity, "player", player.player);
            ReflectionHelpers.callInstanceMethod(activity, "updateKeepScreenOn");
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            player.playing = true; ReflectionHelpers.callInstanceMethod(activity, "updateKeepScreenOn");
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0);
            controller.pause();
            assertFalse(player.playing);
            assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            controller.resume(); assertTrue(player.playing);
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void videoUsesTheWholeScreenWhileControlsLeaveRoomForTheHint() {
        ActivityController<PlaybackActivity> controller = create(null).start().resume().visible();
        try {
            PlaybackActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 960, 540);
            PlayerView video = ReflectionHelpers.getField(activity, "playerView");
            View content = video.findViewById(androidx.media3.ui.R.id.exo_content_frame);
            View controls = video.findViewById(androidx.media3.ui.R.id.exo_controller);
            View hint = ReflectionHelpers.getField(activity, "hint");
            assertEquals(video.getHeight(), content.getHeight());
            assertEquals(video.getWidth(), content.getWidth());
            assertNotNull(controls); assertTrue(controls.getBottom() <= hint.getTop());
        } finally { controller.pause().stop().destroy(); }
    }
    private ActivityController<PlaybackActivity> autoplay() {
        prefs().edit().clear().putBoolean("autoNext",true).putInt("nextDelay",5).commit();
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),PlaybackActivity.class).putExtra("url",URL)
                .putExtra("nextUrl",Show.MAMA.url()+"episodes/2/");
        ActivityController<PlaybackActivity> c=Robolectric.buildActivity(PlaybackActivity.class,intent);
        ReflectionHelpers.setField(c.get(),"network",new OfflineExecutor());c.setup().visible();
        ReflectionHelpers.callInstanceMethod(c.get(),"handlePlaybackState",ReflectionHelpers.ClassParameter.from(int.class,Player.STATE_ENDED));
        return c;
    }
    @Test public void autoplayWaitsForCountdownAndOpensNextEpisodeOnce() {
        ActivityController<PlaybackActivity> c=autoplay();
        try {
            assertNotNull(ReflectionHelpers.getField(c.get(),"nextTick"));
            assertEquals(5,(int)ReflectionHelpers.getField(c.get(),"secondsLeft"));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(4100));
            assertEquals(1,(int)ReflectionHelpers.getField(c.get(),"secondsLeft"));
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).peekNextStartedActivity());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
            Intent next=org.robolectric.Shadows.shadowOf(c.get()).getNextStartedActivity();
            assertNotNull("remaining="+ReflectionHelpers.getField(c.get(),"secondsLeft")+" cancelled="+ReflectionHelpers.getField(c.get(),"autoCancelled"),next);assertEquals(Show.MAMA.url()+"episodes/2/",next.getStringExtra("url"));
            assertTrue(prefs().getBoolean("watched:"+URL,false));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10));
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).peekNextStartedActivity());
        } finally {c.pause().stop().destroy();}
    }
    @Test public void remoteInputCancelsCountdownAndItDoesNotReturnAfterHome() {
        ActivityController<PlaybackActivity> c=autoplay();
        try {
            c.get().dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_DOWN));
            c.get().dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_DOWN));
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).peekNextStartedActivity());
            c.pause().stop().start().resume();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6));
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).peekNextStartedActivity());
        } finally {c.pause().stop().destroy();}
    }
    @Test public void leavingPlaybackCancelsPendingAutoplay() {
        ActivityController<PlaybackActivity> c=autoplay();
        try {
            c.pause().stop();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(10));
            assertNull(org.robolectric.Shadows.shadowOf(c.get()).peekNextStartedActivity());
        } finally {c.destroy();}
    }

    @Test public void remoteOkOnQualityOpensTheDialogInsteadOfPausingVideo() {
        prefs().edit().clear().commit();ActivityController<PlaybackActivity> c=create(null).start().resume().visible();
        try {
            PlaybackActivity activity=c.get();TestPlayer player=new TestPlayer();ReflectionHelpers.setField(activity,"player",player.player);
            ((View)ReflectionHelpers.getField(activity,"messagePanel")).setVisibility(View.GONE);
            ReflectionHelpers.callInstanceMethod(activity,"showTransport",ReflectionHelpers.ClassParameter.from(boolean.class,false));
            Button quality=ReflectionHelpers.getField(activity,"quality");quality.setVisibility(View.VISIBLE);
            View root=activity.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);
            key(activity, android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            key(activity, android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            key(activity, android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertSame("quality focus", quality, root.findFocus());
            activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_CENTER));
            activity.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_CENTER));
            android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("quality dialog; focused="+activity.getCurrentFocus()+" seeking="+ReflectionHelpers.getField(activity,"seeking"),dialog);assertTrue(dialog.isShowing());assertTrue(player.playing);
            dialog.getListView().performItemClick(null,0,0);assertEquals(0,prefs().getInt("quality",-1));
        } finally {c.pause().stop().destroy();}
    }

    private ActivityController<PlaybackActivity> ready(TestPlayer player) {
        prefs().edit().clear().commit();
        ActivityController<PlaybackActivity> c = create(null).start().resume().visible();
        ReflectionHelpers.setField(c.get(), "player", player.player);
        ReflectionHelpers.callInstanceMethod(c.get(), "handlePlaybackState", ReflectionHelpers.ClassParameter.from(int.class, Player.STATE_READY));
        return c;
    }
    private void key(PlaybackActivity activity, int code) {
        activity.dispatchKeyEvent(new android.view.KeyEvent(0, code));
        activity.dispatchKeyEvent(new android.view.KeyEvent(1, code));
    }
    @Test public void seekingWaitsForConfirmationAndCancelPreservesPositionAndPause() {
        TestPlayer p = new TestPlayer(); p.playing = false;
        ActivityController<PlaybackActivity> c = ready(p);
        try {
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(0, p.seeks); assertEquals(185000, p.position);
            assertEquals(205000, (long) ReflectionHelpers.getField(c.get(), "seekTarget"));
            c.get().onBackPressed(); assertEquals(0, p.seeks); assertFalse(p.playing);
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals(1, p.seeks); assertEquals(175000, p.position); assertTrue(p.playing);
        } finally { c.pause().stop().destroy(); }
    }
    @Test public void heldSeekAcceleratesAndStaysInsideDuration() {
        TestPlayer p = new TestPlayer(); ActivityController<PlaybackActivity> c = ready(p);
        try {
            c.get().dispatchKeyEvent(new android.view.KeyEvent(0, 0, 0, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, 15));
            assertEquals(245000, (long) ReflectionHelpers.getField(c.get(), "seekTarget"));
            for (int i=0;i<70;i++) key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            assertEquals(0, (long) ReflectionHelpers.getField(c.get(), "seekTarget"));
            for (int i=0;i<400;i++) key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals(p.duration - 1000, (long) ReflectionHelpers.getField(c.get(), "seekTarget"));
            assertEquals(0, p.seeks);
        } finally { c.pause().stop().destroy(); }
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test public void backClosesPanelBeforeLeavingAndOkAlwaysTogglesOnVideo() {
        TestPlayer p = new TestPlayer(); ActivityController<PlaybackActivity> c = ready(p);
        try {
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_CENTER); assertFalse(p.playing);
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_CENTER); assertTrue(p.playing);
            if (System.getProperty("utv.preview.output") != null) {
                key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_DOWN);
                snapshot(c.get(), "player-panel.png");
                key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_UP);
                key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
                snapshot(c.get(), "player-seek.png");
                c.get().onBackPressed();
            }
            c.get().onBackPressed(); assertFalse(c.get().isFinishing());
            assertEquals(View.GONE, ((View)ReflectionHelpers.getField(c.get(), "transport")).getVisibility());
            c.get().onBackPressed(); assertTrue(c.get().isFinishing());
        } finally { c.pause().stop().destroy(); }
    }
    @Test public void leavingDuringSeekResumesOriginalStateAndDoesNotSaveUnconfirmedTarget() {
        TestPlayer p = new TestPlayer(); ActivityController<PlaybackActivity> c = ready(p);
        try {
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT); assertFalse(p.playing);
            c.pause(); assertEquals(185000, prefs().getLong("position:" + URL, -1));
            assertTrue((boolean)ReflectionHelpers.getField(c.get(), "playWhenReady"));
            c.resume(); assertTrue(p.playing); assertFalse((boolean)ReflectionHelpers.getField(c.get(), "seeking"));
        } finally { c.pause().stop().destroy(); }
    }
    @Test public void transportAutohidesWhilePlayingButStaysWhilePausedAndSeeking() {
        TestPlayer p = new TestPlayer(); ActivityController<PlaybackActivity> c = ready(p);
        try {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
            View panel = ReflectionHelpers.getField(c.get(), "transport"); assertEquals(View.GONE, panel.getVisibility());
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
            assertEquals(View.VISIBLE, panel.getVisibility());
            key(c.get(), android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5));
            assertEquals(View.VISIBLE, panel.getVisibility()); assertEquals(0, p.seeks);
        } finally { c.pause().stop().destroy(); }
    }
    @Test public void episodeListHighlightsCurrentAndRejectsOtherShow() throws Exception {
        TestPlayer p = new TestPlayer(); ActivityController<PlaybackActivity> c = ready(p);
        try {
            org.json.JSONArray list = new org.json.JSONArray();
            list.put(new org.json.JSONObject().put("u",URL).put("s",6).put("n",1));
            list.put(new org.json.JSONObject().put("u",Show.MAMENKIN.url()+"episodes/1/").put("s",1).put("n",1));
            String next = Show.MAMA.url()+"episodes/2/";
            list.put(new org.json.JSONObject().put("u",next).put("s",6).put("n",2));
            prefs().edit().putString("catalog",list.toString()).commit();
            ReflectionHelpers.callInstanceMethod(c.get(), "chooseEpisode");
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            EpisodePicker dialog=ReflectionHelpers.getField(c.get(),"episodePicker");
            LinearLayout cards=ReflectionHelpers.getField(dialog,"cards");assertEquals(2,cards.getChildCount());
            assertEquals("episode:"+URL,dialog.getWindow().getDecorView().findFocus().getTag());
            cards.getChildAt(1).performClick();
            Intent intent = org.robolectric.Shadows.shadowOf(c.get()).getNextStartedActivity();
            assertEquals(next,intent.getStringExtra("url")); assertEquals(185000,prefs().getLong("position:"+URL,-1));
        } finally { c.pause().stop().destroy(); }
    }

    private void snapshot(PlaybackActivity activity, String name) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 960, 540);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(960,540,android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        java.io.File folder = new java.io.File(System.getProperty("utv.preview.output")); folder.mkdirs();
        try(java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder,name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
        } catch(java.io.IOException error) { throw new AssertionError(error); }
    }
    @Test public void closingPreviewCancelsPendingDecoderCreationAndDiscardsOldSource() {
        SeekPreview preview = new SeekPreview(RuntimeEnvironment.getApplication());
        preview.setSource("https://cdn.media1.ru/videos/test/master.m3u8",URL);
        preview.show(100000); preview.show(200000); preview.close();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertNull(ReflectionHelpers.getField(preview,"player")); assertEquals(View.GONE,preview.view.getVisibility());
    }

    @Test public void previewFollowsScrubberAndStaysInsideBothTvEdges() {
        TestPlayer p=new TestPlayer();ActivityController<PlaybackActivity> c=ready(p);
        try{
            View root=c.get().getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);
            p.position=0;key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            SeekPreview preview=ReflectionHelpers.getField(c.get(),"seekPreview");
            android.widget.FrameLayout.LayoutParams start=(android.widget.FrameLayout.LayoutParams)preview.view.getLayoutParams();int first=start.leftMargin;
            assertTrue(first>=28);assertTrue(first+start.width<=932);
            c.get().onBackPressed();p.position=p.duration-1000;key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            android.widget.FrameLayout.LayoutParams end=(android.widget.FrameLayout.LayoutParams)preview.view.getLayoutParams();
            assertTrue(end.leftMargin>first);assertTrue(end.leftMargin+end.width<=932);
            PlayerTimeline bar=ReflectionHelpers.getField(c.get(),"timebar");int[] xy=new int[2];bar.getLocationOnScreen(xy);
            assertTrue(end.topMargin+end.height<xy[1]);assertEquals(0,p.seeks);
        }finally{c.pause().stop().destroy();}
    }
    @Test public void tenSecondControlStartsPreviewWithoutSeekingMainVideo() {
        TestPlayer p=new TestPlayer();ActivityController<PlaybackActivity> c=ready(p);
        try{
            Button pause=ReflectionHelpers.getField(c.get(),"pauseButton");pause.requestFocus();pause.focusSearch(View.FOCUS_LEFT).requestFocus();
            key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            assertTrue((boolean)ReflectionHelpers.getField(c.get(),"seeking"));assertEquals(175000,(long)ReflectionHelpers.getField(c.get(),"seekTarget"));assertEquals(0,p.seeks);
        }finally{c.pause().stop().destroy();}
    }
    @Test public void interruptedButtonPressDoesNotOpenQualityAfterHome() {
        TestPlayer p=new TestPlayer();ActivityController<PlaybackActivity> c=ready(p);
        try{
            Button quality=ReflectionHelpers.getField(c.get(),"quality");quality.requestFocus();
            c.get().dispatchKeyEvent(new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_CENTER));c.pause().resume();
            c.get().dispatchKeyEvent(new android.view.KeyEvent(1,android.view.KeyEvent.KEYCODE_DPAD_CENTER));
            assertFalse((boolean)ReflectionHelpers.getField(c.get(),"dialogOpen"));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void extraMenuRetainsRestartAndOpensQualityWithoutLosingDialogState() {
        TestPlayer p=new TestPlayer();ActivityController<PlaybackActivity> c=ready(p);
        try{
            key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            android.app.AlertDialog menu=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(menu.isShowing());
            assertEquals("С начала",menu.getListView().getAdapter().getItem(1));
            menu.getListView().performItemClick(null,0,0);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            android.app.AlertDialog quality=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotSame(menu,quality);assertTrue(quality.isShowing());assertTrue((boolean)ReflectionHelpers.getField(c.get(),"dialogOpen"));assertTrue(p.playing);
        }finally{c.pause().stop().destroy();}
    }

}
