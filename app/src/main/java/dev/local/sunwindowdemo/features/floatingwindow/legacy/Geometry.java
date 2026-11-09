package dev.local.sunwindowdemo;

/** SPDX-License-Identifier: Apache-2.0
 * Geometry adapted from Nameless/SunOS WindowResizingAlgorithm.
 * Copyright (C) 2023-2024 The Nameless-AOSP Project
 * Changes: pure Java, explicit safe rectangle, landscape left placement.
 */
final class Geometry {
    record Placement(int width, int height, float scale, float x, float y) {}

    static Placement miniTopRight(Placement p,float normal,int left,int top,int right,int bottom,float margin){
        return miniTopRight(p,normal,left,top,right,bottom,margin,false);
    }
    static Placement miniTopRight(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large){
        if(p==null||p.width()<=0||p.height()<=0||!Float.isFinite(normal)||normal<=0
            ||!Float.isFinite(margin)||right<=left||bottom<=top||margin<0)throw new IllegalArgumentException("safe area");
        // SunOS small=.28, large portrait=.43 / landscape=.517. Keep the large
        // preview below the normal size and fit the actual display safe area.
        float small=Math.min(.28f,normal);
        float scale=large?Math.max(small,Math.min(normal*.9f,p.width()>p.height()?.517f:.43f)):small;
        scale=Math.min(scale,Math.min((right-left-2*margin)/p.width(),(bottom-top-2*margin)/p.height()));
        if(scale<=0)throw new IllegalArgumentException("mini fit");
        return new Placement(p.width(),p.height(),scale,right-margin-p.width()*scale,top+margin);
    }
    static Placement resizeMini(Placement p,float normal,int left,int top,int right,int bottom,float margin,boolean large){
        Placement target=miniTopRight(p,normal,left,top,right,bottom,margin,large);
        float available=bottom-top-2*margin-p.height()*p.scale();
        float ratio=available<=0?0:Math.max(0,Math.min(1,(p.y()-top-margin)/available));
        boolean dockLeft=p.x()+p.width()*p.scale()/2<(left+right)/2f;
        float x=dockLeft?left+margin:target.x();
        float y=top+margin+ratio*Math.max(0,bottom-top-2*margin-p.height()*target.scale());
        return new Placement(p.width(),p.height(),target.scale(),x,y);
    }
    static Placement place(int logicalShort, boolean appLandscape,
            int left, int top, int right, int bottom, int margin) {
        if (logicalShort <= 0 || right <= left || bottom <= top || margin < 0)
            throw new IllegalArgumentException("geometry");
        int longSide = Math.round(logicalShort * (16f / 9f));
        int w = appLandscape ? longSide : logicalShort;
        int h = appLandscape ? logicalShort : longSide;
        boolean displayPortrait = bottom - top >= right - left;
        float scale = appLandscape ? (displayPortrait ? .9f : .75f)
            : (displayPortrait ? .75f : 1f);
        if (appLandscape == displayPortrait) scale *= (float) logicalShort / longSide;
        int availableW = right - left - 2 * margin;
        int availableH = bottom - top - 2 * margin;
        if (availableW <= 0 || availableH <= 0) throw new IllegalArgumentException("safe area");
        float fit=Math.min((float)availableW/w,(float)availableH/h);
        scale=displayPortrait?Math.min(scale,fit):fit;
        if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("scale");
        return new Placement(w, h, scale,
            displayPortrait ? left + (right - left - w * scale) / 2f : left + margin,
            top + (bottom - top - h * scale) / 2f);
    }
}
