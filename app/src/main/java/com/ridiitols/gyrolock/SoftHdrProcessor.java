package com.ridiitols.gyrolock;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import androidx.exifinterface.media.ExifInterface;
import java.io.File;
import java.io.FileOutputStream;

/**
 * Single-frame photo tone mapping. HDR is intentionally applied AFTER capture,
 * so it does not alter the live camera preview. Dramatic Cool is the same grade
 * used by the live preview and the saved photo.
 */
public final class SoftHdrProcessor {
    private SoftHdrProcessor() {}

    public static boolean apply(File jpeg, boolean hdrEnabled, boolean dramaticCoolEnabled) {
        if (jpeg == null || !jpeg.isFile() || jpeg.length() <= 0) return false;
        if (!hdrEnabled && !dramaticCoolEnabled) return true;
        File parent = jpeg.getParentFile();
        if (parent == null) return false;
        File temp = new File(parent, jpeg.getName() + ".proc.tmp.jpg");
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            long pixels = (long) bounds.outWidth * bounds.outHeight;
            if (pixels > 18_000_000L) return false;

            ExifInterface original = new ExifInterface(jpeg.getAbsolutePath());
            String[] tags = {
                    ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME,
                    ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE,
                    ExifInterface.TAG_MODEL, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
                    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY
            };
            String[] values = new String[tags.length];
            for (int i = 0; i < tags.length; i++) values[i] = original.getAttribute(tags[i]);

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inMutable = true;
            bitmap = BitmapFactory.decodeFile(jpeg.getAbsolutePath(), opts);
            if (bitmap == null) return false;

            int w = bitmap.getWidth(), h = bitmap.getHeight();
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x++) {
                    int c = row[x];
                    int a = (c >>> 24) & 0xff;
                    double r = ((c >>> 16) & 0xff) / 255.0;
                    double g = ((c >>> 8) & 0xff) / 255.0;
                    double b = (c & 0xff) / 255.0;
                    double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;

                    if (hdrEnabled) {
                        // 3.0.10: deliberately gentle single-frame HDR. The previous
                        // curve lifted shadows and compressed highlights too aggressively.
                        // Only make a small tonal correction and preserve the camera look.
                        double shadow = smooth(0.60, 0.08, l);
                        double highlight = smooth(0.62, 0.94, l);
                        double target = l
                                + 0.095 * shadow * (1.0 - l)
                                - 0.075 * highlight * highlight;
                        target = clamp(target, 0.0, 1.0);
                        double scale = l > 0.015 ? target / l : 1.0;
                        r = clamp(r * scale, 0, 1);
                        g = clamp(g * scale, 0, 1);
                        b = clamp(b * scale, 0, 1);

                        // Almost-flat midtone curve: keep the original camera contrast.
                        r = toneCurve(r);
                        g = toneCurve(g);
                        b = toneCurve(b);
                    }

                    if (dramaticCoolEnabled) {
                        double shadow = 1.0 - smooth(0.18, 0.82, l);
                        double cool = 0.028 + 0.035 * shadow;
                        r = clamp(r - cool * 0.60, 0, 1);
                        g = clamp(g + cool * 0.05, 0, 1);
                        b = clamp(b + cool, 0, 1);
                        double gray = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                        double sat = 1.105;
                        r = clamp(gray + (r - gray) * sat, 0, 1);
                        g = clamp(gray + (g - gray) * sat, 0, 1);
                        b = clamp(gray + (b - gray) * sat, 0, 1);
                    }

                    row[x] = (a << 24)
                            | ((int)(r * 255.0 + 0.5) << 16)
                            | ((int)(g * 255.0 + 0.5) << 8)
                            | (int)(b * 255.0 + 0.5);
                }
                bitmap.setPixels(row, 0, w, 0, y, w, 1);
            }

            try (FileOutputStream out = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out)) return false;
                out.flush();
                out.getFD().sync();
            }

            ExifInterface processed = new ExifInterface(temp.getAbsolutePath());
            for (int i = 0; i < tags.length; i++) if (values[i] != null) processed.setAttribute(tags[i], values[i]);
            processed.saveAttributes();

            File backup = new File(parent, jpeg.getName() + ".original.tmp");
            if (!jpeg.renameTo(backup)) return false;
            if (!temp.renameTo(jpeg)) { backup.renameTo(jpeg); return false; }
            if (!backup.delete()) backup.deleteOnExit();
            return true;
        } catch (Throwable e) {
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (temp.exists()) temp.delete();
        }
    }

    private static double toneCurve(double v) {
        // Very mild S curve, centered at 0.5.
        return clamp(0.5 + (v - 0.5) * 1.012, 0.0, 1.0);
    }
    private static double smooth(double e0, double e1, double x) {
        double t = clamp((x - e0) / (e1 - e0), 0, 1);
        return t * t * (3.0 - 2.0 * t);
    }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
