package com.newash.videocast

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.cast.framework.CastStateListener
import com.google.android.gms.cast.framework.IntroductoryOverlay
import com.newash.videocast.subs.OpenSubtitlesClient
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private val pickVideo = registerForActivityResult(OpenDocument()) { it?.let(vm::onVideoPicked) }
    private val pickSubtitle = registerForActivityResult(OpenDocument()) { it?.let(vm::onSubtitlePicked) }
    private val askNotifications = registerForActivityResult(RequestPermission()) {}

    private val castIcon by lazy { findViewById<MediaRouteButton>(R.id.cast_button) }
    private val stepVideo by lazy { findViewById<TextView>(R.id.step_video) }
    private val videoName by lazy { findViewById<TextView>(R.id.video_name) }
    private val stepSubtitles by lazy { findViewById<TextView>(R.id.step_subtitles) }
    private val subtitleName by lazy { findViewById<TextView>(R.id.subtitle_name) }
    private val clearSubtitle by lazy { findViewById<Button>(R.id.clear_subtitle) }
    private val searchSubtitles by lazy { findViewById<Button>(R.id.search_subtitles) }
    private val stepChromecast by lazy { findViewById<TextView>(R.id.step_chromecast) }
    private val deviceStatus by lazy { findViewById<TextView>(R.id.device_status) }
    private val statusView by lazy { findViewById<TextView>(R.id.status) }
    private val controls by lazy { findViewById<View>(R.id.controls) }
    private val controlsHint by lazy { findViewById<TextView>(R.id.controls_hint) }
    private val nowPlaying by lazy { findViewById<TextView>(R.id.now_playing) }
    private val seek by lazy { findViewById<SeekBar>(R.id.seek) }
    private val time by lazy { findViewById<TextView>(R.id.time) }
    private val volumeBar by lazy { findViewById<SeekBar>(R.id.volume) }
    private val playPause by lazy { findViewById<Button>(R.id.play_pause) }
    private val rewind by lazy { findViewById<Button>(R.id.rewind) }
    private val forward by lazy { findViewById<Button>(R.id.forward) }
    private val playOnTvButton by lazy { findViewById<Button>(R.id.cast) }

    private val errorColor by lazy {
        TypedValue().also { theme.resolveAttribute(androidx.appcompat.R.attr.colorError, it, true) }.data
    }
    private val neutralStatusColors by lazy { statusView.textColors }

    private var searchDialog: SearchDialog? = null
    private var introShown = false
    private val castStateListener = CastStateListener { state ->
        if (state != CastState.NO_DEVICES_AVAILABLE && !introShown) {
            introShown = true
            IntroductoryOverlay.Builder(this, castIcon)
                .setTitleText(getString(R.string.intro_overlay))
                .setOverlayColor(R.color.cast_intro_scrim)
                .setSingleTime()
                .build()
                .show()
        }
    }

    private val sharedCastContext
        get() = runCatching { CastContext.getSharedInstance(this) }.getOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        CastButtonFactory.setUpMediaRouteButton(this, castIcon)
        sharedCastContext?.addCastStateListener(castStateListener)
        findViewById<TextView>(R.id.version_footer).text = BuildConfig.VERSION_NAME
        if (savedInstanceState == null) requestNotificationPermissionOnce()

        findViewById<View>(R.id.pick_video).onClick { pickVideo.launch(arrayOf("video/*")) }
        findViewById<View>(R.id.pick_subtitle).onClick { pickSubtitle.launch(arrayOf("*/*")) }
        findViewById<View>(R.id.stop).onClick(vm::stopCasting)
        searchSubtitles.onClick(vm::openSearch)
        clearSubtitle.onClick(vm::clearSubtitle)
        playOnTvButton.onClick(vm::startCasting)
        playPause.onClick(vm::togglePlayPause)
        rewind.onClick { vm.seekBy(-10_000) }
        forward.onClick { vm.seekBy(30_000) }
        statusView.onClick { vm.state.value.crash?.let(::showCrashDialog) }

        seek.onSeek(
            onPreview = { fraction ->
                val duration = vm.state.value.cast.durationMs
                if (duration > 0) time.text = timeLine((duration * fraction).toLong(), duration)
            },
            onCommit = vm::seekToFraction,
        )
        volumeBar.onSeek(onCommit = vm::setVolume)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect(::render) }
        }
    }

    override fun onDestroy() {
        sharedCastContext?.removeCastStateListener(castStateListener)
        searchDialog?.dismissSilently()
        searchDialog = null
        super.onDestroy()
    }

    /** targetSdk 35 enforces edge-to-edge; keep content clear of bars and notches. */
    private fun applySystemBarInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

    private fun requestNotificationPermissionOnce() {
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun render(state: UiState) = with(state) {
        stepVideo.text = getString(R.string.step_video).withCheck(video != null)
        videoName.text = video?.let { "${it.name} (${it.sizeBytes.toHumanSize()})" }
            ?: getString(R.string.no_video)
        stepSubtitles.text = getString(R.string.step_subtitles).withCheck(subtitle != null)
        subtitleName.text = subtitle?.name ?: getString(R.string.no_subtitles)
        clearSubtitle.isVisible = subtitle != null
        searchSubtitles.isEnabled = video != null
        stepChromecast.text = getString(R.string.step_chromecast).withCheck(cast.connected)
        deviceStatus.text = when {
            cast.connected -> cast.deviceName ?: getString(R.string.connecting)
            cast.connecting -> getString(R.string.connecting)
            else -> getString(R.string.not_connected)
        }

        val hasVideo = video != null
        playOnTvButton.run {
            isEnabled = video != null && video.sizeBytes > 0 && cast.connected && !loading
            text = getString(if (subtitleDirty && hasVideo) R.string.recast_subtitles else R.string.play_on_tv)
        }

        controls.isVisible = cast.hasMedia
        // The receiver keeps its media session across app restarts; with no video
        // picked these controls steer a leftover stream whose server is gone —
        // play/pause/stop still work (receiver-side), but seeking cannot.
        controlsHint.isVisible = !hasVideo
        seek.isEnabled = hasVideo
        rewind.isEnabled = hasVideo
        forward.isEnabled = hasVideo
        nowPlaying.text = listOfNotNull(video?.name, cast.deviceName?.let { "→ $it" }).joinToString("  ")
        playPause.text = if (cast.playing) "❚❚" else "▶"
        if (!seek.isPressed) {
            seek.progress = if (cast.durationMs > 0) (cast.positionMs * 1000 / cast.durationMs).toInt() else 0
            time.text = if (cast.buffering) {
                "${getString(R.string.buffering)} / ${cast.durationMs.toTimeString()}"
            } else {
                timeLine(cast.positionMs, cast.durationMs)
            }
        }
        if (!volumeBar.isPressed) volumeBar.progress = (cast.volume * 100).toInt()

        renderStatus(state)
        syncSearchDialog(search)
    }

    /** One message line, colored by severity; crash notices open the detail dialog. */
    private fun renderStatus(state: UiState) {
        val (text, isError) = when {
            state.crash != null -> getString(R.string.crash_notice) to true
            state.note != null -> state.note.text to true
            state.loading -> getString(R.string.loading_on_tv) to false
            state.cast.hasMedia -> null to false
            !state.cast.connected -> getString(R.string.ready_connect) to false
            state.video == null -> getString(R.string.ready_pick_video) to false
            else -> getString(R.string.ready_to, state.cast.deviceName ?: "Chromecast") to false
        }
        statusView.showMessage(text, isError, errorColor, neutralStatusColors)
    }

    private fun showCrashDialog(stack: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_notice)
            .setMessage(stack.take(4000))
            .setPositiveButton(R.string.close, null)
            .setOnDismissListener { vm.dismissCrash() }
            .show()
    }

    private fun syncSearchDialog(search: SearchState?) {
        if (search == null) {
            searchDialog?.dismissSilently()
            searchDialog = null
            return
        }
        (searchDialog ?: SearchDialog(
            activity = this,
            initial = search,
            errorColor = errorColor,
            onSearch = vm::searchSubtitles,
            onPick = vm::downloadSubtitle,
            onDismiss = vm::closeSearch,
        ).also { searchDialog = it }).update(search)
    }

}

/** The OpenSubtitles search dialog — plain system AlertDialog around dialog_search.xml. */
private class SearchDialog(
    activity: AppCompatActivity,
    initial: SearchState,
    private val errorColor: Int,
    onSearch: (query: String, languages: String) -> Unit,
    onPick: (OpenSubtitlesClient.Result) -> Unit,
    onDismiss: () -> Unit,
) {
    private val content = activity.layoutInflater.inflate(R.layout.dialog_search, null)
    private val query = content.findViewById<EditText>(R.id.query)
    private val languages = content.findViewById<EditText>(R.id.languages)
    private val searchButton = content.findViewById<Button>(R.id.search)
    private val progress = content.findViewById<ProgressBar>(R.id.progress)
    private val message = content.findViewById<TextView>(R.id.dialog_message)
    private val messageDefaultColors = message.textColors
    private val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)

    private var results: List<OpenSubtitlesClient.Result> = emptyList()
    private var rendered: SearchState? = null

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(R.string.opensubtitles)
        .setView(content)
        .setNegativeButton(R.string.close, null)
        .setOnDismissListener { onDismiss() }
        .show()

    init {
        query.setText(initial.query)
        languages.setText(initial.languages)
        val submit = { onSearch(query.text.toString(), languages.text.toString().ifBlank { "en" }) }
        searchButton.setOnClickListener { submit() }
        val onIme = TextView.OnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else {
                false
            }
        }
        query.setOnEditorActionListener(onIme)
        languages.setOnEditorActionListener(onIme)
        content.findViewById<ListView>(R.id.results).run {
            adapter = this@SearchDialog.adapter
            setOnItemClickListener { _, _, position, _ -> results.getOrNull(position)?.let(onPick) }
        }
    }

    fun update(search: SearchState) {
        if (search == rendered) return // ignore the 500 ms cast-progress ticks
        rendered = search
        results = search.results
        progress.isVisible = search.searching
        searchButton.isEnabled = !search.searching
        message.showMessage(search.message, search.messageIsError, errorColor, messageDefaultColors)
        adapter.run {
            clear()
            addAll(results.map { "[${it.language}] ⇩${it.downloads}  ${it.name}" })
        }
    }

    fun dismissSilently() {
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }
}

private fun View.onClick(action: () -> Unit) = setOnClickListener { action() }

private val SeekBar.fraction: Float
    get() = progress / max.coerceAtLeast(1).toFloat()

private fun SeekBar.onSeek(onPreview: (fraction: Float) -> Unit = {}, onCommit: (fraction: Float) -> Unit) =
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onPreview(seekBar.fraction)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) = onCommit(seekBar.fraction)
    })

private fun TextView.showMessage(text: String?, error: Boolean, errorColor: Int, neutral: ColorStateList) {
    isVisible = text != null
    this.text = text.orEmpty()
    if (error) setTextColor(errorColor) else setTextColor(neutral)
}

private fun String.withCheck(done: Boolean): String = if (done) "✓ $this" else this

private fun timeLine(positionMs: Long, durationMs: Long): String =
    "${positionMs.toTimeString(durationMs)} / ${durationMs.toTimeString()}"

/** [precisionRef] keeps "0:05:30 / 1:40:00" aligned instead of "5:30 / 1:40:00". */
private fun Long.toTimeString(precisionRef: Long = this): String = (this / 1000).let { s ->
    if (maxOf(this, precisionRef) >= 3_600_000) {
        String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    } else {
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

private fun Long.toHumanSize(): String = when {
    this >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", toDouble() / (1L shl 30))
    this >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", toDouble() / (1L shl 20))
    this > 0 -> "${this / (1L shl 10)} KB"
    else -> "unknown size"
}
