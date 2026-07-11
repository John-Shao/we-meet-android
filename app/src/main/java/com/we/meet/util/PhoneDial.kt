package com.we.meet.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.we.meet.R

/**
 * Open the system dialer pre-filled with [phone] (ACTION_DIAL — no CALL_PHONE
 * permission, the user confirms before it rings). No-op + toast when the number
 * is blank or the device has no dialer (tablet without a SIM).
 */
fun dialNumber(context: Context, phone: String) {
    val number = phone.trim()
    if (number.isBlank()) {
        Toast.makeText(context, R.string.dial_no_number, Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, R.string.dial_unsupported, Toast.LENGTH_SHORT).show()
        }
}
