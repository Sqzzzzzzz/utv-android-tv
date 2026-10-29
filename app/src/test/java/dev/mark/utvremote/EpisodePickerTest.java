package dev.mark.utvremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class EpisodePickerTest {
    private ActivityController<Activity> host;
    private EpisodePicker picker;
    private final List<String> opened=new ArrayList<>();
    private String url(int n){return Show.MAMA.url()+"episodes/"+n+"/";}
    @Before public void setup() throws Exception {
        host=Robolectric.buildActivity(Activity.class).setup().visible();
        org.json.JSONArray list=new org.json.JSONArray();
        for(int s:new int[]{6,1,3})for(int n:new int[]{3,1,2})list.put(new org.json.JSONObject().put("u",url(s*10+n)).put("s",s).put("n",n).put("d","67 мин"));
        list.put(new org.json.JSONObject().put("u",Show.MAMENKIN.url()+"episodes/1/").put("s",6).put("n",4));
        list.put(new org.json.JSONObject().put("u",url(61)).put("s",6).put("n",1));
        SharedPreferences prefs=host.get().getSharedPreferences("watch",0);prefs.edit().clear().putString("catalog",list.toString()).commit();
        picker=new EpisodePicker(host.get(),Show.MAMA,url(62),new WatchState(prefs),opened::add);picker.show();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    @After public void cleanup(){picker.dismiss();host.pause().stop().destroy();}
    @Test public void seasonsAscendCurrentFocusedAndSwitchingDoesNotPlay() {
        Map<Integer,Button> tabs=ReflectionHelpers.getField(picker,"tabs");assertEquals(Arrays.asList(1,3,6),new ArrayList<>(tabs.keySet()));
        LinearLayout cards=ReflectionHelpers.getField(picker,"cards");assertEquals(3,cards.getChildCount());
        assertEquals("episode:"+url(62),picker.getWindow().getDecorView().findFocus().getTag());
        tabs.get(1).performClick();assertEquals(3,cards.getChildCount());assertEquals("episode:"+url(11),cards.getChildAt(0).getTag());
        assertTrue(opened.isEmpty());cards.getChildAt(2).performClick();assertEquals(Collections.singletonList(url(13)),opened);assertFalse(picker.isShowing());
    }
    @Test public void currentEpisodeClosesWithoutRestartingPlayback() {
        View current=picker.getWindow().getDecorView().findViewWithTag("episode:"+url(62));current.performClick();assertTrue(opened.isEmpty());assertFalse(picker.isShowing());
    }
    @Test public void remoteFocusAndPanelFitTv() throws Exception {
        View root=picker.getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(346,View.MeasureSpec.EXACTLY));root.layout(0,0,960,346);
        View card=root.findFocus();assertEquals("episode:"+url(63),card.focusSearch(View.FOCUS_RIGHT).getTag());
        View tab=card.focusSearch(View.FOCUS_UP);assertEquals("season:6",tab.getTag());assertSame(card,tab.focusSearch(View.FOCUS_DOWN));
        if(System.getProperty("utv.preview.output")!=null){
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(960,346,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bitmap));
            java.io.File dir=new java.io.File(System.getProperty("utv.preview.output"));dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"episode-picker.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        }
    }
}
