package com.alkaidlab.dexlocal.core

internal object NativeDexPolicy {
    fun isMainScreenDex(enabled: Int, state: Int, displayType: Int): Boolean = enabled == 4 && state == 0 && displayType == 101
}
