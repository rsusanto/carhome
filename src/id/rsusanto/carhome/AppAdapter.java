package id.rsusanto.carhome;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Big, touch-friendly app tiles for the app drawer. (Home tiles are built by MainActivity.appTile.) */
final class AppAdapter extends BaseAdapter {
    private final Context ctx;
    private final int iconPx, tileHeightPx;
    private final float labelSp;
    private final List<AppEntry> items = new ArrayList<>();
    private boolean simpleIcons = true;

    void setSimpleIcons(boolean simple) {
        simpleIcons = simple;
        notifyDataSetChanged();
    }

    AppAdapter(Context ctx, int iconPx, int tileHeightPx, float labelSp) {
        this.ctx = ctx;
        this.iconPx = iconPx;
        this.tileHeightPx = tileHeightPx;
        this.labelSp = labelSp;
    }

    void setItems(List<AppEntry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public AppEntry getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            LinearLayout tile = new LinearLayout(ctx);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setGravity(Gravity.CENTER);
            tile.setLayoutParams(new AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, tileHeightPx));
            int pad = Ui.dp(ctx, 8);
            tile.setPadding(pad, pad, pad, pad);
            tile.setBackground(Ui.pressOnlyBackground(ctx));

            ImageView icon = new ImageView(ctx);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(icon, new LinearLayout.LayoutParams(iconPx, iconPx));

            TextView label = new TextView(ctx);
            label.setTextColor(Color.WHITE);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(ctx, 8);
            tile.addView(label, lp);

            h = new Holder();
            h.icon = icon;
            h.label = label;
            tile.setTag(h);
            convertView = tile;
        } else {
            h = (Holder) convertView.getTag();
        }
        AppEntry e = items.get(position);
        h.icon.setImageDrawable(e.displayIcon(ctx, simpleIcons, false));
        h.label.setText(e.label);
        return convertView;
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
    }
}
