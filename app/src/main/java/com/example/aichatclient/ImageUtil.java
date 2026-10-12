package com.example.aichatclient;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Turns a photo from the gallery/camera into a small RGB image the vision model can read. */
public class ImageUtil {

    public static class Prepared {
        public final String path;      // small JPEG copy, also used to show the picture in the chat
        public final byte[] rgb;       // width * height * 3 bytes
        public final int width, height;

        Prepared(String path, byte[] rgb, int width, int height) {
            this.path = path;
            this.rgb = rgb;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * Decodes the image, applies the camera rotation, shrinks it so the longest side is at most
     * maxSide pixels and returns the raw RGB bytes.
     * saveCopy = true writes a JPEG copy into the app's private storage (for the chat history).
     */
    public static Prepared prepare(Context ctx, Uri uri, int maxSide, boolean saveCopy)
            throws IOException {
        ContentResolver cr = ctx.getContentResolver();

        // 1) image size without decoding it
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the image");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Not an image");

        // 2) decode a reduced version (keeps memory low for 12 MP+ photos)
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide * 2) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open the image");
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) throw new IOException("Could not decode the image");

        // 3) rotate like the camera says, and scale down to maxSide
        float scale = Math.min(1f, maxSide / (float) Math.max(decoded.getWidth(), decoded.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        int rotation = exifRotation(cr, uri);
        if (rotation != 0) m.postRotate(rotation);
        Bitmap bmp = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
        if (bmp != decoded) decoded.recycle();

        // 4) raw RGB (transparent areas become white)
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgb = new byte[w * h * 3];
        int j = 0;
        for (int p : px) {
            int a = p >>> 24;
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (a < 255) {
                r = (r * a + 255 * (255 - a)) / 255;
                g = (g * a + 255 * (255 - a)) / 255;
                b = (b * a + 255 * (255 - a)) / 255;
            }
            rgb[j++] = (byte) r;
            rgb[j++] = (byte) g;
            rgb[j++] = (byte) b;
        }

        // 5) small JPEG copy for the chat history
        String path = null;
        if (saveCopy) {
            File dir = new File(ctx.getFilesDir(), "images");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create image folder");
            File f = new File(dir, UUID.randomUUID() + ".jpg");
            try (FileOutputStream fo = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fo);
            }
            path = f.getAbsolutePath();
        } else if ("file".equals(uri.getScheme())) {
            path = uri.getPath();
        }
        bmp.recycle();
        return new Prepared(path, rgb, w, h);
    }

    /** Re-reads an image that was saved earlier (to keep asking about it after reopening a chat). */
    public static Prepared fromFile(Context ctx, String path, int maxSide) throws IOException {
        return prepare(ctx, Uri.fromFile(new File(path)), maxSide, false);
    }

    private static int exifRotation(ContentResolver cr, Uri uri) {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            switch (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
