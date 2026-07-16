package com.overdrive.app

import android.app.Activity // <--- Alterado de androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class AppLauncherGhostActivity : Activity() { // <--- Now inherits from the base Activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // without layout. totally transparent.
    }

    override fun onResume() {
        super.onResume()
        // 2.5 second to app opens over it and then destroy itself
        window.decorView.postDelayed({
            finishAndRemoveTask()
        }, 2500)
    }
}