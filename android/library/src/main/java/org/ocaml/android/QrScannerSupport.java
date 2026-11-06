package org.ocaml.android;

import android.app.Activity;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailabilityLight;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
import java.lang.ref.WeakReference;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Provider UI and decoding stay in the SDK. All entry points run on the UI thread. */
final class QrScannerSupport {
    private QrScannerSupport() {}
    private static Request active;

    static final class Request {
        final WeakReference<Activity> owner;
        final CompletableFuture<String> result = new CompletableFuture<>();
        Request(Activity activity) { owner = new WeakReference<>(activity); }
        void completeText(String text) {
            if (text == null) fail(new UnsupportedOperationException("QR code has no textual payload"));
            else { release(); result.complete(text); }
        }
        void fail(Exception error) { release(); result.completeExceptionally(normalize(error)); }
        private void release() { if (active == this) active = null; owner.clear(); }
        void accept(Task<Barcode> task) {
            // Do not scope the listener to the Activity: it stops while the
            // provider's scan screen is visible, which would lose the result.
            task.addOnCompleteListener(completed -> {
                if (completed.isCanceled()) fail(new CancellationException("QR scan cancelled"));
                else if (!completed.isSuccessful()) fail(completed.getException());
                else {
                    Barcode barcode = completed.getResult();
                    if (barcode == null || barcode.getFormat() != Barcode.FORMAT_QR_CODE)
                        fail(new IllegalStateException("Scanner returned no QR code"));
                    else completeText(barcode.getRawValue());
                }
            });
        }
    }

    static Request begin(Activity owner) {
        if (owner.isDestroyed() || owner.isFinishing())
            throw new NoSuchElementException("QR scanner activity is destroyed or finishing");
        if (active != null) throw new IllegalStateException("A QR scan is already in progress");
        active = new Request(owner);
        return active;
    }

    static CompletableFuture<String> scan(Activity owner) {
        Request request = begin(owner);
        try {
            int status = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(owner);
            if (status != ConnectionResult.SUCCESS)
                throw new UnsupportedOperationException("Google Play services unavailable: " + status);
            GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
            request.accept(GmsBarcodeScanning.getClient(owner, options).startScan());
        } catch (Exception error) { request.fail(error); }
        return request.result;
    }

    static void cancelFor(Activity owner) {
        if (active != null && active.owner.get() == owner)
            active.fail(new NoSuchElementException("QR scanner activity destroyed"));
    }

    static Exception normalize(Exception error) {
        if (error instanceof MlKitException) {
            int code = ((MlKitException) error).getErrorCode();
            String message = "QR scanner error: " + code;
            switch (code) {
                case MlKitException.CANCELLED:
                case MlKitException.CODE_SCANNER_CANCELLED:
                    return new CancellationException("QR scan cancelled");
                case MlKitException.PERMISSION_DENIED:
                case MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED:
                    return new SecurityException(message, error);
                case MlKitException.UNAVAILABLE:
                case MlKitException.CODE_SCANNER_UNAVAILABLE:
                case MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD:
                    return new UnsupportedOperationException(message, error);
                default: return new IllegalStateException(message, error);
            }
        }
        return error == null ? new IllegalStateException("QR scanner failed without an error") : error;
    }
}
