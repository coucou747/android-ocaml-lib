package org.ocaml.android;

import android.content.Context;
import android.os.Build;
import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.health.connect.client.PermissionController;
import java.util.Collections;

/** Android 14's Health contract is an AndroidX permission request, not an activity intent. */
public final class HealthPermissionsChecks {
    public static void run(Context context) {
        if (!ComponentActivity.class.isAssignableFrom(HealthPermissionsActivity.class))
            throw new AssertionError("Health permission host must provide an ActivityResultRegistry");
        if (Build.VERSION.SDK_INT >= 34) {
            android.content.Intent intent=PermissionController.createRequestPermissionResultContract()
                .createIntent(context,Collections.singleton("android.permission.health.READ_STEPS"));
            if (!ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS.equals(intent.getAction()))
                throw new AssertionError("Health contract must be dispatched by an ActivityResultLauncher");
        }
    }
}
