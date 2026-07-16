package com.overdrive.app

import android.app.Activity // <--- Alterado de androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class AppLauncherGhostActivity : Activity() { // <--- Agora herda da classe base Activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Não definimos layout. Ela nasce totalmente transparente.
    }

    override fun onResume() {
        super.onResume()
        // Dá 2.5 segundos para os apps reais serem movidos por cima dela e fecha-se
        window.decorView.postDelayed({
            finish()
        }, 2500)
    }
}