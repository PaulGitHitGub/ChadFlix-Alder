package dev.alder

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
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

/**
 * ChadFlix search: the catalogue under the focused title's hero ([ExploreScreen]); typing, the keys under Voice at the
 * left and Alder's Movies and Shows rows of home posters on the right.
 */
class SearchActivity : ExploreScreen() {
    private lateinit var input: EditText // the header's words: the typed query, or the catalogue's name as a hint
    private lateinit var recentBox: LinearLayout
    override lateinit var results: LinearLayout
    override lateinit var resultsScroll: ScrollView
    private lateinit var keyboard: LinearLayout
    private lateinit var headerBar: View
    private lateinit var nav: LinearLayout
    private lateinit var avatar: View
    private lateinit var tabSlot: View // the magnifier tab's room in the top bar, under the capsule
    private lateinit var capsule: LinearLayout // Voice and Keyboard in the top bar ([attachCapsule])
    private lateinit var topPill: SearchPill // draws and moves the top bar's pill ([attachCapsule])
    private var arrived = false // the pill has opened from Home's (or the magnifier)
    private var pageViews = emptyList<View>() // the page under the top bar, faded in as the pill lands
    private var voice: View? = null
    private var shownAt: Long? = null // when the page first had the remote (uptime ms)
    private var styleVoice: () -> Unit = {}
    private var recognizer: SpeechRecognizer? = null // ChadFlix: in-app listening, no Google overlay
    private var recognizerService: ComponentName? = null // null: the TV's default speech service
    private var listening = false
    private var heardSpeech = false // the service got as far as ready-for-speech this session
    private var beforeVoice: String? = null // the query as it was when listening began, while the header shows voice's words
    private var quiet = false // the header's words are changing for voice: don't search them
    private val restoreHeader = Runnable { restoreQuery() }
    private var searched: String? = null // the query the results on screen are for
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

    private lateinit var keyboardButton: LinearLayout
    // The controls row, the heading and the results, which move with [arrange].
    private lateinit var resultsColumn: LinearLayout
    private lateinit var headingRow: View
    private lateinit var controlsRow: View // the filters and Sort, between the hero and the posters
    private var arranged = false
    private val rearranged = Runnable { if (lookupMode == null && input.text.isBlank() && gridOutOfDate()) search(false) }
    private var lookupMode: String? = null
    private var savedTitleQuery = ""
    private var enteringLookup = false

    override fun refreshCatalogue() {
        if (lookupMode == null) {
            cancelVoice()
            setHeader("")
            main.removeCallbacks(typed)
            search(false)
        }
    }

    override fun lookup(facet: String?, restoreTitle: Boolean) {
        cancelVoice()
        if (lookupMode == null) savedTitleQuery = input.text.toString()
        enteringLookup = facet != null
        lookupMode = facet
        setHeader(if (facet == null && restoreTitle) savedTitleQuery else "")
        main.removeCallbacks(typed)
        toggleKeys(facet != null)
        search(false)
    }

    /** Opens or closes the keys, putting the remote on [to] if given. */
    private fun toggleKeys(open: Boolean, to: View? = null) {
        keyboard.visibility = if (open) View.VISIBLE else View.GONE
        recentBox.visibility = if (open && lookupMode == null) View.VISIBLE else View.GONE
        // Down from Voice goes by sight (the controls row below) unless the keys are open under it.
        voice?.nextFocusDownId = if (open) firstKey?.id ?: View.NO_ID else View.NO_ID
        lastKeyboardFocus = if (open) firstKey else keyboardButton
        updateHeading()
        // Closing the keys: back to Directors & Cast after the people picker, else (typed words, no bar) Voice Search.
        (to ?: if (open) firstKey else peopleButton.takeIf { it.isShown } ?: voice ?: keyboardButton)?.requestFocus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("PillLag", "Search onCreate start ${SystemClock.uptimeMillis()}") // ponytail: remove once measured
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(HomeStyle.BLACK); clipChildren = false }
        headerBar = header()
        root.addView(headerBar, FrameLayout.LayoutParams(-1, dp(76)))
        styleNav()

        // The heading over the posters' left edge: the catalogue's name, or what's typed.
        input = EditText(this).apply {
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
            setPadding(0, 0, 0, 0)
            setSelectAllOnFocus(false)
            // No box: the header's words are underlined while the field has the remote.
            setOnFocusChangeListener { _, has ->
                paintFlags = if (has) paintFlags or Paint.UNDERLINE_TEXT_FLAG else paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
                invalidate()
                if (!has) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
            }
        }
        val sort = buildSortControl()

        keyboard = column(Gravity.START)
        buildKeyboard()
        keyboardButton = sideAction(R.drawable.ic_search, getString(R.string.ex_keyboard)) { toggleKeys(true) }
        attachCapsule()
        recentBox = column(Gravity.START).apply { clipChildren = false; clipToPadding = false }
        results = column(Gravity.START).apply { setPadding(0, 0, 0, dp(32)) }
        resultsScroll = object : ScrollView(this) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0
        }.apply {
            isVerticalScrollBarEnabled = false
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
            addView(results)
        }
        arrangePage(root, sort, dp((width - RESULTS_X).toInt()))
        upFromControls((voice ?: keyboardButton).id) // Up from the controls row: Voice, straight up in the top bar
        if (animationsOn()) pageViews.forEach { it.alpha = 0f } // shown as the top bar's pill lands ([attachCapsule])
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(HomeStyle.BLACK))
        setContentView(root)
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            focusMoved(now)
            userMovedFocus = true
            if (lookupMode != null && results.hasFocus()) currentFocus?.let { focused ->
                val area = Rect()
                focused.getDrawingRect(area)
                results.offsetDescendantRectToMyCoords(focused,area)
                if (area.top < resultsScroll.scrollY) resultsScroll.scrollTo(0,area.top)
                else if (area.bottom > resultsScroll.scrollY+resultsScroll.height)
                    resultsScroll.scrollTo(0,area.bottom-resultsScroll.height)
            }
            styleNav()
            lastKey?.let { key ->
                if (!key.hasFocus()) updateKeyStyle(key, false)
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!quiet) {
                    // Real words (typing, a recent chip, Delete): voice gives way, so a late result
                    // or the "Didn't catch that" restore never overwrites them.
                    if (listening) {
                        Log.i(VOICE_TAG, "cancel: typed")
                        runCatching { recognizer?.cancel() }
                        listening = false
                        styleVoice()
                    }
                    main.removeCallbacks(restoreHeader)
                    beforeVoice = null
                }
                updateHeading()
                if (quiet) return
                invalidateCatalogue()
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
        updateHeading()
        toggleKeys(false)
        // The remote starts on Voice, in the top bar where the Search tab was.
        (voice ?: keyboardButton).let { start -> start.post { start.requestFocus() } }
        // The first frame is only the top bar and its pill (the page is hidden under it): the catalogue and the recent
        // searches start once it's drawn, so the pill moves as soon as the page opens.
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                root.post { if (!isDestroyed) { showRecent(); if (searched == null) search(false) } }
                return true
            }
        })
        Log.d("PillLag", "Search onCreate end ${SystemClock.uptimeMillis()}")
    }

    /**
     * The page: the hero across the top under the top bar (Voice and Keyboard are in it: [attachCapsule]), the controls row under it
     * and the posters under that, which scroll while the hero and the row stay put. While typing (the keys open, words
     * searched, the people picker) the keys go under Voice and the heading and results right of them ([arrange]); the
     * views here start in that arrangement.
     */
    private fun arrangePage(root: FrameLayout, sort: View, resultsW: Int) {
        root.addView(keyboard, FrameLayout.LayoutParams(dp(KEYS_W), -2).apply { leftMargin = dp(KEYS_X); topMargin = dp(SearchLayout.KEYS_TOP) })
        root.addView(recentBox, FrameLayout.LayoutParams(dp(KEYS_W), -2).apply {
            leftMargin = dp(KEYS_X); topMargin = dp(SearchLayout.KEYS_TOP + 7 * KEY_H + 8)
        })
        headingRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        }
        controlsRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(buildFilterBar(), LinearLayout.LayoutParams(0, dp(ACTION_H), 1f))
            addView(sort, LinearLayout.LayoutParams(-2, dp(ACTION_H)).apply { marginStart = dp(10) })
        }
        resultsColumn = column(Gravity.START).apply {
            addView(controlsRow, LinearLayout.LayoutParams(-1, dp(ACTION_H)).apply {
                marginStart = dp(2); marginEnd = dp(SearchLayout.SIDE); bottomMargin = dp(10)
            })
            addView(headingRow, LinearLayout.LayoutParams(-1, dp(QUERY_H)).apply { marginEnd = dp(KEYS_X) })
            addView(resultsScroll, LinearLayout.LayoutParams(resultsW, 0, 1f))
        }
        val hero = buildHero()
        root.addView(hero, 0, FrameLayout.LayoutParams(-1, dp(SearchLayout.HERO_H)))
        root.addView(resultsColumn, FrameLayout.LayoutParams(resultsW, -1).apply { leftMargin = dp(RESULTS_X); topMargin = dp(SearchLayout.ROW_TOP) })
        pageViews = listOf(hero, resultsColumn)
    }

    /**
     * The top bar's Search tab, on this page one white capsule holding Keyboard | Voice (owner's placement B). Its
     * right edge stays where the magnifier's ended, so Movies, Shows and the rest don't move; [tabSlot] keeps the
     * magnifier's room in the bar. Focus is a black pill inside it. Voice has the remote on arrival, Up from the
     * controls row comes back to it, Right from Voice is the first page's name, Left from Keyboard the avatar.
     * The capsule's views are invisible focus targets: [SearchPill], behind the names, draws the pills and Keyboard |
     * Voice and moves them (the designer's "Slingshot"), starting from Home's pill ([HOME_PILL]) or the magnifier.
     */
    private fun attachCapsule() {
        capsule = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
            alpha = 0f // drawn by the pill
            // Owner: Keyboard | Voice, Voice next to the pages' names (where the pill arrives from them).
            addView(keyboardButton, LinearLayout.LayoutParams(-2, dp(CAPSULE_H - 4)))
            voice?.let { addView(it, LinearLayout.LayoutParams(-2, dp(CAPSULE_H - 4))) }
        }
        topPill = SearchPill(this, headerBar, capsule, voice as? LinearLayout, keyboardButton, tabSlot, avatar.parent as View) {
            (1 until nav.childCount).mapNotNull { nav.getChildAt(it) as? TextView }
        }
        val bar = headerBar as FrameLayout
        bar.addView(topPill, 0, FrameLayout.LayoutParams(-1, -1))
        // Never over the avatar (many pages' names, or "Listening…"): then it grows right over the magnifier's room.
        val place = {
            capsule.x = maxOf(nav.left + tabSlot.right - capsule.width, (avatar.parent as View).right + dp(8)).toFloat()
            topPill.relayout()
            // The first time everything has its place, in this same layout pass, so the first frame drawn already has the
            // pill where Home's was (or on the magnifier): it opens from there.
            if (!arrived && capsule.width > 0) {
                arrived = true
                // Home may have started the move on its own bar the moment the remote landed: carry on from there.
                val gone = intent.getLongExtra(PILL_PLAYED, 0L).coerceAtLeast(0L)
                topPill.arrive(intent.getIntArrayExtra(HOME_PILL), currentFocus?.takeIf { it === voice || it === keyboardButton }, gone)
                // The page (hidden since onCreate) comes in as the pill lands; the old one is gone at once.
                pageViews.forEach { it.animate().alpha(1f).setStartDelay((PAGE_IN_AT - gone).coerceAtLeast(0)).setDuration(PAGE_IN_MS).setInterpolator(android.view.animation.LinearInterpolator()).start() }
            }
        }
        nav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        capsule.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() } // "Listening…" widens Voice
        bar.addView(capsule, FrameLayout.LayoutParams(-2, dp(CAPSULE_H), Gravity.START or Gravity.CENTER_VERTICAL))
        keyboardButton.nextFocusLeftId = avatar.id
        voice?.nextFocusLeftId = keyboardButton.id
        voice?.let { keyboardButton.nextFocusRightId = it.id }
        linkCapsule()
    }

    /**
     * Right from the capsule's right end (Voice) to the first page's name, Left from that name back to it (the names are
     * rebuilt as the add-ons' types arrive).
     */
    private fun linkCapsule() {
        if (!::capsule.isInitialized) return
        val first = nav.getChildAt(1) ?: return
        (voice ?: keyboardButton).nextFocusRightId = first.id
        first.nextFocusLeftId = (voice ?: keyboardButton).id
    }

    /**
     * The catalogue's arrangement ([w]: the keys closed: hero, controls row, posters across the page) or the typing one
     * (keys at the left, the heading and results right of them). A catalogue built for the other one is built again
     * for this one's width.
     */
    private fun arrange(w: Boolean) {
        if (!::resultsColumn.isInitialized || (arranged && w == wide)) return
        arranged = true
        wide = w
        val screenW = resources.displayMetrics.widthPixels
        val x = if (w) dp(SearchLayout.SIDE - 2) else dp(RESULTS_X) // the posters' first column keeps 2 dp at its left for its ring
        (resultsColumn.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = x; topMargin = dp(if (w) SearchLayout.CONTROLS_TOP else SearchLayout.ROW_TOP); width = screenW - x
        }
        (resultsScroll.layoutParams as LinearLayout.LayoutParams).width = screenW - x
        headingRow.visibility = if (w) View.GONE else View.VISIBLE
        controlsRow.visibility = if (w) View.VISIBLE else View.GONE
        resultsColumn.requestLayout()
        main.removeCallbacks(rearranged)
        main.post(rearranged)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && shownAt == null) shownAt = SystemClock.uptimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (::nav.isInitialized) updateNavTabs()
    }

    override fun onPause() {
        cancelVoice()
        pendingTab?.let(main::removeCallbacks) // ChadFlix: a tab rested on, then the page left: nothing opens behind
        pendingTab = null
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
        avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", SMALL, bold = true, color = HomeStyle.WHITE).apply {
            id = View.generateViewId()
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
        // The magnifier tab's room, kept so the pages' names sit where they do on Home; the capsule covers it.
        tabSlot = View(this)
        nav.addView(tabSlot, LinearLayout.LayoutParams(dp(44), -1))
        // Last time's types first, so the first layout has the same names (and capsule place) as Home's bar expects.
        catalogTypes = searchTabTypes(this)
        updateNavTabs()
        background({ Stremio.savedAddons(authKey.orEmpty()).orEmpty().flatMap { a -> a.catalogs.map { it.type } } }) { r ->
            r.getOrNull()?.takeIf { it.isNotEmpty() }?.let { catalogTypes = searchTabTypes(this, save = it); updateNavTabs() }
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

    /** The top bar's pill follows the remote ([SearchPill]): the capsule, a page's name, or faint for the avatar and gear. */
    private fun styleNav() {
        if (::topPill.isInitialized) topPill.focusOn(currentFocus)
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
            "services" to (HomeStyle.SERVICES to getString(R.string.tab_services)),
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
                this.id = View.generateViewId() // for the capsule's Right ([linkCapsule])
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(5), dp(16), dp(5))
                isFocusable = true
                val open = Runnable {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                    // The pill is already on the page's name: Home takes over under it with no screen change.
                    @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                }
                // The white pill under the name and its colour are [SearchPill]'s.
                setOnFocusChangeListener { _, has ->
                    // ChadFlix: as on Home, resting on a page's name opens it.
                    pendingTab?.let(main::removeCallbacks)
                    pendingTab = if (has) Runnable { if (hasWindowFocus()) open.run() }.also { main.postDelayed(it, HomeStyle.NAV_WAIT) } else null
                }
                setOnClickListener { pendingTab?.let(main::removeCallbacks); open.run() }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        if (focusedId != null) nav.getChildAt(ids.indexOf(focusedId) + 1).requestFocus()
        linkCapsule()
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
        delete.setOnLongClickListener { clearQuery(); true } // hold Delete: the whole query

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            val view = sideAction(R.drawable.ic_mic, getString(R.string.search_voice), focused = { v -> lastKeyboardFocus = v }) {
                // OK while listening: stop and search what was heard so far.
                if (listening) return@sideAction stopVoice()
                // ChadFlix: ask for the microphone once; the speech service checks the asking app holds it.
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
                else requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            }
            val label = view.getChildAt(1) as TextView
            styleVoice = {
                label.text = getString(if (listening) R.string.search_listening else R.string.search_voice)
                if (::topPill.isInitialized) { topPill.listening = listening; topPill.invalidate() }
            }
            voice = view
        }
        rows.forEachIndexed { r, keys ->
            keys.forEachIndexed { c, v ->
                v.nextFocusUpId = if (r > 0) rows[r - 1][c].id else voice?.id ?: input.id
                v.nextFocusDownId = if (r < rows.lastIndex) rows[r + 1][c].id else (if (c < 3) space else delete).id
            }
        }
        space.nextFocusUpId = rows.last()[1].id
        delete.nextFocusUpId = rows.last()[4].id
        voice?.nextFocusDownId = rows[0][0].id
    }

    /**
     * Voice and Keyboard: an icon and words in the top bar's capsule. The capsule is invisible: its views are the focus
     * targets and give [SearchPill] the places to draw them.
     */
    private fun sideAction(iconRes: Int, words: String, focused: (View) -> Unit = {}, click: () -> Unit) = LinearLayout(this).apply {
        id = View.generateViewId()
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ACTION_PAD), 0, dp(ACTION_PAD), 0)
        isFocusable = true
        contentDescription = words
        addView(ImageView(this@SearchActivity).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(dp(13), dp(13)))
        addView(text(words, HomeStyle.TAB_SP), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        setOnClickListener { click() }
        setOnFocusChangeListener { v, has -> if (has) focused(v) }
    }

    /**
     * The hero and the controls row show over the catalogue with the keys closed. While typing the heading names the
     * catalogue, so the remote doesn't stop on it.
     */
    private fun updateHeading() {
        // While voice has the header (listening, "Didn't catch that"), the query before it decides: nothing moves
        // until real words come.
        val catalogue = lookupMode == null && (beforeVoice ?: input.text.toString()).isBlank()
        arrange(catalogue && keyboard.visibility != View.VISIBLE)
        showCatalogueControls(wide)
        if (catalogue && input.hasFocus()) (voice ?: firstKey?.takeIf { keyboard.visibility == View.VISIBLE } ?: keyboardButton).requestFocus()
        input.isFocusable = !catalogue
    }

    /** No words (hold Delete, Back on the keys, Clear Search): the catalogue again; the remote stays where it is. */
    private fun clearQuery() {
        cancelVoice()
        input.setText("")
        main.removeCallbacks(typed)
        search(false)
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
        // Listening dropped a pending typed search: run it now so the header and the results agree.
        if (old.trim() != searched) { main.removeCallbacks(typed); main.post(typed) }
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
        main.removeCallbacks(typed) // a pending typed search mustn't run on voice's partial words
        beforeVoice = input.text.toString()
        heardSpeech = false
        listening = true
        styleVoice()
        updateHeading()
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
        // The OK that opened Search (the top bar also opens it on a rest) does nothing here: a press begun before the
        // page had the remote, or in its first moment, would otherwise start Voice Search or open a picker.
        val opened = shownAt
        if (event.keyCode in OK_KEYS && (opened == null || event.downTime < opened + ARRIVAL_GRACE)) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            // Back while listening cancels it and keeps the page.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && listening) { cancelVoice(); return true }
            // Marquee: Back in a picker in the hero's place steps back out of it.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && backFromPicker()) return true
            if (event.keyCode == KeyEvent.KEYCODE_BACK && keyboard.visibility == View.VISIBLE) {
                when {
                    lookupMode != null -> lookup(null)
                    // Back on the keys first clears what's typed, then closes them.
                    input.text.isNotEmpty() && !resultsScroll.hasFocus() -> clearQuery()
                    else -> toggleKeys(false, keyboardButton)
                }
                return true
            }
            // Back with words found (a voice search: the keys are closed): the catalogue again, before Back leaves.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && lookupMode == null && input.text.isNotEmpty()) {
                (voice ?: keyboardButton).requestFocus()
                clearQuery()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && resultsScroll.hasFocus()) {
                (voice ?: keyboardButton).requestFocus()
                return true
            }
            // (The results and the recent chips, shown only with the keys, are handled above.)
            // The top bar's pages and gear, or the heading: Back to Voice (from the capsule itself, Back leaves).
            if (event.keyCode == KeyEvent.KEYCODE_BACK && ((headerBar.hasFocus() && !capsule.hasFocus()) || input.hasFocus())) {
                if ((voice ?: keyboardButton).requestFocus()) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus in rightEdgeKeys && lookupMode == null && input.text.isNotBlank()) {
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
        // Each chip as wide as its words, as many to a line as fit the keyboard's width, at most two lines; only a chip
        // wider than the whole column is cut short.
        val columnW = dp(KEYS_W)
        val gap = dp(5)
        var line: LinearLayout? = null
        var used = 0
        var lines = 0
        values.forEach { query ->
                val chipView = chip(query).apply {
                    textSize = SMALL
                    maxWidth = columnW
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
                val w = minOf(columnW, chipView.paint.measureText(query).toInt() + chipView.paddingLeft + chipView.paddingRight + 1)
                val row = line?.takeIf { used + gap + w <= columnW } ?: run {
                    if (lines == 2) return@forEach
                    lines++
                    used = -gap
                    LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
                        .also { line = it; recentBox.addView(it, LinearLayout.LayoutParams(-1, dp(27))) }
                }
                if (firstChip == null) firstChip = chipView
                row.addView(chipView, LinearLayout.LayoutParams(-2, dp(24)).apply { if (row.childCount > 0) marginStart = gap })
                used += gap + w
        }

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
                // Focus first, onto something showing: after a voice search the keys are hidden.
                (voice ?: firstKey?.takeIf { keyboard.visibility == View.VISIBLE } ?: keyboardButton).requestFocus()
                clearQuery()
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
        searched = query
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
        clearGrid()
        val mode = lookupMode
        input.hint = if (mode != null) getString(R.string.ex_people) else heading
        if (mode != null) {
            findPeople(query,enteringLookup || moveFocus)
            enteringLookup = false
            return
        }
        showLoading()
        if (query.isEmpty()) {
            catalogue()
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
        if (::topPill.isInitialized) topPill.stop() // nothing animates on after the page has gone
        pageViews.forEach { it.animate().cancel() }
        clearGrid()
        main.removeCallbacks(typed)
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
            group.addView(text(HomeStyle.titleCase(label), HomeStyle.ROW_TITLE, bold = true, color = HomeStyle.WHITE),
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
                paddingStartPx = dp(2), // room for the first card's focus border, which sits outside the card
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
            group.addView(rail.scroller, LinearLayout.LayoutParams(-1, posterH + dp(10)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2))
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ROW_GAP) })
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
        val card = MediaCardView(this, meta.name, meta.poster, meta.background, widthPx = posterH * 2 / 3, heightPx = posterH,
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
            if (meta.type == "movie" || meta.type == "series") setOnLongClickListener {
                choose(meta.name, listOf(getString(R.string.save_to_watchlist))) {
                    saveToWatchlist(WatchTitle(meta.id, meta.type, meta.name, meta.poster, meta.background))
                }
                true
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

    /**
     * ChadFlix: search's posters (2:3, home's corners and border), as tall as lets Movies' title, row and caption, then
     * Shows' title and its whole row fit under the search line; never taller than Home's.
     */
    private val posterH by lazy {
        val title = HomeStyle.height(text("", HomeStyle.ROW_TITLE, bold = true)) + dp(2)
        val caption = HomeStyle.height(text("", HomeStyle.INFO, bold = true)) + dp(2)
        // Sized with the earlier, looser gaps (104 dp top, 16 dp between rows) and kept so: the owner liked this size,
        // and the tighter gaps now leave the TV's safe area under the Shows row.
        val room = (resources.displayMetrics.heightPixels - dp(104)) - 2 * title - 2 * dp(10) - caption - dp(16)
        (room / 2).coerceIn(dp(100), dp(HomeStyle.CARD_H))
    }

    private var pendingTab: Runnable? = null // ChadFlix: the page whose name the remote rests on in the top bar

    private companion object {
        const val RECENT = 4
        // dp at 1080p, while typing: the keys 198 wide at 33 from the left edge, the heading and results right of them.
        const val RESULTS_X = 259 // the heading and the rows start here, right of the keys
        const val QUERY_H = 28 // the heading line
        const val ROW_GAP = 12 // dp: a row's caption to the next row's title (was 16)
        const val KEYS_X = 33
        const val ACTION_H = 28 // the controls row's pills
        const val ACTION_PAD = 12 // Voice's and Keyboard's sides inside their focus pill
        const val CAPSULE_H = 30 // the top bar's Keyboard | Voice capsule, as tall as a page's name focused
        const val PAGE_IN_AT = 180L // ms: the page fades in as the pill's left edge settles (the README's page dip)
        const val PAGE_IN_MS = 70L
        const val KEY_W = 33
        const val KEY_H = 32
        const val KEYS_W = KEY_W * 6
        const val PER_TYPE = 12
        const val TYPING_PAUSE = 500L
        const val SEARCH_WAIT = 6000L
        const val MIC_REQUEST = 102
        const val MISSED_SHOWN = 2000L
        const val VOICE_TAG = "ChadFlixVoice"
        const val ARRIVAL_GRACE = 500L // ms after the page first has the remote in which OK is ignored
        val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        const val KATNISS = "com.google.android.katniss"
        // Insufficient permissions, server, client, server disconnected (API 31): the binding itself failed.
        val RETRY_ERRORS = setOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT, 11)
        val searchPool = Images.newestFirst(8)
    }
}
