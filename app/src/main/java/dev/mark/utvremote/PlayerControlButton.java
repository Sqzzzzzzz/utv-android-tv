package dev.mark.utvremote;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;

/** Native vector-like transport glyphs, with real Button accessibility/focus semantics. */
final class PlayerControlButton extends Button {
    static final int PLAY=0, BACK=1, FORWARD=2;
    private final int kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyph=new Path();
    PlayerControlButton(Context context,int kind){
        super(context);this.kind=kind;setId(generateViewId());setFocusable(true);setFocusableInTouchMode(true);setStateListAnimator(null);setAllCaps(false);setPadding(0,0,0,0);restyle();
    }
    private void restyle(){
        GradientDrawable shape=new GradientDrawable();shape.setShape(GradientDrawable.OVAL);
        shape.setColor(kind==PLAY?Color.WHITE:Color.TRANSPARENT);
        if(isFocused())shape.setStroke(TvUi.dp(getContext(),2),TvUi.MAGENTA);
        setBackground(shape);
    }
    @Override protected void onFocusChanged(boolean focused,int direction,android.graphics.Rect old){super.onFocusChanged(focused,direction,old);restyle();invalidate();}
    @Override protected void onDraw(Canvas c){
        float cx=getWidth()/2f,cy=getHeight()/2f,s=Math.min(getWidth(),getHeight());
        paint.setStyle(Paint.Style.FILL);paint.setColor(kind==PLAY?0xFF101116:Color.WHITE);
        if(kind==PLAY){
            if(getText().toString().equals("Пауза")){
                c.drawRoundRect(cx-s*.14f,cy-s*.18f,cx-s*.04f,cy+s*.18f,s*.02f,s*.02f,paint);
                c.drawRoundRect(cx+s*.04f,cy-s*.18f,cx+s*.14f,cy+s*.18f,s*.02f,s*.02f,paint);
            }else{Path p=glyph;p.reset();p.moveTo(cx-s*.10f,cy-s*.20f);p.lineTo(cx+s*.20f,cy);p.lineTo(cx-s*.10f,cy+s*.20f);p.close();c.drawPath(p,paint);}
        }else{
            float r=s*.30f;paint.setStrokeWidth(TvUi.dp(getContext(),2));paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);
            c.drawArc(cx-r,cy-r,cx+r,cy+r,-90,kind==BACK?-300:300,false,paint);
            paint.setStyle(Paint.Style.FILL);Path arrow=glyph;arrow.reset();float x=cx,y=cy-r;
            arrow.moveTo(x+(kind==BACK?-s*.14f:s*.14f),y);arrow.lineTo(x,y-s*.10f);arrow.lineTo(x,y+s*.10f);arrow.close();c.drawPath(arrow,paint);
            paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(Typeface.DEFAULT_BOLD);paint.setTextSize(s*.28f);c.drawText("10",cx,cy+s*.11f,paint);
        }
    }
}
