package id.rsusanto.carhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.Locale;

/**
 * Uniform line icons (Tabler Icons, MIT) instead of each app's own icon.
 * Apps are matched by package name / label keywords; anything unknown gets a letter tile.
 */
final class SimpleIcons {
    // [background, foreground] pairs for the colored home-screen tiles
    static final int[] TEAL = {0xFFE1F5EE, 0xFF0F6E56};
    static final int[] RED = {0xFFFCEBEB, 0xFFA32D2D};
    static final int[] BLUE = {0xFFE6F1FB, 0xFF185FA5};
    static final int[] GRAY = {0xFFF1EFE8, 0xFF444441};
    static final int[] DARK = {0xFF1B2029, 0xFFFFFFFF}; // app drawer

    private static final Object[][] RULES = {
            // keywords (any match, checked against "package label" lowercase), icon, color
            {new String[]{"music", "spotify", "joox", "deezer", "tidal", "soundcloud", "mp3", "audio player"}, R.drawable.ti_music, RED},
            {new String[]{"youtube"}, R.drawable.ti_player_play, RED},
            {new String[]{"camera", "dvr", "dashcam"}, R.drawable.ti_camera, GRAY},
            {new String[]{"video", "movie", "netflix", "vlc", "player", "vidio", "iflix"}, R.drawable.ti_movie, RED},
            {new String[]{"podcast", "audiobook"}, R.drawable.ti_headphones, RED},
            {new String[]{"waze", "navigat", "sygic", "petal maps"}, R.drawable.ti_navigation, BLUE},
            {new String[]{"maps", " map"}, R.drawable.ti_map_pin, TEAL},
            {new String[]{"radio"}, R.drawable.ti_radio, GRAY},
            {new String[]{"bluetooth"}, R.drawable.ti_bluetooth, BLUE},
            {new String[]{"zlink", "carplay", "android auto", "projection", "carlink", "mirror", "tlink"}, R.drawable.ti_link, BLUE},
            {new String[]{"dialer", "phone", "contacts", " call"}, R.drawable.ti_phone, BLUE},
            {new String[]{"whatsapp", "telegram", "messag", "sms", "naver.line"}, R.drawable.ti_message, BLUE},
            {new String[]{"mail"}, R.drawable.ti_mail, BLUE},
            {new String[]{"gallery", "photo"}, R.drawable.ti_photo, GRAY},
            {new String[]{"file", "explorer", "documents"}, R.drawable.ti_folder, GRAY},
            {new String[]{"chrome", "browser", "firefox", "opera", " web"}, R.drawable.ti_world, GRAY},
            {new String[]{"vending", "play store", "store", "market"}, R.drawable.ti_shopping_bag, GRAY},
            {new String[]{"calculator"}, R.drawable.ti_calculator, GRAY},
            {new String[]{"dsp", "equaliz", "sound", "amplifier"}, R.drawable.ti_adjustments, GRAY},
            {new String[]{"calendar"}, R.drawable.ti_calendar, GRAY},
            {new String[]{"clock", "alarm", "timer"}, R.drawable.ti_clock, GRAY},
            {new String[]{"weather", "drive", "cloud"}, R.drawable.ti_cloud, GRAY},
            {new String[]{"recorder", "voice", "assistant", "googlequicksearchbox"}, R.drawable.ti_microphone, GRAY},
            {new String[]{"obd", "torque", "vehicle", "tpms"}, R.drawable.ti_car, GRAY},
            {new String[]{"download", "torrent"}, R.drawable.ti_download, GRAY},
            {new String[]{"book", "reader", "kindle"}, R.drawable.ti_book, GRAY},
            {new String[]{"setting"}, R.drawable.ti_settings, GRAY},
    };

    private SimpleIcons() {}

    /** @param colored true for pastel home tiles, false for the dark drawer style */
    static Drawable forApp(Context c, String pkg, String label, boolean colored) {
        String hay = " " + (pkg + " " + label).toLowerCase(Locale.ROOT);
        for (Object[] rule : RULES) {
            for (String k : (String[]) rule[0]) {
                if (hay.contains(k)) {
                    int[] pal = colored ? (int[]) rule[2] : DARK;
                    Drawable glyph = c.getDrawable((Integer) rule[1]).mutate();
                    glyph.setTint(pal[1]);
                    return new Tile(pal[0], glyph, null, pal[1]);
                }
            }
        }
        int[] pal = colored ? GRAY : DARK;
        String letter = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.ROOT);
        return new Tile(pal[0], null, letter, pal[1]);
    }

    /** Rounded square with either a centered glyph or a letter. */
    static final class Tile extends Drawable {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable glyph;
        private final String letter;
        private final RectF r = new RectF();

        Tile(int bgColor, Drawable glyph, String letter, int fg) {
            bg.setColor(bgColor);
            text.setColor(fg);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            this.glyph = glyph;
            this.letter = letter;
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            r.set(b);
            float rad = b.width() * 0.24f;
            canvas.drawRoundRect(r, rad, rad, bg);
            if (glyph != null) {
                int inset = Math.round(b.width() * 0.22f);
                glyph.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
                glyph.draw(canvas);
            } else {
                text.setTextSize(b.height() * 0.46f);
                float y = b.exactCenterY() - (text.descent() + text.ascent()) / 2f;
                canvas.drawText(letter, b.exactCenterX(), y, text);
            }
        }

        @Override public int getIntrinsicWidth() { return 192; }
        @Override public int getIntrinsicHeight() { return 192; }
        @Override public void setAlpha(int a) { bg.setAlpha(a); text.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
