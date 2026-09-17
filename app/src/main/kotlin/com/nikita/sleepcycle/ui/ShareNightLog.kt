package com.nikita.sleepcycle.ui

// File purpose: shares one saved night log file through the system share sheet, via FileProvider so another app
// can read a file that lives in our private storage.

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nikita.sleepcycle.ui.state.NightLogSummary

private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

/** Opens the system share sheet for one night log file, granting the receiving app temporary read access. */
fun shareNightLogFile(context: Context, log: NightLogSummary) {
    val authority = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX
    val uri = FileProvider.getUriForFile(context, authority, log.file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, log.displayName))
}
