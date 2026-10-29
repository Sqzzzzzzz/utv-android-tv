package dev.mark.utvremote;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A remote-first filmstrip, sharing the catalogue's original-poster loader. */
final class EpisodePicker extends Dialog {
    interface Selection { void open(String url); }
    private final Activity host;
    private final String current;
    private final Show show;
    private final WatchState watch;
    private final Selection selection;
    private final PosterLoader posters = new PosterLoader();
    private final TreeMap<Integer,List<org.json.JSONObject>> seasons = new TreeMap<>();
    private final Map<Integer,Button> tabs = new LinkedHashMap<>();
    private final LinearLayout cards;
    private final Button close;
    private int season;
    private int dp(float n) { return TvUi.dp(host,n); }
    EpisodePicker(Activity host, Show show, String current, WatchState watch, Selection selection) {
        super(host); this.host=host;this.show=show;this.current=current;this.watch=watch;this.selection=selection;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        try {
            org.json.JSONArray list=new org.json.JSONArray(watch.prefs.getString(show.key("catalog"),"[]"));
            Set<String> seen=new HashSet<>();
            for(int i=0;i<list.length();i++) {
                org.json.JSONObject e=list.optJSONObject(i);if(e==null)continue;
                String url=e.optString("u");int s=e.optInt("s"),n=e.optInt("n");
                if(Show.fromUrl(url)!=show || s<1 || n<1 || !seen.add(url))continue;
                seasons.computeIfAbsent(s,k->new ArrayList<>()).add(e);
                if(url.equals(current))season=s;
            }
        } catch(org.json.JSONException ignored) { }
        for(List<org.json.JSONObject> list:seasons.values())list.sort(Comparator.comparingInt(e->e.optInt("n")));
        if(!seasons.isEmpty() && !seasons.containsKey(season))season=seasons.firstKey();
        LinearLayout root=new LinearLayout(host);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28),dp(18),dp(28),dp(12));root.setBackground(TvUi.shape(host,0xFA101116,0));
        LinearLayout header=new LinearLayout(host);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=TvUi.text(host,"Серии  ·  "+show.title,23,Color.WHITE);TvUi.bold(title);
        header.addView(title,new LinearLayout.LayoutParams(0,dp(38),1));title.setGravity(Gravity.CENTER_VERTICAL);
        close=TvUi.button(host,"Закрыть");close.setOnClickListener(v->dismiss());header.addView(close,new LinearLayout.LayoutParams(dp(110),dp(36)));
        root.addView(header);
        HorizontalScrollView strip=new HorizontalScrollView(host);strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row=new LinearLayout(host);strip.addView(row);root.addView(strip,new LinearLayout.LayoutParams(-1,dp(52)));
        for(int s:seasons.keySet()) {
            Button tab=TvUi.button(host,s+" сезон");tab.setTag("season:"+s);tabs.put(s,tab);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(110),dp(36));lp.rightMargin=dp(8);lp.topMargin=dp(8);row.addView(tab,lp);
            tab.setOnClickListener(v->{season=s;render(false);});tab.setNextFocusUpId(close.getId());
        }
        List<Button> seasonButtons=new ArrayList<>(tabs.values());
        for(int i=0;i<seasonButtons.size();i++){
            Button tab=seasonButtons.get(i);tab.setNextFocusLeftId(seasonButtons.get(Math.max(0,i-1)).getId());tab.setNextFocusRightId(seasonButtons.get(Math.min(seasonButtons.size()-1,i+1)).getId());
        }
        close.setNextFocusUpId(close.getId());close.setNextFocusRightId(close.getId());close.setNextFocusLeftId(close.getId());
        HorizontalScrollView filmstrip=new HorizontalScrollView(host);filmstrip.setHorizontalScrollBarEnabled(false);
        filmstrip.setClipToPadding(false);cards=new LinearLayout(host);cards.setPadding(dp(3),dp(3),dp(3),dp(3));filmstrip.addView(cards);
        root.addView(filmstrip,new LinearLayout.LayoutParams(-1,dp(196)));
        TextView hint=TvUi.text(host,"← →  Выбрать серию     ↑  Сезоны     OK  Смотреть     Назад  К видео",12,0xFFA8A9B3);
        hint.setPadding(0,dp(8),0,0);root.addView(hint,new LinearLayout.LayoutParams(-1,dp(30)));
        setContentView(root);setOnShowListener(d->render(true));
        setOnDismissListener(d->posters.close());
        Window window=getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.25f);window.setGravity(Gravity.BOTTOM);}
    }
    @Override public void show() {super.show();getWindow().setLayout(-1,-2);}
    // Parent installs its own dismissal callback; close resources regardless of listener replacement.
    @Override public void dismiss() {posters.close();super.dismiss();}
    private void render(boolean focusCurrent) {
        posters.newBatch();cards.removeAllViews();
        for(Map.Entry<Integer,Button> entry:tabs.entrySet())entry.getValue().setText((entry.getKey()==season?"•  ":"")+entry.getKey()+" сезон");
        List<org.json.JSONObject> list=seasons.get(season);
        if(list==null || list.isEmpty()) {TextView empty=TvUi.text(host,"Список серий пока не загружен. Обновите каталог шоу.",17,TvUi.MUTED);cards.addView(empty);close.requestFocus();return;}
        List<View> items=new ArrayList<>();View target=null;
        for(org.json.JSONObject e:list) {
            String url=e.optString("u");boolean now=url.equals(current);
            LinearLayout card=new LinearLayout(host);card.setOrientation(LinearLayout.VERTICAL);card.setId(View.generateViewId());card.setTag("episode:"+url);
            card.setFocusable(true);card.setFocusableInTouchMode(true);card.setPadding(dp(3),dp(3),dp(3),dp(3));
            card.setBackground(TvUi.shape(host,0xFF202127,now?TvUi.MAGENTA:0));card.setClipToOutline(true);
            card.setContentDescription("Серия "+e.optInt("n")+(now?", сейчас":""));
            FrameLayout picture=new FrameLayout(host);ImageView image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageResource(show==Show.MAMA?R.drawable.hero_mama:R.drawable.hero_mamenkin);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            picture.addView(image,new FrameLayout.LayoutParams(-1,-1));card.addView(picture,new LinearLayout.LayoutParams(-1,dp(126)));
            if(now || watch.watched(url)) {
                TextView badge=TvUi.text(host,now?"Сейчас":"✓ Просмотрено",10,Color.WHITE);TvUi.bold(badge);badge.setGravity(Gravity.CENTER);
                badge.setBackground(TvUi.shape(host,now?TvUi.MAGENTA:0xDF101116,0));
                FrameLayout.LayoutParams badgeLp=new FrameLayout.LayoutParams(dp(now?65:110),dp(24),Gravity.TOP|Gravity.RIGHT);badgeLp.setMargins(dp(6),dp(6),dp(6),0);picture.addView(badge,badgeLp);
            }
            TextView label=TvUi.text(host,"Серия "+e.optInt("n"),18,Color.WHITE);TvUi.bold(label);label.setPadding(dp(9),dp(7),dp(9),0);card.addView(label);
            String duration=e.optString("d");TextView detail=TvUi.text(host,duration.isEmpty()?"Сезон "+season:duration,12,TvUi.MUTED);detail.setPadding(dp(9),dp(3),dp(9),dp(4));card.addView(detail);
            ProgressBar progress=new ProgressBar(host,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgress(watch.progress(url,duration));progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TvUi.MAGENTA));card.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(230),dp(186));lp.rightMargin=dp(12);cards.addView(card,lp);items.add(card);
            card.setOnFocusChangeListener((v,focused)->card.setBackground(TvUi.shape(host,focused?0xFF303139:0xFF202127,focused?Color.WHITE:(now?TvUi.MAGENTA:0))));
            card.setOnClickListener(v->{dismiss();if(!now)selection.open(url);});card.setNextFocusUpId(tabs.get(season).getId());card.setNextFocusDownId(card.getId());
            if(now)target=card;
            posters.load(e.optString("i"),url,image);
        }
        if(target==null)target=items.get(0);
        for(int i=0;i<items.size();i++){View card=items.get(i);card.setNextFocusLeftId(items.get(Math.max(0,i-1)).getId());card.setNextFocusRightId(items.get(Math.min(items.size()-1,i+1)).getId());}
        for(Button tab:tabs.values())tab.setNextFocusDownId(target.getId());close.setNextFocusDownId(tabs.get(season).getId());
        if(focusCurrent)target.requestFocus();
    }
}
