package dev.alder

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Paint
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.util.Log
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.text.Normalizer
import java.util.concurrent.Future

/** More complete title matches lead; ties retain the installed add-ons' order. */
internal fun <T> rankSearchTitles(items: List<T>, query: String, rating: (T) -> Double? = { null }, title: (T) -> String): List<T> {
    fun words(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val wanted = words(query)
    if (wanted.isEmpty()) return items
    val articles = setOf("the", "a", "an")
    // "matrix" finds "The Matrix" as fully as the short film "Matrix"; then the better-rated title goes first.
    return items.sortedWith(compareBy<T> { item ->
        val actual = words(title(item)).let { if (wanted.first() !in articles && it.firstOrNull() in articles) it.drop(1) else it }
        when {
            actual == wanted -> 0
            actual.take(wanted.size) == wanted -> 1
            wanted.all { it in actual } -> 2
            actual.joinToString(" ").contains(wanted.joinToString(" ")) -> 3
            else -> 4
        }
    }.thenByDescending { rating(it) ?: 0.0 })
}

/**
 * What a render of [sections] shows, in order: the same key means the same rows, so a late add-on that adds nothing
 * redraws nothing. Each card is given by its id, poster and name, so a preferred add-on's answer with another poster
 * for the same title still redraws.
 */
internal fun searchRenderKey(sections: Map<String, List<String>>, query: String): String =
    query + "|" + sections.entries.joinToString("|") { (label, cards) -> label + ":" + cards.joinToString(",") }

/** SpeechRecognizer's error codes by name, for the log. */
internal fun voiceErrorName(code: Int): String = when (code) {
    1 -> "network timeout"; 2 -> "network"; 3 -> "audio"; 4 -> "server"; 5 -> "client"; 6 -> "speech timeout"
    7 -> "no match"; 8 -> "recognizer busy"; 9 -> "insufficient permissions"; 10 -> "too many requests"
    11 -> "server disconnected"; 12 -> "language not supported"; 13 -> "language unavailable"
    else -> "unknown"
}

internal fun <T> searchAnswersInAddonOrder(answers: Array<List<T>?>): List<T> = answers.filterNotNull().flatten()

/** ChadFlix search: Voice Search and the keyboard on the left, Alder's Movies and Shows rows of home posters on the right. */
class SearchActivity : Screen() {
    private lateinit var input: EditText // the header's words: the typed query, or "Popular Titles" while it's empty
    private lateinit var recentBox: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var keyboard: LinearLayout
    private lateinit var headerBar: View
    private lateinit var nav: LinearLayout
    private lateinit var searchTab: ImageView
    private var voice: View? = null
    private var clear: View? = null // above Voice Search while the query has words
    private var styleVoice: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null // ChadFlix: in-app listening, no Google overlay
    private var recognizerService: ComponentName? = null // null: the TV's default speech service
    private var listening = false
    private var heardSpeech = false // the service got as far as ready-for-speech this session
    private var beforeVoice: String? = null // the query as it was when listening began, while the header shows voice's words
    private var quiet = false // the header's words are changing for voice: don't search them
    private val restoreHeader = Runnable { restoreQuery() }
    private var navTabIds = emptyList<String>()
    private val rightEdgeKeys = mutableSetOf<View>()
    private val cards = mutableListOf<MediaCardView>()
    private var firstKey: View? = null
    private var lastKey: View? = null
    private var lastKeyboardFocus: View? = null
    private var firstResult: View? = null
    private var resultsAction: View? = null
    private var rendered: String? = null // [searchRenderKey] of what's on screen
    private var request = 0
    private var userMovedFocus = false
    private val searchTasks = mutableListOf<Future<*>>()
    private val typed = Runnable { search(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(HomeStyle.BLACK); clipChildren = false }
        headerBar = header()
        root.addView(headerBar, FrameLayout.LayoutParams(-1, dp(76)))
        styleNav()

        // The query's header to the right of the keyboard: a search glyph, then "Popular Titles" until something is typed.
        input = EditText(this).apply {
            hint = getString(R.string.search_popular_titles)
            setHintTextColor(HomeStyle.WHITE)
            setTextColor(HomeStyle.WHITE)
            textSize = HomeStyle.ROW_TITLE
            typeface = text("", HomeStyle.ROW_TITLE, bold = true).typeface
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            id = View.generateViewId()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            showSoftInputOnFocus = false // phone remotes and TalkBack type into it without our own keyboard's help
            background = null
            setPadding(dp(12), 0, 0, 0)
            setSelectAllOnFocus(false)
            // No box: the header's words are underlined while the field has the remote.
            setOnFocusChangeListener { _, has ->
                paintFlags = if (has) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                invalidate()
                if (!has) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
            }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SearchActivity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(HomeStyle.WHITE)
            }, LinearLayout.LayoutParams(dp(12), dp(12)))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        }, FrameLayout.LayoutParams(dp((width - RESULTS_X - PAD).toInt()), dp(28)).apply { leftMargin = dp(RESULTS_X); topMargin = dp(66) })

        keyboard = column(Gravity.START)
        buildKeyboard()
        voice?.let { root.addView(it, FrameLayout.LayoutParams(dp(KEYS_W), dp(KEY_H)).apply { leftMargin = dp(KEYS_X); topMargin = dp(104) }) }
        root.addView(keyboard, FrameLayout.LayoutParams(dp(KEYS_W), -2).apply { leftMargin = dp(KEYS_X); topMargin = dp(148) })

        recentBox = column(Gravity.START).apply { clipChildren = false; clipToPadding = false }
        root.addView(recentBox, FrameLayout.LayoutParams(dp(KEYS_W), -2).apply { leftMargin = dp(KEYS_X); topMargin = dp(384) })
        showRecent()

        results = column(Gravity.START).apply { setPadding(0, 0, 0, dp(32)) }
        resultsScroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
        }.apply {
            isVerticalScrollBarEnabled = false
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(results)
        }
        // Runs to the screen's right edge, as Home's rows do: stopping 48 dp short sliced the last poster in half.
        root.addView(resultsScroll, FrameLayout.LayoutParams(dp((width - RESULTS_X).toInt()), dp((height - 104).toInt())).apply {
            leftMargin = dp(RESULTS_X); topMargin = dp(104)
        })
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(HomeStyle.BLACK))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            userMovedFocus = true
            styleNav()
            lastKey?.let { key ->
                if (!key.hasFocus()) updateKeyStyle(key, false)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateClear()
                if (quiet) return
                ++request
                searchTasks.forEach { it.cancel(true) }
                searchTasks.clear()
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(typed)
            search(true)
            true
        }
        // Our keyboard only: the Android one never opens.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        clear?.let { root.addView(it, FrameLayout.LayoutParams(dp(KEYS_W), dp(KEY_H)).apply { leftMargin = dp(KEYS_X); topMargin = dp(104 - KEY_H - 12) }) }
        updateClear()
        (voice ?: firstKey)?.let { it.post { it.requestFocus() } }
        search(false)
    }

    override fun onResume() {
        super.onResume()
        if (::nav.isInitialized) updateNavTabs()
    }

    override fun onPause() {
        cancelVoice()
        super.onPause()
    }

    override fun onStop() {
        cancelVoice()
        super.onStop()
    }

    // The add-ons' catalog types, for Live and the per-type tabs that Home shows too.
    private var catalogTypes: List<String> = emptyList()

    private fun header(): View {
        val profile = Profiles.active(this)
        val bar = FrameLayout(this).apply { setPadding(dp(PAD), 0, dp(PAD), dp(12)) } // as Home's bar
        val avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", SMALL, bold = true, color = HomeStyle.WHITE).apply {
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.nav_profile)
            val rest = rounded(HomeStyle.AVATAR, 20)
            fun style(focused: Boolean) {
                background = if (focused) rounded(HomeStyle.WHITE, 20) else rest
                setTextColor(if (focused) HomeStyle.BLACK else HomeStyle.WHITE)
                grow(focused).start()
            }
            style(false)
            isFocusable = true
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener {
                val pendingUpdate = Updates.available(this@SearchActivity)
                val items = buildList {
                    if (pendingUpdate != null) add(getString(R.string.menu_install_update, pendingUpdate.version))
                    add(getString(R.string.menu_switch_profile))
                    add(getString(R.string.menu_settings))
                }
                choose(profile?.name ?: getString(R.string.nav_profile), items) { picked ->
                    if (pendingUpdate != null && picked == 0) return@choose installUpdate(pendingUpdate)
                    val offset = if (pendingUpdate != null) picked - 1 else picked
                    startActivity(Intent(this@SearchActivity,
                        if (offset == 0) ProfilesActivity::class.java else SettingsActivity::class.java)
                        .apply { if (offset == 0) {
                            putExtra("pick", true)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        } })
                }
            }
        }
        val dot = View(this).apply {
            background = rounded(TUNGSTEN, 4)
            elevation = dp(4).toFloat()
            visibility = if (Updates.hasUpdate(this@SearchActivity)) View.VISIBLE else View.GONE
        }
        val avatarBox = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30)))
            addView(dot, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1)
                marginEnd = dp(1)
            })
        }
        bar.addView(avatarBox, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        searchTab = ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            contentDescription = getString(R.string.nav_search)
            setPadding(dp(13), dp(7), dp(13), dp(7))
            isFocusable = true
            setOnClickListener { (lastKeyboardFocus ?: firstKey)?.requestFocus() }
        }
        nav.addView(searchTab, LinearLayout.LayoutParams(dp(44), -1))
        updateNavTabs()
        background({ Stremio.savedAddons(authKey.orEmpty()).orEmpty().flatMap { a -> a.catalogs.map { it.type } } }) { r ->
            r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { catalogTypes = it; updateNavTabs() }
        }
        bar.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // As Home's: a gear for Settings at the right.
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = getString(R.string.menu_settings)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            isFocusable = true
            fun style(on: Boolean) {
                background = if (on) HomeStyle.pill(HomeStyle.WHITE) else null
                setColorFilter(if (on) HomeStyle.BLACK else HomeStyle.WHITE)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener { startActivity(Intent(this@SearchActivity, SettingsActivity::class.java)) }
        }, FrameLayout.LayoutParams(dp(44), dp(32), Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    /** As Home's bar: the open page (Search) keeps a white pill while the remote is below, a faint one while it's elsewhere up here. */
    private fun styleNav() {
        val lit = searchTab.isFocused || headerBar.findFocus() == null
        searchTab.background = HomeStyle.pill(if (lit) HomeStyle.WHITE else HomeStyle.OPEN_TAB)
        searchTab.setColorFilter(if (lit) HomeStyle.BLACK else HomeStyle.WHITE)
    }

    private fun updateNavTabs() {
        // The page goes to MainActivity by its English name; the bar shows it in the viewer's language.
        val pages = mapOf(
            "home" to ("Home" to getString(R.string.tab_home)),
            "discover" to ("Discover" to getString(R.string.tab_discover)),
            "shows" to ("Shows" to getString(R.string.tab_shows)),
            "movies" to ("Movies" to getString(R.string.tab_movies)),
            "live" to ("Live" to getString(R.string.tab_live)),
            "library" to ("My Alder" to getString(R.string.tab_my_list)),
        )
        val visible = orderTabs(Settings.tabOrder(this), tabsFor(catalogTypes)).filter { Settings.tabShown(this, it) }
        val ids = listOfNotNull("home".takeIf { Settings.startTab(this) == "home" }) + visible // as Home: only when it is the start page
        if (ids == navTabIds) return
        val focusedId = nav.focusedChild?.let { navTabIds.getOrNull(nav.indexOfChild(it) - 1) }
        navTabIds = ids
        nav.removeViews(1, nav.childCount - 1)
        ids.forEach { id ->
            // A per-type tab ("type:anime") goes to Home's page of that type, which Home names by the type.
            val (page, words) = pages[id] ?: id.removePrefix("type:").let { it to typeTabName(this, it) }
            nav.addView(text(words, HomeStyle.TAB_SP, color = HomeStyle.WHITE).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(5), dp(16), dp(5))
                isFocusable = true
                setOnFocusChangeListener { _, has ->
                    background = if (has) HomeStyle.pill(HomeStyle.WHITE) else null
                    setTextColor(if (has) HomeStyle.BLACK else HomeStyle.WHITE)
                }
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        if (focusedId != null) nav.getChildAt(ids.indexOf(focusedId) + 1).requestFocus()
    }

    /** ChadFlix's keyboard: a-z then 1-0 in six columns, Space and Delete as words under them, Voice Search above. */
    private fun buildKeyboard() {
        val rows = "abcdefghijklmnopqrstuvwxyz1234567890".chunked(6).map { chars ->
            val row = keyRow()
            chars.mapIndexed { column, char -> key(char.toString(), row, 1, column == 5) { append(char.toString()) } }
        }
        val last = keyRow()
        val space = key(getString(R.string.search_space), last, 3, false) { append(" ") }
        val delete = key(getString(R.string.search_delete), last, 3, true) { delete() }

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            val icon = ImageView(this).apply { setImageResource(R.drawable.ic_mic) }
            val label = text(getString(R.string.search_voice), SMALL, bold = true)
            voice = LinearLayout(this).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                isFocusable = true
                contentDescription = getString(R.string.search_voice)
                fun style(has: Boolean) {
                    background = HomeStyle.pill(if (has) HomeStyle.WHITE else HomeStyle.OPEN_TAB)
                    icon.imageTintList = ColorStateList.valueOf(if (listening) HomeStyle.LIST_RED else if (has) HomeStyle.BLACK else HomeStyle.WHITE)
                    label.setTextColor(if (has) HomeStyle.BLACK else HomeStyle.WHITE)
                    label.text = getString(if (listening) R.string.search_listening else R.string.search_voice)
                }
                style(false)
                styleVoice = { style(hasFocus()) }
                setOnFocusChangeListener { v, has ->
                    if (has) lastKeyboardFocus = v
                    style(has)
                }
                setOnClickListener {
                    // OK while listening: stop and search what was heard so far.
                    if (listening) return@setOnClickListener stopVoice()
                    // ChadFlix: ask for the microphone once; the speech service checks the asking app holds it.
                    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
                    else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
                }
                addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)))
                addView(label, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
            }
            rightEdgeKeys += voice!!
        }
        // ChadFlix: Clear, in Voice Search's style, above it while the query has words.
        val clearIcon = ImageView(this).apply { setImageResource(R.drawable.ic_close) }
        val clearLabel = text(getString(R.string.search_clear_pill), SMALL, bold = true)
        clear = LinearLayout(this).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            isFocusable = true
            visibility = View.GONE
            contentDescription = getString(R.string.search_clear_pill)
            fun style(has: Boolean) {
                background = HomeStyle.pill(if (has) HomeStyle.WHITE else HomeStyle.OPEN_TAB)
                clearIcon.imageTintList = ColorStateList.valueOf(if (has) HomeStyle.BLACK else HomeStyle.WHITE)
                clearLabel.setTextColor(if (has) HomeStyle.BLACK else HomeStyle.WHITE)
            }
            style(false)
            setOnFocusChangeListener { v, has ->
                if (has) lastKeyboardFocus = v
                style(has)
            }
            setOnClickListener {
                cancelVoice()
                (voice ?: firstKey)?.requestFocus()
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
            }
            addView(clearIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
            addView(clearLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        rightEdgeKeys += clear!!

        rows.forEachIndexed { r, keys ->
            keys.forEachIndexed { c, v ->
                v.nextFocusUpId = if (r > 0) rows[r - 1][c].id else voice?.id ?: input.id
                v.nextFocusDownId = if (r < rows.lastIndex) rows[r + 1][c].id else (if (c < 3) space else delete).id
            }
        }
        firstRow = rows[0]
        space.nextFocusUpId = rows.last()[1].id
        delete.nextFocusUpId = rows.last()[4].id
        voice?.nextFocusDownId = rows[0][0].id
        clear?.nextFocusUpId = input.id
        clear?.nextFocusDownId = (voice ?: rows[0][0]).id
    }

    private var firstRow = emptyList<View>()

    /** Clear shows while the query has words; Up from Voice Search (or the keys) goes Clear → query field, Down mirrors. */
    private fun updateClear() {
        val c = clear ?: return
        val show = (beforeVoice ?: input.text.toString()).isNotEmpty()
        if (!show && c.hasFocus()) (voice ?: firstKey)?.requestFocus()
        if (!show && lastKeyboardFocus === c) lastKeyboardFocus = voice ?: firstKey
        c.visibility = if (show) View.VISIBLE else View.GONE
        val above = if (show) c.id else input.id
        voice?.nextFocusUpId = above
        if (voice == null) firstRow.forEach { it.nextFocusUpId = above }
        input.nextFocusDownId = if (show) c.id else (voice ?: firstKey)?.id ?: View.NO_ID
    }

    /** The header's words without searching them: voice's partial words, "Didn't catch that", the query back again. */
    private fun setHeader(words: String) {
        quiet = true
        input.setText(words)
        input.setSelection(words.length)
        quiet = false
    }

    /** Ends a "Didn't catch that" or a cancelled listen: the query as it was before. */
    private fun restoreQuery() {
        main.removeCallbacks(restoreHeader)
        val old = beforeVoice ?: return
        beforeVoice = null
        setHeader(old)
    }

    /** The Shield's own speech service, named outright: tried once when the default binding refuses. */
    private fun katnissService(): ComponentName? = runCatching {
        packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .let { found -> found.firstOrNull { it.packageName == KATNISS } ?: found.firstOrNull() }
    }.getOrNull() ?: ComponentName(KATNISS, "com.google.android.apps.tvsearch.voice.recognition.KatnissRecognitionService")

    private fun recognizer(): SpeechRecognizer = recognizer ?: run {
        val service = recognizerService
        Log.i(VOICE_TAG, "create recognizer: " + (service?.flattenToShortString() ?: "default service"))
        (if (service == null) SpeechRecognizer.createSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this, service)).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
    }

    private fun startVoice() {
        if (isFinishing || isDestroyed) return
        val r = runCatching { recognizer() }.getOrElse { Log.w(VOICE_TAG, "create failed", it); return }
        r.cancel() // one session at a time
        restoreQuery()
        beforeVoice = input.text.toString()
        heardSpeech = false
        listening = true
        styleVoice()
        updateClear()
        Log.i(VOICE_TAG, "startListening (" + (recognizerService?.flattenToShortString() ?: "default service") + ")")
        runCatching {
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        }.onFailure { Log.w(VOICE_TAG, "startListening failed", it); listening = false; styleVoice(); restoreQuery() }
    }

    /** OK while listening: the service stops and answers with what it heard (results or no match). */
    private fun stopVoice() {
        Log.i(VOICE_TAG, "stopListening")
        runCatching { recognizer?.stopListening() }
        listening = false
        styleVoice()
    }

    /** Back, Clear, leaving the page: drop the session and put the query back. */
    private fun cancelVoice() {
        if (listening) Log.i(VOICE_TAG, "cancel")
        runCatching { recognizer?.cancel() }
        listening = false
        styleVoice()
        restoreQuery()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { heardSpeech = true; Log.i(VOICE_TAG, "onReadyForSpeech") }
        override fun onBeginningOfSpeech() { Log.i(VOICE_TAG, "onBeginningOfSpeech") }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { Log.i(VOICE_TAG, "onEndOfSpeech") }
        override fun onEvent(eventType: Int, params: Bundle?) { Log.i(VOICE_TAG, "onEvent $eventType") }

        override fun onPartialResults(partialResults: Bundle?) {
            if (beforeVoice == null) return
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            if (!words.isNullOrEmpty()) setHeader(words)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            styleVoice()
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
            Log.i(VOICE_TAG, "onResults: " + (if (spoken.isNullOrEmpty()) "nothing" else "${spoken.length} chars"))
            if (spoken.isNullOrEmpty()) return missed()
            main.removeCallbacks(restoreHeader)
            beforeVoice = null
            input.setText(spoken)
            input.setSelection(spoken.length)
            main.removeCallbacks(typed)
            search(true)
        }

        override fun onError(error: Int) {
            Log.w(VOICE_TAG, "onError $error (${voiceErrorName(error)})" + (recognizerService?.let { " via ${it.flattenToShortString()}" } ?: " via default service"))
            val wasListening = listening
            listening = false
            styleVoice()
            // The default binding refused before listening began: name Katniss's service outright and try once more.
            if (wasListening && !heardSpeech && recognizerService == null && error in RETRY_ERRORS) {
                runCatching { recognizer?.destroy() }
                recognizer = null
                recognizerService = katnissService()
                restoreQuery()
                return startVoice()
            }
            missed()
        }
    }

    /** No words: "Didn't catch that" in the header for a moment, then the query as it was. */
    private fun missed() {
        if (beforeVoice == null) return
        setHeader(getString(R.string.search_voice_missed))
        main.removeCallbacks(restoreHeader)
        main.postDelayed(restoreHeader, MISSED_SHOWN)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Granted: straight into listening. Refused: voice search simply doesn't start; typing still works.
        if (requestCode == MIC_REQUEST && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) main.post { startVoice() }
    }

    private fun keyRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        keyboard.addView(this, LinearLayout.LayoutParams(-1, dp(KEY_H)))
    }

    /** Plain white words; the focused key is a white pill with black words, as in the top bar. */
    private fun updateKeyStyle(view: View, has: Boolean) {
        view.background = if (has) HomeStyle.pill(HomeStyle.WHITE) else null
        if (view is TextView) view.setTextColor(if (has) HomeStyle.BLACK else HomeStyle.WHITE)
    }

    private fun key(label: String, row: LinearLayout, span: Int, rightEdge: Boolean, click: () -> Unit): TextView {
        val view = text(label, if (label.length > 1) SMALL else BODY, color = HomeStyle.WHITE).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            isFocusable = true
        }
        view.setOnFocusChangeListener { v, has ->
            if (has) lastKeyboardFocus = view
            updateKeyStyle(v, has)
        }
        view.setOnClickListener {
            val previous = lastKey
            lastKey = view
            if (previous != null && previous !== view && !previous.hasFocus()) updateKeyStyle(previous, false)
            click()
        }
        row.addView(view, LinearLayout.LayoutParams(dp(KEY_W * span), -1))
        if (firstKey == null) firstKey = view
        if (rightEdge) rightEdgeKeys += view
        return view
    }

    private fun append(value: String) {
        restoreQuery()
        val at = input.selectionStart.coerceIn(0, input.length())
        input.text.insert(at, value)
        input.setSelection(at + value.length)
    }

    private fun delete() {
        restoreQuery()
        val at = input.selectionStart.coerceIn(0, input.length())
        if (at > 0) {
            input.text.delete(at - 1, at)
            input.setSelection(at - 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            // Back while listening cancels it and keeps the page.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && listening) { cancelVoice(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                (resultsScroll.hasFocus() || recentBox.hasFocus() || headerBar.hasFocus() || input.hasFocus())) {
                if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus in rightEdgeKeys) {
                // Still loading (nothing to land on): stay put rather than let Android's search jump to the gear.
                (firstResult ?: resultsAction)?.requestFocus()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cards.any { it === focus }) {
                if (firstResult === focus || cards.indexOfFirst { it === focus } == 0) {
                    if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
                }
            }
            if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_SPACE) { append(" "); return true }
            val char = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
            if (char != null && !event.isCtrlPressed && !event.isAltPressed) {
                append(char.toString())
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun recentKey() = "recent:${Profiles.active(this)?.id.orEmpty()}"

    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString(recentKey(), "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        val values = (listOf(query) + recent().filterNot { it.equals(query, ignoreCase = true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString(recentKey(), values.joinToString("\n")).apply()
        showRecent()
    }

    private fun showRecent(restoreFocus: Boolean = false) {
        recentBox.removeAllViews()
        val values = recent().take(RECENT)
        if (values.isEmpty()) {
            if (restoreFocus) (lastKeyboardFocus ?: firstKey)?.requestFocus()
            return
        }
        recentBox.addView(caps(R.string.search_recent, TEXT_3), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        var firstChip: View? = null
        values.chunked(2).forEach { pair ->
            val line = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            pair.forEach { query ->
                val chipView = chip(query).apply {
                    textSize = SMALL
                    maxWidth = dp(95)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    contentDescription = getString(R.string.search_recent_hint, query)
                    setOnClickListener {
                        input.setText(query)
                        input.setSelection(query.length)
                        main.removeCallbacks(typed)
                        search(true)
                    }
                    setOnLongClickListener {
                        choose(getString(R.string.search_remove_recent),
                            listOf(getString(R.string.search_remove_query, query), getString(R.string.action_cancel))) { picked ->
                            if (picked == 0) {
                                getSharedPreferences("search", MODE_PRIVATE).edit()
                                    .putString(recentKey(), recent().filterNot { it == query }.joinToString("\n")).apply()
                                showRecent(restoreFocus = true)
                            }
                        }
                        true
                    }
                }
                if (firstChip == null) firstChip = chipView
                line.addView(chipView, LinearLayout.LayoutParams(-2, dp(24)).apply { if (line.childCount > 0) marginStart = dp(5) })
            }
            recentBox.addView(line, LinearLayout.LayoutParams(-1, dp(27)))
        }
        recentBox.addView(text(R.string.search_hold_to_remove, HomeStyle.INFO, color = HomeStyle.PLOT),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        if (restoreFocus) (firstChip ?: lastKeyboardFocus ?: firstKey)?.requestFocus()
    }

    private fun showLoading() {
        resultsAction = null
        results.removeAllViews()
        results.addView(ring(), LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            topMargin = dp(24)
            marginStart = dp(16)
        })
    }

    private fun showEmpty() {
        results.removeAllViews()
        val clearBtn = pill(R.string.search_clear).apply {
            setOnClickListener {
                input.setText("")
                main.removeCallbacks(typed)
                search(false)
                firstKey?.requestFocus()
            }
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        resultsAction = clearBtn
        results.addView(column(Gravity.START,
            text(R.string.search_no_matches, BODY, color = HomeStyle.WHITE),
            clearBtn.marginTop(14),
        ).apply { setPadding(dp(2), dp(16), dp(18), dp(16)) })
    }

    private fun showFailed(message: String, retry: () -> Unit): View {
        val card = failed(message, retry)
        val btn = card.getChildAt(1)?.apply {
            setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        resultsAction = btn
        results.addView(card)
        return card
    }

    private fun search(moveFocus: Boolean, previous: Failure? = null) {
        val query = input.text.toString().trim()
        val token = ++request
        userMovedFocus = false
        searchTasks.forEach { it.cancel(true) }
        searchTasks.clear()
        cards.forEach { it.unload() }
        firstResult = null
        resultsAction = null
        cards.clear()
        rendered = null
        resultsScroll.scrollTo(0, 0)
        showLoading()
        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(authKey.orEmpty()).orEmpty().ifEmpty { Stremio.addons(authKey.orEmpty()) }
                val catalogs = addons.flatMap { it.catalogs }
                    .filter { it.type == "movie" || it.type == "series" }
                // Popular or trending first, then any other; one that fails or comes back empty gives way to the next.
                var lastError: Throwable? = null
                val found = catalogs.sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                    .take(4).asSequence()
                    .map { c ->
                        runCatching { c.addon.savedCatalog(c)?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c) }
                            .onFailure { lastError = it }
                            .getOrDefault(emptyList())
                            .filterNot { isAddonMessage(it.name) }.take(PER_TYPE)
                    }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
                if (found.isEmpty() && lastError != null) throw lastError!!
                found
            }) { answer ->
                if (token != request) return@background
                val error = answer.exceptionOrNull()
                if (error != null) FailureLog.record(FailureLog.Operation.SEARCH, failureOf(error))
                val titles = answer.getOrNull().orEmpty()
                if (titles.isEmpty()) {
                    results.removeAllViews()
                    val msg = error?.let { failureMessage(R.string.search_popular_unavailable, failureOf(it)) }
                        ?: getString(R.string.search_popular_unavailable)
                    showFailed(msg) { search(false) }
                } else render(linkedMapOf(getString(R.string.search_popular_titles) to titles), "", false)
            }
            return
        }
        background({ Stremio.savedAddons(authKey.orEmpty()) ?: Stremio.addons(authKey.orEmpty()) }) { loaded ->
            if (token != request) return@background
            val addons = loaded.getOrElse {
                val failure = failureOf(it)
                FailureLog.record(FailureLog.Operation.SEARCH, failure)
                results.removeAllViews()
                showFailed(failureMessage(R.string.search_unavailable, failure, source = "Stremio")) { search(true, failure) }
                return@background
            }
            val answers = arrayOfNulls<List<Meta>>(addons.size)
            var completed = 0
            val failures = arrayOfNulls<Failure>(addons.size)
            var finished = false
            fun update(final: Boolean = false) {
                if (token != request || finished) return
                val found = searchAnswersInAddonOrder(answers)
                    .filter { it.type == "movie" || it.type == "series" }
                    .filterNot { isAddonMessage(it.name) }
                    .distinctBy { it.type to it.id }
                val ranked = rankSearchTitles(found, query, rating = { it.rating?.toDoubleOrNull() }) { it.name }
                val sections = linkedMapOf(getString(R.string.tab_movies) to ranked.filter { it.type == "movie" }.take(PER_TYPE),
                    getString(R.string.tab_shows) to ranked.filter { it.type == "series" }.take(PER_TYPE)).filterValues { it.isNotEmpty() }
                val settled = final || completed == addons.size
                if (sections.isNotEmpty()) render(sections, query, moveFocus && settled && !userMovedFocus)
                if (!settled) return
                val failure = searchFailure(failures.filterNotNull(), completed, addons.size)
                if (sections.isEmpty()) results.removeAllViews()
                if (failure != null) {
                    FailureLog.record(FailureLog.Operation.SEARCH, failure)
                    val message = if (sections.isEmpty() && answers.all { it == null }) R.string.search_unavailable else R.string.search_incomplete
                    showFailed(failureMessage(message, failure)) { search(true, failure) }
                    if (moveFocus && !userMovedFocus && sections.isEmpty()) resultsAction?.requestFocus()
                } else {
                    previous?.let { FailureLog.record(FailureLog.Operation.SEARCH, it, recovered = true) }
                    if (sections.isEmpty()) showEmpty()
                }
            }
            if (addons.isEmpty()) { update(true); return@background }
            main.postDelayed({ if (!isDestroyed && token == request) { update(true); finished = true } }, SEARCH_WAIT)
            addons.forEachIndexed { index, addon ->
                searchTasks += searchPool.submit {
                    val answer = runCatching { addon.searchCatalogs(query) }
                    main.post {
                        if (isDestroyed || token != request || finished) return@post
                        completed++
                        answer.fold({ (titles, error) ->
                            answers[index] = titles
                            failures[index] = error?.let(::failureOf)
                        }, { failures[index] = failureOf(it) })
                        update()
                        if (completed == addons.size) finished = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(restoreHeader)
        runCatching { recognizer?.destroy() }
        recognizer = null
        searchTasks.forEach { it.cancel(true) }
        cards.forEach { it.unload() }
        super.onDestroy()
    }

    private fun render(sections: Map<String, List<Meta>>, query: String, moveFocus: Boolean) {
        val selected = (currentFocus?.tag as? String)?.takeIf { resultsScroll.hasFocus() }
        // Each add-on's answer calls this. Rebuilding cards that didn't change cleared their posters (blank tiles while
        // they loaded again) and could move the remote; the same rows stay as they are.
        // ponytail: any change still rebuilds the whole pane; diff rows in place if late add-ons reorder a lot.
        val key = searchRenderKey(sections.mapValues { (_, list) -> list.map { "${it.type}:${it.id}:${it.poster}:${it.background}:${it.name}" } }, query)
        if (key == rendered) {
            if (selected == null && moveFocus) firstResult?.requestFocus()
            return
        }
        rendered = key
        val posters = mutableListOf<() -> Unit>()
        cards.forEach { it.unload() }
        cards.clear()
        firstResult = null
        resultsAction = null
        results.removeAllViews()
        sections.forEach { (label, list) ->
            val group = column(Gravity.START)
            // Popular has no title of its own: the header above already says "Popular Titles".
            if (query.isNotEmpty()) group.addView(text(HomeStyle.titleCase(label), HomeStyle.ROW_TITLE, bold = true, color = HomeStyle.WHITE),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            val caption = text("", HomeStyle.INFO, bold = true, color = HomeStyle.WHITE).apply {
                visibility = View.INVISIBLE
                maxWidth = dp(280)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            val rail = HorizontalCardRail(
                screen = this,
                cardGapPx = dp(CARD_GAP_RAIL),
                paddingStartPx = 0,
                paddingTopPx = dp(2),
                paddingEndPx = dp(PAD),
                paddingBottomPx = dp(8),
                rightFadePx = dp(30),
                stopAtStart = false,
                cullLeft = true,
            ).apply {
                onScroll = {
                    cards.getOrNull(this.selected)?.takeIf { it.hasFocus() }?.let { card ->
                        caption.translationX = (card.left - scroller.scrollX).toFloat()
                            .coerceIn(0f, (scroller.width - dp(PAD) - caption.width).coerceAtLeast(0).toFloat())
                    }
                }
            }
            list.forEachIndexed { index, meta ->
                rail.appendCard(card(meta, caption, rail, index, index == 0, posters))
            }
            group.addView(rail.scroller, LinearLayout.LayoutParams(-1, dp(HomeStyle.CARD_H + 10)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }
        // The first [Images.THREADS] requests start at once on idle threads; the rest wait in a newest-first queue. So the
        // first cards (the ones on screen) go first, then the rest last card first, which puts the next on-screen card
        // at the front of the queue.
        (posters.take(Images.THREADS) + posters.drop(Images.THREADS).asReversed()).forEach { it() }
        val target = if (selected != null) cards.firstOrNull { it.tag == selected } ?: firstResult else if (moveFocus) firstResult else null
        target?.post { target.requestFocus() }
    }

    private fun card(meta: Meta, caption: TextView, rail: HorizontalCardRail, index: Int, firstInRow: Boolean, posters: MutableList<() -> Unit>): MediaCardView {
        // Home's poster card: its size and small radius, a white border while focused, no growth and no dimming.
        val card = MediaCardView(this, meta.name, meta.poster, meta.background, widthPx = dp(HomeStyle.CARD_W), heightPx = dp(HomeStyle.CARD_H),
            dimAlpha = 0f, fadeIn = false, retryOnFailure = true, home = true).apply {
            tag = "${meta.type}:${meta.id}"
            setOnFocusChanged { has ->
                if (has) {
                    (rail.scroller.parent as? View)?.let { group ->
                        resultsScroll.smoothScrollTo(0, group.top)
                    }
                    caption.text = meta.name + (meta.year?.let { "$DOT${it.take(4)}" } ?: "")
                    caption.visibility = View.VISIBLE
                    rail.scrollToCard(index)
                    caption.post { rail.onScroll?.invoke() }
                } else caption.visibility = View.INVISIBLE
            }
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) remember(query)
                startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                    .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                    .putExtra("poster", meta.poster).putExtra("background", meta.background))
            }
            if (firstInRow) setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        cards += card
        if (firstResult == null) firstResult = card
        posters += { card.load() }
        return card
    }

    private companion object {
        const val RECENT = 4
        const val RESULTS_X = 298 // dp: the header and the rows start here, right of the keyboard
        const val KEYS_X = 40
        const val KEY_W = 33
        const val KEY_H = 32
        const val KEYS_W = KEY_W * 6
        const val PER_TYPE = 12
        const val TYPING_PAUSE = 500L
        const val SEARCH_WAIT = 6000L
        const val MIC_REQUEST = 102
        const val MISSED_SHOWN = 2000L
        const val VOICE_TAG = "ChadFlixVoice"
        const val KATNISS = "com.google.android.katniss"
        // Insufficient permissions, server, client, server disconnected (API 31): the binding itself failed.
        val RETRY_ERRORS = setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT, 11)
        val searchPool = Images.newestFirst(8)
    }
}
