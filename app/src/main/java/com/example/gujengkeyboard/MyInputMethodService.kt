package com.example.gujengkeyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.LruCache
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import kotlinx.coroutines.*
import java.util.Locale

class MyInputMethodService : InputMethodService() {

    // Apna API Key yahan dalein ya BuildConfig se lein
    private val API_KEY = BuildConfig.GEMINI_API_KEY
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private lateinit var suggestionLayout: LinearLayout
    private lateinit var mainKeyboardContainer: LinearLayout
    private lateinit var rootView: View
    // 🔥 Change 1: Default Caps State = 1 (Capital)
    private var capsState = 1
    private var lastShiftTime: Long = 0
    private var isListening = false
    private val translationCache = LruCache<String, String>(100)
    private val handler = Handler(Looper.getMainLooper())

    private var isDeleting = false
    private val deleteHandler = Handler(Looper.getMainLooper())
    private var deleteRunnable: Runnable? = null
    private var deleteCount = 0
    private var lastAiCallTime: Long = 0
    // 🔥 New Variables for Language Memory
    private var targetLanguage = "English" // Default
    private var currentLanguage = "en"
    private lateinit var sharedPrefs: android.content.SharedPreferences

    // Theme Management
    private var isDarkTheme = true // Default to dark theme

    // Combined feature button state (last used AI feature)
    private var currentFeatureMode: String = "Translate"

    override fun onCreateInputView(): View {
        // 🔥 Global variable mein save karein
        rootView = layoutInflater.inflate(R.layout.keyboard_view, null)

        suggestionLayout = rootView.findViewById(R.id.suggestion_strip)
        mainKeyboardContainer = rootView.findViewById(R.id.main_keyboard)

        // Height Setup (Screen ka 40-45%)
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        rootView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screenHeight * 0.45).toInt()
        )

        // Pehli baar setup
        sharedPrefs = getSharedPreferences("KeyboardPrefs", android.content.Context.MODE_PRIVATE)
        targetLanguage = sharedPrefs.getString("target_lang", "English") ?: "English"
        isDarkTheme = sharedPrefs.getBoolean("is_dark_theme", true)
        DictionaryUtils.loadDictionary(applicationContext)
        
        // Apply theme
        applyTheme(rootView)

        return rootView // Yahan return kar diya, ab setInputView baar-baar call nahi karna
    }

    // 🔥 FUNCTION TO LOAD EMOJI KEYBOARD
    private fun showEmojiKeyboard() {
        // 1. Container khali karo
        mainKeyboardContainer.removeAllViews()

        // 2. Emoji Layout load karo
        val emojiView = layoutInflater.inflate(R.layout.layout_emoji_board, mainKeyboardContainer, false)
        mainKeyboardContainer.addView(emojiView)
        
        // 3. Apply theme to emoji keyboard
        applyTheme(emojiView)

        // 3. RecyclerView Setup
        val recyclerView = emojiView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.emojiRecyclerView)
        recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 7)

        val adapter = EmojiAdapter(EmojiData.smileys) { selectedEmoji ->
            currentInputConnection?.commitText(selectedEmoji, 1)
        }
        recyclerView.adapter = adapter

        // 4. Listeners setup
        emojiView.findViewById<View>(R.id.tab_faces).setOnClickListener { adapter.updateData(EmojiData.smileys) }
        emojiView.findViewById<View>(R.id.tab_animals).setOnClickListener { adapter.updateData(EmojiData.animals) }
        emojiView.findViewById<View>(R.id.tab_food).setOnClickListener { adapter.updateData(EmojiData.food) }

        // 5. Back Button (Ab ye Switch Layout call karega)
        emojiView.findViewById<View>(R.id.btn_back_abc).setOnClickListener {
            switchKeyboardLayout(R.layout.layout_qwerty)
        }

        emojiView.findViewById<View>(R.id.btn_backspace).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        // Note: Yahan setInputView call NAHI karna hai
    }
    private fun checkAutoCaps() {
        // Agar Caps Lock (State 2) ON hai, to use disturb mat karo
        if (capsState == 2) return

        val inputConnection = currentInputConnection ?: return
        val textBefore = inputConnection.getTextBeforeCursor(2, 0).toString()

        // Logic: Empty hai, ya New Line hai, ya ". " hai -> Capital Karo
        if (textBefore.isEmpty() || textBefore.endsWith("\n") || textBefore.endsWith(". ")) {
            if (capsState != 1) { // Agar pehle se capital nahi hai tabhi update karo
                capsState = 1
                updateKeysVisual(mainKeyboardContainer)
            }
        } else {
            // Baaki time Small rakho
            if (capsState != 0) {
                capsState = 0
                updateKeysVisual(mainKeyboardContainer)
            }
        }
    }


    // 🔥 Change 2: Jab Keyboard Screen par aaye, tab Capital logic check karein
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Hamesha QWERTY se start karein taaki fresh layout rahe
        switchKeyboardLayout(R.layout.layout_qwerty)
        checkAutoCaps()
        // Agar text box khali hai, to Capital on karein
        val textBeforeCursor = currentInputConnection?.getTextBeforeCursor(2, 0)
        if (textBeforeCursor.isNullOrEmpty() || textBeforeCursor.toString().endsWith(". ") || textBeforeCursor.toString().endsWith("\n")) {
            capsState = 1
        } else {
            capsState = 0
        }
        updateKeysVisual(mainKeyboardContainer)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        checkAutoCaps() // Auto Capital check

        if (!isListening) {
            val currentText = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString() ?: ""

            if (currentText.isNotEmpty()) {
                val lastChar = currentText.last()
                val words = currentText.trim().split(" ")
                val lastWord = words.lastOrNull() ?: ""

                var suggestions: List<String> = emptyList()

                // Logic: Space hai to Next Word, nahi to Current Word complete karo
                if (lastChar == ' ') {
                    suggestions = DictionaryUtils.getSuggestions("", lastWord)
                } else {
                    suggestions = DictionaryUtils.getSuggestions(lastWord, "")
                }

                // 🔥 FIX: Agar suggestions khali hain, to TOOLS dikhao (Blank mat rakho)
                if (suggestions.isNotEmpty()) {
                    updateSuggestionStrip("Predictions", suggestions)
                } else {
                    updateSuggestionStrip("Tools")
                }

            } else {
                // Agar text box khali hai to Tools dikhao
                updateSuggestionStrip("Tools")
            }
        }
    }
    // --- DELETE FUNCTIONALITY ---
    private fun startDelete() {
        isDeleting = true
        deleteCount = 0
        executeDelete()
        deleteRunnable = object : Runnable {
            override fun run() {
                if (!isDeleting) return
                executeDelete()
                deleteCount++
                val delay = if (deleteCount > 5) 50L else 100L
                deleteHandler.postDelayed(this, delay)
            }
        }
        deleteHandler.postDelayed(deleteRunnable!!, 400)
    }

    private fun stopDelete() {
        isDeleting = false
        deleteRunnable?.let { deleteHandler.removeCallbacks(it) }
    }

    private fun executeDelete() {
        val inputConnection = currentInputConnection ?: return
        if (deleteCount > 20) {
            val textBefore = inputConnection.getTextBeforeCursor(50, 0).toString()
            if (textBefore.isNotEmpty()) {
                val lastSpaceIndex = textBefore.trimEnd().lastIndexOf(' ')
                val lengthToDelete = if (lastSpaceIndex == -1) textBefore.length else textBefore.length - (lastSpaceIndex + 1)
                inputConnection.deleteSurroundingText(if (lengthToDelete == 0) 1 else lengthToDelete, 0)
            } else {
                inputConnection.deleteSurroundingText(1, 0)
            }
        } else {
            inputConnection.deleteSurroundingText(1, 0)
        }
    }

    // --- SETUP KEYBOARD LISTENERS ---
    private fun setupKeyboard(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child.id == R.id.suggestion_strip || child.id == R.id.suggestion_strip_scroll) continue

            if (child is Button) {
                child.isSoundEffectsEnabled = true
                val keyText = child.text.toString()

                if (keyText == "⌫") {
                    child.setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                animateKeyPress(v, true)
                                startDelete()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                animateKeyPress(v, false)
                                stopDelete()
                                true
                            }
                            else -> false
                        }
                    }
                } else {
                    // Add instant press animation
                    child.setOnTouchListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                animateKeyPress(v, true)
                                false // Let click handler also fire
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                animateKeyPress(v, false)
                                false
                            }
                            else -> false
                        }
                    }
                    child.setOnClickListener { handleKeyPress(child) }
                }
            } else if (child is ViewGroup) {
                setupKeyboard(child)
            }
        }
    }
    
    // Instant key press animation with zero delay
    private fun animateKeyPress(view: View, isPressed: Boolean) {
        val scale = if (isPressed) 0.90f else 1.0f
        val alpha = if (isPressed) 0.75f else 1.0f
        
        // Instant visual feedback - no delay
        view.scaleX = scale
        view.scaleY = scale
        view.alpha = alpha
        
        // Haptic feedback for better UX
        if (isPressed) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        
        // Smooth animation for release
        if (!isPressed) {
            view.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(100)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    // --- SWITCH LAYOUT LOGIC ---
    private fun switchKeyboardLayout(layoutId: Int) {
        // 1. Agar hum Emoji mode (Full screen) mein the, to wapas RootView par aao
        // (Ye tab kaam aayega agar emoji keyboard ne setInputView call kiya tha)
        try {
            setInputView(rootView)
        } catch (e: Exception) {
            // Ignore
        }

        // 2. Container khali karo (Purani keys hatao)
        mainKeyboardContainer.removeAllViews()

        // 3. Naya Layout Container ke andar daalo
        val newLayout = layoutInflater.inflate(layoutId, mainKeyboardContainer, false)
        mainKeyboardContainer.addView(newLayout)

        // 4. Apply theme to new layout
        applyTheme(newLayout)
        
        // 5. Buttons ko Active karo
        setupKeyboard(mainKeyboardContainer)

        // 6. Caps Lock visual update
        updateKeysVisual(mainKeyboardContainer)

        // 7. Language Reset logic
        if (layoutId == R.layout.layout_qwerty) {
            currentLanguage = "en"
        }

        // 8. 🔥 STRIP WAPAS LAO
        updateSuggestionStrip("Tools")
    }
    // --- HANDLE KEY PRESS ---
    private fun handleKeyPress(button: Button) {
        val inputConnection = currentInputConnection ?: return
        val keyText = button.text.toString()

        when (keyText) {
            " ", "English" -> {
                // 🔥 LEARNING LOGIC START 🔥
                val inputConnection = currentInputConnection
                val textBefore = inputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""

                // Last word nikalo (Jo abhi type kiya)
                val words = textBefore.trim().split(" ")
                if (words.isNotEmpty()) {
                    val currentWord = words.last() // Abhi likha hua word
                    val prevWord = if (words.size > 1) words[words.size - 2] else "" // Uske pehle ka word

                    // Dictionary ko bolo: "Ye pattern yaad karlo"
                    DictionaryUtils.learnWordPair(prevWord, currentWord)
                }
                // 🔥 LEARNING LOGIC END 🔥

                inputConnection?.commitText(" ", 1)

                // Ab next word suggest karo (Current word empty hai kyunki space dabaya)
                val newText = inputConnection?.getTextBeforeCursor(50, 0)?.toString() ?: ""
                val lastWordForPrediction = newText.trim().split(" ").lastOrNull() ?: ""
                val predictions = DictionaryUtils.getSuggestions("", lastWordForPrediction)

                updateSuggestionStrip("Predictions", predictions)
            }

            // 👇 SWITCHING LOGIC 👇

            // ... inside handleKeyPress ...

            // Number Layout
            "!#1", "?123" -> switchKeyboardLayout(R.layout.layout_symbols)

            // Page 2 (Symbols)
            "1/2" -> switchKeyboardLayout(R.layout.layout_symbols_page2)

            // Back to page 1
            "2/2" -> switchKeyboardLayout(R.layout.layout_symbols)

            // Wapas ABC
            "ABC" -> switchKeyboardLayout(R.layout.layout_qwerty)

            // Combined feature key – open tools & run last selected feature if text selected
            "✨" -> {
                updateSuggestionStrip("Tools")
                callGeminiAI(currentFeatureMode)
            }

            "😀" -> {
                showEmojiKeyboard() // Naya Function Call
            }
            "🌙", "☀️" -> {
                toggleTheme()
            }

            // Enter key: send proper newline / action
            "↵" -> {
                // Try IME action if available, otherwise send Enter key event
                val editorInfo = currentInputEditorInfo
                val handled = if (editorInfo != null &&
                    editorInfo.imeOptions and EditorInfo.IME_ACTION_DONE != 0
                ) {
                    inputConnection.performEditorAction(editorInfo.imeOptions)
                    true
                } else {
                    inputConnection.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                    )
                    inputConnection.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                    )
                    true
                }
                if (!handled) {
                    inputConnection.commitText("\n", 1)
                }
            }
            // 🔥 UPDATE: Dono naam handle karein ("⇧" aur "Shift")
            "⇧", "Shift" -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastShiftTime < 500) capsState = 2 else capsState = if (capsState == 0) 1 else 0
                lastShiftTime = currentTime
                updateKeysVisual(button.rootView as ViewGroup)
            }

            else -> {
                var textToType = keyText
                if (keyText.length == 1 && keyText[0].isLetter()) {
                    textToType = if (capsState > 0) keyText.uppercase(Locale.ROOT) else keyText.lowercase(Locale.ROOT)
                }
                inputConnection.commitText(textToType, 1)

                // 1 Letter type karne ke baad wapas Small (agar Caps Lock nahi hai to)
                if (capsState == 1) {
                    capsState = 0
                    updateKeysVisual(button.rootView as ViewGroup)
                }
            }
        }
    }

    // --- SUGGESTION STRIP ---
    private fun updateSuggestionStrip(mode: String, suggestions: List<String> = emptyList()) {
        suggestionLayout.removeAllViews()
        val context = suggestionLayout.context

        // 1. Listening Mode
        if (isListening) {
            val btn = Button(context)
            btn.text = "Listening... 🔴"
            btn.textSize = 14f
            btn.setTextColor(resources.getColor(android.R.color.white, null))
            btn.setBackgroundColor(resources.getColor(android.R.color.holo_red_dark, null))
            btn.setOnClickListener {
                speechRecognizer?.stopListening()
                isListening = false
                updateSuggestionStrip("Tools")
            }
            suggestionLayout.addView(btn)
            return
        }

        // 2. Language Selection Mode (Naya Feature)
        if (mode == "Languages") {
            val languages = listOf("English", "Gujarati", "Hindi", "Marathi", "Spanish", "French", "German", "Sanskrit")

            // "Back" Button
            addButtonToStrip(context, "⬅️ Back", isTool = true)

            for (lang in languages) {
                val btn = Button(context)
                btn.text = lang + if(lang == targetLanguage) " ✅" else "" // Selected dikhane ke liye
                btn.textSize = 14f
                btn.setBackgroundResource(
                    if (isDarkTheme) R.drawable.key_selector
                    else R.drawable.key_selector_light
                )
                btn.setTextColor(
                    resources.getColor(
                        if (isDarkTheme) R.color.text_primary_dark
                        else R.color.text_primary_light,
                        null
                    )
                )
                btn.setPadding(30, 0, 30, 0)

                btn.setOnClickListener {
                    // 🔥 Save Language
                    targetLanguage = lang
                    sharedPrefs.edit().putString("target_lang", lang).apply()

                    // Wapas Tools par jao
                    updateSuggestionStrip("Tools")
                    Toast.makeText(context, "Language set to $lang", Toast.LENGTH_SHORT).show()
                }
                suggestionLayout.addView(btn)
            }
            return
        }

        // 3. Tools Mode (Updated)
        if (mode == "Tools") {
            // Theme Toggle Button
            addButtonToStrip(context, if (isDarkTheme) "☀️ Light" else "🌙 Dark", isTool = true)
            
            // Button 1: Translate (Jo saved hai wahi dikhayega)
            addButtonToStrip(context, "Trans ($targetLanguage) ⚡", isTool = true)

            // Button 2: Change Language
            addButtonToStrip(context, "Change Lang 🗣️", isTool = true)

            val otherTools = listOf("Grammar ✔️", "Tone 👔", "Summary ✂️", "Mic 🎙️")
            for (tool in otherTools) {
                addButtonToStrip(context, tool, isTool = true)
            }
        }
        // 4. Predictions Mode
        else if (mode == "Predictions") {
            for (word in suggestions) {
                addButtonToStrip(context, word, isTool = false)
            }
        }
    }

    private fun addButtonToStrip(context: android.content.Context, text: String, isTool: Boolean) {
        val btn = Button(context)
        btn.text = text
        btn.textSize = 14f
        btn.setBackgroundResource(
            if (isDarkTheme) R.drawable.key_selector
            else R.drawable.key_selector_light
        )
        btn.setTextColor(
            resources.getColor(
                if (isDarkTheme) R.color.text_primary_dark
                else R.color.text_primary_light,
                null
            )
        )
        btn.setPadding(30, 0, 30, 0)

        btn.setOnClickListener {
            if (isTool) {
                when {
                    text.contains("Back") -> updateSuggestionStrip("Tools")
                    text.contains("Change Lang") -> updateSuggestionStrip("Languages") // List kholo
                    text.contains("☀️") || text.contains("🌙") -> toggleTheme()

                    // 🔥 Translate Button ab Dynamic hai
                    text.contains("Trans") -> callGeminiAI("Translate")

                    text.contains("Grammar") -> callGeminiAI("Grammar")
                    text.contains("Tone") -> callGeminiAI("Professional")
                    text.contains("Summary") -> callGeminiAI("Summary")
                    text.contains("Mic") -> startVoiceInput()
                }
            } else {
                val inputConnection = currentInputConnection ?: return@setOnClickListener
                val currentText = inputConnection.getTextBeforeCursor(50, 0).toString()
                val lastSpace = currentText.lastIndexOf(' ')
                val incompleteWord = if (lastSpace == -1) currentText else currentText.substring(lastSpace + 1)
                inputConnection.deleteSurroundingText(incompleteWord.length, 0)
                inputConnection.commitText("$text ", 1)
            }
        }
        suggestionLayout.addView(btn)
    }

    // --- GEMINI AI INTEGRATION ---
    private fun callGeminiAI(mode: String = "Translate") {
        // Remember last selected feature mode
        currentFeatureMode = mode

        val inputConnection = currentInputConnection ?: return
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAiCallTime < 2000) return
        lastAiCallTime = currentTime

        val selectedText = inputConnection.getTextBeforeCursor(100, 0)?.toString()?.trim()
        if (selectedText.isNullOrEmpty()) return

        val cacheKey = "$mode:$selectedText"
        val cachedResult = translationCache.get(cacheKey)
        if (cachedResult != null) {
            inputConnection.deleteSurroundingText(selectedText.length, 0)
            inputConnection.commitText(cachedResult, 1)
            return
        }

        inputConnection.deleteSurroundingText(selectedText.length, 0)
        inputConnection.commitText("($mode...)", 1)

        serviceScope.launch(Dispatchers.IO) {
            try {
                // 🔥 STRICT PROMPTS (No Extra Talk) 🔥
                // 🔥 DYNAMIC PROMPT (Uses targetLanguage)
                val prompt = when(mode) {
                    "Grammar" -> "Correct the grammar. Output ONLY corrected text. Text: $selectedText"
                    "Professional" -> "Make this professional. Output ONLY text. Text: $selectedText"
                    "Summary" -> "Summarize in one sentence. Output ONLY summary. Text: $selectedText"

                    // 👇 Yahan Magic Hoga (Use saved language) 👇
                    else -> "Translate the following text to $targetLanguage. Output ONLY the translation. No notes. Text: $selectedText"
                }
                // Note: Ensure GeminiRequest, Content, Part, RetrofitClient are defined in your project
                val request = GeminiRequest(listOf(Content(listOf(Part(prompt)))))
                val cleanUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent?key=$API_KEY"
                val response = RetrofitClient.instance.generateResponse(cleanUrl, request)

                if (response.candidates != null && response.candidates.isNotEmpty()) {
                    val resultText = response.candidates[0].content?.parts?.get(0)?.text?.trim() ?: "Error"
                    translationCache.put(cacheKey, resultText)
                    withContext(Dispatchers.Main) {
                        inputConnection.deleteSurroundingText("($mode...)".length, 0)
                        inputConnection.commitText(resultText, 1)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    inputConnection.deleteSurroundingText("($mode...)".length, 0)
                    inputConnection.commitText(selectedText, 1)
                }
            }
        }
    }

    // --- VOICE INPUT ---
    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null

    private fun startVoiceInput() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(applicationContext, "Permission Needed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                speechIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                speechIntent?.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { isListening = true; updateSuggestionStrip("Listening") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening = false; updateSuggestionStrip("Tools") }
                    override fun onError(error: Int) { isListening = false; updateSuggestionStrip("Tools") }
                    override fun onResults(results: android.os.Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) currentInputConnection?.commitText(matches[0] + " ", 1)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            handler.post { speechRecognizer?.startListening(speechIntent) }
        } catch (e: Exception) {
            Toast.makeText(this, "Voice Error", Toast.LENGTH_SHORT).show()
        }
    }

    // --- VISUAL UPDATES (CAPS LOCK) ---
    private fun updateKeysVisual(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) updateKeysVisual(child)
            else if (child is Button) {
                val text = child.text.toString()
                // Agar button single letter hai (A-Z), to use CapsState ke hisab se bada/chota karein
                if (text.length == 1 && text[0].isLetter()) {
                    child.text = if (capsState > 0) text.uppercase(Locale.ROOT) else text.lowercase(Locale.ROOT)
                }
            }
        }
    }

    // Theme Management Functions
    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        sharedPrefs.edit().putBoolean("is_dark_theme", isDarkTheme).apply()
        applyTheme(rootView)
        // Reload current layout to apply theme
        val currentLayout = when {
            mainKeyboardContainer.childCount > 0 -> {
                // Detect current layout - simple approach: reload qwerty
                R.layout.layout_qwerty
            }
            else -> R.layout.layout_qwerty
        }
        switchKeyboardLayout(currentLayout)
        Toast.makeText(this, if (isDarkTheme) "Dark Mode" else "Light Mode", Toast.LENGTH_SHORT).show()
    }
    
    private fun applyTheme(view: View) {
        val bgColor = if (isDarkTheme) {
            resources.getColor(R.color.keyboard_background_dark, null)
        } else {
            resources.getColor(R.color.keyboard_background_light, null)
        }
        
        val suggestionBgColor = if (isDarkTheme) {
            resources.getColor(R.color.suggestion_bg_dark, null)
        } else {
            resources.getColor(R.color.suggestion_bg_light, null)
        }
        
        view.setBackgroundColor(bgColor)
        
        // Apply theme to all buttons recursively
        applyThemeToViewGroup(view as? ViewGroup)
        
        // Update suggestion strip background
        if (::suggestionLayout.isInitialized) {
            suggestionLayout.setBackgroundColor(suggestionBgColor)
        }
    }
    
    private fun applyThemeToViewGroup(viewGroup: ViewGroup?) {
        viewGroup?.let {
            for (i in 0 until it.childCount) {
                val child = it.getChildAt(i)
                when (child) {
                    is Button -> {
                        applyThemeToButton(child)
                    }
                    is ViewGroup -> {
                        applyThemeToViewGroup(child)
                    }
                }
            }
        }
    }
    
    private fun applyThemeToButton(button: Button) {
        val keyText = button.text.toString()
        
        // Skip special buttons that have custom backgrounds
        if (keyText == "AI" || keyText == "↵") {
            return
        }
        
        val isSpecial = keyText in listOf("⇧", "⌫", "?123", "!#1", "ABC", "1/2", "2/2", "😀", "✨")
        
        if (isDarkTheme) {
            button.setBackgroundResource(
                if (isSpecial) R.drawable.key_special_selector
                else R.drawable.key_selector
            )
            button.setTextColor(resources.getColor(R.color.text_primary_dark, null))
        } else {
            button.setBackgroundResource(
                if (isSpecial) R.drawable.key_special_selector_light
                else R.drawable.key_selector_light
            )
            button.setTextColor(resources.getColor(R.color.text_primary_light, null))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        speechRecognizer?.destroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
}