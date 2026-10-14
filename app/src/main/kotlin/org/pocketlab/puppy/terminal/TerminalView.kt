package org.pocketlab.puppy.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import org.pocketlab.puppy.session.TerminalSession
import org.pocketlab.puppy.session.TerminalSize

/**
 * Draws one attached [TerminalSession] and turns touch, mouse, hardware
 * keys and IME input into terminal input. One instance shows whichever
 * session is active; sessions keep running while detached.
 */
class TerminalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Called with text to paste; the host may confirm multi-line pastes first. */
    var pasteHandler: (String) -> Unit = { text -> session?.paste(text) }

    /** Called when pinch-zoom changes the font size (sp). */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Called when the user taps the terminal (e.g. to dismiss a drawer). */
    var onTap: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var typeface: Typeface = Typeface.MONOSPACE
    var fontSizeSp: Float = DEFAULT_FONT_SP
        private set
    private var renderer = createRenderer(null)

    var session: TerminalSession? = null
        set(value) {
            if (field === value) return
            field?.let {
                it.frameListener = null
                it.core.visible = false
            }
            field = value
            clearSelection()
            preedit = null
            value?.let {
                it.frameListener = { postInvalidateOnAnimation() }
                it.core.visible = true
                updateSize()
            }
            invalidate()
        }

    private var preedit: String? = null
    private var cursorOn = true
    private val blink = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private var selection: Selection? = null
        set(value) {
            if (value != null && field == null) {
                session?.frame?.let { selectionScroll = it.scrollTotal to it.scrollOffset }
            }
            field = value
        }
    private var selectionScroll = 0 to 0
    private var draggingHandle = HANDLE_NONE
    private var selectingByDrag = false
    private var actionMode: ActionMode? = null
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scrollRemainder = 0f
    private val scroller = OverScroller(context)
    private var lastFlingY = 0
    private var mouseButtonsDown = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isHapticFeedbackEnabled = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Terminal"
    }

    fun setFont(typeface: Typeface, sizeSp: Float) {
        this.typeface = typeface
        this.fontSizeSp = sizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        renderer = createRenderer(renderer)
        updateSize()
        invalidate()
    }

    fun setSelectionColors(background: Int?, foreground: Int?) {
        renderer.selectionBackground = background
        renderer.selectionForeground = foreground
        invalidate()
    }

    private fun createRenderer(previous: TerminalRenderer?): TerminalRenderer {
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        return TerminalRenderer(typeface, px, density, symbolsTypeface(context)).also { r ->
            r.selectionBackground = previous?.selectionBackground
            r.selectionForeground = previous?.selectionForeground
        }
    }

    // ------------------------------------------------------------- layout

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    private fun updateSize() {
        val s = session ?: return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val cols = max(1, floor(w / renderer.cellWidth).toInt())
        val rows = max(1, floor(h / renderer.cellHeight).toInt())
        s.resize(
            TerminalSize(cols, rows, (cols * renderer.cellWidth).toInt(), (rows * renderer.cellHeight).toInt()),
            renderer.cellWidth.toInt().coerceAtLeast(1),
            renderer.cellHeight.toInt().coerceAtLeast(1),
        )
    }

    // ---------------------------------------------------------------- draw

    override fun onDraw(canvas: Canvas) {
        // Compose hosts this view without clipping, and drawColor fills the whole clip.
        canvas.clipRect(0, 0, width, height)
        val frame = session?.frame
        if (frame == null) {
            canvas.drawColor(DEFAULT_BACKGROUND)
            return
        }
        // Fill the margin left over by whole cells with the terminal background.
        canvas.drawColor(frame.defaultBackground)
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        // The selection marks screen cells; once output scrolls them, it would
        // cover different text, so drop it.
        selection?.let { if (frame.scrollTotal != selectionScroll.first || frame.scrollOffset != selectionScroll.second) clearSelection() }
        renderer.coreCellWidth = renderer.cellWidth.toInt().coerceAtLeast(1)
        renderer.coreCellHeight = renderer.cellHeight.toInt().coerceAtLeast(1)
        renderer.selection = selection
        renderer.draw(canvas, frame, hasFocus(), cursorOn, preedit)
        selection?.let { drawHandles(canvas, it.normalized(), frame) }
        canvas.restore()
        if (frame.cursorBlinking && hasFocus()) scheduleBlink() else stopBlink()
    }

    private var blinking = false
    private fun scheduleBlink() {
        if (blinking) return
        blinking = true
        postDelayed(blink, BLINK_MS)
    }

    private fun stopBlink() {
        if (!blinking) return
        blinking = false
        removeCallbacks(blink)
        cursorOn = true
    }

    private fun drawHandles(canvas: Canvas, sel: Selection, frame: TerminalFrame) {
        handlePaint.color = frame.cursorColor ?: frame.defaultForeground
        val r = HANDLE_RADIUS_DP * density
        val sx = sel.startX * renderer.cellWidth
        val sy = (sel.startY + 1) * renderer.cellHeight
        val ex = (sel.endX + 1) * renderer.cellWidth
        val ey = (sel.endY + 1) * renderer.cellHeight
        canvas.drawCircle(sx, sy + r, r, handlePaint)
        canvas.drawCircle(ex, ey + r, r, handlePaint)
    }

    // --------------------------------------------------------------- input

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text without suggestions or autocorrect; CJK IMEs still compose.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    internal fun setPreedit(text: String?) {
        val t = text?.takeIf { it.isNotEmpty() }
        if (t == preedit) return
        preedit = t
        invalidate()
    }

    /** Text committed by the IME. Newlines become Enter key presses. */
    internal fun commitText(text: String) {
        val s = session ?: return
        clearSelection()
        val parts = text.split('\n')
        parts.forEachIndexed { index, part ->
            if (part.isNotEmpty()) s.sendText(part)
            if (index < parts.size - 1) sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    internal fun sendKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        handleKey(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        handleKey(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyUp(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && event.characters != null) {
            commitText(event.characters)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        val s = session ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.isCtrlPressed && event.isShiftPressed) {
            // App shortcuts use Ctrl+Shift so Ctrl+C/Ctrl+V stay with the shell.
            when (event.keyCode) {
                KeyEvent.KEYCODE_C -> { copySelection(); return true }
                KeyEvent.KEYCODE_V -> { pasteFromClipboard(); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> { changeFontSize(fontSizeSp + 1); return true }
                KeyEvent.KEYCODE_MINUS -> { changeFontSize(fontSizeSp - 1); return true }
            }
        }
        if (event.action == KeyEvent.ACTION_UP && event.isCtrlPressed && event.isShiftPressed &&
            event.keyCode in setOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_MINUS)
        ) return true
        val encoded = KeyMapper.encode(event) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            clearSelection()
            cursorOn = true
        }
        s.core.encodeKey(encoded.key, encoded.text, encoded.mods, encoded.consumedMods, encoded.action, encoded.unshifted) {
            s.writeFromCore(it)
        }
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        session?.let { s -> s.core.encodeFocus(gainFocus) { s.writeFromCore(it) } }
        invalidate()
    }

    // ------------------------------------------------------------- touch

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val frame = session?.frame
        val cols = frame?.cols ?: 1
        val rows = frame?.rows ?: 1
        val cx = floor((x - paddingLeft) / renderer.cellWidth).toInt().coerceIn(0, cols - 1)
        val cy = floor((y - paddingTop) / renderer.cellHeight).toInt().coerceIn(0, rows - 1)
        return cx to cy
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            onTap?.invoke()
            val frame = session?.frame
            if (frame != null && frame.mouseTracking) {
                sendMouse(MOUSE_PRESS, BUTTON_LEFT, e.x, e.y, true)
                sendMouse(MOUSE_RELEASE, BUTTON_LEFT, e.x, e.y, false)
            }
            showKeyboard()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val frame = session?.frame ?: return
            if (scaling) return
            val (x, y) = cellAt(e.x, e.y)
            selection = Selection.word(frame, x, y)
            selectingByDrag = true
            performHapticFeedback(HAPTIC_FEEDBACK_ENABLED)
            startSelectionMode()
            invalidate()
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling || selection != null) return true
            scrollPixels(distanceY, e2.x, e2.y)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selection != null) return true
            lastFlingY = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2)
            postOnAnimation(flingStep)
            return true
        }
    })

    private val flingStep = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            val y = scroller.currY
            scrollPixels((y - lastFlingY).toFloat(), width / 2f, height / 2f)
            lastFlingY = y
            postOnAnimation(this)
        }
    }

    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var startSize = fontSizeSp
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            startSize = fontSizeSp
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (fontSizeSp * detector.scaleFactor).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (abs(target - fontSizeSp) >= 0.5f) {
                setFont(typeface, target)
                return true
            }
            return false
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            if (fontSizeSp != startSize) onFontSizeChanged?.invoke(fontSizeSp)
        }
    })

    private fun changeFontSize(sp: Float) {
        setFont(typeface, sp)
        onFontSizeChanged?.invoke(fontSizeSp)
    }

    /** Scrolls history, or sends wheel/arrow input when a program owns the screen. */
    private fun scrollPixels(dy: Float, x: Float, y: Float) {
        val s = session ?: return
        val frame = s.frame ?: return
        scrollRemainder += dy
        val lines = (scrollRemainder / renderer.cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * renderer.cellHeight
        when {
            frame.mouseTracking -> repeat(abs(lines)) {
                sendMouse(MOUSE_PRESS, if (lines < 0) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN, x, y, false)
            }
            frame.altScreen -> repeat(abs(lines)) {
                sendKey(if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            }
            else -> s.core.scrollBy(lines)
        }
    }

    private fun sendMouse(action: Int, button: Int, x: Float, y: Float, anyPressed: Boolean, meta: Int = 0) {
        val s = session ?: return
        val frame = s.frame ?: return
        s.core.encodeMouse(
            action, button, KeyMapper.mods(meta), x - paddingLeft, y - paddingTop,
            (frame.cols * renderer.cellWidth).toInt(), (frame.rows * renderer.cellHeight).toInt(), anyPressed,
        ) { s.writeFromCore(it) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouse(event)) return true

        scaleDetector.onTouchEvent(event)
        val sel = selection
        if (sel != null && !scaling) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingHandle = hitHandle(sel.normalized(), event.x, event.y)
                    if (draggingHandle != HANDLE_NONE) return true
                }
                MotionEvent.ACTION_MOVE -> if (draggingHandle != HANDLE_NONE || selectingByDrag) {
                    val (x, y) = cellAt(event.x, event.y - if (draggingHandle != HANDLE_NONE) renderer.cellHeight else 0f)
                    val n = sel.normalized()
                    selection = when {
                        draggingHandle == HANDLE_START -> Selection(x, y, n.endX, n.endY)
                        draggingHandle == HANDLE_END -> Selection(n.startX, n.startY, x, y)
                        else -> Selection(sel.startX, sel.startY, x, y)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = draggingHandle != HANDLE_NONE || selectingByDrag
                    draggingHandle = HANDLE_NONE
                    selectingByDrag = false
                    if (wasDragging) {
                        selection = selection?.normalized()
                        actionMode?.invalidateContentRect()
                        return true
                    }
                }
            }
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun handleMouse(event: MotionEvent): Boolean {
        val frame = session?.frame ?: return false
        if (!frame.mouseTracking) return false
        val button = when {
            event.actionButton == MotionEvent.BUTTON_SECONDARY || event.buttonState and MotionEvent.BUTTON_SECONDARY != 0 -> BUTTON_RIGHT
            event.actionButton == MotionEvent.BUTTON_TERTIARY || event.buttonState and MotionEvent.BUTTON_TERTIARY != 0 -> BUTTON_MIDDLE
            else -> BUTTON_LEFT
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                mouseButtonsDown++
                sendMouse(MOUSE_PRESS, button, event.x, event.y, true, event.metaState)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                mouseButtonsDown = (mouseButtonsDown - 1).coerceAtLeast(0)
                sendMouse(MOUSE_RELEASE, button, event.x, event.y, mouseButtonsDown > 0, event.metaState)
            }
            MotionEvent.ACTION_MOVE -> sendMouse(MOUSE_MOTION, button, event.x, event.y, mouseButtonsDown > 0, event.metaState)
            else -> return false
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    scrollPixels(-v * renderer.cellHeight * 3, event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_HOVER_MOVE -> {
                    if (session?.frame?.mouseTracking == true) {
                        sendMouse(MOUSE_MOTION, 0, event.x, event.y, false, event.metaState)
                        return true
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun hitHandle(sel: Selection, x: Float, y: Float): Int {
        val r = HANDLE_RADIUS_DP * density * 2.5f
        val sx = paddingLeft + sel.startX * renderer.cellWidth
        val sy = paddingTop + (sel.startY + 1) * renderer.cellHeight
        val ex = paddingLeft + (sel.endX + 1) * renderer.cellWidth
        val ey = paddingTop + (sel.endY + 1) * renderer.cellHeight
        return when {
            hypot(x - ex, y - ey) < r -> HANDLE_END
            hypot(x - sx, y - sy) < r -> HANDLE_START
            else -> HANDLE_NONE
        }
    }

    // ---------------------------------------------------------- selection

    private fun startSelectionMode() {
        if (actionMode != null) {
            actionMode?.invalidateContentRect()
            return
        }
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, MENU_COPY, 0, android.R.string.copy)
                menu.add(0, MENU_PASTE, 1, android.R.string.paste)
                menu.add(0, MENU_SELECT_ALL, 2, android.R.string.selectAll)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    MENU_COPY -> copySelection()
                    MENU_PASTE -> { clearSelection(); pasteFromClipboard() }
                    MENU_SELECT_ALL -> {
                        val f = session?.frame ?: return true
                        selection = Selection(0, 0, f.cols - 1, f.rows - 1)
                        invalidate()
                        mode.invalidateContentRect()
                        return true
                    }
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                if (selection != null) {
                    selection = null
                    invalidate()
                }
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
                val sel = selection?.normalized()
                if (sel == null) {
                    outRect.set(0, 0, width, height)
                    return
                }
                outRect.set(
                    paddingLeft + (sel.startX * renderer.cellWidth).toInt(),
                    paddingTop + (sel.startY * renderer.cellHeight).toInt(),
                    paddingLeft + ((sel.endX + 1) * renderer.cellWidth).toInt(),
                    paddingTop + ((sel.endY + 1) * renderer.cellHeight).toInt(),
                )
            }
        }, ActionMode.TYPE_FLOATING)
    }

    fun clearSelection() {
        if (selection == null && actionMode == null) return
        selection = null
        actionMode?.finish()
        actionMode = null
        invalidate()
    }

    fun copySelection() {
        val frame = session?.frame ?: return
        val text = selection?.text(frame) ?: return
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
        clearSelection()
    }

    fun pasteFromClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) pasteHandler(text)
    }

    // ------------------------------------------------------ accessibility

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
        val frame = session?.frame ?: return
        // Expose the lines around the cursor, not the whole changing screen.
        val cy = frame.cursorY.coerceIn(0, frame.rows - 1)
        val from = (cy - 2).coerceAtLeast(0)
        info.text = (from..cy).joinToString("\n") { frame.lineText(it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBlink()
        scroller.forceFinished(true)
    }

    companion object {
        @Volatile private var symbols: Typeface? = null

        /** The bundled Symbols Nerd Font Mono, loaded once per process. */
        private fun symbolsTypeface(context: Context): Typeface? = symbols ?: runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, "fonts/SymbolsNerdFontMono-Regular.ttf")
        }.getOrNull()?.also { symbols = it }

        const val DEFAULT_FONT_SP = 13f
        const val MIN_FONT_SP = 6f
        const val MAX_FONT_SP = 36f
        private const val DEFAULT_BACKGROUND = 0xFF1E1F29.toInt()
        private const val BLINK_MS = 600L
        private const val HANDLE_RADIUS_DP = 7f

        private const val HANDLE_NONE = 0
        private const val HANDLE_START = 1
        private const val HANDLE_END = 2

        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_SELECT_ALL = 3

        // Ghostty mouse encoder values.
        private const val MOUSE_PRESS = 0
        private const val MOUSE_RELEASE = 1
        private const val MOUSE_MOTION = 2
        private const val BUTTON_LEFT = 1
        private const val BUTTON_RIGHT = 2
        private const val BUTTON_MIDDLE = 3
        private const val BUTTON_WHEEL_UP = 4
        private const val BUTTON_WHEEL_DOWN = 5
    }
}
