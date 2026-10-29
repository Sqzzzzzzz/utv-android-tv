package dev.mark.utvremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Draw-only timeline; DPAD selects a target without seeking the main player. */
final class PlayerTimeline extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private long position,duration;
    PlayerTimeline(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void update(long position,long duration){this.duration=Math.max(0,duration);this.position=Math.max(0,Math.min(position,this.duration));invalidate();}
    float markerX(){float inset=TvUi.dp(getContext(),7);return inset+(getWidth()-2*inset)*(duration>0?position/(float)duration:0);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float inset=TvUi.dp(getContext(),7), y=getHeight()/2f;
        paint.setStrokeWidth(TvUi.dp(getContext(),4));paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFF77777C);canvas.drawLine(inset,y,getWidth()-inset,y,paint);
        paint.setColor(0xFFF8F8F8);canvas.drawLine(inset,y,markerX(),y,paint);
        paint.setColor(TvUi.MAGENTA);canvas.drawCircle(markerX(),y,TvUi.dp(getContext(),6),paint);
    }
}
