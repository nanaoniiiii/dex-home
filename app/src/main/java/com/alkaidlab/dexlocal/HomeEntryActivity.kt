package com.alkaidlab.dexlocal

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle

/** HOME may be requested on either display. The visible host always lives on phone display 0. */
class HomeEntryActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        try {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = 0 }
            startActivity(Intent(this, DexHostActivity::class.java).setAction(Intent.ACTION_MAIN)
                .putExtra("dex_home", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), options.toBundle())
        } finally { finish() }
    }
}
