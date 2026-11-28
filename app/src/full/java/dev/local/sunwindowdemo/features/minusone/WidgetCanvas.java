package dev.local.sunwindowdemo.features.minusone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

final class WidgetCanvas extends FrameLayout {
    record Slot(int column, int row, int columns, int rows) {
        int right() { return column + columns; }
        int bottom() { return row + rows; }
        boolean intersects(Slot other) {
            return column < other.right() && right() > other.column
                    && row < other.bottom() && bottom() > other.row;
        }
        Slot at(int column, int row) { return new Slot(column, row, columns, rows); }
        int distance(Slot other) { return Math.abs(column - other.column) + Math.abs(row - other.row); }
    }

    private final WidgetGrid grid;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int accent, surface;
    private final float density;
    private Slot preview;
    private boolean allowed;

    WidgetCanvas(Context context, WidgetGrid grid) {
        super(context);
        this.grid = grid;
        density = getResources().getDisplayMetrics().density;
        accent = context.getColor(android.R.color.system_accent1_100);
        surface = context.getColor(android.R.color.system_accent1_600);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    int left(Slot slot) { return slot.column() * (grid.cellWidth() + grid.gapX()); }
    int top(Slot slot) { return slot.row() * (grid.cellHeight() + grid.gapY()); }

    void place(ResizableWidgetFrame frame, Slot slot) {
        var params = new LayoutParams(grid.width(slot.columns()), grid.height(slot.rows()));
        params.leftMargin = left(slot);
        params.topMargin = top(slot);
        frame.setLayoutParams(params);
    }

    void relocate(View view, Slot slot) {
        var params = (LayoutParams) view.getLayoutParams();
        params.leftMargin = left(slot);
        params.topMargin = top(slot);
        view.setLayoutParams(params);
        view.layout(params.leftMargin, params.topMargin,
                params.leftMargin + view.getWidth(), params.topMargin + view.getHeight());
    }

    void setPreview(Slot slot, boolean valid) {
        if (java.util.Objects.equals(preview, slot) && allowed == valid) return;
        preview = slot;
        allowed = valid;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (preview == null) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((accent & 0xffffff) | 0x78000000);
        int stepX = grid.cellWidth() + grid.gapX(), stepY = grid.cellHeight() + grid.gapY();
        var clip = canvas.getClipBounds();
        int first = Math.max(0, clip.top / stepY);
        int last = Math.min(getHeight() / stepY + 1, clip.bottom / stepY + 1);
        for (int row = first; row < last; row++) {
            for (int column = 0; column < grid.columns(); column++) {
                canvas.drawCircle(column * stepX + grid.cellWidth() / 2f,
                        row * stepY + grid.cellHeight() / 2f, 1.5f * density, paint);
            }
        }
        float x = left(preview), y = top(preview);
        float right = x + grid.width(preview.columns()), bottom = y + grid.height(preview.rows());
        int color = allowed ? accent : 0xffffc6bd;
        paint.setColor((color & 0xffffff) | 0x43000000);
        canvas.drawRoundRect(x, y, right, bottom, 24 * density, 24 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        paint.setColor(allowed ? surface : 0xffa83b30);
        canvas.drawRoundRect(x + density, y + density, right - density, bottom - density,
                24 * density, 24 * density, paint);
    }

    static boolean fits(Slot candidate, int columns, Map<Integer, Slot> positions, int id) {
        if (candidate.column() < 0 || candidate.row() < 0 || candidate.right() > columns) return false;
        for (var entry : positions.entrySet()) {
            if (entry.getKey() != id && entry.getValue().intersects(candidate)) return false;
        }
        return true;
    }

    static Slot firstFit(int width, int height, int columns, Map<Integer, Slot> positions) {
        for (int row = 0; ; row++) {
            for (int column = 0; column <= columns - width; column++) {
                var candidate = new Slot(column, row, width, height);
                if (fits(candidate, columns, positions, -1)) return candidate;
            }
        }
    }

    static Map<Integer, Slot> compactAfterRemoval(int fromRow, Map<Integer, Slot> positions) {
        var ordered = new ArrayList<>(positions.entrySet());
        ordered.sort(Comparator.comparingInt((Map.Entry<Integer, Slot> entry) -> entry.getValue().row())
                .thenComparingInt(entry -> entry.getValue().column()));
        var settled = new LinkedHashMap<Integer, Slot>();
        var moves = new LinkedHashMap<Integer, Slot>();
        for (var entry : ordered) {
            Slot original = entry.getValue(), next = original;
            if (original.row() >= fromRow) {
                int row = fromRow;
                for (Slot above : settled.values()) {
                    if (original.column() < above.right() && original.right() > above.column())
                        row = Math.max(row, above.bottom());
                }
                next = original.at(original.column(), Math.min(row, original.row()));
            }
            settled.put(entry.getKey(), next);
            if (!next.equals(original)) moves.put(entry.getKey(), next);
        }
        return moves;
    }

    /** Returns the additional moves needed to clear a drop, or null when it cannot fit. */
    static Map<Integer, Slot> rearrange(Slot drop, int columns, int rows, Map<Integer, Slot> positions, int id) {
        if (!inside(drop, columns, rows)) return null;
        if (fits(drop, columns, positions, id)) return Map.of();
        var original = positions.get(id);
        int dx = drop.column() - original.column(), dy = drop.row() - original.row();
        boolean horizontal = Math.abs(dx) >= Math.abs(dy);
        int direction = Integer.signum(horizontal ? dx : dy);
        var push = pushAside(drop, columns, rows, positions, id, horizontal, direction);
        var nearest = moveToVacancies(drop, columns, rows, positions, id);
        if (push == null) return nearest;
        if (nearest == null || movement(push, positions) <= movement(nearest, positions) + 1) return push;
        return nearest;
    }

    private static Map<Integer, Slot> pushAside(Slot drop, int columns, int rows, Map<Integer, Slot> positions,
                                               int id, boolean horizontal, int direction) {
        if (direction == 0) return null;
        var ordered = new ArrayList<>(positions.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> {
            var slot = entry.getValue();
            if (horizontal) return direction > 0 ? slot.column() : -slot.right();
            return direction > 0 ? slot.row() : -slot.bottom();
        }));
        var settled = new LinkedHashMap<Integer, Slot>();
        var moves = new LinkedHashMap<Integer, Slot>();
        settled.put(id, drop);
        for (var entry : ordered) {
            if (entry.getKey() == id) continue;
            var original = entry.getValue();
            var target = original;
            boolean moved;
            do {
                moved = false;
                for (var occupied : settled.values()) {
                    if (!occupied.intersects(target)) continue;
                    target = horizontal ? target.at(direction > 0 ? occupied.right()
                                    : occupied.column() - target.columns(), target.row())
                            : target.at(target.column(), direction > 0 ? occupied.bottom()
                                    : occupied.row() - target.rows());
                    moved = true;
                }
            } while (moved);
            if (!inside(target, columns, rows)) return null;
            settled.put(entry.getKey(), target);
            if (!target.equals(original)) moves.put(entry.getKey(), target);
        }
        return moves;
    }

    private static Map<Integer, Slot> moveToVacancies(Slot drop, int columns, int rows,
                                                      Map<Integer, Slot> positions, int id) {
        var occupied = new LinkedHashMap<Integer, Slot>();
        var blocked = new ArrayList<Map.Entry<Integer, Slot>>();
        var moves = new LinkedHashMap<Integer, Slot>();
        occupied.put(id, drop);
        for (var entry : positions.entrySet()) {
            if (entry.getKey() == id) continue;
            if (drop.intersects(entry.getValue())) blocked.add(entry);
            else occupied.put(entry.getKey(), entry.getValue());
        }
        blocked.sort(Comparator.comparingInt((Map.Entry<Integer, Slot> entry) ->
                entry.getValue().columns() * entry.getValue().rows()).reversed());
        for (var entry : blocked) {
            var source = entry.getValue();
            Slot nearest = null;
            for (int column = 0; column <= columns - source.columns(); column++) {
                var candidate = source.at(column, source.row());
                nearest = closer(candidate, nearest, source, columns, rows, occupied);
                for (var neighbor : occupied.values()) {
                    if (column >= neighbor.right() || candidate.right() <= neighbor.column()) continue;
                    nearest = closer(candidate.at(column, neighbor.row() - source.rows()),
                            nearest, source, columns, rows, occupied);
                    nearest = closer(candidate.at(column, neighbor.bottom()),
                            nearest, source, columns, rows, occupied);
                }
            }
            if (nearest == null) return null;
            moves.put(entry.getKey(), nearest);
            occupied.put(entry.getKey(), nearest);
        }
        return moves;
    }

    private static Slot closer(Slot candidate, Slot current, Slot source, int columns, int rows,
                               Map<Integer, Slot> occupied) {
        if (current != null && candidate.distance(source) >= current.distance(source)) return current;
        return inside(candidate, columns, rows) && fits(candidate, columns, occupied, -1) ? candidate : current;
    }

    private static boolean inside(Slot slot, int columns, int rows) {
        return slot.column() >= 0 && slot.right() <= columns && slot.row() >= 0 && slot.bottom() <= rows;
    }

    private static int movement(Map<Integer, Slot> moves, Map<Integer, Slot> original) {
        return moves.entrySet().stream().mapToInt(entry -> entry.getValue().distance(original.get(entry.getKey()))).sum();
    }
}
