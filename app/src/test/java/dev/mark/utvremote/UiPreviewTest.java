package dev.mark.utvremote;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Optional native rendering of the actual activities. Skipped in normal builds. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w960dp-h540dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class UiPreviewTest {
    private final Map<String,String> posters = new HashMap<>();
    private File assets, output;
    private JSONArray loadCatalog(Show show) throws Exception {
        JSONArray catalog=new JSONArray(new String(Files.readAllBytes(new File(assets,show.slug+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<catalog.length();i++) {
            JSONObject e=catalog.getJSONObject(i);posters.put(e.getString("u"),e.optString("i"));e.put("i","");
        }
        return catalog;
    }
    private View findShow(View view, Show show) {
        if(view.getTag()==show)return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=findShow(((ViewGroup)view).getChildAt(i),show);if(found!=null)return found;
        }
        return null;
    }
    private Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button)view).getText())) return (Button)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button b=findButton(((ViewGroup)view).getChildAt(i),label); if(b!=null)return b;
        }
        return null;
    }
    private View findCard(View view, int season, int number) {
        if(view.getTag() instanceof CatalogRepository.Episode) {
            CatalogRepository.Episode e=(CatalogRepository.Episode)view.getTag();
            if(e.season==season && e.number==number)return view;
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=findCard(((ViewGroup)view).getChildAt(i),season,number);if(found!=null)return found;
        }
        return null;
    }
    private void fillPosters(View view) throws Exception {
        if(view.getTag() instanceof CatalogRepository.Episode) {
            CatalogRepository.Episode e=(CatalogRepository.Episode)view.getTag();String url=posters.get(e.url);
            if(url!=null && !url.isEmpty()) {
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format("%02x",b));
                Bitmap bitmap=BitmapFactory.decodeFile(new File(assets,name+".png").toString());
                if(bitmap!=null) {
                    View picture=((ViewGroup)view).getChildAt(0);
                    ((ImageView)((ViewGroup)picture).getChildAt(0)).setImageBitmap(bitmap);
                }
            }
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)fillPosters(((ViewGroup)view).getChildAt(i));
    }
    private void draw(View root, String filename) throws Exception {
        int w=RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().widthPixels;
        int h=RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));
        root.layout(0,0,w,h);fillPosters(root);
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));
        try(FileOutputStream stream=new FileOutputStream(new File(output,filename))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream));}
    }
    @Test public void renderNativeActivityStates() throws Exception {
        Assume.assumeTrue("Enable with -Dutv.preview=true and supply preview assets",Boolean.getBoolean("utv.preview"));
        assets=new File(System.getProperty("utv.preview.assets"));output=new File(System.getProperty("utv.preview.output"));
        assertTrue(output.isDirectory() || output.mkdirs());
        RuntimeEnvironment.getApplication().getSharedPreferences("watch",Context.MODE_PRIVATE).edit().clear()
                .putString(Show.MAMA.key("catalog"),loadCatalog(Show.MAMA).toString()).putInt("selectedSeason",6)
                .putString(Show.MAMENKIN.key("catalog"),loadCatalog(Show.MAMENKIN).toString())
                .putInt(Show.MAMENKIN.key("selectedSeason"),2).commit();
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("watch",Context.MODE_PRIVATE);
        JSONArray mama=new JSONArray(prefs.getString("catalog","[]")), synok=new JSONArray(prefs.getString(Show.MAMENKIN.key("catalog"),"[]"));
        String firstMama="", secondMama="", firstSynok="", secondSynok="";
        for(int i=0;i<mama.length();i++) { JSONObject e=mama.getJSONObject(i); if(e.getInt("s")==6 && e.getInt("n")==1)firstMama=e.getString("u");if(e.getInt("s")==6 && e.getInt("n")==2)secondMama=e.getString("u"); }
        for(int i=0;i<synok.length();i++) { JSONObject e=synok.getJSONObject(i); if(e.getInt("s")==2 && e.getInt("n")==1)firstSynok=e.getString("u");if(e.getInt("s")==2 && e.getInt("n")==2)secondSynok=e.getString("u"); }
        prefs.edit().putString("lastUrl",firstMama).putString(Show.MAMENKIN.key("lastUrl"),firstSynok)
                .putLong("position:"+firstMama,1080000).putLong("position:"+firstSynok,600000)
                .putStringSet("newEpisodes",new java.util.HashSet<>(java.util.Collections.singleton(secondMama))).commit();
        new WatchState(prefs).mark(Show.MAMENKIN,secondSynok,true);
        ActivityController<ShowSelectionActivity> home=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
        Intent chosen;
        try {
            View root=home.get().getWindow().getDecorView();View card=findShow(root,Show.MAMENKIN);
            assertNotNull(card);card.requestFocus();draw(root,"utv-shows.png");card.performClick();
            chosen=Shadows.shadowOf(home.get()).getNextStartedActivity();
            assertEquals(Show.MAMENKIN.slug,chosen.getStringExtra("show"));
        } finally {home.pause().stop().destroy();}
        for(Show show:new Show[]{Show.MAMENKIN,Show.MAMA}) {
            Intent intent=show==Show.MAMENKIN?chosen:new Intent(RuntimeEnvironment.getApplication(),MainActivity.class).putExtra("show",show.slug);
            ActivityController<MainActivity> main=Robolectric.buildActivity(MainActivity.class,intent);
            ReflectionHelpers.setField(main.get(),"network",new OfflineExecutor());main.setup().visible();
            try {
            MainActivity activity=main.get();View root=activity.getWindow().getDecorView();
            // Prepare the normal loaded state using a saved, real episode catalogue.
            Button refresh=ReflectionHelpers.getField(activity,"refresh");refresh.setEnabled(true);refresh.setAlpha(1);
            TextView footer=ReflectionHelpers.getField(activity,"footer");footer.setText(MainActivity.NAV_HINT);
            int season=show==Show.MAMA?6:2;
            assertNotNull(findCard(root,season,1));findCard(root,season,1).requestFocus();
            draw(root,"utv-"+show.slug+".png");
            } finally {main.pause().stop().destroy();}
        }

        Intent intent=new Intent(RuntimeEnvironment.getApplication(),PlaybackActivity.class)
                .putExtra("url",new JSONArray(new String(Files.readAllBytes(new File(assets,Show.MAMENKIN.slug+".json").toPath()),java.nio.charset.StandardCharsets.UTF_8)).getJSONObject(0).getString("u"))
                .putExtra("title","Маменькин сынок · 1 сезон · 1 серия");
        ActivityController<SettingsActivity> settings=Robolectric.buildActivity(SettingsActivity.class).setup().visible();
        try { draw(settings.get().getWindow().getDecorView(),"utv-settings.png"); }
        finally {settings.pause().stop().destroy();}
        ActivityController<PlaybackActivity> playback=Robolectric.buildActivity(PlaybackActivity.class,intent);
        ReflectionHelpers.setField(playback.get(),"network",new OfflineExecutor());playback.create().start().resume().visible();
        try {
            PlaybackActivity activity=playback.get();View root=activity.getWindow().getDecorView();
            draw(root,"utv-mamenkin-player-loading.png");
            ReflectionHelpers.callInstanceMethod(activity,"showError",ReflectionHelpers.ClassParameter.from(String.class,
                    "Не удалось воспроизвести видео.\nПроверь интернет или попробуй другой выпуск."));
            draw(root,"utv-player-error.png");
            ReflectionHelpers.callInstanceMethod(activity,"handlePlaybackState",
                    ReflectionHelpers.ClassParameter.from(int.class,androidx.media3.common.Player.STATE_ENDED));
            draw(root,"utv-player-completed.png");
            prefs.edit().putBoolean("autoNext",true).putInt("nextDelay",10).commit();
            ReflectionHelpers.callInstanceMethod(activity,"maybeAutoNext");draw(root,"utv-player-autoplay.png");
        }
        finally {playback.pause().stop().destroy();}
    }
}
