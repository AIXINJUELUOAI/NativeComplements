package dev.local.sunwindowdemo.features.iconlayers;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.util.Arrays;

/** Small, deterministic image operations, performed only on worker threads. */
public final class IconImages {
    private IconImages() {}
    /** Generated subjects stay well inside the visible mask, close to stock launcher icon proportions. */
    static final float SUBJECT=.65f;
    public static Bitmap render(Drawable drawable, int side) {
        Bitmap b = Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        Rect old = new Rect(drawable.getBounds());
        int width=drawable.getIntrinsicWidth(),height=drawable.getIntrinsicHeight();
        float scale=width>0&&height>0?(float)side/Math.max(width,height):1;
        int w=width>0&&height>0?Math.max(1,Math.round(width*scale)):side,h=width>0&&height>0?Math.max(1,Math.round(height*scale)):side;
        try { drawable.setBounds((side-w)/2,(side-h)/2,(side+w)/2,(side+h)/2); drawable.draw(new Canvas(b)); }
        finally { drawable.setBounds(old); }
        return b;
    }
    public static Bitmap solid(int color, int side) {
        Bitmap b = Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888); b.eraseColor(color); return b;
    }
    public record Layers(Bitmap background, Bitmap foreground) {}

    public static Layers separate(Bitmap source) {
        Bitmap square = source;
        Bitmap cut = removeBorder(square);
        int[] pixels=new int[square.getWidth()*square.getHeight()];square.getPixels(pixels,0,square.getWidth(),0,0,square.getWidth(),square.getHeight());
        int color = cut!=null?borderColor(pixels,square.getWidth(),square.getHeight()):dominant(square);
        // ponytail: flat illustrations have no recoverable semantic layers; retain the artwork on a color background.
        // AdaptiveIconDrawable expands each layer outside the visible viewport.
        // Normalize the subject once and compensate that expansion, as native adaptive layers do.
        float inset=.25f;
        try{inset=(Float)android.graphics.drawable.AdaptiveIconDrawable.class.getDeclaredMethod("getExtraInsetFraction").invoke(null);}catch(Exception ignored){}
        float viewport=1f/(1+2*inset);
        if(cut==null&&roundedShape(square)){
            // Keep the icon's own edge color behind a centered subject at the same reduced size as other generated icons.
            Rect bounds=contentBounds(square);
            return new Layers(solid(insideEdgeColor(square,bounds),square.getWidth()),fit(square,square.getWidth(),viewport*SUBJECT));
        }
        Bitmap foreground = fit(cut != null ? cut : square,square.getWidth(),viewport*.80f*SUBJECT);
        return new Layers(solid(color,square.getWidth()),foreground);
    }
    static boolean roundedShape(Bitmap b){
        int w=b.getWidth(),h=b.getHeight();
        if(Color.alpha(b.getPixel(0,0))>=24||Color.alpha(b.getPixel(w-1,0))>=24||Color.alpha(b.getPixel(0,h-1))>=24||Color.alpha(b.getPixel(w-1,h-1))>=24)return false;
        Rect r=contentBounds(b);return r.width()>=w*.6f&&r.height()>=h*.6f;
    }
    static int insideEdgeColor(Bitmap b,Rect r){
        int[] rs=new int[32],gs=new int[32],bs=new int[32];int n=0;
        for(int i=0;i<8;i++){float t=(i+1)/9f;
            int x=r.left+Math.round(r.width()*t),y=r.top+Math.round(r.height()*t);
            int[][] pts={{x,r.top+3},{x,r.bottom-4},{r.left+3,y},{r.right-4,y}};
            for(int[] p:pts){int c=b.getPixel(Math.min(b.getWidth()-1,Math.max(0,p[0])),Math.min(b.getHeight()-1,Math.max(0,p[1])));if(Color.alpha(c)<128)continue;rs[n]=Color.red(c);gs[n]=Color.green(c);bs[n]=Color.blue(c);n++;}
        }
        if(n==0)return dominant(b);
        Arrays.sort(rs,0,n);Arrays.sort(gs,0,n);Arrays.sort(bs,0,n);
        return Color.rgb(rs[n/2],gs[n/2],bs[n/2]);
    }
    static Bitmap compactSolid(Bitmap source){
        int color=source.getPixel(0,0);
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)if(source.getPixel(x,y)!=color)return source;
        return source.getWidth()==1&&source.getHeight()==1?source:solid(color,1);
    }
    /** Preserve layer padding/alignment; 30% larger than the 0.6.4 algorithm foreground. */
    static Bitmap algorithmForeground(Bitmap source){
        int w=source.getWidth(),h=source.getHeight();
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(source,null,new RectF(w*.0775f,h*.0775f,w*.9225f,h*.9225f),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** Remove a near-uniform outer field by flood fill, preserving disconnected logo details. */
    static Bitmap removeBorder(Bitmap source) {
        int w=source.getWidth(),h=source.getHeight(),n=w*h;
        int[] pixels=new int[n]; source.getPixels(pixels,0,w,0,0,w,h);
        int edge=borderColor(pixels,w,h),matches=0,total=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x==0||y==0||x==w-1||y==h-1){total++;if(close(pixels[y*w+x],edge))matches++;}
        if(matches<total*.75f)return null;
        boolean[] seen=new boolean[n]; int[] queue=new int[n];int head=0,tail=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((x==0||y==0||x==w-1||y==h-1)&&close(pixels[y*w+x],edge)){
            int at=y*w+x;if(!seen[at]){seen[at]=true;queue[tail++]=at;}
        }
        while(head<tail){int at=queue[head++],x=at%w,y=at/w;
            for(int direction=0;direction<4;direction++){
                int next=switch(direction){case 0->x>0?at-1:-1;case 1->x<w-1?at+1:-1;case 2->y>0?at-w:-1;default->y<h-1?at+w:-1;};
                if(next>=0&&!seen[next]&&close(pixels[next],edge)){seen[next]=true;queue[tail++]=next;}
            }
        }
        for(int i=0;i<n;i++)if(seen[i])pixels[i]=Color.TRANSPARENT;
        int content=0;for(int pixel:pixels)if(Color.alpha(pixel)>24)content++;
        if(content<n*.015f||content>n*.85f)return null;
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);result.setPixels(pixels,0,w,0,0,w,h);return result;
    }
    private static boolean close(int a,int b){
        if(Color.alpha(a)<24)return true;
        int dr=Color.red(a)-Color.red(b),dg=Color.green(a)-Color.green(b),db=Color.blue(a)-Color.blue(b);
        return dr*dr+dg*dg+db*db<42*42;
    }
    private static int borderColor(int[] p,int w,int h){
        int[] r=new int[2*w+2*h],g=new int[r.length],b=new int[r.length];int count=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x==0||y==0||x==w-1||y==h-1){int v=p[y*w+x];r[count]=Color.red(v);g[count]=Color.green(v);b[count]=Color.blue(v);count++;}
        Arrays.sort(r,0,count);Arrays.sort(g,0,count);Arrays.sort(b,0,count);return Color.rgb(r[count/2],g[count/2],b[count/2]);
    }
    static int dominant(Bitmap source){
        int[] buckets=new int[512];int chosen=0;
        for(int y=0;y<source.getHeight();y+=2)for(int x=0;x<source.getWidth();x+=2){int c=source.getPixel(x,y);if(Color.alpha(c)<128)continue;
            int key=(Color.red(c)>>5)<<6|(Color.green(c)>>5)<<3|Color.blue(c)>>5;
            if(++buckets[key]>buckets[chosen])chosen=key;
        }
        return Color.rgb(((chosen>>6)&7)*32+16,((chosen>>3)&7)*32+16,(chosen&7)*32+16);
    }
    public static Rect contentBounds(Bitmap source){
        int l=source.getWidth(),t=source.getHeight(),r=0,b=0;
        for(int y=0;y<source.getHeight();y++)for(int x=0;x<source.getWidth();x++)if(Color.alpha(source.getPixel(x,y))>24){l=Math.min(l,x);t=Math.min(t,y);r=Math.max(r,x+1);b=Math.max(b,y+1);}
        return r<=l||b<=t?new Rect():new Rect(l,t,r,b);
    }
    public static Bitmap fit(Bitmap source,int side,float fill){
        Rect content=contentBounds(source);Bitmap out=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        if(content.isEmpty())return out;
        float scale=side*fill/Math.max(content.width(),content.height()),w=content.width()*scale,h=content.height()*scale;
        new Canvas(out).drawBitmap(source,content,new RectF((side-w)/2,(side-h)/2,(side+w)/2,(side+h)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        return out;
    }
    /** Preserve source canvas for adaptive icons; normalize only the notification version. */
    public static Bitmap monochrome(Bitmap source, boolean normalize) {
        int w=source.getWidth(),h=source.getHeight(),n=w*h;int[] pixels=new int[n];source.getPixels(pixels,0,w,0,0,w,h);
        int opaque=0;for(int p:pixels)if(Color.alpha(p)>24)opaque++;
        Bitmap clean=source;
        if(opaque>n*.85f){clean=removeBorder(source);if(clean==null)return null;clean.getPixels(pixels,0,w,0,0,w,h);}
        int visible=0;for(int i=0;i<n;i++){int a=Color.alpha(pixels[i]);if(a>24)visible++;pixels[i]=Color.argb(a,255,255,255);}
        if(visible<n*.002f)return null;
        Bitmap mask=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);mask.setPixels(pixels,0,w,0,0,w,h);
        return normalize?fit(mask,64,.82f):mask;
    }
}
