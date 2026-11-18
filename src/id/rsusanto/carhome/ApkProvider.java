package id.rsusanto.carhome;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Lets the system installer read the downloaded update on Android 7+ (a tiny stand-in for
 * AndroidX's FileProvider). Serves exactly one file, read-only, and only to apps we grant it to.
 */
public class ApkProvider extends ContentProvider {
    static final String AUTHORITY = "id.rsusanto.carhome.apk";
    static final String APK_MIME = "application/vnd.android.package-archive";

    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + Updater.APK_FILE);
    }

    private File file() {
        return Updater.apkFile(getContext());
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public String getType(Uri uri) {
        return APK_MIME;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode.contains("w")) throw new SecurityException("read-only");
        if (!Updater.APK_FILE.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** Installers ask for the name and size before opening the file. */
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = Updater.APK_FILE;
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = file().length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
