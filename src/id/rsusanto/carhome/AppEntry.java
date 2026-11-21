package id.rsusanto.carhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;

final class AppEntry {
    static final String ALL_APPS_KEY = "__all_apps__";

    final ComponentName component; // null for the "All apps" tile
    final String label;
    /**
     * The app's own icon, or null while simple icons are on: then it isn't loaded at all, since a
     * launcher icon per installed app adds up to several MB that would never be drawn.
     */
    final Drawable icon;

    private Drawable simpleColored, simpleDark; // created on demand

    AppEntry(ComponentName component, String label, Drawable icon) {
        this.component = component;
        this.label = label;
        this.icon = icon;
    }

    String key() {
        return component == null ? ALL_APPS_KEY : component.flattenToShortString();
    }

    boolean isAllApps() {
        return component == null;
    }

    Drawable displayIcon(Context c, boolean simple, boolean colored) {
        if (isAllApps() || (!simple && icon != null)) return icon;
        if (colored) {
            if (simpleColored == null)
                simpleColored = SimpleIcons.forApp(c, component.getPackageName(), label, true);
            return simpleColored;
        }
        if (simpleDark == null)
            simpleDark = SimpleIcons.forApp(c, component.getPackageName(), label, false);
        return simpleDark;
    }
}
