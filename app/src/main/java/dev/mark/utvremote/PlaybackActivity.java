package dev.mark.utvremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.C;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackActivity extends Activity {
    private static final String PLAYBACK_HINT = "OK — пауза / пуск     ← → — перемотка     ↓ — управление     Menu — ещё     Назад — скрыть / каталог";
    private static final String DIALOG_HINT = "Стрелки — выбор     OK — нажать кнопку     Назад — каталог";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private PlayerView playerView;
    private ExoPlayer player;
    private SharedPreferences prefs;
    private WatchState watch;
    private Button quality, cancelNext, pauseButton;
    private LinearLayout transport;
    private TextView timeline;
    private PlayerTimeline timebar;
    private TextView durationText;
    private SeekPreview seekPreview;
    private boolean seeking, seekWasPlaying, dialogOpen;
    private EpisodePicker episodePicker;
    private View pressedButton;
    private long seekOrigin, seekTarget;
    private final Runnable hideTransport = () -> {
        if (player != null && player.getPlayWhenReady() && !seeking && !dialogOpen) hideTransport();
    };
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            updateTimeline();
            if (active) handler.postDelayed(this, 500);
        }
    };
    private TextView countdown;
    private boolean autoCancelled, launchingNext;
    private int secondsLeft;
    private long nextDeadline;
    private Runnable nextTick;
    private Show show;
    private LinearLayout messagePanel, endPanel;
    private TextView status, heading, hint;
    private Button retry;
    private String episodeUrl, title, nextUrl, nextTitle;
    private int generation;
    private boolean active, resumed, pausedByLifecycle, completed;
    private long resumePosition;
    private boolean playWhenReady = true;
    private final Runnable saveTick = new Runnable() {
        @Override public void run() { savePosition(); if (active) handler.postDelayed(this, 5000); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TvUi.fullscreen(this);
        prefs = getSharedPreferences("watch", MODE_PRIVATE);
        watch = new WatchState(prefs);
        episodeUrl = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        nextUrl = getIntent().getStringExtra("nextUrl");
        nextTitle = getIntent().getStringExtra("nextTitle");
        show = Show.fromUrl(episodeUrl);
        if (show == null) { finish(); return; }
        if (title == null) title = watch.title(show, episodeUrl);
        if (nextUrl != null && Show.fromUrl(nextUrl) != show) nextUrl = null;
        if (nextUrl == null) {
            try {
                org.json.JSONArray catalog = new org.json.JSONArray(prefs.getString(show.key("catalog"), "[]"));
                for (int i = 0; i + 1 < catalog.length(); i++) {
                    if (!episodeUrl.equals(catalog.getJSONObject(i).getString("u"))) continue;
                    org.json.JSONObject next = catalog.getJSONObject(i + 1);
                    if (Show.fromUrl(next.getString("u")) != show) break;
                    nextUrl = next.getString("u");
                    nextTitle = show.title + " · " + next.getInt("s") + " сезон · " + next.getInt("n") + " серия";
                    break;
                }
            } catch (org.json.JSONException ignored) { }
        }
        resumePosition = prefs.getLong("position:" + episodeUrl, 0);
        if (state != null) {
            resumePosition = Math.max(0, state.getLong("position", resumePosition));
            playWhenReady = state.getBoolean("playWhenReady", true);
            completed = state.getBoolean("completed", false);
            autoCancelled = state.getBoolean("autoCancelled", false);
        }
        buildScreen();
    }

    private int dp(int value) { return TvUi.dp(this, value); }
    private void buildScreen() {
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setId(View.generateViewId());
        playerView.setUseController(false);
        playerView.setControllerShowTimeoutMs(4000);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        playerView.setShowNextButton(false); playerView.setShowPreviousButton(false);
        View progress = playerView.findViewById(androidx.media3.ui.R.id.exo_progress);
        if (progress instanceof androidx.media3.ui.DefaultTimeBar) {
            androidx.media3.ui.DefaultTimeBar bar = (androidx.media3.ui.DefaultTimeBar) progress;
            bar.setPlayedColor(TvUi.RED); bar.setScrubberColor(TvUi.ACCENT);
            bar.setBufferedColor(0xFF6E6E81); bar.setUnplayedColor(TvUi.PANEL);
        }
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        String caption=title;
        int split=caption.indexOf(" · ");
        if(split>=0)caption=caption.substring(0,split)+"\n"+caption.substring(split+3);
        android.text.SpannableString styled=new android.text.SpannableString(caption);
        int newline=caption.indexOf('\n');
        if(newline>=0)styled.setSpan(new android.text.style.RelativeSizeSpan(.72f),newline+1,caption.length(),0);
        heading = TvUi.text(this, "", 22, TvUi.TEXT);heading.setText(styled);
        android.graphics.drawable.Drawable logo=getDrawable(R.drawable.brand_mark);logo.setBounds(0,0,dp(36),dp(26));
        heading.setCompoundDrawables(logo,null,null,null);heading.setCompoundDrawablePadding(dp(14));
        heading.setPadding(dp(32), dp(20), dp(32), dp(20));
        TvUi.bold(heading); heading.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xB3000000,0x00000000}));
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(heading, titleLp);
        hint = TvUi.text(this, DIALOG_HINT, 13, TvUi.MUTED);
        hint.setGravity(Gravity.CENTER); hint.setPadding(dp(16), dp(6), dp(16), dp(10));
        hint.setBackgroundColor(0xE6000000);
        root.addView(hint, new FrameLayout.LayoutParams(-1, dp(34), Gravity.BOTTOM));
        // Reserve space in the controls only; padding PlayerView shrinks the video itself.
        View controls = playerView.findViewById(androidx.media3.ui.R.id.exo_controller);
        if (controls != null) {
            FrameLayout.LayoutParams controlsLp = (FrameLayout.LayoutParams) controls.getLayoutParams();
            controlsLp.bottomMargin = dp(34); controls.setLayoutParams(controlsLp);
        }
        transport = new LinearLayout(this);transport.setOrientation(LinearLayout.VERTICAL);
        transport.setPadding(dp(28),dp(12),dp(28),dp(6));
        transport.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xA6000000,0xF2000000}));
        FrameLayout bar=new FrameLayout(this);
        timeline=TvUi.text(this,"0:00",13,TvUi.TEXT);timeline.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(timeline,new FrameLayout.LayoutParams(dp(58),-1,Gravity.LEFT));
        durationText=TvUi.text(this,"—:—",13,TvUi.TEXT);durationText.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        bar.addView(durationText,new FrameLayout.LayoutParams(dp(68),-1,Gravity.RIGHT));
        timebar=new PlayerTimeline(this);
        FrameLayout.LayoutParams track=new FrameLayout.LayoutParams(-1,-1);track.leftMargin=dp(60);track.rightMargin=dp(72);bar.addView(timebar,track);
        transport.addView(bar,new LinearLayout.LayoutParams(-1,dp(32)));
        FrameLayout buttons=new FrameLayout(this);
        Button episodes=textAction("Серии",()->chooseEpisode());
        buttons.addView(episodes,new FrameLayout.LayoutParams(dp(112),dp(48),Gravity.LEFT|Gravity.CENTER_VERTICAL));
        quality=textAction("Качество · Авто",()->chooseQuality());
        buttons.addView(quality,new FrameLayout.LayoutParams(dp(174),dp(48),Gravity.RIGHT|Gravity.CENTER_VERTICAL));
        LinearLayout central=new LinearLayout(this);central.setGravity(Gravity.CENTER_VERTICAL);
        Button back10=new PlayerControlButton(this,PlayerControlButton.BACK);back10.setText("Назад на 10 секунд");back10.setOnClickListener(v->moveSeek(-1,0));
        central.addView(back10,new LinearLayout.LayoutParams(dp(52),dp(52)));
        pauseButton=new PlayerControlButton(this,PlayerControlButton.PLAY);pauseButton.setText("Пауза");pauseButton.setOnClickListener(v->togglePlayback());
        LinearLayout.LayoutParams playLp=new LinearLayout.LayoutParams(dp(66),dp(66));playLp.leftMargin=dp(18);playLp.rightMargin=dp(18);central.addView(pauseButton,playLp);
        Button forward10=new PlayerControlButton(this,PlayerControlButton.FORWARD);forward10.setText("Вперёд на 10 секунд");forward10.setOnClickListener(v->moveSeek(1,0));central.addView(forward10,new LinearLayout.LayoutParams(dp(52),dp(52)));
        buttons.addView(central,new FrameLayout.LayoutParams(-2,-1,Gravity.CENTER));transport.addView(buttons,new LinearLayout.LayoutParams(-1,dp(78)));
        FrameLayout.LayoutParams transportLp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);transportLp.bottomMargin=dp(34);root.addView(transport,transportLp);transport.setVisibility(View.GONE);
        playerView.setFocusable(true);playerView.setFocusableInTouchMode(true);playerView.setNextFocusDownId(pauseButton.getId());
        View[] order={episodes,back10,pauseButton,forward10,quality};
        for(int i=0;i<order.length;i++){
            View button=order[i];if(button instanceof PlayerControlButton)button.setOnFocusChangeListener((v,f)->updateHint());button.setNextFocusUpId(playerView.getId());button.setNextFocusDownId(button.getId());
            button.setNextFocusLeftId(order[Math.max(0,i-1)].getId());button.setNextFocusRightId(order[Math.min(order.length-1,i+1)].getId());
        }
        seekPreview=new SeekPreview(this);
        root.addView(seekPreview.view,new FrameLayout.LayoutParams(dp(204),dp(126),Gravity.TOP|Gravity.LEFT));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(seeking)positionPreview();});
        playerView.setOnFocusChangeListener((v,f)->updateHint());
        messagePanel = new LinearLayout(this); messagePanel.setOrientation(LinearLayout.VERTICAL);
        messagePanel.setGravity(Gravity.CENTER); messagePanel.setPadding(dp(32), dp(24), dp(32), dp(24));
        messagePanel.setBackground(TvUi.shape(this, TvUi.PANEL, 0));
        status = TvUi.text(this, "Получаем видео…", 21, TvUi.TEXT); TvUi.bold(status); status.setGravity(Gravity.CENTER);
        messagePanel.addView(status, new LinearLayout.LayoutParams(-1, -2));
        retry = TvUi.button(this, "Попробовать снова"); retry.setVisibility(View.GONE);
        TvUi.primary(retry);
        retry.setOnClickListener(v -> startPlayback());
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(dp(260), dp(48));
        buttonLp.topMargin = dp(20); messagePanel.addView(retry, buttonLp);
        Button back = TvUi.button(this, "Вернуться к сериям"); back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(260), dp(48));
        backLp.topMargin = dp(10); messagePanel.addView(back, backLp);
        root.addView(messagePanel, new FrameLayout.LayoutParams(dp(600), -2, Gravity.CENTER));
        endPanel = new LinearLayout(this); endPanel.setOrientation(LinearLayout.VERTICAL);
        endPanel.setGravity(Gravity.CENTER); endPanel.setPadding(dp(32), dp(24), dp(32), dp(24));
        endPanel.setBackground(TvUi.shape(this, TvUi.PANEL, 0)); endPanel.setVisibility(View.GONE);
        TextView done = TvUi.text(this, "Выпуск закончился", 25, TvUi.TEXT); endPanel.addView(done);
        if (nextUrl != null && CatalogRepository.isSitePage(nextUrl)) {
            Button next = TvUi.button(this, "Следующая серия");
            TvUi.primary(next);
            next.setOnClickListener(v -> openNext());
            endPanel.addView(next, new LinearLayout.LayoutParams(dp(260), dp(50)));
        }
        Button again = TvUi.button(this, "Смотреть сначала");
        again.setOnClickListener(v -> {
            cancelAutoNext(); releasePlayer(); completed = false; autoCancelled = false; resumePosition = 0; playWhenReady = true;
            startPlayback();
        });
        LinearLayout.LayoutParams againLp = new LinearLayout.LayoutParams(dp(260), dp(48));
        againLp.topMargin = dp(10); endPanel.addView(again, againLp);
        Button catalog = TvUi.button(this, "Вернуться к сериям"); catalog.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams catalogLp = new LinearLayout.LayoutParams(dp(260), dp(48));
        catalogLp.topMargin = dp(10); endPanel.addView(catalog, catalogLp);
        countdown = TvUi.text(this, "", 14, TvUi.ACCENT); countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-1, -2); countLp.topMargin = dp(12);
        endPanel.addView(countdown, countLp); countdown.setVisibility(View.GONE);
        cancelNext = TvUi.button(this, "Отменить автопереход"); cancelNext.setVisibility(View.GONE);
        cancelNext.setOnClickListener(v -> cancelAutoNext());
        endPanel.addView(cancelNext, new LinearLayout.LayoutParams(dp(300), dp(42)));
        root.addView(endPanel, new FrameLayout.LayoutParams(dp(470), -2, Gravity.CENTER));
        setContentView(root);
    }

    private void startPlayback() {
        cancelAutoNext();
        cancelSeek(); hideTransport();
        rememberPlayback();
        int request = ++generation;
        releasePlayer(); endPanel.setVisibility(View.GONE);
        playerView.setUseController(false);
        messagePanel.setVisibility(View.VISIBLE); retry.setVisibility(View.GONE);
        status.setText("Получаем видео…");
        focusPanel(messagePanel); updateKeepScreenOn();
        network.execute(() -> {
            try {
                String stream = new CatalogRepository().resolveStream(episodeUrl);
                runOnUiThread(() -> {
                    if (!active || isDestroyed() || request != generation) return;
                    DefaultHttpDataSource.Factory data = new DefaultHttpDataSource.Factory()
                            .setUserAgent(SiteHttp.USER_AGENT)
                            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
                            .setDefaultRequestProperties(Collections.singletonMap("Referer", episodeUrl));
                    seekPreview.setSource(stream, episodeUrl);
                    player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                            .setHandleAudioBecomingNoisy(true).build();
                    playerView.setPlayer(player);
                    player.addListener(new Player.Listener() {
                        @Override public void onPlaybackStateChanged(int state) {
                            handlePlaybackState(state);
                        }
                        @Override public void onTracksChanged(androidx.media3.common.Tracks tracks) { applyQuality(); }
                        @Override public void onPlayWhenReadyChanged(boolean ready, int reason) { updateKeepScreenOn(); }
                        @Override public void onPlayerError(PlaybackException error) {
                            android.util.Log.w("UTVRemote", "Playback failed", error);
                            NetworkDiagnostics.save(PlaybackActivity.this, "Плеер: " + title, error);
                            showError("Не удалось воспроизвести видео.\nПроверь интернет или попробуй другой выпуск.");
                        }
                    });
                    MediaItem item = new MediaItem.Builder().setUri(stream).setMimeType(MimeTypes.APPLICATION_M3U8).build();
                    player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(item));
                    applyQuality();
                    player.seekTo(resumePosition); player.prepare(); player.setPlayWhenReady(playWhenReady && !pausedByLifecycle);
                    status.setText("Загружаем выпуск…"); updateKeepScreenOn();
                });
            } catch (Exception ex) {
                android.util.Log.w("UTVRemote", "Stream resolution failed", ex);
                NetworkDiagnostics.save(this, "Видео: " + title, ex);
                runOnUiThread(() -> {
                    if (!active || isDestroyed() || request != generation) return;
                    showError(ex instanceof CatalogRepository.ExternalVideoException ? ex.getMessage()
                            : "Серия пока недоступна.\nПроверь подключение и попробуй снова.");
                });
            }
        });
    }
    private void handlePlaybackState(int state) {
        if (state == Player.STATE_READY && !completed) {
            if (messagePanel.getVisibility() == View.VISIBLE && retry.getVisibility() != View.VISIBLE) {
                watch.opened(show, episodeUrl);
                hint.setText(PLAYBACK_HINT);
                messagePanel.setVisibility(View.GONE); playerView.requestFocus(); showTransport(false);
            }
            prefs.edit().putString(show.key("lastUrl"), episodeUrl).putString(show.key("lastTitle"), title).apply();
        } else if (state == Player.STATE_ENDED) {
            completed = true; resumePosition = 0; watch.mark(show, episodeUrl, true);
            showCompleted();
        }
        updateKeepScreenOn();
    }
    private void showCompleted() {
        cancelSeek(); hideTransport();
        messagePanel.setVisibility(View.GONE); playerView.setUseController(false);
        endPanel.setVisibility(View.VISIBLE); focusPanel(endPanel); maybeAutoNext(); updateKeepScreenOn();
    }
    // Keep remote focus inside the visible dialog, including while Retry is hidden.
    private void focusPanel(LinearLayout panel) {
        cancelButtonPress();
        transport.setVisibility(View.GONE);
        quality.setVisibility(View.VISIBLE);
        heading.setVisibility(View.VISIBLE); hint.setVisibility(View.VISIBLE); hint.setText(DIALOG_HINT);
        List<View> buttons = new ArrayList<>();
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child instanceof Button && child.getVisibility() == View.VISIBLE) buttons.add(child);
        }
        for (int i = 0; i < buttons.size(); i++) {
            View button = buttons.get(i);
            button.setNextFocusLeftId(button.getId()); button.setNextFocusRightId(button.getId());
            button.setNextFocusUpId(buttons.get(Math.max(0, i - 1)).getId());
            button.setNextFocusDownId(buttons.get(Math.min(buttons.size() - 1, i + 1)).getId());
        }
        if (!buttons.isEmpty()) buttons.get(0).requestFocus();
    }
    private void updateKeepScreenOn() {
        boolean loading = messagePanel.getVisibility() == View.VISIBLE && retry.getVisibility() != View.VISIBLE;
        boolean watching = player != null && player.getPlayWhenReady()
                && (player.getPlaybackState() == Player.STATE_READY || player.getPlaybackState() == Player.STATE_BUFFERING);
        if (resumed && !completed && (loading || watching))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void showError(String text) {
        cancelSeek(); hideTransport();
        rememberPlayback();
        if (player != null) player.pause();
        playerView.setUseController(false);
        status.setText(text); messagePanel.setVisibility(View.VISIBLE); retry.setVisibility(View.VISIBLE);
        focusPanel(messagePanel); updateKeepScreenOn();
    }
    private void rememberPlayback() {
        savePosition();
        if (player != null && !pausedByLifecycle && !completed && retry.getVisibility() != View.VISIBLE)
            playWhenReady = player.getPlayWhenReady();
    }
    private void savePosition() {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE && player.getPlayerError() == null) return;
        long position = Math.max(0, player.getCurrentPosition());
        long duration = player.getDuration();
        // Only a real end counts as completed; pausing in the last seconds still resumes there.
        if (completed || state == Player.STATE_ENDED) position = 0;
        else if (duration != C.TIME_UNSET && duration > 0) position = Math.min(position, duration);
        resumePosition = position;
        SharedPreferences.Editor edit = prefs.edit().putLong("position:" + episodeUrl, position);
        if (duration != C.TIME_UNSET && duration > 0) edit.putLong("duration:" + episodeUrl, duration);
        edit.apply();
    }
    private void releasePlayer() {
        if (player == null) return;
        seekPreview.close(); playerView.setPlayer(null); player.release(); player = null;
    }
    @Override protected void onStart() {
        super.onStart(); if (playerView == null) return;
        active = true;
        if (completed) showCompleted(); else startPlayback();
        handler.postDelayed(saveTick, 5000); handler.post(progressTick);
    }
    @Override protected void onPause() {
        if(episodePicker!=null)episodePicker.dismiss();
        cancelAutoNext(); cancelSeek(); cancelButtonPress(); handler.removeCallbacks(hideTransport); rememberPlayback(); pausedByLifecycle = true; resumed = false;
        if (player != null) player.pause();
        if (playerView != null) updateKeepScreenOn();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume(); TvUi.fullscreen(this);
        pausedByLifecycle = false; resumed = true;
        if (player != null && !completed && retry.getVisibility() != View.VISIBLE) player.setPlayWhenReady(playWhenReady);
        if (playerView != null) { updateKeepScreenOn(); if (completed) maybeAutoNext();
            else if (player != null && messagePanel.getVisibility() != View.VISIBLE) showTransport(false); }
    }
    @Override protected void onStop() {
        active = false; ++generation; handler.removeCallbacks(progressTick); handler.removeCallbacks(hideTransport); handler.removeCallbacks(saveTick); savePosition(); releasePlayer(); super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        cancelSeek(); rememberPlayback();
        state.putLong("position", resumePosition); state.putBoolean("playWhenReady", playWhenReady);
        state.putBoolean("completed", completed); state.putBoolean("autoCancelled", autoCancelled); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { cancelAutoNext(); network.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy(); }
    @Override public void onBackPressed() {
        if (messagePanel == null || messagePanel.getVisibility() == View.VISIBLE || endPanel.getVisibility() == View.VISIBLE) { cancelAutoNext(); finish(); return; }
        if (seeking) { cancelSeek(); showTransport(false); return; }
        if (transport.getVisibility() == View.VISIBLE) { hideTransport(); return; }
        savePosition(); finish();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (endPanel != null && endPanel.getVisibility() == View.VISIBLE && down) cancelAutoNext();
        if (player == null || messagePanel.getVisibility() == View.VISIBLE || endPanel.getVisibility() == View.VISIBLE)
            return super.dispatchKeyEvent(event);
        if (key == KeyEvent.KEYCODE_BACK) {
            if(down)cancelButtonPress();
            if (!down) onBackPressed(); return true;
        }
        if (key == KeyEvent.KEYCODE_MENU) {
            if (down && event.getRepeatCount() == 0) { cancelButtonPress(); if (seeking) cancelSeek(); chooseActions(); }
            return true;
        }
        View focused = getWindow().getDecorView().findFocus();
        boolean onButton = focused instanceof Button;
        if (onButton && down) { handler.removeCallbacks(hideTransport); handler.postDelayed(hideTransport, 6000); }
        if (onButton && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) {
                cancelButtonPress();View target = focused.focusSearch(key == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                if (target != null) target.requestFocus();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || key == KeyEvent.KEYCODE_MEDIA_PLAY || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down && event.getRepeatCount() == 0) {
                if (seeking) cancelSeek();
                if (key == KeyEvent.KEYCODE_MEDIA_PLAY) player.play();
                else if (key == KeyEvent.KEYCODE_MEDIA_PAUSE) player.pause();
                else togglePlayback();
                showTransport(false);
            }
            return true;
        }
        if (((key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) && !onButton)
                || key == KeyEvent.KEYCODE_MEDIA_REWIND || key == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            if (down) moveSeek(key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_MEDIA_REWIND ? -1 : 1, event.getRepeatCount());
            return true;
        }
        if ((key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) && onButton) {
            if(down&&event.getRepeatCount()==0){pressedButton=focused;focused.setPressed(true);}
            if(!down&&pressedButton!=null){View action=pressedButton;cancelButtonPress();if(!event.isCanceled()&&action==focused)action.performClick();}
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
            if (down && event.getRepeatCount() == 0) {
                if (seeking) confirmSeek(); else togglePlayback();
            }
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
            if (down && !seeking) {
                if(key==KeyEvent.KEYCODE_DPAD_DOWN&&onButton){
                    if(event.getRepeatCount()==0){cancelButtonPress();chooseActions();}
                    return true;
                }
                cancelButtonPress();showTransport(false);
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) pauseButton.requestFocus();
                else playerView.requestFocus();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private Button textAction(String text,Runnable action){
        Button button=new Button(this);button.setId(View.generateViewId());button.setText(text);button.setAllCaps(false);button.setTextSize(15);button.setTextColor(TvUi.TEXT);
        button.setTypeface(getResources().getFont(R.font.onest_regular));button.setPadding(0,0,0,0);button.setStateListAnimator(null);button.setFocusable(true);button.setFocusableInTouchMode(true);
        button.setBackground(TvUi.shape(this,Color.TRANSPARENT,0));button.setOnFocusChangeListener((v,f)->{button.setBackground(TvUi.shape(this,Color.TRANSPARENT,f?TvUi.MAGENTA:0));updateHint();});
        button.setOnClickListener(v->action.run());return button;
    }
    private void positionPreview(){
        if(!seeking||timebar.getWidth()==0)return;
        int[] bar=new int[2],root=new int[2];timebar.getLocationOnScreen(bar);((View)seekPreview.view.getParent()).getLocationOnScreen(root);
        seekPreview.positionAt(bar[0]-root[0]+timebar.markerX(),bar[1]-root[1]+timebar.getHeight()/2,((View)seekPreview.view.getParent()).getWidth());
    }
    private void chooseActions(){
        if(player==null)return;
        List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        labels.add("Качество");actions.add(()->chooseQuality());
        labels.add("С начала");actions.add(()->{player.seekTo(0);player.play();showTransport(false);});
        String previous=adjacentEpisode(-1);
        if(previous!=null){labels.add("Предыдущая серия");actions.add(()->openEpisode(previous,watch.title(show,previous)));}
        if(nextUrl!=null){labels.add("Следующая серия");actions.add(()->openNext());}
        labels.add("Серии");actions.add(()->chooseEpisode());
        dialogOpen=true;showTransport(false);
        Runnable[] selectedAction={null};
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("Управление")
                .setItems(labels.toArray(new String[0]),(d,index)->{selectedAction[0]=actions.get(index);d.dismiss();}).setNegativeButton("Назад",null).create();
        dialog.setOnDismissListener(d->{dialogOpen=false;showTransport(true);if(selectedAction[0]!=null)selectedAction[0].run();});dialog.show();
    }
    private void updateHint(){
        if(hint==null||(messagePanel!=null&&messagePanel.getVisibility()==View.VISIBLE)||(endPanel!=null&&endPanel.getVisibility()==View.VISIBLE))return;
        boolean buttons=getWindow().getDecorView().findFocus() instanceof Button;
        hint.setText(seeking?"← → — выбрать момент · удержание — быстрее     OK — смотреть     Назад — отменить":
                buttons?"← → — выбор кнопки     OK — нажать     ↑ — к видео     ↓ — ещё     Назад — скрыть":PLAYBACK_HINT);
    }
    private void showTransport(boolean focus) {
        if (messagePanel.getVisibility() == View.VISIBLE || endPanel.getVisibility() == View.VISIBLE) return;
        transport.setVisibility(View.VISIBLE); heading.setVisibility(View.VISIBLE); hint.setVisibility(View.VISIBLE);
        updateHint();
        updateTimeline();
        if (focus) pauseButton.requestFocus();
        handler.removeCallbacks(hideTransport);
        if (player != null && player.getPlayWhenReady() && !seeking && !dialogOpen) handler.postDelayed(hideTransport, 4000);
    }
    private void cancelButtonPress(){if(pressedButton!=null){pressedButton.setPressed(false);pressedButton=null;}}
    private void hideTransport() {
        cancelButtonPress();handler.removeCallbacks(hideTransport);
        if (transport == null) return;
        transport.setVisibility(View.GONE); heading.setVisibility(View.GONE); hint.setVisibility(View.GONE);
        playerView.requestFocus();
    }
    private void togglePlayback() {
        if (player.getPlayWhenReady()) player.pause(); else player.play();
        showTransport(false); updateKeepScreenOn();
    }
    private void updateTimeline() {
        if (player == null || transport == null || transport.getVisibility() != View.VISIBLE) return;
        long duration = player.getDuration(), position = seeking ? seekTarget : player.getCurrentPosition();
        timeline.setText(time(player.getCurrentPosition()));
        durationText.setText(duration>0?time(duration):"—:—");
        timebar.update(position,duration);
        pauseButton.setText(player.getPlayWhenReady()?"Пауза":"Продолжить");
        if(seeking)positionPreview();
    }
    private void moveSeek(int direction, int repeats) {
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) { showTransport(false); return; }
        if (!seeking) {
            seeking = true; seekOrigin = player.getCurrentPosition(); seekTarget = seekOrigin;
            seekWasPlaying = player.getPlayWhenReady(); player.pause();
        }
        long step = repeats < 5 ? 10000 : repeats < 15 ? 30000 : 60000;
        seekTarget = Math.max(0, Math.min(Math.max(0, duration - 1000), seekTarget + direction * step));
        playerView.requestFocus(); showTransport(false); seekPreview.show(seekTarget); positionPreview(); updateKeepScreenOn();
    }
    private void confirmSeek() {
        if (!seeking) return;
        seeking = false; seekPreview.close(); player.seekTo(seekTarget); player.play(); showTransport(false); updateKeepScreenOn();
    }
    private void cancelSeek() {
        if (!seeking) return;
        seeking = false; seekPreview.close();
        if (player != null) player.setPlayWhenReady(seekWasPlaying);
    }
    private String adjacentEpisode(int offset) {
        try {
            org.json.JSONArray list = new org.json.JSONArray(prefs.getString(show.key("catalog"), "[]"));
            for (int i = 0; i < list.length(); i++) {
                if (!episodeUrl.equals(list.getJSONObject(i).getString("u"))) continue;
                int index = i + offset;
                if (index < 0 || index >= list.length()) return null;
                String url = list.getJSONObject(index).getString("u");
                return Show.fromUrl(url) == show ? url : null;
            }
        } catch (org.json.JSONException ignored) { }
        return null;
    }
    private void chooseEpisode() {
        if(episodePicker!=null && episodePicker.isShowing())return;
        cancelButtonPress();dialogOpen=true;showTransport(false);
        episodePicker=new EpisodePicker(this,show,episodeUrl,watch,url->openEpisode(url,watch.title(show,url)));
        episodePicker.setOnDismissListener(d->{episodePicker=null;dialogOpen=false;showTransport(true);});
        episodePicker.show();
    }
    private void openEpisode(String url, String episodeTitle) {
        if (launchingNext || Show.fromUrl(url) != show) return;
        launchingNext = true; cancelSeek(); cancelAutoNext(); savePosition();
        startActivity(new Intent(this, PlaybackActivity.class).putExtra("url", url).putExtra("title", episodeTitle)); finish();
    }
    private void applyQuality() {
        if (player != null) VideoQuality.apply(player, prefs.getInt("quality", 0));
        quality.setText("Качество · " + VideoQuality.label(player == null ? prefs.getInt("quality", 0) : VideoQuality.effective(player.getCurrentTracks(), prefs.getInt("quality", 0))));
    }
    private void chooseQuality() {
        if (player == null) return;
        java.util.List<Integer> values = new ArrayList<>(VideoQuality.available(player.getCurrentTracks()));
        String[] labels = new String[values.size()]; int selected = 0;
        for (int i = 0; i < values.size(); i++) {
            labels[i] = VideoQuality.label(values.get(i)); if (values.get(i) == VideoQuality.effective(player.getCurrentTracks(), prefs.getInt("quality", 0))) selected = i;
        }
        dialogOpen = true; showTransport(false);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle("Качество видео")
                .setSingleChoiceItems(labels, selected, (choice, index) -> {
                    prefs.edit().putInt("quality", values.get(index)).apply(); applyQuality(); choice.dismiss();
                }).setNegativeButton("Назад", null).create();
        dialog.setOnDismissListener(d -> { dialogOpen = false; showTransport(true); });
        dialog.show();
    }
    private void openNext() {
        if (launchingNext || nextUrl == null || Show.fromUrl(nextUrl) != show) return;
        openEpisode(nextUrl, nextTitle);
    }
    private void maybeAutoNext() {
        if (!active || !resumed || autoCancelled || nextTick != null || !prefs.getBoolean("autoNext", false)
                || nextUrl == null || Show.fromUrl(nextUrl) != show) return;
        secondsLeft = Math.max(5, Math.min(30, prefs.getInt("nextDelay", 10)));
        nextDeadline = android.os.SystemClock.uptimeMillis() + secondsLeft * 1000L;
        countdown.setVisibility(View.VISIBLE); cancelNext.setVisibility(View.VISIBLE);
        countdown.setText("Следующая серия через " + secondsLeft + " с");
        // Rebuild focus links so the newly visible Cancel button can be reached.
        focusPanel(endPanel);
        nextTick = () -> {
            if (!active || !resumed || !completed || endPanel.getVisibility() != View.VISIBLE) { cancelAutoNext(); return; }
            long remaining = nextDeadline - android.os.SystemClock.uptimeMillis();
            if (remaining <= 0) { openNext(); return; }
            secondsLeft = (int) ((remaining + 999) / 1000);
            countdown.setText("Следующая серия через " + secondsLeft + " с");
            handler.postAtTime(nextTick, Math.min(nextDeadline, android.os.SystemClock.uptimeMillis() + 1000));
        };
        handler.postDelayed(nextTick, 1000);
    }
    private void cancelAutoNext() {
        if (nextTick != null) { handler.removeCallbacks(nextTick); nextTick = null; autoCancelled = true; }
        if (countdown != null) countdown.setVisibility(View.GONE);
        if (cancelNext != null) {
            boolean wasVisible = cancelNext.getVisibility() == View.VISIBLE;
            View focused = getWindow().getDecorView().findFocus(); cancelNext.setVisibility(View.GONE);
            if (wasVisible && endPanel != null && endPanel.getVisibility() == View.VISIBLE) {
                focusPanel(endPanel);
                if (focused != null && focused != cancelNext && focused.getVisibility() == View.VISIBLE) focused.requestFocus();
            }
        }
    }
    public static String time(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
