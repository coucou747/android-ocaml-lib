package org.ocaml.android;

import android.app.Activity;
import com.google.mlkit.common.MlKitException;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;

/** Provider outcomes without opening its camera or needing Google Play services. */
public final class QrScannerChecks {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void error(int code, Class<?> expected) {
        check(expected.isInstance(QrScannerSupport.normalize(new MlKitException("test", code))), "QR error mapping: " + code);
    }
    public static void run(Activity owner) {
        QrScannerSupport.Request request = QrScannerSupport.begin(owner);
        try { QrScannerSupport.begin(owner); throw new AssertionError("Concurrent QR scan accepted"); }
        catch (IllegalStateException expected) {}
        String text = "https://example.invalid/été?code=🐫\n\u0000";
        request.completeText(text);
        check(text.equals(request.result.join()), "QR raw text preserved");
        request = QrScannerSupport.begin(owner);request.completeText("");
        check(request.result.join().isEmpty(), "Empty text is not cancellation");
        request = QrScannerSupport.begin(owner);request.completeText(null);
        check(request.result.handle((value, failure) -> failure).join() instanceof UnsupportedOperationException, "Binary-only QR rejected explicitly");
        request = QrScannerSupport.begin(owner);QrScannerSupport.cancelFor(owner);
        check(request.result.handle((value, failure) -> failure).join() instanceof NoSuchElementException, "Destroyed owner completes pending scan");
        QrScannerSupport.Request next = QrScannerSupport.begin(owner);
        request.completeText("late"); // Late provider completion must not release the next request.
        try { QrScannerSupport.begin(owner); throw new AssertionError("Late result released another scan"); }
        catch (IllegalStateException expected) {}
        next.fail(new CancellationException("test"));
        check(next.result.isCancelled(), "Provider cancellation");
        error(MlKitException.CODE_SCANNER_CANCELLED, CancellationException.class);
        error(MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED, SecurityException.class);
        error(MlKitException.CODE_SCANNER_UNAVAILABLE, UnsupportedOperationException.class);
        error(MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD, UnsupportedOperationException.class);
        error(MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR, IllegalStateException.class);
    }
}
