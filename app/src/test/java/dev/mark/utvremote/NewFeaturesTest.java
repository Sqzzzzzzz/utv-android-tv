package dev.mark.utvremote;

import android.content.*;
import android.content.pm.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w960dp-h540dp-land-mdpi")
public final class NewFeaturesTest {
    private SharedPreferences prefs;
    private WatchState watch;
    private static final String ONE = Show.MAMA.url()+"episodes/1/", TWO=Show.MAMA.url()+"episodes/2/";
    @Before public void setup() { prefs=RuntimeEnvironment.getApplication().getSharedPreferences("watch",0);prefs.edit().clear().commit();watch=new WatchState(prefs); }
    private CatalogRepository.Episode episode(String url) { return new CatalogRepository.Episode(1,1,url,"","60 мин",""); }
    private String catalog() { return "[{\"s\":1,\"n\":1,\"u\":\""+ONE+"\"},{\"s\":1,\"n\":2,\"u\":\""+TWO+"\"}]"; }
    @Test public void firstCatalogIsBaselineAndNewEpisodesStayNewUntilPlayback() {
        watch.catalogUpdated(Show.MAMA,Arrays.asList(episode(ONE))); assertFalse(watch.isNew(Show.MAMA,ONE));
        watch.catalogUpdated(Show.MAMA,Arrays.asList(episode(ONE),episode(TWO)));assertTrue(watch.isNew(Show.MAMA,TWO));
        watch.catalogUpdated(Show.MAMA,Arrays.asList(episode(ONE),episode(TWO)));assertTrue(watch.isNew(Show.MAMA,TWO));
        watch.opened(Show.MAMA,TWO);assertFalse(watch.isNew(Show.MAMA,TWO));
        watch.catalogUpdated(Show.MAMA,Collections.singletonList(episode(ONE)));
        watch.catalogUpdated(Show.MAMA,Arrays.asList(episode(ONE),episode(TWO)));assertFalse(watch.isNew(Show.MAMA,TWO));
    }
    @Test public void updatingOldApkUsesExistingCachedEpisodesAsBaseline() {
        prefs.edit().putString("catalog","[{\"u\":\""+ONE+"\"}]").commit();
        watch.catalogUpdated(Show.MAMA,Arrays.asList(episode(ONE),episode(TWO)));
        assertFalse(watch.isNew(Show.MAMA,ONE));assertTrue(watch.isNew(Show.MAMA,TWO));
        assertFalse(watch.isNew(Show.MAMENKIN,TWO));
    }
    @Test public void watchedMarksCanBeRemovedAndActualDurationDrivesProgress() {
        prefs.edit().putLong("position:"+ONE,1800000).putLong("duration:"+ONE,3600000).commit();
        assertEquals(500,watch.progress(ONE,"120 мин"));
        watch.mark(Show.MAMA,ONE,true);assertTrue(watch.watched(ONE));assertEquals(1000,watch.progress(ONE,"60 мин"));
        assertEquals(0,prefs.getLong("position:"+ONE,-1));watch.mark(Show.MAMA,ONE,false);
        assertFalse(watch.watched(ONE));assertEquals(0,watch.progress(ONE,"60 мин"));
    }
    @Test public void resumeMovesToUnwatchedNextEpisodeAndDisappearsAfterLast() {
        prefs.edit().putString("catalog",catalog()).putString("lastUrl",ONE).putLong("position:"+ONE,12000).commit();
        assertEquals(ONE,watch.resumeUrl(Show.MAMA));watch.mark(Show.MAMA,ONE,true);assertEquals(TWO,watch.resumeUrl(Show.MAMA));
        watch.mark(Show.MAMA,TWO,true);assertEquals("",watch.resumeUrl(Show.MAMA));assertEquals("",watch.resumeUrl(Show.MAMENKIN));
    }
    @Test public void homeResumeStartsPlaybackWithoutOpeningTheCatalog() {
        prefs.edit().putString("catalog",catalog()).putString("lastUrl",ONE).putLong("position:"+ONE,120000).commit();
        ActivityController<ShowSelectionActivity> controller=Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
        try {
            View root=controller.get().getWindow().getDecorView();View resume=root.findViewWithTag("resume:"+Show.MAMA.slug);
            assertEquals(View.VISIBLE,resume.getVisibility());resume.performClick();
            Intent intent=Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(PlaybackActivity.class.getName(),intent.getComponent().getClassName());assertEquals(ONE,intent.getStringExtra("url"));
        } finally {controller.pause().stop().destroy();}
    }
    @Test public void qualitySelectsExactSupportedTrackAndFallsBackToNearestLower() {
        TrackGroup group=new TrackGroup("video",new Format.Builder().setSampleMimeType("video/avc").setHeight(1080).build(),
                new Format.Builder().setSampleMimeType("video/avc").setHeight(720).build(),new Format.Builder().setSampleMimeType("video/avc").setHeight(468).build());
        Tracks tracks=new Tracks(Collections.singletonList(new Tracks.Group(group,true,new int[]{C.FORMAT_HANDLED,C.FORMAT_HANDLED,C.FORMAT_HANDLED},new boolean[]{false,true,false})));
        TrackSelectionParameters base=new TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build();
        TrackSelectionParameters chosen=VideoQuality.parameters(base,tracks,720);
        assertEquals(Integer.valueOf(1),chosen.overrides.get(group).trackIndices.get(0));
        assertEquals(Integer.valueOf(2),VideoQuality.parameters(chosen,tracks,480).overrides.get(group).trackIndices.get(0));
        assertTrue(VideoQuality.parameters(chosen,tracks,0).overrides.isEmpty());
        assertEquals(new TreeSet<>(Arrays.asList(0,468,720,1080)),VideoQuality.available(tracks));
        assertEquals(468,VideoQuality.effective(tracks,480));assertEquals(0,VideoQuality.effective(tracks,0));
    }
    @Test public void unsupportedQualityIsNotOfferedAndMissingLowQualityUsesLowestSupported() {
        TrackGroup group=new TrackGroup("video",new Format.Builder().setSampleMimeType("video/avc").setHeight(1080).build(),new Format.Builder().setSampleMimeType("video/avc").setHeight(720).build());
        Tracks tracks=new Tracks(Collections.singletonList(new Tracks.Group(group,false,new int[]{C.FORMAT_UNSUPPORTED_TYPE,C.FORMAT_HANDLED},new boolean[]{false,true})));
        assertEquals(new TreeSet<>(Arrays.asList(0,720)),VideoQuality.available(tracks));
        TrackSelectionParameters base=new TrackSelectionParameters.Builder(RuntimeEnvironment.getApplication()).build();
        assertEquals(Integer.valueOf(1),VideoQuality.parameters(base,tracks,240).overrides.get(group).trackIndices.get(0));
    }
    private String manifest(String url,String hash) { return "{\"versionCode\":9,\"versionName\":\"0.2.1\",\"apkUrl\":\""+url+"\",\"sha256\":\""+hash+"\"}"; }
    @Test public void updateManifestRequiresHttpsAndIntegrityHash() throws Exception {
        String hash=String.join("",Collections.nCopies(64,"a"));
        assertEquals(9,UpdateRepository.parse(manifest("https://example.com/update.apk",hash)).code);
        for(String url:Arrays.asList("http://example.com/a.apk","https://user:password@example.com/a.apk","file:///a.apk")) {
            try {UpdateRepository.parse(manifest(url,hash));fail("Unsafe update URL");}catch(IOException expected) { }
        }
        try {UpdateRepository.parse(manifest("https://example.com/a.apk","invalid"));fail();}catch(IOException expected) { }
    }
    @Test public void downloadCopyRejectsOversizedAndEmptyFilesAndHashIsDeterministic() throws Exception {
        for(byte[] bytes:new byte[][]{new byte[0],new byte[11]}) {
            try {UpdateRepository.copy(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),10);fail();}catch(IOException expected) { }
        }
        File file=File.createTempFile("utv-hash", ".apk");
        try {try(FileOutputStream out=new FileOutputStream(file)){out.write("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",UpdateRepository.digest(file));
        } finally {file.delete();}
    }
    @Test public void settingsPersistAutoplayAndFilePickerUsesApkMime() {
        ActivityController<SettingsActivity> controller=Robolectric.buildActivity(SettingsActivity.class).setup().visible();
        try {
            View root=controller.get().getWindow().getDecorView();find(root,"Следующая серия · по кнопке").performClick();assertTrue(prefs.getBoolean("autoNext",false));
            find(root,"Установить обновление из APK").performClick();
            Intent intent=Shadows.shadowOf(controller.get()).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,intent.getAction());assertEquals("application/vnd.android.package-archive",intent.getType());
        } finally {controller.pause().stop().destroy();}
    }
    private Button find(View view,String label) {
        if(view instanceof Button && label.contentEquals(((Button)view).getText()))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button b=find(((ViewGroup)view).getChildAt(i),label);if(b!=null)return b;}return null;
    }
    @Test public void updaterRejectsDowngradesWrongPackageAndWrongSigningKey() throws Exception {
        ActivityController<SettingsActivity> controller=Robolectric.buildActivity(SettingsActivity.class).setup();
        try {
            SettingsActivity activity=controller.get();PackageManager pm=activity.getPackageManager();
            PackageInfo installed=pm.getPackageInfo(activity.getPackageName(),0);installed.versionCode=8;installed.signingInfo=null;
            installed.signatures=new Signature[]{new Signature("1234")};Shadows.shadowOf(pm).installPackage(installed);
            File file=new File(activity.getCacheDir(),"test.apk");PackageInfo candidate=new PackageInfo();candidate.packageName=activity.getPackageName();
            candidate.versionCode=9;candidate.signatures=new Signature[]{new Signature("1234")};
            Shadows.shadowOf(pm).setPackageArchiveInfo(file.getPath(),candidate);activity.validate(file,9);
            candidate.versionCode=8;assertInvalid(activity,file);candidate.versionCode=9;
            candidate.packageName="other.app";assertInvalid(activity,file);candidate.packageName=activity.getPackageName();
            candidate.signatures=new Signature[]{new Signature("5678")};assertInvalid(activity,file);
        } finally {controller.pause().stop().destroy();}
    }
    private void assertInvalid(SettingsActivity a,File f) throws Exception {try{a.validate(f,0);fail("Invalid APK accepted");}catch(IOException expected){}}
    @Test public void episodeMenuMarksAndUnmarksWithoutStartingPlayback() {
        prefs.edit().putString("catalog",catalog()).commit();
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class);
        org.robolectric.util.ReflectionHelpers.setField(c.get(),"network",new OfflineExecutor());c.setup().visible();
        try {
            LinearLayout grid=org.robolectric.util.ReflectionHelpers.getField(c.get(),"grid");View card=((ViewGroup)grid.getChildAt(0)).getChildAt(0);
            assertTrue(card.performLongClick());android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            dialog.getListView().performItemClick(null,1,1);assertTrue(watch.watched(ONE));
            assertTrue(((TextView)((ViewGroup)card).getChildAt(2)).getText().toString().contains("Просмотрено"));
            card.performLongClick();org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null,1,1);
            assertFalse(watch.watched(ONE));assertNull(Shadows.shadowOf(c.get()).peekNextStartedActivity());
        } finally {c.pause().stop().destroy();}
    }
    @Test public void updateProviderSharesOnlyTheUpdateCache() throws Exception {
        Context app=RuntimeEnvironment.getApplication();File dir=new File(app.getCacheDir(),"updates");dir.mkdirs();File apk=new File(dir,"pending.apk");
        try {
            try(OutputStream out=new FileOutputStream(apk)){out.write(new byte[]{1,2,3});}
            android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(app,app.getPackageName()+".updates",apk);
            assertEquals("content",uri.getScheme());
            try(InputStream in=app.getContentResolver().openInputStream(uri)){assertEquals(1,in.read());}
            try {androidx.core.content.FileProvider.getUriForFile(app,app.getPackageName()+".updates",new File(app.getFilesDir(),"watch"));fail("Private data exposed");}
            catch(IllegalArgumentException expected) { }
        } finally {apk.delete();}
    }

}
