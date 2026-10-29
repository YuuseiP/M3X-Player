package cn.yuusei.m3x;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Only packaged into the instrumentation APK. Models writable, read-only and interrupted SAF documents. */
public final class LyricsTestProvider extends ContentProvider {
    private final java.util.Set<String> failed = new java.util.HashSet<>();
    public boolean onCreate() { return true; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-z-]+\\.flac")) throw new FileNotFoundException("invalid fixture");
        File file = new File(getContext().getCacheDir(), name);
        if (!mode.equals("r") && name.startsWith("readonly")) throw new FileNotFoundException("read only fixture");
        if (!mode.equals("r") && name.startsWith("fault") && failed.add(name)) {
            try (RandomAccessFile out = new RandomAccessFile(file, "rw")) { out.setLength(0); } catch (IOException e) { throw new FileNotFoundException(e.getMessage()); }
            throw new FileNotFoundException("injected provider failure after truncate");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }
    public android.os.Bundle call(String method, String name, android.os.Bundle extras) {
        if (!method.equals("reset") || name == null || !name.matches("[a-z-]+\\.flac")) throw new IllegalArgumentException();
        File target = new File(getContext().getCacheDir(), name); failed.remove(name);
        try (InputStream in = getContext().getAssets().open("fixture-24-96.flac"); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } catch (IOException error) { throw new IllegalStateException(error); }
        return new android.os.Bundle();
    }
    public String getType(Uri uri) { return "audio/flac"; }
    public Cursor query(Uri u, String[] p, String s, String[] a, String order) { return null; }
    public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
