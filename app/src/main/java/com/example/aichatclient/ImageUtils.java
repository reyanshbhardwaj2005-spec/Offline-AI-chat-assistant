package com.example.aichatclient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.InputStream;

public class ImageUtils {

    public static class Rgb {
        public byte[] data;
        public int w, h;
    }

    /** Decodes, fixes EXIF rotation, downsizes so the longest side is <= maxSide. */
    public static Bitmap decode(Context c, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bmp == null) throw new Exception("Unsupported image");

        int rot = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {}
        if (rot != 0) {
            Matrix m = new Matrix();
            m.postRotate(rot);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }

        float s = Math.min(1f, maxSide / (float) Math.max(bmp.getWidth(), bmp.getHeight()));
        if (s < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * s)),
                    Math.max(1, Math.round(bmp.getHeight() * s)), true);
        }
        return bmp;
    }

    /** Small preview for the chip above the input box. */
    public static Bitmap thumbnail(Context c, Uri uri, int size) {
        try { return decode(c, uri, size); } catch (Exception e) { return null; }
    }

    /** Raw RGB bytes (3 per pixel) for the native side. Call on a background thread. */
    public static Rgb load(Context c, Uri uri, int maxSide) throws Exception {
        Bitmap b = decode(c, uri, maxSide);
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        byte[] rgb = new byte[w * h * 3];
        for (int i = 0, j = 0; i < px.length; i++) {
            rgb[j++] = (byte) ((px[i] >> 16) & 0xFF);
            rgb[j++] = (byte) ((px[i] >> 8) & 0xFF);
            rgb[j++] = (byte) (px[i] & 0xFF);
        }
        Rgb r = new Rgb();
        r.data = rgb; r.w = w; r.h = h;
        return r;
    }
}
