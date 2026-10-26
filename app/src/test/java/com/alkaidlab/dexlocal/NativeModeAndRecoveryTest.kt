package com.alkaidlab.dexlocal

import com.alkaidlab.dexlocal.core.NativeDexPolicy
import com.alkaidlab.dexlocal.core.SettingLease
import org.junit.Assert.*
import org.junit.Test

class NativeModeAndRecoveryTest {
    @Test fun stableMainScreenDexIsAccepted() { assertTrue(NativeDexPolicy.isMainScreenDex(4, 0, 101)) }
    @Test fun externalDexIsNotMainScreenSuccess() { assertFalse(NativeDexPolicy.isMainScreenDex(4, 0, 102)) }
    @Test fun disabledDesktopIsNotSuccess() { assertFalse(NativeDexPolicy.isMainScreenDex(2, 0, 0)) }
    @Test fun enablingDesktopIsNotSuccess() { assertFalse(NativeDexPolicy.isMainScreenDex(3, 30, 101)) }
    @Test fun configurationTransitionIsNotStable() { assertFalse(NativeDexPolicy.isMainScreenDex(4, 40, 101)) }
    @Test fun absentSettingIsDeletedOnRestore() {
        var value: String? = "120"
        SettingLease("system", "peak_refresh_rate", null, "120").restore({ value }, { value = it })
        assertNull(value)
    }
    @Test fun emptySettingIsRestoredAsEmpty() {
        var value: String? = "120"
        SettingLease("system", "peak_refresh_rate", "", "120").restore({ value }, { value = it })
        assertEquals("", value)
    }
    @Test fun userChangeIsPreserved() {
        var value: String? = "90"
        SettingLease("system", "peak_refresh_rate", "60", "120").restore({ value }, { value = it })
        assertEquals("90", value)
    }
}
