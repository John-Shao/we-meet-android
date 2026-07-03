package com.we.meet.ui.approval

import com.we.meet.R

/** Map a backend status string to its label resource. Unknown → shown verbatim. */
fun approvalStatusLabelRes(status: String): Int? = when (status) {
    "pending" -> R.string.approval_status_pending
    "approved" -> R.string.approval_status_approved
    "rejected" -> R.string.approval_status_rejected
    "cancelled" -> R.string.approval_status_cancelled
    "needs_assignment" -> R.string.approval_status_needs_assignment
    else -> null
}
