package dev.local.sunwindowdemo.features.minusone;

import android.os.Bundle;

public record WidgetGrid(int columns, int rows, int cellWidth, int cellHeight, int gapX, int gapY,
                         int paddingLeft, int paddingTop, int paddingRight, int paddingBottom) {
    public WidgetGrid(int columns, int rows, int cellWidth, int cellHeight, int gapX, int gapY) {
        this(columns, rows, cellWidth, cellHeight, gapX, gapY, 0, 0, 0, 0);
    }
    public Bundle toBundle() {
        var out = new Bundle();
        out.putInt("columns", columns);
        out.putInt("rows", rows);
        out.putInt("cellWidth", cellWidth);
        out.putInt("cellHeight", cellHeight);
        out.putInt("gapX", gapX);
        out.putInt("gapY", gapY);
        out.putInt("paddingLeft", paddingLeft);
        out.putInt("paddingTop", paddingTop);
        out.putInt("paddingRight", paddingRight);
        out.putInt("paddingBottom", paddingBottom);
        return out;
    }

    static WidgetGrid fromBundle(Bundle bundle) {
        return new WidgetGrid(bundle.getInt("columns"), bundle.getInt("rows"), bundle.getInt("cellWidth"),
                bundle.getInt("cellHeight"), bundle.getInt("gapX"), bundle.getInt("gapY"),
                bundle.getInt("paddingLeft"), bundle.getInt("paddingTop"),
                bundle.getInt("paddingRight"), bundle.getInt("paddingBottom"));
    }

    boolean valid() { return columns > 0 && rows > 0 && cellWidth > 0 && cellHeight > 0; }
    int width(int span) { return span * (cellWidth + gapX) - gapX; }
    int height(int span) { return span * (cellHeight + gapY) - gapY; }
    int columnSpan(int px) { return clamp(Math.round((px + gapX) / (float) (cellWidth + gapX)), 1, columns); }
    int rowSpan(int px) { return clamp(Math.round((px + gapY) / (float) (cellHeight + gapY)), 1, rows); }
    int minColumns(int px) { return clamp((int) Math.ceil((px + gapX) / (double) (cellWidth + gapX)), 1, columns); }
    int minRows(int px) { return clamp((int) Math.ceil((px + gapY) / (double) (cellHeight + gapY)), 1, rows); }
    int maxColumns(int px) { return clamp((px + gapX) / (cellWidth + gapX), 1, columns); }
    int maxRows(int px) { return clamp((px + gapY) / (cellHeight + gapY), 1, rows); }

    static int clamp(int value, int min, int max) { return Math.min(Math.max(value, min), max); }
}
