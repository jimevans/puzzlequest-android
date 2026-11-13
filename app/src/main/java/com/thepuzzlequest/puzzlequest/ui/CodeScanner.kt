package com.thepuzzlequest.puzzlequest.ui

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/**
 * Reading a puzzle's QR code with Google Play services' scanner. It shows its
 * own camera screen and hands the app only the code it read, so the app needs
 * no camera permission, and no picture leaves the phone. The code on a
 * puzzle's card holds just its activation code, which is sent as if typed.
 */
object CodeScanner {
    /** Whether this phone can scan: it has a camera, and Google Play services. */
    fun isAvailable(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    /**
     * Opens the scanner. It calls [onCode] with what it read, [onFailed] if it
     * couldn't scan (as when its module couldn't be downloaded), and nothing
     * if the player closed it.
     */
    fun scan(
        context: Context,
        onCode: (String) -> Unit,
        onFailed: () -> Unit,
    ) {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning
            .getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(onCode)
                    ?: onFailed()
            }.addOnFailureListener { onFailed() }
    }
}
