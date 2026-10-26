package com.alkaidlab.dexlocal

import com.alkaidlab.dexlocal.core.CaptureGeometry
import com.alkaidlab.dexlocal.core.HomeRestorePolicy
import org.junit.Assert.*
import org.junit.Test

class HomeAndGeometryTest {
    @Test fun originalHomeIsRestoredWhenOursIsStillSelected() { assertTrue(HomeRestorePolicy.shouldRestore("ours", "ours", "samsung")) }
    @Test fun aLaterUserHomeChoiceIsPreserved() { assertFalse(HomeRestorePolicy.shouldRestore("third", "ours", "samsung")) }
    @Test fun anInvalidSelfBackupCannotRestore() { assertFalse(HomeRestorePolicy.shouldRestore("ours", "ours", "ours")) }
    @Test fun widescreenPhoneFitsDesktopWithoutDistortion() {
        assertEquals(CaptureGeometry.Viewport(240, 0, 1920, 1080), CaptureGeometry.fit(1920, 1080, 2400, 1080))
    }
    @Test fun desktopEdgesAreReachable() {
        assertEquals(0f, CaptureGeometry.mapPosition(240f, 0f, 1920, 1080, 2400, 1080).first, 0f)
        assertEquals(1919f, CaptureGeometry.mapPosition(2160f, 1080f, 1920, 1080, 2400, 1080).first, 0f)
    }
}
