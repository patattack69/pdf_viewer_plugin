package dev.britto.pdf_viewer_plugin;

import com.github.barteksc.pdfviewer.PDFView;
import com.shockwave.pdfium.PdfDocument;
import com.shockwave.pdfium.PdfiumCore;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.platform.PlatformView;

/// Disable the PdfView recycle when onDetachedFromWindow is called, fixing the
/// flutter hot reload.
class CustomPDFView extends PDFView {
    // TEMPORARY: lifecycle diagnostics sink.
    interface DiagnosticsSink {
        void log(String message);
    }

    boolean enableRecycle = true;
    DiagnosticsSink diagnosticsSink;

    public CustomPDFView(Context context, AttributeSet set) {
        super(context, set);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        logLifecycle("view: attached to window");
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.i("PdfViewer", "onDetachedFromWindow");
        logLifecycle("view: detached from window");
        enableRecycle = false;
        super.onDetachedFromWindow();
        enableRecycle = true;
    }

    @Override
    public void recycle() {
        if (enableRecycle) {
            logLifecycle("view: recycle");
            super.recycle();
        } else {
            logLifecycle("view: recycle skipped (detach workaround)");
        }
    }

    private void logLifecycle(String message) {
        if (diagnosticsSink != null) diagnosticsSink.log(message);
    }
}

public class PdfViewer implements PlatformView, MethodCallHandler {
    // TEMPORARY: load diagnostics, pulled by Dart via `getDiagnostics` and pushed via `diagnostics`.
    private static final int MAX_PAGE_ERRORS = 5;
    private static final int MAX_LAYOUT_LOGS = 5;
    private static final int EOF_SEARCH_BYTES = 1024;
    private static final int CHECK_RENDER_WIDTH = 300;
    private static final String[] PDFIUM_LIBRARIES = {"modpng", "modft2", "modpdfium", "jniPdfium"};

    final MethodChannel methodChannel;
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> diagnostics = new ArrayList<>();
    private final long createdAt = SystemClock.elapsedRealtime();
    private boolean disposed = false;
    private int pageErrors = 0;
    private int draws = 0;
    private int layouts = 0;
    private PDFView pdfView;
    private String filePath;

    PdfViewer(final Context context,
              MethodChannel methodChannel,
              Map<String, Object> params,
              View containerView) {
//        Log.i("PdfViewer", "init");

        this.context = context;
        this.methodChannel = methodChannel;
        this.methodChannel.setMethodCallHandler(this);

        if (!params.containsKey("filePath")) {
            log("no filePath param");
            return;
        }
        filePath = (String)params.get("filePath");

        logDevice();
        CustomPDFView customView = new CustomPDFView(context, null);
        customView.diagnosticsSink = this::log;
        pdfView = customView;
        pdfView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            if (width == oldRight - oldLeft && height == oldBottom - oldTop) return;
            layouts++;
            if (layouts <= MAX_LAYOUT_LOGS) {
                log("layout: view=" + width + "x" + height + " (was " + (oldRight - oldLeft) + "x" + (oldBottom - oldTop) + ")");
            } else if (layouts == MAX_LAYOUT_LOGS + 1) {
                log("layout: more size changes suppressed");
            }
        });
        loadPdfView();
        runChecksInBackground();
    }

    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (call.method.equals("getPdfViewer")) {
            result.success(null);
        } else if (call.method.equals("getDiagnostics")) {
            result.success(new ArrayList<>(diagnostics));
        } else {
            result.notImplemented();
        }
    }

    private void loadPdfView() {
        log("load: start");
        pdfView.fromFile(new File(filePath))
                .enableSwipe(true) // allows to block changing pages using swipe
                .swipeHorizontal(false)
                .enableDoubletap(true)
                .defaultPage(0)
                .onLoad(nbPages -> {
                    logLayout("load: complete pages=" + nbPages);
                    mainHandler.postDelayed(() -> logLayout("load+2s"), 2000);
                })
                .onError(t -> log("load: error " + describeThrowable(t)))
                .onPageError((page, t) -> {
                    pageErrors++;
                    if (pageErrors <= MAX_PAGE_ERRORS) {
                        log("page error: page=" + page + " " + describeThrowable(t));
                    } else if (pageErrors == MAX_PAGE_ERRORS + 1) {
                        log("page error: more errors suppressed");
                    }
                })
                .onRender((nbPages, pageWidth, pageHeight) -> logLayout(String.format(Locale.US,
                        "render: initial pages=%d page=%.0fx%.0f", nbPages, pageWidth, pageHeight)))
                .onDraw((canvas, pageWidth, pageHeight, displayedPage) -> {
                    draws++;
                    if (draws == 1) {
                        log(String.format(Locale.US, "draw: first page=%d canvas=%dx%d",
                                displayedPage, canvas.getWidth(), canvas.getHeight()));
                    }
                })
                .load();
    }

    @Override
    public View getView() {
        return pdfView;
    }

     @Override
    public void onFlutterViewAttached(@NonNull View flutterView) {
        log("flutter view: attached");
    }

    @Override
    public void onFlutterViewDetached() {
        log("flutter view: detached");
    }


    @Override
    public void dispose() {
        log("dispose"); // Logcat only: Dart is usually gone by now.
        disposed = true;
        mainHandler.removeCallbacksAndMessages(null);
        methodChannel.setMethodCallHandler(null);
    }

    // region TEMPORARY diagnostics

    /// Callable from any thread: the line is stored and pushed on the main thread.
    private void log(final String message) {
        final String line = "+" + (SystemClock.elapsedRealtime() - createdAt) + "ms " + message;
        Log.i("PdfViewer", line);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            addLine(line);
        } else {
            mainHandler.post(() -> addLine(line));
        }
    }

    private void addLine(String line) {
        if (disposed) return;
        diagnostics.add(line);
        // The whole list is sent every time: messages sent before Dart registers its handler may be dropped.
        methodChannel.invokeMethod("diagnostics", new ArrayList<>(diagnostics));
    }

    private static String describeThrowable(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        for (int depth = 0; current != null && depth < 3; depth++) {
            if (depth > 0) sb.append(" <- ");
            sb.append(current.getClass().getName()).append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return sb.toString();
    }

    private void logDevice() {
        String abi = Build.VERSION.SDK_INT >= 21 ? Build.SUPPORTED_ABIS[0] : Build.CPU_ABI;
        String pageSize = Build.VERSION.SDK_INT >= 21 ? String.valueOf(Os.sysconf(OsConstants._SC_PAGESIZE)) : "?";
        log("device: sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL + " abi=" + abi + " pageSize=" + pageSize);
    }

    private void logLayout(String label) {
        if (pdfView == null) return;
        log(String.format(Locale.US,
                "%s: view=%dx%d shown=%b recycled=%b page=%d/%d zoom=%.2f optimal=%.0fx%.0f draws=%d",
                label, pdfView.getWidth(), pdfView.getHeight(), pdfView.isShown(), pdfView.isRecycled(),
                pdfView.getCurrentPage(), pdfView.getPageCount(), pdfView.getZoom(),
                pdfView.getOptimalPageWidth(), pdfView.getOptimalPageHeight(), draws));
    }

    private void runChecksInBackground() {
        final String path = filePath;
        new Thread(() -> {
            logFile(path);
            logNativeLibraries();
            logPdfium(path);
            logPdfRenderer(path);
        }, "PdfViewerDiagnostics").start();
    }

    private void logFile(String path) {
        File file = new File(path);
        log("native file: exists=" + file.exists() + " size=" + file.length() + " readable=" + file.canRead());
        if (!file.exists()) return;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            long length = raf.length();
            byte[] head = new byte[(int) Math.min(8, length)];
            raf.readFully(head);
            int tailLength = (int) Math.min(EOF_SEARCH_BYTES, length);
            byte[] tail = new byte[tailLength];
            raf.seek(length - tailLength);
            raf.readFully(tail);
            boolean eof = new String(tail, "ISO-8859-1").contains("%%EOF");
            log("native file: head=" + toHex(head) + " eofInLast1KB=" + eof);
        } catch (Throwable t) {
            log("native file: read failed " + describeThrowable(t));
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /// PdfiumCore swallows library load failures (e.g. on 16 KB page size devices), so reload them to get the reason.
    private void logNativeLibraries() {
        for (String library : PDFIUM_LIBRARIES) {
            try {
                System.loadLibrary(library); // No-op when already loaded.
            } catch (Throwable t) {
                log("pdfium libs: " + library + " " + describeThrowable(t));
                return;
            }
        }
        log("pdfium libs: ok");
    }

    /// Opens the file with the same Pdfium build the viewer uses and renders page 1 off-screen.
    private void logPdfium(String path) {
        PdfiumCore core;
        try {
            core = new PdfiumCore(context);
        } catch (Throwable t) {
            log("pdfium: init failed " + describeThrowable(t));
            return;
        }
        ParcelFileDescriptor fd = null;
        PdfDocument document = null;
        try {
            fd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            document = core.newDocument(fd);
            int pages = core.getPageCount(document);
            PdfDocument.Meta meta = core.getDocumentMeta(document);
            log("pdfium: pages=" + pages
                    + " producer=\"" + truncate(meta.getProducer()) + "\" creator=\"" + truncate(meta.getCreator()) + "\"");
            if (pages == 0) return;
            core.openPage(document, 0);
            int widthPt = core.getPageWidthPoint(document, 0);
            int heightPt = core.getPageHeightPoint(document, 0);
            String plain = renderPdfium(core, document, widthPt, heightPt, false);
            String annotated = renderPdfium(core, document, widthPt, heightPt, true);
            log("pdfium: page1=" + widthPt + "x" + heightPt + "pt ink=" + plain + " inkWithAnnots=" + annotated);
        } catch (Throwable t) {
            log("pdfium: failed " + describeThrowable(t));
        } finally {
            if (document != null) {
                core.closeDocument(document); // Also closes fd.
            } else if (fd != null) {
                try { fd.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static String renderPdfium(PdfiumCore core, PdfDocument document, int widthPt, int heightPt,
                                       boolean annotations) {
        Bitmap bitmap = createCheckBitmap(widthPt, heightPt);
        if (bitmap == null) return "badSize";
        try {
            core.renderPageBitmap(document, bitmap, 0, 0, 0, bitmap.getWidth(), bitmap.getHeight(), annotations);
            return inkRatio(bitmap);
        } catch (Throwable t) {
            return "error(" + t.getClass().getSimpleName() + ")";
        } finally {
            bitmap.recycle();
        }
    }

    /// Cross-check with the platform renderer (a newer Pdfium shipped with Android).
    private void logPdfRenderer(String path) {
        if (Build.VERSION.SDK_INT < 21) {
            log("pdfrenderer: unavailable");
            return;
        }
        ParcelFileDescriptor fd = null;
        PdfRenderer renderer = null;
        try {
            fd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(fd); // Takes ownership of fd.
            int pages = renderer.getPageCount();
            if (pages == 0) {
                log("pdfrenderer: pages=0");
                return;
            }
            PdfRenderer.Page page = renderer.openPage(0);
            String ink;
            Bitmap bitmap = createCheckBitmap(page.getWidth(), page.getHeight());
            if (bitmap == null) {
                ink = "badSize";
            } else {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                ink = inkRatio(bitmap);
                bitmap.recycle();
            }
            log("pdfrenderer: pages=" + pages + " page1=" + page.getWidth() + "x" + page.getHeight() + "pt ink=" + ink);
            page.close();
        } catch (Throwable t) {
            log("pdfrenderer: failed " + describeThrowable(t));
        } finally {
            if (renderer != null) {
                renderer.close();
            } else if (fd != null) {
                try { fd.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private static Bitmap createCheckBitmap(int widthPt, int heightPt) {
        if (widthPt <= 0 || heightPt <= 0) return null;
        int height = (int) Math.min(4000L, Math.max(1L, (long) CHECK_RENDER_WIDTH * heightPt / widthPt));
        Bitmap bitmap = Bitmap.createBitmap(CHECK_RENDER_WIDTH, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        return bitmap;
    }

    /// Share of pixels that are not (near) white, i.e. whether anything visible was drawn.
    private static String inkRatio(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int ink = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) > 16
                    && (Color.red(pixel) < 240 || Color.green(pixel) < 240 || Color.blue(pixel) < 240)) {
                ink++;
            }
        }
        return String.format(Locale.US, "%.2f%%", 100.0 * ink / pixels.length);
    }

    private static String truncate(String value) {
        if (value == null) return "null";
        return value.length() > 40 ? value.substring(0, 40) + "..." : value;
    }

    // endregion
}
