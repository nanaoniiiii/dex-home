package com.alkaidlab.dexlocal.core

internal object HomeRestorePolicy {
    fun shouldRestore(currentPackage: String, ownPackage: String, previousPackage: String): Boolean =
        currentPackage == ownPackage && previousPackage.isNotBlank() && previousPackage != ownPackage
}
