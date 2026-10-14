package org.pocketlab.puppy.terminal

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Receives text from the system input method (design §7.2).
 *
 * Composing text stays local and is drawn as preedit; only committed text
 * reaches the terminal. Deletes with nothing composed become Backspace.
 */
internal class TerminalInputConnection(private val view: TerminalView) : BaseInputConnection(view, true) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.commitText(text, newCursorPosition)
        flush()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.setComposingText(text, newCursorPosition)
        view.setPreedit(editable?.toString())
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        super.setComposingRegion(start, end)
        view.setPreedit(editable?.toString())
        return true
    }

    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        flush()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val content = editable
        if (content.isNullOrEmpty()) {
            // Some keyboards (e.g. Samsung with spell check) delete several at once.
            repeat(beforeLength.coerceAtMost(MAX_DELETES)) { view.sendKey(KeyEvent.KEYCODE_DEL) }
            repeat(afterLength.coerceAtMost(MAX_DELETES)) { view.sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }
        val result = super.deleteSurroundingText(beforeLength, afterLength)
        view.setPreedit(content.toString())
        return result
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Committed text first so ordering is preserved.
        flush()
        return view.dispatchKeyEvent(event)
    }

    /** Sends everything in the editable that is not being composed, then clears it. */
    private fun flush() {
        val content = editable ?: return
        val composingStart = getComposingSpanStart(content)
        if (composingStart >= 0) {
            view.setPreedit(content.toString())
            return
        }
        val text = content.toString()
        content.clear()
        view.setPreedit(null)
        if (text.isNotEmpty()) view.commitText(text)
    }

    companion object {
        private const val MAX_DELETES = 256
    }
}
