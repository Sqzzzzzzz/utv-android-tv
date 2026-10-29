package dev.mark.utvremote;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w960dp-h540dp-land-mdpi")
public final class TvNavigationTest {
    private ActivityController<MainActivity> create() throws Exception {
        return create(Show.MAMA);
    }
    private String fixture(String name) throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private ActivityController<MainActivity> create(Show show) throws Exception {
        String json;
        try (java.io.InputStream in = getClass().getResourceAsStream("/catalog.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("watch", Context.MODE_PRIVATE)
                .edit().clear().putString("catalog", json).putInt("selectedSeason", 6)
                .putString(Show.MAMENKIN.key("catalog"), fixture("mamenkin-catalog.json"))
                .putInt(Show.MAMENKIN.key("selectedSeason"), 2).commit();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), MainActivity.class).putExtra("show", show.slug);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent);
        ReflectionHelpers.setField(controller.get(), "network", new OfflineExecutor());
        controller.setup().visible();
        View decor = controller.get().getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 960, 540);
        return controller;
    }
    private List<View> cards(View v) {
        List<View> result = new ArrayList<>();
        if (v.getTag() instanceof CatalogRepository.Episode) result.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(cards(group.getChildAt(i)));
        }
        return result;
    }
    private Button button(View v, String text) {
        if (v instanceof Button && text.contentEquals(((Button) v).getText())) return (Button) v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = button(group.getChildAt(i), text); if (found != null) return found;
            }
        }
        return null;
    }
    @Test public void remoteFocusMovesAcrossRowsAndReturnsToSeasonWithoutEscapingGrid() throws Exception {
        ActivityController<MainActivity> controller = create();
        try {
            List<View> items = cards(controller.get().getWindow().getDecorView());
            assertEquals(8, items.size());
            assertSame(items.get(1), items.get(0).focusSearch(View.FOCUS_RIGHT));
            assertSame(items.get(4), items.get(1).focusSearch(View.FOCUS_DOWN));
            assertSame(items.get(1), items.get(4).focusSearch(View.FOCUS_UP));
            assertSame(items.get(0), items.get(0).focusSearch(View.FOCUS_LEFT));
            assertEquals("6 сезон", ((Button) items.get(0).focusSearch(View.FOCUS_UP)).getText().toString());
            View season = items.get(0).focusSearch(View.FOCUS_UP);
            assertEquals("5 сезон", ((Button) season.focusSearch(View.FOCUS_LEFT)).getText().toString());
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void choosingSeasonReplacesEpisodesAndFocusesFirstCard() throws Exception {
        ActivityController<MainActivity> controller = create();
        try {
            View root = controller.get().getWindow().getDecorView();
            Button season = button(root, "5 сезон"); assertNotNull(season); season.performClick();
            List<View> items = cards(root); assertEquals(10, items.size());
            CatalogRepository.Episode e = (CatalogRepository.Episode) items.get(0).getTag();
            assertEquals(5, e.season); assertEquals(1, e.number);
            assertTrue(items.get(0).isFocused());
            assertSame(season, items.get(0).focusSearch(View.FOCUS_UP));
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void refreshingCatalogKeepsTheFocusedEpisode() throws Exception {
        ActivityController<MainActivity> controller = create();
        try {
            View root = controller.get().getWindow().getDecorView();
            List<View> before = cards(root); assertTrue(before.get(4).requestFocus());
            assertSame(before.get(4), root.findFocus());
            String url = ((CatalogRepository.Episode) before.get(4).getTag()).url;
            ReflectionHelpers.callInstanceMethod(controller.get(), "renderCatalog");
            root.measure(View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 960, 540);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(root.findFocus());
            assertEquals(url, ((CatalogRepository.Episode) root.findFocus().getTag()).url);
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void downFromThirdColumnCanReachAnIncompleteLastRow() throws Exception {
        ActivityController<MainActivity> controller = create();
        try {
            List<View> items = cards(controller.get().getWindow().getDecorView());
            assertSame(items.get(7), items.get(5).focusSearch(View.FOCUS_DOWN));
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void secondShowKeepsItsSeasonAndResumeHistorySeparate() throws Exception {
        ActivityController<MainActivity> controller = create(Show.MAMENKIN);
        try {
            MainActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            List<View> items = cards(root); assertEquals(9, items.size());
            CatalogRepository.Episode episode = (CatalogRepository.Episode) items.get(0).getTag();
            assertEquals(Show.MAMENKIN, Show.fromUrl(episode.url));
            View season = items.get(0).focusSearch(View.FOCUS_UP);
            assertEquals("1 сезон", ((Button) season.focusSearch(View.FOCUS_LEFT)).getText().toString());
            String oldUrl = "https://www.u-tv.ru/shows/mama-v-16/episodes/1900566/";
            android.content.SharedPreferences prefs = activity.getSharedPreferences("watch", Context.MODE_PRIVATE);
            prefs.edit().putString("lastUrl", oldUrl).putLong("position:"+oldUrl, 120000)
                    .putString(Show.MAMENKIN.key("lastUrl"), episode.url).putLong("position:"+episode.url, 210000).commit();
            controller.pause().resume();
            Button resume = button(root, "Продолжить · 3:30"); assertNotNull(resume); resume.performClick();
            Intent playback = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(episode.url, playback.getStringExtra("url"));
            assertTrue(playback.getStringExtra("title").startsWith(Show.MAMENKIN.title));
            assertEquals(oldUrl, prefs.getString("lastUrl", ""));
            button(root, "1 сезон").performClick(); assertEquals(12, cards(root).size());
        } finally { controller.pause().stop().destroy(); }
    }
    @Test public void homeOffersBothShowsAndLaunchesTheChosenOneWithTheRemote() {
        RuntimeEnvironment.getApplication().getSharedPreferences("watch",0).edit().putBoolean("autoCheckUpdates",false).commit();
        ActivityController<ShowSelectionActivity> controller = Robolectric.buildActivity(ShowSelectionActivity.class).setup().visible();
        try {
            ShowSelectionActivity activity = controller.get(); View root = activity.getWindow().getDecorView();
            List<View> shows = new ArrayList<>(); collectShows(root, shows); assertEquals(2, shows.size());
            assertSame(shows.get(1), shows.get(0).focusSearch(View.FOCUS_RIGHT));
            shows.get(1).performClick(); Intent opened = Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(), opened.getComponent().getClassName());
            assertEquals(Show.MAMENKIN.slug, opened.getStringExtra("show"));
        } finally { controller.pause().stop().destroy(); }
    }
    private void collectShows(View view, List<View> result) {
        if (view.getTag() instanceof Show) result.add(view);
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++)
            collectShows(((ViewGroup)view).getChildAt(i), result);
    }
    @Test public void nextEpisodeStaysInTheCurrentShowWhenReturningFromAResume() throws Exception {
        org.json.JSONArray catalog = new org.json.JSONArray(fixture("mamenkin-catalog.json"));
        String first = catalog.getJSONObject(0).getString("u"), second = catalog.getJSONObject(1).getString("u");
        RuntimeEnvironment.getApplication().getSharedPreferences("watch", Context.MODE_PRIVATE).edit().clear()
                .putString(Show.MAMENKIN.key("catalog"), catalog.toString()).commit();
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), PlaybackActivity.class).putExtra("url", first)
                .putExtra("nextUrl", "https://www.u-tv.ru/shows/mama-v-16/episodes/1900566/");
        ActivityController<PlaybackActivity> controller = Robolectric.buildActivity(PlaybackActivity.class, intent).create();
        try {
            assertEquals(Show.MAMENKIN, ReflectionHelpers.getField(controller.get(), "show"));
            assertEquals(second, ReflectionHelpers.getField(controller.get(), "nextUrl"));
            assertTrue(((String) ReflectionHelpers.getField(controller.get(), "nextTitle")).startsWith(Show.MAMENKIN.title));
        } finally { controller.destroy(); }
    }
}
