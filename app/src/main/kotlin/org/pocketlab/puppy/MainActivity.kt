package org.pocketlab.puppy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.pocketlab.puppy.runtime.LocalEnvironment
import org.pocketlab.puppy.session.SessionSpec
import org.pocketlab.puppy.session.TerminalSession
import org.pocketlab.puppy.session.TerminalSize
import org.pocketlab.puppy.session.local.LocalPtyBackend
import org.pocketlab.puppy.terminal.TerminalView

class MainActivity : ComponentActivity() {
    private var session: TerminalSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val env = LocalEnvironment(this)
        val s = TerminalSession(SessionSpec.Local, "Local", 10_000, TerminalSize(80, 24, 800, 480), {
            LocalPtyBackend(env.shellCommand())
        })
        s.start()
        session = s
        val view = TerminalView(this)
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        view.session = s
        setContentView(view)
        view.showKeyboard()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) session?.close()
    }
}
