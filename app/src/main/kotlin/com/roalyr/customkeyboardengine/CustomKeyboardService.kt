package com.roalyr.customkeyboardengine

import android.content.ClipboardManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import java.io.File

class CustomKeyboardService : InputMethodService() {

    private lateinit var windowManager: WindowManager
    private var floatingKeyboardView: CustomKeyboardView? = null
    private var keyboardView: CustomKeyboardView? = null
    private var serviceKeyboardView: CustomKeyboardView? = null
    private var inputView: View? = null

    private var languageLayouts = mutableListOf<Pair<CustomKeyboard, Boolean>>()
    private var serviceLayouts = mutableMapOf<String, Pair<CustomKeyboard, Boolean>>()
    private var currentLanguageLayoutIndex = 0

    private var isFloatingKeyboardOpen = false
    private var isFloatingKeyboard = false
    private var floatingKeyboardWidth: Int = 0
    private var floatingKeyboardHeight: Int = 0
    private var floatingKeyboardPosX: Int = 0
    private var floatingKeyboardPosY: Int = 0

    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var isShiftPressed = false
    private var isCtrlPressed = false
    private var isAltPressed = false
    private var isCapsPressed = false

    private var metaState = 0 // Combined meta state
    private var modifiedMetaState = 0 // Modified meta state for handling caps lock

    private lateinit var clipboardManager: ClipboardManager
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        //Log.i(TAG, "System clipboard updated.")
        updateClipboardMap() // Reflect updates in your clipboard keys
    }
    private var isClipboardOpen = false
    private var currentThemeOverride: Boolean? = null
    private var isEmojiOpen = false

    private lateinit var settings: KeyboardSettings

    private var lastSettingsError: String? = null

    private val homeLauncherPackages = setOf(
        "com.mi.appfinder",                          // MIUI home search (confirmed)
        "com.miui.home",                             // MIUI launcher (fallback)
        "com.mi.android.globallauncher",             // Xiaomi Global launcher
        "com.google.android.apps.nexuslauncher",     // Pixel launcher
        "com.google.android.googlequicksearchbox",   // Google Search
        "com.android.launcher3"                      // AOSP launcher
    )

    private var isTransparentMode = false
    private var lastSpaceTime: Long = 0L
    private var lastCommittedChar: Char? = null
    private var isEffectiveDark = false

    // Apps that are always dark regardless of system theme.
    // Add packages here that should force the keyboard dark.
    private val forceDarkPackages = setOf<String>(
        "com.termux",
        "com.termux.api",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.discord",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",       // TikTok
        "com.instagram.android",
        "com.netflix.mediaclient",
        "com.spotify.music",
        "com.microsoft.office.excel",
        "com.microsoft.office.word"
    )

    // Apps that are always light (rarely needed).
    private val forceLightPackages = setOf<String>(
        // Add packages you want forced light here, e.g.:
        // "com.example.someapp"
    )


    companion object {
        private const val TAG = "CustomKeyboardService"
    }



    ////////////////////////////////////////////
    // Init the keyboard
    override fun onCreateInputView(): View? {
        createInputView()
        return inputView
    }

    override fun onCreate() {
        initWindowManager()
        initClipboardManager()
        loadKeyboardSettings()
        CustomKeyboardClipboard.ensureMapSize(Constants.CLIPBOARD_MAX_SIZE)
        super.onCreate()
    }

    private fun loadKeyboardSettings() {
        settings = SettingsManager.loadSettings(this) { error ->
            if (error == lastSettingsError) return@loadSettings
            lastSettingsError = error
            Handler(Looper.getMainLooper()).post {
                ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, error)
            }
        }
    }

    override fun onDestroy() {
        closeAllKeyboards()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        recreateKeyboards()
        // After rotating the screen - reposition the floating KB vertically within new limits.
        translateVertFloatingKeyboard(floatingKeyboardPosY)
        super.onConfigurationChanged(newConfig)
    }

    private fun isSentenceEnd(c: Char?): Boolean {
        return c == '.' || c == '!' || c == '?' || c == '\n'
    }

    private fun shouldAutoCapitalize(): Boolean {
        // Prefer local state — the input connection is async and lags behind
        // by one keypress, which caused double-capitalization.
        lastCommittedChar?.let { return isSentenceEnd(it) }
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: return true
        if (before.isEmpty()) return true
        val last = before.trimEnd().lastOrNull() ?: return true
        return isSentenceEnd(last)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        if (!restarting) {
            reloadKeyboardLayouts()
            recreateKeyboards()
        }
        super.onStartInputView(info, restarting)

        val pkg = info?.packageName ?: ""
        val shouldBeTransparent = !isFloatingKeyboard && pkg in homeLauncherPackages
        isTransparentMode = shouldBeTransparent
        val target = shouldBeTransparent
        inputView?.post { applyTransparencyMode(target) }

        // Per-app theme awareness
        val themeOverride: Boolean? = when {
            pkg in forceDarkPackages -> true
            pkg in forceLightPackages -> false
            else -> null  // follow system
        }
        currentThemeOverride = themeOverride
        val systemDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        isEffectiveDark = themeOverride ?: systemDark
        inputView?.post { applyTransparencyMode(target) }  // refresh nav bar
        keyboardView?.setThemeOverride(themeOverride)
        serviceKeyboardView?.setThemeOverride(themeOverride)
        keyboardView?.invalidate()
        serviceKeyboardView?.invalidate()

        // Auto-capitalize first letter on keyboard open
        if (shouldAutoCapitalize()) {
            isShiftPressed = true
            metaState = metaState or KeyEvent.META_SHIFT_ON
        }
        keyboardView?.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardView?.cancelAllEvents()
        floatingKeyboardView?.cancelAllEvents()
        serviceKeyboardView?.cancelAllEvents()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        keyboardView?.cancelAllEvents()
        floatingKeyboardView?.cancelAllEvents()
        serviceKeyboardView?.cancelAllEvents()
    }

    private fun initClipboardManager() {
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        //Log.i(TAG, "Clipboard listener registered.")
    }

    ////////////////////////////////////////////
    // Handle window creation and inflation
    private fun createInputView() {
        inputView = null
        inputView = layoutInflater.inflate(R.layout.standard_keyboard_view, null)
    }

    private fun initWindowManager() {
        if (::windowManager.isInitialized) return // Check if already initialized
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }


    private fun createKeyboardLayout(
        rootView: View?,
        keyboardView: CustomKeyboardView?,
        isFloating: Boolean
    ): CustomKeyboardView? {
        val layout = when {
            isClipboardOpen -> getClipboardLayout()
            isEmojiOpen -> getEmojiLayout()
            else -> getLanguageLayout()
        }

        return layout?.let { (customKeyboard) ->
            keyboardView?.updateKeyboard(customKeyboard)
            keyboardView?.updateSettings(settings)
            keyboardView?.setThemeOverride(currentThemeOverride)
            serviceKeyboardView?.setThemeOverride(currentThemeOverride)

            // Initialize and synchronize clipboard keys (clipboard mode only)
            if (isClipboardOpen) {
                val clipboardKeys = customKeyboard.getAllKeys().filter { it.keyCode == Constants.KEYCODE_CLIPBOARD_ENTRY }
                CustomKeyboardClipboard.initializeClipboardKeys(clipboardKeys.map { it.id })
                synchronizeClipboardKeys()
            }


            if (keyboardView != null) {
                setKeyboardActionListener(keyboardView)
            }

            keyboardView
        } ?: run {
            val errorMsg = "No valid ${if (isClipboardOpen) "clipboard" else "language"} layout found."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            null
        }
    }






    private fun createFloatingKeyboard() {
        if (!Settings.canDrawOverlays(this)) {
            val errorMsg = "Overlay permission denied"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            return
        }

        floatingKeyboardView = layoutInflater.inflate(R.layout.floating_keyboard_view, null) as? CustomKeyboardView

        val keyboard = createKeyboardLayout(null, floatingKeyboardView, isFloating = true)
        if (keyboard == null) {
            val errorMsg = "Failed to initialize floating keyboard."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            return
        }

        val params = WindowManager.LayoutParams(
            floatingKeyboardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            floatingKeyboardPosX,
            floatingKeyboardPosY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(floatingKeyboardView, params)
            isFloatingKeyboardOpen = true
            floatingKeyboardView?.post { floatingKeyboardHeight = floatingKeyboardView?.measuredHeight ?: 0 }
        } catch (e: Exception) {
            val errorMsg = "Failed to add floating keyboard view: ${e.message}"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
        }
    }

    private fun createStandardKeyboard(): View? {
        val rootView = inputView ?: run {
            val errorMsg = "Input view is null."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            return null
        }

        keyboardView = rootView.findViewById(R.id.keyboard_view) as? CustomKeyboardView
        val keyboard = createKeyboardLayout(rootView, keyboardView, isFloating = false)

        if (keyboard == null) {
            val errorMsg = "Failed to initialize standard keyboard."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
        }

        return rootView
    }

    private fun createServiceKeyboard(): View? {
        val rootView = inputView ?: run {
            val errorMsg = "Input view is null."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            return null
        }

        serviceKeyboardView = rootView.findViewById(R.id.service_keyboard_view) as? CustomKeyboardView
        serviceKeyboardView?.let { view ->
            val customKeyboardPair = serviceLayouts[Constants.LAYOUT_SERVICE_DEFAULT]
            if (customKeyboardPair != null) {
                val (customKeyboard) = customKeyboardPair
                view.updateKeyboard(customKeyboard)
                view.updateSettings(settings)
                setKeyboardActionListener(view)

            } else {
                val errorMsg = "Service keyboard layout not found."
                ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            }
        } ?: run {
            val errorMsg = "Service keyboard view not found."
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
        }

        return rootView
    }



    ////////////////////////////////////////////
    // Unified method for keyboard switching
    private fun cycleLanguageLayout() {
        currentLanguageLayoutIndex = (currentLanguageLayoutIndex + 1) % languageLayouts.size
        //Log.i(TAG, "Cycled to language layout index: $currentLanguageLayoutIndex")
        reloadKeyboardLayouts() // Always reload to reflect changes
        recreateKeyboards()
    }

    private fun toggleClipboardLayout() {
        isClipboardOpen = !isClipboardOpen
        if (isClipboardOpen) isEmojiOpen = false
        reloadKeyboardLayouts()
        recreateKeyboards()
    }

    private fun toggleEmojiLayout() {
        isEmojiOpen = !isEmojiOpen
        if (isEmojiOpen) isClipboardOpen = false
        reloadKeyboardLayouts()
        recreateKeyboards()
    }


    private fun getLanguageLayout(): Pair<CustomKeyboard, Boolean>? {
        return languageLayouts.getOrNull(currentLanguageLayoutIndex)
    }

    private fun getClipboardLayout(): Pair<CustomKeyboard, Boolean>? {
        // Replace with your clipboard layout logic
        val clipboardLayout = serviceLayouts[Constants.LAYOUT_CLIPBOARD_DEFAULT] // Example: "clipboard_layout.json"
        if (clipboardLayout == null) {
            Log.w(TAG, "Clipboard layout not found.")
        }
        return clipboardLayout
    }

    private fun getEmojiLayout(): Pair<CustomKeyboard, Boolean>? {
        val emojiLayout = serviceLayouts[Constants.LAYOUT_EMOJI_DEFAULT]
        if (emojiLayout == null) {
            Log.w(TAG, "Emoji layout not found.")
        }
        return emojiLayout
    }

    private fun switchKeyboardMode() {
        isFloatingKeyboard = !isFloatingKeyboard
        //Log.i(TAG, "Switched keyboard mode to ${if (isFloatingKeyboard) "Floating" else "Standard"}.")
        reloadKeyboardLayouts() // Reload and apply the updated mode
        recreateKeyboards()
    }

    private fun recreateKeyboards(){
        closeAllKeyboards()
        screenHeight = getScreenHeight()
        screenWidth = getScreenWidth()

        inputView = if (isFloatingKeyboard) {
            // Check width to prevent keyboard from crossing screen
            if (floatingKeyboardWidth == 0) {
                floatingKeyboardWidth = getScreenWidth()
            }
            if (floatingKeyboardWidth > screenWidth) {
                floatingKeyboardWidth = getScreenWidth()
            }
            createInputView()
            createServiceKeyboard()
            createFloatingKeyboard()
            inputView
        } else {
            createInputView()
            createStandardKeyboard()
            inputView
        }

        inputView?.let {
            setInputView(it)
        } ?: {
            val errorMsg = "Error creating new input view"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
        }
        invalidateAllKeysOnAllKeyboards()
    }


    ////////////////////////////////////////////
    // Helper functions for closing keyboards
    private fun applyTransparencyMode(transparent: Boolean) {
        window?.window?.let { w ->
            if (transparent) {
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    w.isNavigationBarContrastEnforced = false
                }
                w.navigationBarColor = Color.TRANSPARENT
                w.statusBarColor = Color.TRANSPARENT
            } else {
                val solidBg = if (isEffectiveDark) 0xFF1C1C1E.toInt() else 0xFFD1D1D6.toInt()
                w.setBackgroundDrawable(ColorDrawable(solidBg))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    w.isNavigationBarContrastEnforced = true
                }
                w.navigationBarColor = solidBg
            }
        }

        if (transparent) {
            inputView?.setBackgroundColor(Color.TRANSPARENT)
            (inputView?.parent as? ViewGroup)?.setBackgroundColor(Color.TRANSPARENT)
            keyboardView?.background = null
            serviceKeyboardView?.background = null
        } else {
            // Restore opaque background in normal apps
            val bg = if (isEffectiveDark) 0xFF1C1C1E.toInt() else 0xFFD1D1D6.toInt()
            inputView?.setBackgroundColor(bg)
            (inputView?.parent as? ViewGroup)?.setBackgroundColor(bg)
            keyboardView?.background = null
            serviceKeyboardView?.background = null
        }

        keyboardView?.setKeyboardTransparent(transparent)
        serviceKeyboardView?.setKeyboardTransparent(transparent)

        invalidateAllKeysOnAllKeyboards()
    }

    private fun closeAllKeyboards() {
        closeStandardKeyboard()
        closeServiceKeyboard()
        closeFloatingKeyboard()
    }

    private fun closeFloatingKeyboard() {
        floatingKeyboardView?.let { view ->
            if (isFloatingKeyboardOpen && ::windowManager.isInitialized) {
                try {
                    windowManager.removeView(view)
                    isFloatingKeyboardOpen = false
                } catch (e: Exception) {
                    val errorMsg = "Error closing floating keyboard: ${e.message}"
                    ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
                } finally {
                    floatingKeyboardView = null
                }
            }
        }
    }

    private fun closeStandardKeyboard() {
        keyboardView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            keyboardView = null
        }
    }

    private fun closeServiceKeyboard() {
        serviceKeyboardView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            serviceKeyboardView = null
        }
    }

    ////////////////////////////////////////////
    // Helper functions
    private fun invalidateAllKeysOnAllKeyboards() {
        floatingKeyboardView?.invalidateAllKeys()
        keyboardView?.invalidateAllKeys()
        serviceKeyboardView?.invalidateAllKeys()
    }

    ////////////////////////////////////////////
    // Helper functions for floating keyboard
    private fun updateFloatingKeyboard() {
        if (isFloatingKeyboardOpen && ::windowManager.isInitialized) {
            floatingKeyboardView?.let { view ->
                screenWidth = getScreenWidth() // Update screen width dynamically

                val params = WindowManager.LayoutParams(
                    floatingKeyboardWidth,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    floatingKeyboardPosX,
                    floatingKeyboardPosY,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                )

                windowManager.updateViewLayout(view, params)
                view.invalidateAllKeys()
            }
        }
    }

    private fun resizeFloatingKeyboard(increment: Int) {
        if (isFloatingKeyboardOpen) {
            floatingKeyboardWidth = (floatingKeyboardWidth + increment)
                .coerceIn(settings.keyboardMinimalWidth, screenWidth)

            floatingKeyboardView?.let { view ->
                val layoutParams = view.layoutParams as WindowManager.LayoutParams
                layoutParams.width = floatingKeyboardWidth
                windowManager.updateViewLayout(view, layoutParams)
            }
            updateFloatingKeyboard()
        }
    }

    private fun translateFloatingKeyboard(xOffset: Int) {
        if (isFloatingKeyboardOpen) {
            floatingKeyboardPosX = (floatingKeyboardPosX + xOffset).coerceIn(
                -(screenWidth / 2 - floatingKeyboardWidth / 2),
                (screenWidth / 2 - floatingKeyboardWidth / 2)
            )
            updateFloatingKeyboard()
        }
    }

    private fun translateVertFloatingKeyboard(yOffset: Int) {
        if (isFloatingKeyboardOpen) {
            //Log.i(TAG, "translateVertFloatingKeyboard called with yOffset: $yOffset")

            floatingKeyboardPosY += yOffset
            //Log.i(TAG, "Updated floatingKeyboardPosY: $floatingKeyboardPosY")
            //Log.i(TAG, "Updated floatingKeyboardHEIGH: $floatingKeyboardHeight")

            val density = resources.displayMetrics.density
            val bottomOffsetPx = (Constants.KEYBOARD_TRANSLATION_BOTTOM_OFFSET * density).toInt()
            //Log.i(TAG, "Calculated bottomOffsetPx: $bottomOffsetPx")

            val coerceTop = (-(getScreenHeight() / 2.0 - floatingKeyboardHeight / 2.0)).toInt()
            val coerceBottom = (getScreenHeight() / 2.0 - bottomOffsetPx - floatingKeyboardHeight / 2.0).toInt()
            //Log.i(TAG, "Coerce limits - Top: $coerceTop, Bottom: $coerceBottom")

            floatingKeyboardPosY = floatingKeyboardPosY.coerceIn(coerceTop, coerceBottom)
            //Log.i(TAG, "Coerced floatingKeyboardPosY: $floatingKeyboardPosY")

            updateFloatingKeyboard()
        } else {
            //Log.w(TAG, "translateVertFloatingKeyboard called but floating keyboard is not open.")
        }
    }


    private fun getScreenWidth(): Int {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        return displayMetrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        return displayMetrics.heightPixels
    }



    ////////////////////////////////////////////
    // Listeners
    private fun setKeyboardActionListener(keyboardView: CustomKeyboardView) {
        keyboardView.setOnKeyboardActionListener(object : CustomKeyboardView.OnKeyboardActionListener {

            override fun onKey(code: Int?, label: CharSequence?) {
                if (code != null){
                    handleCustomKey(code, label)
                }
                when (code) {
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                        metaState = toggleMetaState(metaState, KeyEvent.META_SHIFT_ON)
                        isShiftPressed = !isShiftPressed
                        keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                    }
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                        metaState = toggleMetaState(metaState, KeyEvent.META_CTRL_ON)
                        isCtrlPressed = !isCtrlPressed
                        keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                    }
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                        metaState = toggleMetaState(metaState, KeyEvent.META_ALT_ON)
                        isAltPressed = !isAltPressed
                        keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                    }
                    KeyEvent.KEYCODE_CAPS_LOCK -> {
                        isCapsPressed = !isCapsPressed
                        keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                    }
                    else -> {
                        modifiedMetaState = if (isCapsPressed) {
                            metaState or KeyEvent.META_CAPS_LOCK_ON
                        } else {
                            metaState
                        }
                        // Double-space → ". "
                        if (code == KeyEvent.KEYCODE_SPACE) {
                            val now = System.currentTimeMillis()
                            val before = currentInputConnection?.getTextBeforeCursor(3, 0)?.toString() ?: ""
                            val trimmed = before.trimEnd()
                            val lastChar = trimmed.lastOrNull()
                            val alreadySentenceEnd = lastChar == '.' || lastChar == '!' || lastChar == '?'
                            if (now - lastSpaceTime < 400L
                                && before.isNotEmpty()
                                && before.last() == ' '
                                && trimmed.isNotEmpty()
                                && !alreadySentenceEnd) {
                                currentInputConnection?.deleteSurroundingText(1, 0)
                                currentInputConnection?.commitText(". ", 1)
                                lastSpaceTime = 0L
                                lastCommittedChar = '.'
                                if (shouldAutoCapitalize()) {
                                    isShiftPressed = true
                                    metaState = metaState or KeyEvent.META_SHIFT_ON
                                }
                                keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                                return
                            }
                            lastSpaceTime = now
                            lastCommittedChar = ' '
                        }

                        handleKey(code, label)
                        // Track what we just committed so auto-cap is instant, not async
                        lastCommittedChar = label?.firstOrNull()
                        resetMetaStates()
                        // Auto-capitalize the next sentence
                        if (shouldAutoCapitalize()) {
                            isShiftPressed = true
                            metaState = metaState or KeyEvent.META_SHIFT_ON
                        }
                        keyboardView.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                    }
                }
            }
        })
    }

    private fun toggleMetaState(metaState: Int, metaFlag: Int): Int {
        return metaState xor metaFlag // XOR toggles the state
    }

    private fun resetMetaStates() {
        // Store those values in service class.
        metaState = 0
        isShiftPressed = false
        isCtrlPressed = false
        isAltPressed = false
    }

    ////////////////////////////////////////////
    // Handle key events
    private fun handleKey(code: Int?, label: CharSequence?) {
        //Log.d("Inject", "$code, $label, $modifiedMetaState")

        // Defensive: if the key has neither a usable code nor a label, do nothing.
        val isIgnorableCode = (code == null || code == Constants.KEYCODE_IGNORE)
        if (isIgnorableCode && label.isNullOrBlank()) return

        // Manually apply metaState to the key event if key code is written in layout.
        if (code != null && code != Constants.KEYCODE_IGNORE) {
            injectKeyEvent(code, modifiedMetaState)
        } else {
            // If no key codes for a key - attempt to get key code from label.
            if (label != null) { // Check if label is not null
                val keyLabel = label.toString()
                //Log.d("Inject", "$keyLabel, $modifiedMetaState")

                val codeFromLabel = getKeycodeFromLabel(keyLabel)

                if (codeFromLabel != null) {
                    //Log.d("Inject", "$codeFromLabel, $modifiedMetaState")
                    injectKeyEvent(codeFromLabel, modifiedMetaState)
                } else {
                    // If no key code found, commit label as text "as is".
                    val finalKeyLabel = if (isShiftPressed || isCapsPressed) {
                        resetMetaStates()
                        keyboardView?.updateMetaState(isShiftPressed, isCtrlPressed, isAltPressed, isCapsPressed)
                        keyLabel.uppercase()
                    } else {
                        keyLabel.lowercase()
                    }
                    currentInputConnection.commitText(finalKeyLabel, 1)
                }
            } else {
                // Handle cases where label is null (e.g., keys with icons)
            }
        }
    }

    private fun handleCustomKey(code: Int?, label: CharSequence?)  {
        // Handle custom key codes related to this application.
        // Code is mandatory
        if (code != null && code != Constants.KEYCODE_IGNORE) {
            when (code) {

                Constants.KEYCODE_CLOSE_FLOATING_KEYBOARD -> {
                    if (isFloatingKeyboardOpen) {
                        closeFloatingKeyboard()
                    }
                }

                Constants.KEYCODE_OPEN_FLOATING_KEYBOARD -> {
                    if (!isFloatingKeyboardOpen) {
                        createFloatingKeyboard()
                    }
                }

                Constants.KEYCODE_SWITCH_KEYBOARD_MODE -> switchKeyboardMode()
                Constants.KEYCODE_ENLARGE_FLOATING_KEYBOARD -> resizeFloatingKeyboard(+settings.keyboardScaleIncrement)
                Constants.KEYCODE_SHRINK_FLOATING_KEYBOARD -> resizeFloatingKeyboard(-settings.keyboardScaleIncrement)
                Constants.KEYCODE_MOVE_FLOATING_KEYBOARD_LEFT -> translateFloatingKeyboard(-settings.keyboardTranslationIncrement)
                Constants.KEYCODE_MOVE_FLOATING_KEYBOARD_RIGHT -> translateFloatingKeyboard(
                    settings.keyboardTranslationIncrement
                )

                Constants.KEYCODE_MOVE_FLOATING_KEYBOARD_UP -> translateVertFloatingKeyboard(-settings.keyboardTranslationIncrement)
                Constants.KEYCODE_MOVE_FLOATING_KEYBOARD_DOWN -> translateVertFloatingKeyboard(
                    settings.keyboardTranslationIncrement
                )

                Constants.KEYCODE_CYCLE_LANGUAGE_LAYOUT -> {
                    cycleLanguageLayout()
                }

                Constants.KEYCODE_CLIPBOARD_ENTRY -> {
                    // Commit the label of the pressed key
                    val text = label?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        currentInputConnection.commitText(text, 1)
                    }
                }
                Constants.KEYCODE_CLIPBOARD_ERASE -> {
                    CustomKeyboardClipboard.clearClipboard()
                    invalidateAllKeysOnAllKeyboards()
                }
                Constants.KEYCODE_OPEN_CLIPBOARD -> {
                    toggleClipboardLayout()
                }
                Constants.KEYCODE_OPEN_EMOJI -> {
                    toggleEmojiLayout()
                }
                Constants.KEYCODE_EMOJI_ENTRY -> {
                    val text = label?.toString().orEmpty()
                    if (text.isNotEmpty()) {
                        currentInputConnection.commitText(text, 1)
                    }
                }

            }
        }
    }


    ////////////////////////////////////////////
    // Event injections
    private fun injectMetaModifierKeys(metaState: Int, action: Int) {
        // Inject meta keys (SHIFT, CTRL, ALT) as separate key events
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            injectKeyEventInternal(action, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            injectKeyEventInternal(action, KeyEvent.KEYCODE_CTRL_LEFT, 0)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            injectKeyEventInternal(action, KeyEvent.KEYCODE_ALT_LEFT, 0)
        }
    }

    private fun injectKeyEvent(keyCode: Int, metaState: Int) {
        // Inject meta modifier keys down
        injectMetaModifierKeys(metaState, KeyEvent.ACTION_DOWN)
        // Inject the main key event
        injectKeyEventInternal(KeyEvent.ACTION_DOWN, keyCode, metaState)
        injectKeyEventInternal(KeyEvent.ACTION_UP, keyCode, metaState)
        // Inject meta modifier keys up
        injectMetaModifierKeys(metaState, KeyEvent.ACTION_UP)
    }

    private fun injectKeyEventInternal(action: Int, keyCode: Int, metaState: Int) {
        val eventTime = System.currentTimeMillis()
        val event = KeyEvent(eventTime, eventTime, action, keyCode, 0, metaState)
        currentInputConnection?.sendKeyEvent(event)
    }


    ////////////////////////////////////////////
    // Handle primary key code lookup.
    private fun getKeycodeFromLabel(label: String): Int? {
        // Create a mapping of labels to key codes
        val labelToKeycodeMap = mapOf(
            "a" to KeyEvent.KEYCODE_A,
            "b" to KeyEvent.KEYCODE_B,
            "c" to KeyEvent.KEYCODE_C,
            "d" to KeyEvent.KEYCODE_D,
            "e" to KeyEvent.KEYCODE_E,
            "f" to KeyEvent.KEYCODE_F,
            "g" to KeyEvent.KEYCODE_G,
            "h" to KeyEvent.KEYCODE_H,
            "i" to KeyEvent.KEYCODE_I,
            "j" to KeyEvent.KEYCODE_J,
            "k" to KeyEvent.KEYCODE_K,
            "l" to KeyEvent.KEYCODE_L,
            "m" to KeyEvent.KEYCODE_M,
            "n" to KeyEvent.KEYCODE_N,
            "o" to KeyEvent.KEYCODE_O,
            "p" to KeyEvent.KEYCODE_P,
            "q" to KeyEvent.KEYCODE_Q,
            "r" to KeyEvent.KEYCODE_R,
            "s" to KeyEvent.KEYCODE_S,
            "t" to KeyEvent.KEYCODE_T,
            "u" to KeyEvent.KEYCODE_U,
            "v" to KeyEvent.KEYCODE_V,
            "w" to KeyEvent.KEYCODE_W,
            "x" to KeyEvent.KEYCODE_X,
            "y" to KeyEvent.KEYCODE_Y,
            "z" to KeyEvent.KEYCODE_Z,

            "0" to KeyEvent.KEYCODE_0,
            "1" to KeyEvent.KEYCODE_1,
            "2" to KeyEvent.KEYCODE_2,
            "3" to KeyEvent.KEYCODE_3,
            "4" to KeyEvent.KEYCODE_4,
            "5" to KeyEvent.KEYCODE_5,
            "6" to KeyEvent.KEYCODE_6,
            "7" to KeyEvent.KEYCODE_7,
            "8" to KeyEvent.KEYCODE_8,
            "9" to KeyEvent.KEYCODE_9,

            " " to KeyEvent.KEYCODE_SPACE,
            "." to KeyEvent.KEYCODE_PERIOD,
            "," to KeyEvent.KEYCODE_COMMA,
            ";" to KeyEvent.KEYCODE_SEMICOLON,
            "'" to KeyEvent.KEYCODE_APOSTROPHE,
            "/" to KeyEvent.KEYCODE_SLASH,
            "\\" to KeyEvent.KEYCODE_BACKSLASH,
            "`" to KeyEvent.KEYCODE_GRAVE,
            "[" to KeyEvent.KEYCODE_LEFT_BRACKET,
            "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
            "-" to KeyEvent.KEYCODE_MINUS,
            "=" to KeyEvent.KEYCODE_EQUALS,
        )
        // Check if the label exists in the mapping
        return labelToKeycodeMap[label.lowercase()] // Convert label to lowercase for case-insensitivity
    }

    ////////////////////////////////////////////
    // Clipboard management
    private fun updateClipboardMap() {
        val systemClipboardText = getClipboardText()
        //Log.i(TAG, "System clipboard text: ${systemClipboardText ?: "null"}")

        if (systemClipboardText != null && !CustomKeyboardClipboard.containsValue(systemClipboardText)) {
            CustomKeyboardClipboard.addClipboardEntry(systemClipboardText)
            //Log.i(TAG, "Added new clipboard entry.")
        }

        // Invalidate and synchronize clipboard keys if they exist
        if (keyboardView?.keyboard?.getAllKeys()?.any { it.keyCode == Constants.KEYCODE_CLIPBOARD_ENTRY } == true) {
            synchronizeClipboardKeys()
        } else {
            //Log.i(TAG, "No clipboard keys found in the current layout.")
        }
    }


    private fun synchronizeClipboardKeys() {
        val allKeys = keyboardView?.keyboard?.getAllKeys() ?: emptyList()

        // Synchronize clipboard keys using the map
        allKeys.forEach { key ->
            if (key.keyCode == Constants.KEYCODE_CLIPBOARD_ENTRY) {
                val label = CustomKeyboardClipboard.getClipboardEntry(key.id) ?: ""
                key.label = label
                //Log.i(TAG, "Synchronized clipboard key ID ${key.id} with label: '$label'")
            }
        }
        invalidateAllKeysOnAllKeyboards()
    }


    private fun getClipboardText(): String? {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }


    ////////////////////////////////////////////
    // Keyboard reloading
    private fun reloadKeyboardLayouts() {
        // Reload settings on layout reload
        SettingsManager.reloadSettings()
        loadKeyboardSettings()

        // Clear existing layouts
        languageLayouts.clear()
        serviceLayouts.clear()

        val languageDirectory = File(Constants.MEDIA_LAYOUTS_LANGUAGE_DIRECTORY)
        val serviceDirectory = File(Constants.MEDIA_LAYOUTS_SERVICE_DIRECTORY)

        // Reload language layouts
        reloadLayouts(
            directory = languageDirectory,
            layoutType = "Language",
            onFileParsed = { keyboard, fileName, isFallback ->
                languageLayouts.add(Pair(keyboard, isFallback))
            },
            onFallback = { fallbackLayout ->
                languageLayouts.add(Pair(fallbackLayout, true))
                Log.w(TAG, "Using fallback language layout.")
            }
        )

        // Reload service layouts
        reloadLayouts(
            directory = serviceDirectory,
            layoutType = "Service",
            onFileParsed = { keyboard, fileName, isFallback ->
                serviceLayouts[fileName] = Pair(keyboard, isFallback)
            },
            onFallback = { fallbackLayout ->
                serviceLayouts[Constants.LAYOUT_SERVICE_DEFAULT] = Pair(fallbackLayout, true)
                Log.w(TAG, "Using fallback service layout.")
            }
        )

        // Reload clipboard layouts (stored in the same directory as service layouts)
        reloadLayouts(
            directory = serviceDirectory,
            layoutType = "Clipboard",
            onFileParsed = { keyboard, fileName, isFallback ->
                serviceLayouts[fileName] = Pair(keyboard, isFallback)
            },
            onFallback = { fallbackLayout ->
                serviceLayouts[Constants.LAYOUT_CLIPBOARD_DEFAULT] = Pair(fallbackLayout, true)
                Log.w(TAG, "Using fallback clipboard layout.")
            }
        )

        // Reset to a valid language layout index
        if (currentLanguageLayoutIndex >= languageLayouts.size || currentLanguageLayoutIndex < 0) {
            currentLanguageLayoutIndex = 0
        }
    }

    private fun reloadLayouts(
        directory: File,
        layoutType: String,
        onFileParsed: (CustomKeyboard, String, Boolean) -> Unit,
        onFallback: (CustomKeyboard) -> Unit
    ) {
        if (!directory.exists() || !directory.isDirectory) {
            val errorMsg = "$layoutType layouts directory does not exist or is not a directory: ${directory.absolutePath}"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            handleFallback(layoutType, onFallback)
            return
        }

        val files = directory.listFiles()
            ?.filter { it.isFile && it.canRead() && it.name.endsWith(".json") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        if (files.isEmpty()) {
            handleFallback(layoutType, onFallback)
            return
        }

        files.forEach { file ->
            try {
                CustomKeyboard.fromJsonFile(this, file, settings) { error ->
                    ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, error)
                    handleFallback(layoutType, onFallback)
                }?.let { keyboard ->
                    onFileParsed(keyboard, file.nameWithoutExtension, false)
                }
            } catch (e: Exception) {
                val parseError = "Failed to parse. ${e.message}"
                ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, parseError)
                handleFallback(layoutType, onFallback)
            }
        }
    }


    private fun handleFallback(layoutType: String, onFallback: (CustomKeyboard) -> Unit) {
        try {
            val fallbackLayout = when (layoutType) {
                "Language" -> loadFallbackLayout(R.raw.keyboard_default, "language")
                "Service" -> loadFallbackLayout(R.raw.keyboard_service, "service")
                "Clipboard" -> loadFallbackLayout(R.raw.keyboard_clipboard_default, "clipboard")
                else -> throw IllegalArgumentException("Unknown layout type: $layoutType")
            }
            onFallback(fallbackLayout)
        } catch (e: Exception) {
            val errorMsg = "Failed to load fallback layout: ${e.message}"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
        }
    }


    /**
     * Loads a fallback keyboard layout from raw resources.
     *
     * @param resourceId The raw resource ID of the JSON layout.
     * @param layoutName A descriptive name for the layout (used in error messages).
     * @return A [CustomKeyboard] instance parsed from the resource.
     * @throws Exception if parsing fails or the resulting layout is null.
     */
    private fun loadFallbackLayout(resourceId: Int, layoutName: String): CustomKeyboard {
        return try {
            val json = resources.openRawResource(resourceId).bufferedReader().use { it.readText() }
            CustomKeyboard.fromJson(this, json, settings)
                ?: throw Exception("Parsed fallback $layoutName layout is null")
        } catch (e: Exception) {
            val errorMsg = "Error loading fallback $layoutName layout: ${e.message}"
            ClassFunctionsPopups.showErrorPopup(windowManager, this, TAG, errorMsg)
            throw e
        }
    }
}
