package dev.mark.utvremote;
import android.app.Activity;
import android.content.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w960dp-h540dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class CinematicHomeTest {
 private android.content.SharedPreferences prefs(){return RuntimeEnvironment.getApplication().getSharedPreferences("watch",0);}
 private void key(Activity activity,int code){activity.dispatchKeyEvent(new android.view.KeyEvent(0,code));activity.dispatchKeyEvent(new android.view.KeyEvent(1,code));}
 @Before public void clear(){prefs().edit().clear().commit();}
 @Test public void focusUpdatesHeroAndResumeBelongsToSelectedShow() throws Exception {
  String mama=Show.MAMA.url()+"episodes/1/",synok=Show.MAMENKIN.url()+"episodes/2/";
  prefs().edit().putString("lastUrl",mama).putString(Show.MAMENKIN.key("lastUrl"),synok).putLong("position:"+synok,42000).commit();
  ActivityController<ShowSelectionActivity> c=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
  try{
   List<View> cards=ReflectionHelpers.getField(c.get(),"cards");assertEquals(2,cards.size());
   key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(Show.MAMENKIN,ReflectionHelpers.getField(c.get(),"selected"));
   TextView title=ReflectionHelpers.getField(c.get(),"title");assertEquals(Show.MAMENKIN.title,title.getText().toString());
   Button primary=ReflectionHelpers.getField(c.get(),"primary");assertSame(cards.get(1),primary.focusSearch(View.FOCUS_DOWN));
   key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_UP);assertTrue(primary.isFocused());key(c.get(),android.view.KeyEvent.KEYCODE_DPAD_CENTER);Intent intent=Shadows.shadowOf(c.get()).getNextStartedActivity();assertEquals(synok,intent.getStringExtra("url"));
   assertEquals(Show.MAMENKIN.slug,prefs().getString("selectedShow",""));
  }finally{c.pause().stop().destroy();}
 }
 @Test public void noHistoryOpensCatalogAndLayoutFitsTv() throws Exception {
  ActivityController<ShowSelectionActivity> c=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
  try{
   View root=c.get().getWindow().getDecorView();root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY));root.layout(0,0,960,540);
   Button primary=ReflectionHelpers.getField(c.get(),"primary");primary.requestFocus();assertTrue(primary.getText().toString().contains("Смотреть"));
   List<View> cards=ReflectionHelpers.getField(c.get(),"cards");
   for(View card:cards){int[] xy=new int[2];card.getLocationOnScreen(xy);assertTrue(xy[0]>=0);assertTrue(xy[1]>=0);assertTrue(xy[0]+card.getWidth()<=960);assertTrue(xy[1]+card.getHeight()<=540);}
   if(System.getProperty("utv.preview.output")!=null){
    android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(960,540,android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(bitmap));
    java.io.File dir=new java.io.File(System.getProperty("utv.preview.output"));dir.mkdirs();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,"cinema-home.png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
   }
   primary.performClick();Intent intent=Shadows.shadowOf(c.get()).getNextStartedActivity();assertEquals(MainActivity.class.getName(),intent.getComponent().getClassName());assertEquals(Show.MAMA.slug,intent.getStringExtra("show"));
  }finally{c.pause().stop().destroy();}
 }
 @Test public void returnRefreshesResumeWithoutMovingFocus() {
  ActivityController<ShowSelectionActivity> c=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
  try{
   Button primary=ReflectionHelpers.getField(c.get(),"primary");primary.requestFocus();c.pause();prefs().edit().putString("lastUrl",Show.MAMA.url()+"episodes/1/").putLong("position:"+Show.MAMA.url()+"episodes/1/",12000).commit();c.resume();
   assertTrue(primary.isFocused());assertTrue(primary.getText().toString().contains("Продолжить"));
  }finally{c.pause().stop().destroy();}
 }
 @Test @Config(sdk=31) public void homeInterruptsPendingOkWithoutLaunchingOnReturn() {
  ActivityController<ShowSelectionActivity> c=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
  try{
   c.get().dispatchKeyEvent(new android.view.KeyEvent(0,android.view.KeyEvent.KEYCODE_DPAD_CENTER));c.pause();c.resume();
   c.get().dispatchKeyEvent(new android.view.KeyEvent(1,android.view.KeyEvent.KEYCODE_DPAD_CENTER));
   assertNull(Shadows.shadowOf(c.get()).getNextStartedActivity());
  }finally{c.pause().stop().destroy();}
 }

}
