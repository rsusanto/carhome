package id.rsusanto.carhome;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Fixed 3 × 2 grid of square cells. The cell size is the largest square that fits the width
 * without taking more than {@link #MAX_HEIGHT_SHARE} of the height it's offered,
 * so there's always room left above it for the clock and now-playing.
 */
final class SquareGrid extends ViewGroup {
    static final int COLS = 3, ROWS = 2;
    private static final float MAX_HEIGHT_SHARE = 0.64f;
    private final int gap;
    private int cell;

    /** Largest cell that keeps the grid within its share of height {@code h}. */
    static int cellForHeight(int h, int gap) {
        return Math.max(0, (int) ((h * MAX_HEIGHT_SHARE - gap * (ROWS - 1)) / ROWS));
    }

    static int widthForCell(int cell, int gap) {
        return cell * COLS + gap * (COLS - 1);
    }

    SquareGrid(Context c, int gapPx) {
        super(c);
        gap = gapPx;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int byWidth = (w - gap * (COLS - 1)) / COLS;
        int cellSize = byWidth;
        if (MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED) {
            int h = MeasureSpec.getSize(hSpec);
            int byHeight = cellForHeight(h, gap);
            cellSize = Math.min(byWidth, byHeight);
        }
        cell = Math.max(cellSize, 0);
        int spec = MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(spec, spec);
        setMeasuredDimension(w, cell * ROWS + gap * (ROWS - 1));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int gridW = widthForCell(cell, gap);
        int x0 = (r - l - gridW) / 2; // center horizontally if height was the limit
        for (int i = 0; i < getChildCount() && i < COLS * ROWS; i++) {
            View v = getChildAt(i);
            int col = i % COLS, row = i / COLS;
            int x = x0 + col * (cell + gap), y = row * (cell + gap);
            v.layout(x, y, x + cell, y + cell);
        }
    }
}
