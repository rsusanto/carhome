package id.rsusanto.carhome;

import android.content.Context;
import android.widget.LinearLayout;

/**
 * Landscape home row: the right column is exactly as wide as the 3 x 2 tile grid
 * (whose tile size comes from the available height), and the left panel takes the rest.
 * That keeps the clock and now-playing row aligned with the tiles.
 */
final class HomeRow extends LinearLayout {
    private final int gap, gridTopMargin;
    private LinearLayout right;

    HomeRow(Context c, int gapPx, int gridTopMarginPx) {
        super(c);
        setOrientation(HORIZONTAL);
        gap = gapPx;
        gridTopMargin = gridTopMarginPx;
    }

    void setRightColumn(LinearLayout r) { right = r; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        if (right != null && MeasureSpec.getMode(hSpec) != MeasureSpec.UNSPECIFIED) {
            int h = MeasureSpec.getSize(hSpec) - getPaddingTop() - getPaddingBottom();
            int w = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
            int cell = SquareGrid.cellForHeight(h - gridTopMargin, gap);
            int maxCell = (int) ((w * 0.6f - gap * (SquareGrid.COLS - 1)) / SquareGrid.COLS); // left keeps >= ~40%
            cell = Math.min(cell, maxCell);
            LayoutParams lp = (LayoutParams) right.getLayoutParams();
            lp.width = SquareGrid.widthForCell(cell, gap);
            lp.weight = 0;
        }
        super.onMeasure(wSpec, hSpec);
    }
}
