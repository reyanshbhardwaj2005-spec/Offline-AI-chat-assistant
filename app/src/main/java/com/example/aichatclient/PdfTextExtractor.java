package com.example.aichatclient;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a PDF page by page.
 *  - Normal PDFs: text comes straight from the file (PdfBox-Android), no AI needed.
 *  - Scanned pages (almost no text): the page is drawn to an image and read with ML Kit OCR.
 *
 * Call extract() from a background thread (your `executor`), never from the UI thread.
 */
public class PdfTextExtractor {

    public static final int MAX_PAGES = 400;       // safety limit for phones
    public static final int MAX_OCR_PAGES = 80;    // OCR is slow; stop after this many scanned pages
    private static final int MIN_CHARS_PER_PAGE = 30;
    private static final float OCR_SCALE = 2.0f;   // PdfRenderer draws at 72 dpi, so ~144 dpi here
    private static final int MAX_BITMAP_SIDE = 3000;

    public interface Progress {
        void onPage(int page, int total, boolean usingOcr);
    }

    public static class Page {
        public final int number;      // 1-based
        public final String text;
        public final boolean ocr;

        Page(int number, String text, boolean ocr) {
            this.number = number;
            this.text = text;
            this.ocr = ocr;
        }
    }

    public static class Result {
        public final List<Page> pages = new ArrayList<>();
        public int totalPages = 0;
        public int ocrPages = 0;
        public boolean truncated = false;   // hit MAX_PAGES or MAX_OCR_PAGES
    }

    public static Result extract(Context ctx, Uri uri, Progress progress) throws Exception {
        PDFBoxResourceLoader.init(ctx.getApplicationContext());

        File tmp = File.createTempFile("pdf_ingest_", ".pdf", ctx.getCacheDir());
        PdfRenderer renderer = null;
        ParcelFileDescriptor pfd = null;
        TextRecognizer recognizer = null;
        Result result = new Result();

        try {
            copy(ctx, uri, tmp);

            try (PDDocument doc = PDDocument.load(tmp, MemoryUsageSetting.setupTempFileOnly())) {
                if (doc.isEncrypted()) {
                    throw new IOException("This PDF is password protected");
                }
                int total = doc.getNumberOfPages();
                result.totalPages = total;
                int limit = Math.min(total, MAX_PAGES);
                if (total > MAX_PAGES) result.truncated = true;

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);   // helps with multi-column layouts

                for (int p = 1; p <= limit; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String text = stripper.getText(doc).trim();
                    boolean usedOcr = false;

                    if (text.length() < MIN_CHARS_PER_PAGE) {
                        if (result.ocrPages >= MAX_OCR_PAGES) {
                            result.truncated = true;
                        } else {
                            if (progress != null) progress.onPage(p, limit, true);
                            if (renderer == null) {
                                pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                                renderer = new PdfRenderer(pfd);
                                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                            }
                            String ocrText = ocrPage(renderer, recognizer, p - 1).trim();
                            if (ocrText.length() > text.length()) {
                                text = ocrText;
                                usedOcr = true;
                                result.ocrPages++;
                            }
                        }
                    } else if (progress != null) {
                        progress.onPage(p, limit, false);
                    }

                    if (!text.isEmpty()) result.pages.add(new Page(p, text, usedOcr));
                }
            }
        } finally {
            if (recognizer != null) recognizer.close();
            if (renderer != null) renderer.close();
            if (pfd != null) {
                try { pfd.close(); } catch (IOException ignored) { }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return result;
    }

    private static String ocrPage(PdfRenderer renderer, TextRecognizer recognizer, int index)
            throws Exception {
        PdfRenderer.Page page = renderer.openPage(index);
        Bitmap bmp = null;
        try {
            float scale = OCR_SCALE;
            int w = (int) (page.getWidth() * scale);
            int h = (int) (page.getHeight() * scale);
            int longest = Math.max(w, h);
            if (longest > MAX_BITMAP_SIDE) {            // keep memory under control
                float fix = MAX_BITMAP_SIDE / (float) longest;
                w = (int) (w * fix);
                h = (int) (h * fix);
            }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);                // PdfRenderer leaves the page transparent
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            InputImage image = InputImage.fromBitmap(bmp, 0);
            return Tasks.await(recognizer.process(image)).getText();
        } finally {
            page.close();
            if (bmp != null) bmp.recycle();
        }
    }

    private static void copy(Context ctx, Uri uri, File dest) throws IOException {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Could not open the file");
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }
    }
}
