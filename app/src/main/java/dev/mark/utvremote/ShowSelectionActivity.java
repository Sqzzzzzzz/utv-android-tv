package dev.mark.utvremote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/** Cinematic, remote-first launcher for the two supported shows. */
public final class ShowSelectionActivity extends Activity {
    private final List<View> cards = new ArrayList<>();
    private Show selected;
    private ImageView hero;
    private TextView title, episode;
    private Button primary, settings;
    private int dp(float n) { return TvUi.dp(this,n); }
    private LinearLayout column() { LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v; }
    private GradientDrawable fade(GradientDrawable.Orientation direction, int... colors) { return new GradientDrawable(direction,colors); }
    private int cover(Show show) { return show==Show.MAMA ? R.drawable.hero_mama : R.drawable.hero_mamenkin; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);TvUi.fullscreen(this);
        selected=Show.fromSlug(getSharedPreferences("watch",MODE_PRIVATE).getString("selectedShow",""));
        if(selected==null)selected=Show.MAMA;
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(0xFF0D0E12);
        hero=new ImageView(this);hero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int screenWidth=getResources().getDisplayMetrics().widthPixels;
        int screenHeight=getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams art=new FrameLayout.LayoutParams(screenWidth,screenHeight,Gravity.TOP|Gravity.RIGHT);
        root.addView(hero,art);
        View shade=new View(this);shade.setBackground(fade(GradientDrawable.Orientation.LEFT_RIGHT,0xFF0D0E12,0xB00D0E12,0x200D0E12,0x000D0E12));
        root.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        View bottom=new View(this);bottom.setBackground(fade(GradientDrawable.Orientation.TOP_BOTTOM,0x000D0E12,0x000D0E12,0xBB0D0E12,0xFF0D0E12));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout content=column();content.setPadding(dp(32),dp(18),dp(32),dp(14));
        root.addView(content,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.brand_mark);logo.setContentDescription("Ю");logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        header.addView(logo,new LinearLayout.LayoutParams(dp(48),dp(32)));
        TextView brand=TvUi.text(this,"",21,TvUi.TEXT);TvUi.bold(brand);brand.setPadding(dp(10),0,0,0);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        settings=TvUi.button(this,"Настройки");settings.setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        header.addView(settings,new LinearLayout.LayoutParams(dp(136),dp(38)));
        content.addView(header,new LinearLayout.LayoutParams(-1,dp(40)));
        LinearLayout lead=column();lead.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(lead,new LinearLayout.LayoutParams(-1,0,1));
        title=TvUi.text(this,"",42,TvUi.TEXT);TvUi.bold(title);title.setMaxLines(2);
        lead.addView(title,new LinearLayout.LayoutParams(Math.round(screenWidth*.53f),-2));
        TextView meta=TvUi.text(this,"Реалити · 16+",15,TvUi.MUTED);
        LinearLayout.LayoutParams metaLp=new LinearLayout.LayoutParams(-2,-2);metaLp.topMargin=dp(8);lead.addView(meta,metaLp);
        episode=TvUi.text(this,"",12,TvUi.MUTED);episode.setSingleLine(true);episode.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams epLp=new LinearLayout.LayoutParams(Math.round(screenWidth*.52f),-2);epLp.topMargin=dp(8);lead.addView(episode,epLp);
        primary=new Button(this);primary.setId(View.generateViewId());primary.setAllCaps(false);TvUi.bold(primary);
        primary.setFocusable(true);primary.setFocusableInTouchMode(true);primary.setStateListAnimator(null);
        primary.setTextSize(20);primary.setTextColor(0xFF111218);
        primary.setOnFocusChangeListener((v,focused)->stylePrimary());
        primary.setOnClickListener(v->{
            WatchState watch=new WatchState(getSharedPreferences("watch",MODE_PRIVATE));String url=watch.resumeUrl(selected);
            if(url.isEmpty())openShow(selected);
            else startActivity(new Intent(this,PlaybackActivity.class).putExtra("url",url).putExtra("title",watch.title(selected,url)));
        });
        LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(dp(276),dp(54));action.topMargin=dp(16);lead.addView(primary,action);stylePrimary();
        LinearLayout row=new LinearLayout(this);row.setClipChildren(false);row.setClipToPadding(false);
        int tileHeight=Math.min(dp(142),Math.round(screenHeight*.26f));
        for(Show show:Show.values()) {
            FrameLayout card=new FrameLayout(this);card.setId(View.generateViewId());card.setTag(show);card.setContentDescription(show.title);
            card.setFocusable(true);card.setFocusableInTouchMode(true);card.setBackground(TvUi.shape(this,0xFF202127,0));card.setClipToOutline(true);
            ImageView image=new ImageView(this);image.setImageResource(cover(show));image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(image,new FrameLayout.LayoutParams(-1,-1));
            View scrim=new View(this);scrim.setBackground(fade(GradientDrawable.Orientation.TOP_BOTTOM,0x00000000,0x10000000,0xE6000000));card.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
            TextView name=TvUi.text(this,show.title,24,TvUi.TEXT);TvUi.bold(name);name.setPadding(dp(18),dp(8),dp(10),dp(8));name.setMaxLines(2);name.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(name,new FrameLayout.LayoutParams(Math.round((screenWidth-dp(78))*.30f),-1,Gravity.LEFT));
            card.setOnFocusChangeListener((v,focused)->{
                card.setForeground(focused?outline():null);
                if(focused)select(show);
            });
            card.setOnClickListener(v->{select(show);openShow(show);});
            LinearLayout.LayoutParams tile=new LinearLayout.LayoutParams(0,tileHeight,1);if(!cards.isEmpty())tile.leftMargin=dp(14);
            row.addView(card,tile);cards.add(card);
        }
        content.addView(row,new LinearLayout.LayoutParams(-1,tileHeight));
        TextView hint=TvUi.text(this,"← →  Выбор шоу     ↑  Продолжить     OK  Открыть",11,0xFFA5A5B1);
        hint.setPadding(0,dp(8),0,0);content.addView(hint,new LinearLayout.LayoutParams(-1,dp(26)));
        for(int i=0;i<cards.size();i++) {
            View card=cards.get(i);card.setNextFocusLeftId(cards.get(Math.max(0,i-1)).getId());card.setNextFocusRightId(cards.get(Math.min(cards.size()-1,i+1)).getId());
            card.setNextFocusUpId(primary.getId());card.setNextFocusDownId(card.getId());
        }
        primary.setNextFocusUpId(settings.getId());primary.setNextFocusLeftId(primary.getId());primary.setNextFocusRightId(primary.getId());
        settings.setNextFocusDownId(primary.getId());settings.setNextFocusUpId(settings.getId());settings.setNextFocusLeftId(settings.getId());settings.setNextFocusRightId(settings.getId());
        setContentView(root);select(selected);selectedCard().requestFocus();
    }
    private View pressedControl;
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int key=event.getKeyCode();boolean down=event.getAction()==android.view.KeyEvent.ACTION_DOWN;
        View focused=getWindow().getDecorView().findFocus();
        if(focused==null)return super.dispatchKeyEvent(event);
        if(key>=android.view.KeyEvent.KEYCODE_DPAD_UP&&key<=android.view.KeyEvent.KEYCODE_DPAD_RIGHT){
            if(down){
                cancelPress();View next;
                switch(key){
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT:next=focused.focusSearch(View.FOCUS_LEFT);break;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:next=focused.focusSearch(View.FOCUS_RIGHT);break;
                    case android.view.KeyEvent.KEYCODE_DPAD_UP:next=focused.focusSearch(View.FOCUS_UP);break;
                    default:next=focused.focusSearch(View.FOCUS_DOWN);break;
                }
                if(next!=null)next.requestFocus();
            }
            return true;
        }
        if(key==android.view.KeyEvent.KEYCODE_DPAD_CENTER||key==android.view.KeyEvent.KEYCODE_ENTER){
            if(down&&event.getRepeatCount()==0){pressedControl=focused;focused.setPressed(true);}
            if(!down&&pressedControl!=null){View action=pressedControl;pressedControl=null;action.setPressed(false);if(!event.isCanceled()&&action==focused)action.performClick();}
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private void cancelPress(){if(pressedControl!=null){pressedControl.setPressed(false);pressedControl=null;}}
    @Override protected void onPause(){cancelPress();super.onPause();}
    private GradientDrawable outline() { GradientDrawable d=TvUi.shape(this,Color.TRANSPARENT,TvUi.MAGENTA);return d; }
    private void stylePrimary() {
        GradientDrawable d=TvUi.shape(this,primary.isFocused()?Color.WHITE:0xFFF3F1ED,primary.isFocused()?TvUi.MAGENTA:0);
        d.setCornerRadius(dp(28));primary.setBackground(d);primary.setTextColor(0xFF111218);
    }
    private View selectedCard() { return cards.get(selected==Show.MAMA?0:1); }
    private void select(Show show) {
        boolean changed=selected!=show;selected=show;
        if(changed||hero.getDrawable()==null)hero.setImageResource(cover(show));
        title.setText(show.title);
        WatchState watch=new WatchState(getSharedPreferences("watch",MODE_PRIVATE));String url=watch.resumeUrl(show);
        primary.setTag("resume:"+show.slug);primary.setText(url.isEmpty()?"▶ Смотреть":"▶ Продолжить");
        episode.setText(url.isEmpty()?"":watch.title(show,url).replace(show.title+" · ","")+" · "+PlaybackActivity.time(watch.prefs.getLong("position:"+url,0)));
        episode.setVisibility(url.isEmpty()?View.GONE:View.VISIBLE);
        primary.setNextFocusDownId(selectedCard().getId());
        getSharedPreferences("watch",MODE_PRIVATE).edit().putString("selectedShow",show.slug).apply();
    }
    private void openShow(Show show) {startActivity(new Intent(this,MainActivity.class).putExtra("show",show.slug));}
    @Override protected void onResume() {super.onResume();TvUi.fullscreen(this);if(primary!=null)select(selected);}
}
