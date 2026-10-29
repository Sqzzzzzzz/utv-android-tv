package dev.mark.utvremote;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    static final String NAV_HINT = "OK — смотреть    Удержать OK — отметка    Назад — шоу";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final CatalogRepository repository = new CatalogRepository();
    private final PosterLoader posters = new PosterLoader();
    private final List<CatalogRepository.Episode> episodes = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private SharedPreferences prefs;
    private WatchState watch;
    private Show show;
    private LinearLayout seasons, grid;
    private TextView subtitle, footer;
    private Button resume, refresh;
    private ScrollView scroll;
    private int selectedSeason, seasonFocusId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TvUi.fullscreen(this);
        prefs = getSharedPreferences("watch", MODE_PRIVATE);
        watch = new WatchState(prefs);
        show = Show.fromSlug(getIntent().getStringExtra("show"));
        if (show == null) show = Show.MAMA;
        selectedSeason = prefs.getInt(show.key("selectedSeason"), 0);
        buildScreen(); restoreCatalog(); loadCatalog();
    }
    private int dp(float v) { return TvUi.dp(this, v); }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v;
    }
    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    private void buildScreen() {
        LinearLayout root = column(); root.setBackgroundColor(TvUi.BG);
        root.setPadding(dp(32), dp(16), dp(32), dp(12));
        root.setClipChildren(true); root.setClipToPadding(true);
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.brand_mark);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER); mark.setContentDescription("Ю");
        header.addView(mark, new LinearLayout.LayoutParams(dp(53), dp(34)));
        TextView brand = TvUi.text(this, "", 21, TvUi.TEXT); TvUi.bold(brand);
        brand.setPadding(dp(12), 0, 0, 0);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        Button nav = TvUi.button(this, "Настройки");
        nav.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(nav, new LinearLayout.LayoutParams(dp(148), dp(38)));
        header.addView(TvUi.text(this, "u-tv.ru", 13, TvUi.MUTED));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(40)));
        View divider = new View(this); divider.setBackgroundColor(TvUi.PANEL);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout hero = row(); hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.graphics.drawable.GradientDrawable heroBackground = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{TvUi.PANEL, 0xFF20202C});
        heroBackground.setCornerRadius(dp(10)); hero.setBackground(heroBackground);
        View accent = new View(this); accent.setBackground(TvUi.gradient(this, false));
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(dp(3), dp(66));
        accentLp.rightMargin = dp(14); hero.addView(accent, accentLp);
        LinearLayout info = column();
        TextView genre = TvUi.text(this, "РЕАЛИТИ  ·  16+", 11, TvUi.ACCENT); TvUi.bold(genre); info.addView(genre);
        TextView title = TvUi.text(this, show.title, 36, TvUi.TEXT);
        TvUi.bold(title); info.addView(title);
        subtitle = TvUi.text(this, "Загружаем сезоны и выпуски…", 14, TvUi.MUTED);
        info.addView(subtitle); hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout actions = column();
        resume = TvUi.button(this, "Продолжить просмотр");
        TvUi.primary(resume);
        resume.setOnClickListener(v -> resumeLast());
        actions.addView(resume, new LinearLayout.LayoutParams(dp(218), dp(46)));
        refresh = TvUi.button(this, "Обновить каталог");
        refresh.setOnClickListener(v -> loadCatalog());
        refresh.setOnLongClickListener(v -> { NetworkDiagnostics.show(this); return true; });
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(218), dp(40));
        refreshLp.topMargin = dp(6); actions.addView(refresh, refreshLp); hero.addView(actions);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, dp(116));
        heroLp.topMargin = dp(8); heroLp.bottomMargin = dp(10); root.addView(hero, heroLp);
        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false); seasonScroll.setClipToPadding(false);
        seasons = row(); seasons.setPadding(dp(2), dp(2), dp(2), dp(2));
        seasonScroll.addView(seasons);
        root.addView(seasonScroll, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView episodesLabel = TvUi.text(this, "Все выпуски", 16, TvUi.TEXT); TvUi.bold(episodesLabel);
        episodesLabel.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(episodesLabel, new LinearLayout.LayoutParams(-1, dp(25)));
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        scroll.setClipToPadding(false); scroll.setPadding(dp(3), dp(12), dp(3), dp(4));
        scroll.setVerticalScrollBarEnabled(false); scroll.setFocusable(false);
        grid = column(); grid.setClipChildren(false); grid.setClipToPadding(false);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = TvUi.text(this, NAV_HINT, 12, TvUi.MUTED);
        footer.setGravity(Gravity.CENTER_VERTICAL); footer.setSingleLine(true);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(27)));
        setContentView(root); refresh.requestFocus(); updateResume();
    }
    private void loadCatalog() {
        if (!refresh.isEnabled()) return;
        refresh.setEnabled(false); refresh.setAlpha(.5f);
        footer.setText("Получаем свежий список выпусков…");
        network.execute(() -> {
            try {
                List<CatalogRepository.Episode> loaded = repository.loadCatalog(show);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    watch.catalogUpdated(show, loaded);
                    episodes.clear(); episodes.addAll(loaded); saveCatalog(); renderCatalog();
                    refresh.setEnabled(true); refresh.setAlpha(1f);
                    footer.setText(NAV_HINT);
                });
            } catch (Exception ex) {
                android.util.Log.w("UTVRemote", "Catalogue load failed", ex);
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    showLoadFailure(ex);
                    if (episodes.isEmpty() || getWindow().getDecorView().findFocus() == null) refresh.requestFocus();
                });
            }
        });
    }
    private void showLoadFailure(Exception error) {
        refresh.setEnabled(true); refresh.setAlpha(1f);
        NetworkDiagnostics.save(this, "Каталог: " + show.title, error);
        String code = NetworkDiagnostics.code(error);
        footer.setText((episodes.isEmpty() ? "" : "Сохранённый каталог · ") + NetworkDiagnostics.summary(error));
        if (episodes.isEmpty()) subtitle.setText("Ошибка загрузки · " + code);
    }
    private void renderCatalog() {
        View focused = getWindow().getDecorView().findFocus();
        boolean hadCards = !cards.isEmpty();
        String focusedEpisode = focused != null && focused.getTag() instanceof CatalogRepository.Episode
                ? ((CatalogRepository.Episode) focused.getTag()).url : null;
        Integer focusedSeason = focused != null && focused.getTag() instanceof Integer ? (Integer) focused.getTag() : null;
        SortedSet<Integer> numbers = new TreeSet<>();
        for (CatalogRepository.Episode e : episodes) numbers.add(e.season);
        if (numbers.isEmpty()) return;
        if (!numbers.contains(selectedSeason)) selectedSeason = numbers.last();
        seasons.removeAllViews(); Button selected = null;
        for (int number : numbers) {
            Button b = TvUi.button(this, number + " сезон");
            b.setTag(number);
            b.setSelected(number == selectedSeason);
            b.setOnClickListener(v -> {
                selectedSeason = number; prefs.edit().putInt(show.key("selectedSeason"), number).apply();
                seasonFocusId = b.getId();
                for (int i = 0; i < seasons.getChildCount(); i++)
                    seasons.getChildAt(i).setSelected(seasons.getChildAt(i) == b);
                renderEpisodes(); if (!cards.isEmpty()) focusCard(cards.get(0));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(112), dp(43));
            lp.rightMargin = dp(9); seasons.addView(b, lp);
            if (number == selectedSeason) selected = b;
        }
        if (selected != null) seasonFocusId = selected.getId();
        subtitle.setText(quantity(numbers.size(), "сезон", "сезона", "сезонов") + " · "
                + quantity(episodes.size(), "серия", "серии", "серий") + " · 16+");
        renderEpisodes();
        if (hadCards && (focused == resume || focused == refresh)) { focused.requestFocus(); return; }
        for (View card : cards) if (((CatalogRepository.Episode) card.getTag()).url.equals(focusedEpisode)) {
            focusCard(card); return;
        }
        if (focusedSeason != null) for (int i = 0; i < seasons.getChildCount(); i++) {
            View season = seasons.getChildAt(i);
            if (focusedSeason.equals(season.getTag())) { season.requestFocus(); return; }
        }
        if (!cards.isEmpty()) focusCard(cards.get(0));
    }
    private void focusCard(View card) {
        // Newly replaced rows may not have bounds until the next layout pass.
        if (!card.requestFocus()) card.post(card::requestFocus);
    }
    private void renderEpisodes() {
        posters.newBatch(); grid.removeAllViews(); cards.clear(); scroll.scrollTo(0, 0);
        List<CatalogRepository.Episode> visible = new ArrayList<>();
        for (CatalogRepository.Episode e : episodes) if (e.season == selectedSeason) visible.add(e);
        LinearLayout line = null;
        int cardWidth = (getResources().getDisplayMetrics().widthPixels - dp(70) - dp(28)) / 3;
        int imageHeight = Math.round((cardWidth - dp(8)) * 9f / 16f);
        for (int index = 0; index < visible.size(); index++) {
            CatalogRepository.Episode episode = visible.get(index);
            if (index % 3 == 0) {
                line = row(); line.setClipChildren(false); line.setClipToPadding(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(14); grid.addView(line, lp);
            }
            LinearLayout card = column(); card.setId(View.generateViewId());
            card.setTag(episode);
            card.setPadding(dp(4), dp(4), dp(4), dp(10)); TvUi.focusable(card);
            card.setContentDescription(episode.title() + ", " + episode.duration);
            ImageView poster = new ImageView(this); poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            FrameLayout picture = new FrameLayout(this);
            picture.setBackground(TvUi.shape(this, 0xFF353544, 0)); picture.setClipToOutline(true);
            picture.addView(poster, new FrameLayout.LayoutParams(-1, -1));
            poster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            TextView age = TvUi.text(this, "16+", 10, TvUi.TEXT);
            age.setPadding(dp(6), dp(3), dp(6), dp(3));
            age.setBackground(TvUi.shape(this, 0xCC181821, 0));
            FrameLayout.LayoutParams ageLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
            ageLp.leftMargin = dp(8); ageLp.topMargin = dp(8); picture.addView(age, ageLp);
            ImageView play = new ImageView(this); play.setImageResource(R.drawable.ic_play);
            play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams playLp = new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM | Gravity.RIGHT);
            playLp.rightMargin = dp(8); playLp.bottomMargin = dp(8); picture.addView(play, playLp);
            card.addView(picture, new LinearLayout.LayoutParams(-1, imageHeight)); posters.load(episode.image, episode.url, poster);
            TextView name = TvUi.text(this, "Серия " + episode.number, 19, TvUi.TEXT);
            TvUi.bold(name); name.setPadding(dp(10), dp(8), dp(10), 0);
            card.addView(name);
            long position = prefs.getLong("position:" + episode.url, 0);
            String detail = position > 5000 ? "Продолжить с " + PlaybackActivity.time(position) : episode.duration;
            TextView meta = TvUi.text(this, detail.isEmpty() ? "Смотреть выпуск" : detail, 13,
                    position > 5000 ? TvUi.ACCENT : TvUi.MUTED);
            meta.setPadding(dp(10), dp(2), dp(10), 0); card.addView(meta);
            card.setOnClickListener(v -> play(episode));
            card.setOnLongClickListener(v -> { episodeActions(episode, card); return true; });
            card.setOnKeyListener((v, key, event) -> {
                if (key != android.view.KeyEvent.KEYCODE_MENU) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) episodeActions(episode, card);
                return true;
            });
            ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.RED));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF454557));
            LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(4));
            progressLp.setMargins(dp(10), dp(7), dp(10), 0); card.addView(progress, progressLp);
            updateCard(card);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardWidth, -2);
            if (index % 3 != 2) lp.rightMargin = dp(14);
            line.addView(card, lp); cards.add(card);
        }
        for (int i = 0; i < cards.size(); i++) {
            View card = cards.get(i);
            card.setNextFocusUpId(i >= 3 ? cards.get(i - 3).getId() : seasonFocusId);
            card.setNextFocusDownId(i / 3 < (cards.size() - 1) / 3
                    ? cards.get(Math.min(i + 3, cards.size() - 1)).getId() : card.getId());
            card.setNextFocusLeftId(i % 3 == 0 ? card.getId() : cards.get(i - 1).getId());
            card.setNextFocusRightId(i % 3 == 2 || i + 1 == cards.size() ? card.getId() : cards.get(i + 1).getId());
        }
        for (int i = 0; i < seasons.getChildCount(); i++)
            if (!cards.isEmpty()) seasons.getChildAt(i).setNextFocusDownId(cards.get(0).getId());
    }
    private static String quantity(int count, String one, String few, String many) {
        int last = count % 10, lastTwo = count % 100;
        String form = lastTwo >= 11 && lastTwo <= 14 ? many
                : last == 1 ? one : last >= 2 && last <= 4 ? few : many;
        return count + " " + form;
    }
    private void play(CatalogRepository.Episode e) {
        Intent intent = new Intent(this, PlaybackActivity.class);
        intent.putExtra("url", e.url); intent.putExtra("title", show.title + " · " + e.title());
        for (int i = 0; i + 1 < episodes.size(); i++) if (episodes.get(i).url.equals(e.url)) {
            CatalogRepository.Episode next = episodes.get(i + 1);
            intent.putExtra("nextUrl", next.url); intent.putExtra("nextTitle", show.title + " · " + next.title()); break;
        }
        startActivity(intent);
    }
    private void resumeLast() {
        String url = watch.resumeUrl(show);
        for (CatalogRepository.Episode e : episodes) if (e.url.equals(url)) { play(e); return; }
        if (!url.isEmpty()) startActivity(new Intent(this, PlaybackActivity.class)
                .putExtra("url", url).putExtra("title", watch.title(show, url)));
    }
    private void updateResume() {
        String url = watch.resumeUrl(show);
        resume.setVisibility(url.isEmpty() ? View.GONE : View.VISIBLE);
        if (!url.isEmpty()) resume.setText("Продолжить · " + PlaybackActivity.time(prefs.getLong("position:" + url, 0)));
    }
    private void updateCard(View view) {
        CatalogRepository.Episode e = (CatalogRepository.Episode) view.getTag();
        LinearLayout card = (LinearLayout) view;
        TextView meta = (TextView) card.getChildAt(2);
        long position = prefs.getLong("position:" + e.url, 0);
        boolean watched = watch.watched(e.url), fresh = watch.isNew(show, e.url);
        meta.setText(watched ? "✓ Просмотрено" : fresh ? "● Новая серия" : position > 5000
                ? "Продолжить с " + PlaybackActivity.time(position) : e.duration.isEmpty() ? "Смотреть выпуск" : e.duration);
        meta.setTextColor(watched || fresh || position > 5000 ? TvUi.ACCENT : TvUi.MUTED);
        ((ProgressBar) card.getChildAt(3)).setProgress(watch.progress(e.url, e.duration));
        card.setContentDescription(e.title() + ", " + meta.getText());
    }
    private void toggleWatched(CatalogRepository.Episode e, View card) {
        watch.mark(show, e.url, !watch.watched(e.url)); updateCard(card); updateResume();
    }
    private void episodeActions(CatalogRepository.Episode e, View card) {
        new android.app.AlertDialog.Builder(this).setTitle(e.title())
                .setItems(new String[]{"Смотреть", watch.watched(e.url) ? "Снять отметку просмотра" : "Отметить просмотренным"},
                        (dialog, which) -> { if (which == 0) play(e); else toggleWatched(e, card); }).show();
    }
    private void saveCatalog() {
        JSONArray array = new JSONArray();
        try {
            for (CatalogRepository.Episode e : episodes) array.put(new JSONObject()
                    .put("s", e.season).put("n", e.number).put("u", e.url)
                    .put("i", e.image).put("d", e.duration).put("t", e.detail));
            prefs.edit().putString(show.key("catalog"), array.toString()).apply();
        } catch (Exception ignored) { }
    }
    private void restoreCatalog() {
        try {
            JSONArray array = new JSONArray(prefs.getString(show.key("catalog"), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject e = array.getJSONObject(i);
                if (Show.fromUrl(e.getString("u")) == show) episodes.add(new CatalogRepository.Episode(
                        e.getInt("s"), e.getInt("n"), e.getString("u"), e.optString("i"), e.optString("d"), e.optString("t")));
            }
            if (!episodes.isEmpty()) renderCatalog();
        } catch (Exception ignored) { episodes.clear(); }
    }
    @Override protected void onResume() {
        super.onResume(); TvUi.fullscreen(this);
        if (prefs == null) return;
        updateResume();
        for (View view : cards) updateCard(view);
    }

    @Override protected void onDestroy() { posters.close(); network.shutdownNow(); super.onDestroy(); }
}
