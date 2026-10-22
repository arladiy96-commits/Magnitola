package com.example.autolinkprobe;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Static layered artwork drawn once into a bitmap, never a frozen screenshot of GPS data. */
final class PremiumDialArt {
    static final float CX = 640f, CY = 344f, R = 280f;
    static final float START = 150f, SWEEP = 240f;
    static final int CYAN = 0xff28e5f6, GOLD = 0xffffb65c;
    static final RectF OUTER = new RectF(CX-R, CY-R, CX+R, CY+R);

    private PremiumDialArt() {}

    static void draw(Canvas canvas) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 720,
                new int[]{0xff06131c,0xff030910,0xff0b1922,0xff02060a},
                new float[]{0f,.4f,.78f,1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0,0,1280,720,p);
        glow(canvas,630,275,530,0x55204a62);
        glow(canvas,300,560,385,0x1821dcef);
        glow(canvas,1060,570,400,0x18ef9b42);
        // Wide horizon shadows create depth behind the glass instrumentation.
        Path mountains = new Path();
        mountains.moveTo(0,198); mountains.cubicTo(102,228,205,212,298,235);
        mountains.cubicTo(403,203,464,223,543,219);
        mountains.cubicTo(652,206,756,228,862,211);
        mountains.cubicTo(992,243,1066,202,1280,212);
        mountains.lineTo(1280,500); mountains.lineTo(0,500); mountains.close();
        p.setShader(new LinearGradient(0,208,0,498,0xff0b1820,0xff02070b,Shader.TileMode.CLAMP));
        canvas.drawPath(mountains,p);p.setShader(null);
        // Sculpted dashboard fascia and under-lit metallic wing edges.
        Path fascia=new Path();
        fascia.moveTo(0,544);fascia.cubicTo(181,519,329,544,466,560);
        fascia.cubicTo(555,575,734,575,813,560);
        fascia.cubicTo(1007,535,1113,527,1280,545);
        fascia.lineTo(1280,720);fascia.lineTo(0,720);fascia.close();
        p.setShader(new LinearGradient(0,535,0,720,
                new int[]{0xff273742,0xff101f29,0xff02080e},
                null,Shader.TileMode.CLAMP));
        canvas.drawPath(fascia,p);p.setShader(null);
        wing(canvas,p,true);wing(canvas,p,false);
        // Brushed metallic bezel, three raised rings, inner glass.
        arc(canvas,p,OUTER,START,SWEEP,0x552c5765,50f);
        arc(canvas,p,OUTER,START,SWEEP,0xff111e27,38f);
        arc(canvas,p,OUTER,START,SWEEP,0xff2b3c45,28f);
        RectF outerThin = new RectF(345,49,935,639);
        arc(canvas,p,outerThin,START,SWEEP,0x77769aaa,1.3f);
        RectF inner=new RectF(413,117,867,571);
        arc(canvas,p,inner,150,239,0xff203e4a,2f);
        arc(canvas,p,inner,153,113,0x9925c3e1,1.6f);
        arc(canvas,p,inner,283,107,0x99eca553,1.6f);
        // Dynamic progress is painted on top, these are only permanent lighting accents.
        arc(canvas,p,OUTER,150,117,0x5531def0,21f);
        arc(canvas,p,OUTER,274,116,0x77e2a450,20f);
        arc(canvas,p,OUTER,151,111,0xff179dbb,4f);
        arc(canvas,p,OUTER,288,100,0xffc88837,4f);
        // Digitally precise clock graduations + scale labels.
        for(int i=0;i<=160;i+=2) {
            float radians=(float)Math.toRadians(START+SWEEP*i/160f);
            float c=(float)Math.cos(radians),s=(float)Math.sin(radians);
            int len=i%20==0?27:i%10==0?18:8;
            float from=291-len;
            stroke(canvas,p,CX+c*from,CY+s*from,CX+c*292,CY+s*292,
                    i<=80?0xff679bac:0xffb08a57,i%20==0?2.6f:1.2f);
        }
        for(int i=0;i<=160;i+=20) {
            double radians=Math.toRadians(START+SWEEP*i/160f);
            float x=CX+(float)Math.cos(radians)*239;
            float y=CY+(float)Math.sin(radians)*239+9;
            label(canvas,p,""+i,x,y,25,0xffbac8d3);
        }
        // Satin glass side pods: shadows, etched borders, subtle top reflections.
        pod(canvas,p,new RectF(43,225,331,531),32);
        pod(canvas,p,new RectF(974,222,1238,523),32);
        p.setColor(0x554e7385);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.4f);
        canvas.drawCircle(1105,356,93,p);
        p.setColor(0x66315464);canvas.drawCircle(1105,356,75,p);
        p.setStyle(Paint.Style.FILL);
        for(int i=0;i<360;i+=30){
            double a=Math.toRadians(i);
            stroke(canvas,p,1105+(float)Math.cos(a)*85,356+(float)Math.sin(a)*85,
                    1105+(float)Math.cos(a)*92,356+(float)Math.sin(a)*92,
                    0xff7890a0,1.2f);
        }
        label(canvas,p,"N",1105,279,19,0xff90a5b5);
        label(canvas,p,"E",1190,362,19,0xff90a5b5);
        label(canvas,p,"S",1105,454,19,0xff90a5b5);
        label(canvas,p,"W",1020,362,19,0xff90a5b5);
        stroke(canvas,p,335,628,946,628,0x666393aa,1.5f);
        stroke(canvas,p,518,639,518,698,0x5543697a,1);
        stroke(canvas,p,758,639,758,698,0x5543697a,1);
        // Left pod's minimal speed/trip motif, intentionally non-faked.
        p.setColor(0x5594b7c6);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3f);
        canvas.drawArc(new RectF(77,253,107,283),205,130,false,p);
        p.setStyle(Paint.Style.FILL);
        // Accent lines across fascia with genuine luminous inner borders.
        stroke(canvas,p,0,550,310,560,0x774ae2f4,2);
        stroke(canvas,p,970,560,1280,550,0x88ffb565,2);
        stroke(canvas,p,0,610,439,572,0x77ffc068,4);
        stroke(canvas,p,841,572,1280,610,0x77ffc068,4);
    }

    static void progress(Canvas c, Paint p, float speed) {
        if(speed<=0)return;
        float segment=SWEEP*Math.min(speed,160f)/160f;
        arc(c,p,OUTER,START,segment,0x2629e5f9,42f);
        arc(c,p,OUTER,START,segment,0x663aefff,26f);
        arc(c,p,OUTER,START,segment,0xff2ae9ff,13f);
        arc(c,p,OUTER,START,segment,0xffd4fdff,2.4f);
        double theta=Math.toRadians(START+segment);
        float x=CX+(float)Math.cos(theta)*R,y=CY+(float)Math.sin(theta)*R;
        p.setStyle(Paint.Style.FILL);p.setColor(0x7731e8fe);c.drawCircle(x,y,11,p);
        p.setColor(0xffd9fdff);c.drawCircle(x,y,4,p);
    }

    private static void wing(Canvas c, Paint p, boolean left) {
        c.save();
        if(!left) {c.translate(1280,0);c.scale(-1,1);}
        Path path=new Path();path.moveTo(0,551);
        path.cubicTo(160,554,303,538,446,582);
        path.lineTo(507,619);path.cubicTo(322,578,195,634,0,604);path.close();
        p.setShader(new LinearGradient(0,544,0,635,
                0xff304452,0xff041018,Shader.TileMode.CLAMP));
        c.drawPath(path,p);p.setShader(null);c.restore();
    }
    private static void glow(Canvas c,float x,float y,float radius,int color){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(x,y,radius,
                new int[]{color,color&0x00ffffff},null,Shader.TileMode.CLAMP));
        c.drawCircle(x,y,radius,p);
    }
    private static void pod(Canvas c,Paint p,RectF r,int round){
        p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,
                new int[]{0xbb0f202a,0xc906141d,0xd6020a0f},
                null,Shader.TileMode.CLAMP));
        c.drawRoundRect(r,round,round,p);p.setShader(null);
        p.setColor(0xff2a4858);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.2f);
        c.drawRoundRect(r,round,round,p);p.setStyle(Paint.Style.FILL);
        stroke(c,p,r.left+38,r.top+1,r.right-38,r.top+1,0x777eb4bd,1.2f);
    }
    private static void label(Canvas c,Paint p,String text,float x,float y,int size,int color){
        p.setStyle(Paint.Style.FILL);p.setShader(null);p.setColor(color);
        p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif-light",0));
        p.setTextAlign(Paint.Align.CENTER);c.drawText(text,x,y,p);
    }
    private static void arc(Canvas c,Paint p,RectF rect,float from,float sweep,int color,float width){
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);p.setColor(color);p.setShader(null);
        c.drawArc(rect,from,sweep,false,p);p.setStyle(Paint.Style.FILL);
    }
    private static void stroke(Canvas c,Paint p,float x,float y,float x2,float y2,int color,float size){
        p.setStyle(Paint.Style.STROKE);p.setColor(color);p.setShader(null);p.setStrokeWidth(size);
        c.drawLine(x,y,x2,y2,p);p.setStyle(Paint.Style.FILL);
    }
}
