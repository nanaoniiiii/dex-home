package com.alkaidlab.dexlocal.core

/** Keep empty values distinct from absent keys. Restore only changes still owned by this app. */
internal class SettingLease(
    val namespace: String, val key: String, val before: String?, val applied: String
) {
    fun restore(read: () -> String?, write: (String?) -> Unit) {
        if (read() == applied) write(before)
    }
}
