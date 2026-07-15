package com.newash.videocast

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
import com.newash.videocast.subs.OpenSubtitlesClient
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    private val pickVideo = registerForActivityResult(OpenDocument()) { it?.let(vm::onVideoPicked) }
    private val pickSubtitle = registerForActivityResult(OpenDocument()) { it?.let(vm::onSubtitlePicked) }
    private val askNotifications = registerForActivityResult(RequestPermission()) {}

    private val videoName by lazy { findViewById<TextView>(R.id.video_name) }
    private val subtitleName by lazy { findViewById<TextView>(R.id.subtitle_name) }
    private val clearSubtitle by lazy { findViewById<Button>(R.id.clear_subtitle) }
    private val searchSubtitles by lazy { findViewById<Button>(R.id.search_subtitles) }
    private val castButton by lazy { findViewById<Button>(R.id.cast) }
    private val controls by lazy { findViewById<View>(R.id.controls) }
    private val playPause by lazy { findViewById<Button>(R.id.play_pause) }
    private val time by lazy { findViewById<TextView>(R.id.time) }
    private val seek by lazy { findViewById<SeekBar>(R.id.seek) }
    private val statusView by lazy { findViewById<TextView>(R.id.status) }

    private var searchDialog: SearchDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        CastButtonFactory.setUpMediaRouteButton(this, findViewById<MediaRouteButton>(R.id.cast_button))
        if (savedInstanceState == null) requestNotificationPermissionOnce()

        onClick(R.id.pick_video) { pickVideo.launch(arrayOf("video/*")) }
        onClick(R.id.pick_subtitle) { pickSubtitle.launch(arrayOf("*/*")) }
        onClick(R.id.search_subtitles, vm::openSearch)
        onClick(R.id.clear_subtitle, vm::clearSubtitle)
        onClick(R.id.cast, vm::startCasting)
        onClick(R.id.play_pause, vm::togglePlayPause)
        onClick(R.id.rewind) { vm.seekBy(-10_000) }
        onClick(R.id.forward) { vm.seekBy(30_000) }
        onClick(R.id.stop, vm::stopCasting)
        seek.onSeekReleased(vm::seekToFraction)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect(::render) }
        }
    }

    override fun onDestroy() {
        searchDialog?.dismissSilently()
        searchDialog = null
        super.onDestroy()
    }

    /** targetSdk 35 enforces edge-to-edge; keep content out from under the system bars. */
    private fun applySystemBarInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
        videoName.text = video?.let { "${it.name} (${it.sizeBytes.toHumanSize()})" }
            ?: getString(R.string.no_video)
        subtitleName.text = subtitle?.name ?: getString(R.string.no_subtitles)
        clearSubtitle.isVisible = subtitle != null
        searchSubtitles.isEnabled = video != null
        castButton.run {
            isEnabled = video != null && cast.connected
            text = when {
                !cast.connected -> getString(R.string.cast_no_session)
                video == null -> getString(R.string.cast_no_video)
                else -> getString(R.string.cast)
            }
        }
        controls.isVisible = cast.hasMedia
        playPause.text = if (cast.playing) "⏸" else "▶"
        time.text = "${cast.positionMs.toTimeString()} / ${cast.durationMs.toTimeString()}"
        seek.takeUnless(SeekBar::isPressed)?.progress =
            if (cast.durationMs > 0) (cast.positionMs * 1000 / cast.durationMs).toInt() else 0
        statusView.run {
            isVisible = state.status != null
            text = state.status.orEmpty()
        }
        syncSearchDialog(search)
    }

    private fun syncSearchDialog(search: SearchState?) {
        if (search == null) {
            searchDialog?.dismissSilently()
            searchDialog = null
            return
        }
        (searchDialog ?: SearchDialog(
            activity = this,
            defaultQuery = vm.state.value.defaultQuery,
            onSearch = vm::searchSubtitles,
            onPick = vm::downloadSubtitle,
            onDismiss = vm::closeSearch,
        ).also { searchDialog = it }).update(search)
    }

    private fun onClick(id: Int, action: () -> Unit) =
        findViewById<View>(id).setOnClickListener { action() }
}

/** The OpenSubtitles search dialog — plain system AlertDialog around dialog_search.xml. */
private class SearchDialog(
    activity: AppCompatActivity,
    defaultQuery: String,
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
        query.setText(defaultQuery)
        searchButton.setOnClickListener {
            onSearch(query.text.toString(), languages.text.toString().ifBlank { "en" })
        }
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
        message.isVisible = search.message != null
        message.text = search.message.orEmpty()
        adapter.run {
            clear()
            addAll(search.results.map { "[${it.language}] ${it.name}" })
        }
    }

    fun dismissSilently() {
        dialog.setOnDismissListener(null)
        dialog.dismiss()
    }
}

private fun SeekBar.onSeekReleased(action: (fraction: Float) -> Unit) =
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) =
            action(seekBar.progress / seekBar.max.coerceAtLeast(1).toFloat())
    })

private fun Long.toTimeString(): String = (this / 1000).let { s ->
    if (s >= 3600) {
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
