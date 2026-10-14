package org.pocketlab.puppy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.pocketlab.puppy.ui.PuppyRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PuppyApp
        setContent { PuppyRoot(app) }
    }

    override fun onDestroy() {
        (application as PuppyApp).terminalView?.let { view ->
            if (view.context === this) {
                view.session = null
                (application as PuppyApp).terminalView = null
            }
        }
        super.onDestroy()
    }
}
