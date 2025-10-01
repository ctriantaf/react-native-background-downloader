package com.eko.utils

import com.eko.TASK_CANCELING
import com.eko.TASK_COMPLETED
import com.eko.TASK_RUNNING
import com.eko.TASK_SUSPENDED
import com.tonyodev.fetch2.Status

fun convertFetchStatus(status: Status): Int {
    return when (status) {
        Status.NONE -> TASK_SUSPENDED
        Status.QUEUED -> TASK_RUNNING
        Status.DOWNLOADING -> TASK_RUNNING
        Status.PAUSED -> TASK_SUSPENDED
        Status.COMPLETED -> TASK_COMPLETED
        Status.CANCELLED -> TASK_CANCELING
        Status.FAILED -> TASK_SUSPENDED
        Status.REMOVED -> TASK_SUSPENDED
        Status.DELETED -> TASK_SUSPENDED
        Status.ADDED -> TASK_RUNNING
    }
}