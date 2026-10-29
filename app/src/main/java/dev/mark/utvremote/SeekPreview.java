package dev.mark.utvremote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import java.util.Collections;

/** A silent, short-lived video decoder: actual HD frames, never enlarged tiny sprites. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SeekPreview {
    final FrameLayout view;
    private final TextView label,caption;
    private final FrameLayout frame;
    private final View pointer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private TextureView texture;
    private String stream, referer;
    private long target, renderedTarget;
    private boolean failed, hd;
    private final Runnable request = () -> render();
    private final Runnable timeout = () -> unavailable();

    SeekPreview(Context context) {
        view=new FrameLayout(context);view.setVisibility(View.GONE);
        frame=new FrameLayout(context);frame.setBackground(TvUi.shape(context,0xFF181821,0xFFFFFFFF));frame.setClipToOutline(true);
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(-1,-1);box.bottomMargin=TvUi.dp(context,8);view.addView(frame,box);
        label=TvUi.text(context,"",12,TvUi.TEXT);label.setGravity(Gravity.CENTER);label.setPadding(TvUi.dp(context,8),0,TvUi.dp(context,8),0);
        frame.addView(label,new FrameLayout.LayoutParams(-1,-1));
        caption=TvUi.text(context,"",13,TvUi.TEXT);TvUi.bold(caption);caption.setGravity(Gravity.CENTER);caption.setBackgroundColor(0xCC000000);
        FrameLayout.LayoutParams text=new FrameLayout.LayoutParams(-1,TvUi.dp(context,24),Gravity.BOTTOM);text.setMargins(TvUi.dp(context,2),0,TvUi.dp(context,2),TvUi.dp(context,2));frame.addView(caption,text);
        pointer=new View(context){
            private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Path path=new android.graphics.Path();
            @Override protected void onDraw(android.graphics.Canvas c){paint.setColor(android.graphics.Color.WHITE);android.graphics.Path p=path;p.reset();p.moveTo(0,0);p.lineTo(getWidth(),0);p.lineTo(getWidth()/2f,getHeight());p.close();c.drawPath(p,paint);}
        };
        view.addView(pointer,new FrameLayout.LayoutParams(TvUi.dp(context,16),TvUi.dp(context,8),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
    }
    void positionAt(float marker,int timelineY,int parentWidth){
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)view.getLayoutParams();
        int margin=TvUi.dp(view.getContext(),28),gap=TvUi.dp(view.getContext(),18);
        int left=Math.max(margin,Math.min(Math.max(margin,parentWidth-margin-lp.width),Math.round(marker-lp.width/2f)));
        int top=Math.max(TvUi.dp(view.getContext(),88),timelineY-gap-lp.height);
        if(lp.leftMargin!=left||lp.topMargin!=top){lp.leftMargin=left;lp.topMargin=top;view.setLayoutParams(lp);}
        pointer.setTranslationX(Math.max(-lp.width/2f+TvUi.dp(view.getContext(),12),Math.min(lp.width/2f-TvUi.dp(view.getContext(),12),marker-left-lp.width/2f)));
    }
    void setSource(String url, String page) { close(); stream = url; referer = page; failed = false; }
    void show(long position) {
        target = position; view.setVisibility(View.VISIBLE); caption.setText(PlaybackActivity.time(target));
        if (texture != null) texture.setAlpha(0);
        label.setVisibility(View.VISIBLE);
        label.setText(failed ? "Кадр недоступен · " + PlaybackActivity.time(target) : "Загружаем кадр · " + PlaybackActivity.time(target));
        handler.removeCallbacks(request); handler.removeCallbacks(timeout);
        if (!failed && stream != null) handler.postDelayed(request, 400);
    }
    private void render() {
        if (stream == null || failed) return;
        try {
            renderedTarget = target;
            if (player == null) {
                DefaultTrackSelector selector = new DefaultTrackSelector(view.getContext());
                selector.setParameters(selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMinVideoSize(1280, 720).setMaxVideoSize(1280, 720)
                        .setExceedVideoConstraintsIfNecessary(false).setExceedRendererCapabilitiesIfNecessary(false)
                        .setForceHighestSupportedBitrate(true));
                player = new ExoPlayer.Builder(view.getContext()).setTrackSelector(selector)
                        .setLoadControl(new DefaultLoadControl.Builder().setBufferDurationsMs(1000, 3000, 250, 500).build()).build();
                player.setPlayWhenReady(false); player.setVolume(0);
                player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC);
                ExoPlayer created = player;
                player.addListener(new Player.Listener() {
                    @Override public void onVideoSizeChanged(VideoSize size) {
                        if (player != created) return;
                        hd = size.width >= 1280 && size.height >= 720;
                        if (!hd) unavailable();
                    }
                    @Override public void onRenderedFirstFrame() {
                        if (player == created && texture != null && hd && renderedTarget == target && view.getVisibility() == View.VISIBLE) {
                            texture.setAlpha(1); label.setVisibility(View.GONE); handler.removeCallbacks(timeout);
                        }
                    }
                    @Override public void onPlayerError(PlaybackException error) { if (player == created) unavailable(); }
                });
                DefaultHttpDataSource.Factory data = new DefaultHttpDataSource.Factory().setUserAgent(SiteHttp.USER_AGENT)
                        .setConnectTimeoutMs(8000).setReadTimeoutMs(8000)
                        .setDefaultRequestProperties(Collections.singletonMap("Referer", referer));
                player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(new MediaItem.Builder()
                        .setUri(stream).setMimeType(MimeTypes.APPLICATION_M3U8).build()));
                player.seekTo(target); player.prepare();
            } else player.seekTo(target);
            if (texture != null) { player.clearVideoTextureView(texture); frame.removeView(texture); }
            texture = new TextureView(view.getContext()); texture.setAlpha(0);
            FrameLayout.LayoutParams image=new FrameLayout.LayoutParams(-1,-1);image.setMargins(TvUi.dp(view.getContext(),2),TvUi.dp(view.getContext(),2),TvUi.dp(view.getContext(),2),TvUi.dp(view.getContext(),2));
            frame.addView(texture, 0, image); player.setVideoTextureView(texture);
            handler.postDelayed(timeout, 8000);
        } catch (RuntimeException error) { unavailable(); }
    }
    private void unavailable() {
        failed = true; release();
        label.setVisibility(View.VISIBLE); label.setText("Кадр недоступен · " + PlaybackActivity.time(target));
    }
    private void release() {
        handler.removeCallbacks(request); handler.removeCallbacks(timeout);
        if (player != null) { ExoPlayer previous = player; player = null; previous.release(); }
        if (texture != null) { frame.removeView(texture); texture = null; }
        hd = false;
    }
    void close() { release(); failed=false; view.setVisibility(View.GONE); }
}
