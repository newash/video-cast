package com.newash.videocast

import android.Manifest
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

    private var searchDialog: SearchDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        CastButtonFactory.setUpMediaRouteButton(this, view<MediaRouteButton>(R.id.cast_button))
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        onClick(R.id.pick_video) { pickVideo.launch(arrayOf("video/*")) }
        onClick(R.id.pick_subtitle) { pickSubtitle.launch(arrayOf("*/*")) }
        onClick(R.id.search_subtitles, vm::openSearch)
        onClick(R.id.clear_subtitle, vm::clearSubtitle)
        onClick(R.id.cast, vm::startCasting)
        onClick(R.id.play_pause, vm::togglePlayPause)
        onClick(R.id.rewind) { vm.seekBy(-10_000) }
        onClick(R.id.forward) { vm.seekBy(30_000) }
        onClick(R.id.stop, vm::stopCasting)
        view<SeekBar>(R.id.seek).onSeekReleased { fraction -> vm.seekToFraction(fraction) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect(::render) }
        }
    }

    private fun render(state: UiState) = with(state) {
        view<TextView>(R.id.video_name).text =
            video?.let { "${it.name} (${it.sizeBytes.toHumanSize()})" } ?: getString(R.string.no_video)
        view<TextView>(R.id.subtitle_name).text = subtitle?.name ?: getString(R.string.no_subtitles)
        view<View>(R.id.clear_subtitle).isVisible = subtitle != null
        view<Button>(R.id.search_subtitles).isEnabled = video != null
        view<Button>(R.id.cast).run {
            isEnabled = video != null && cast.connected
            text = when {
                !cast.connected -> getString(R.string.cast_no_session)
                video == null -> getString(R.string.cast_no_video)
                else -> getString(R.string.cast)
            }
        }
        view<View>(R.id.controls).isVisible = cast.hasMedia
        view<Button>(R.id.play_pause).text = if (cast.playing) "⏸" else "▶"
        view<TextView>(R.id.position).text = cast.positionMs.toTimeString()
        view<TextView>(R.id.duration).text = cast.durationMs.toTimeString()
        view<SeekBar>(R.id.seek).takeUnless(SeekBar::isPressed)?.progress =
            if (cast.durationMs > 0) (cast.positionMs * 1000 / cast.durationMs).toInt() else 0
        view<TextView>(R.id.status).run {
            isVisible = status != null
            text = status.orEmpty()
        }
        syncSearchDialog(search)
    }

    private fun syncSearchDialog(search: SearchState?) {
        when {
            search == null -> {
                searchDialog?.dismissSilently()
                searchDialog = null
            }
            searchDialog == null -> searchDialog = SearchDialog(
                activity = this,
                defaultQuery = vm.state.value.defaultQuery,
                onSearch = vm::searchSubtitles,
                onPick = vm::downloadSubtitle,
                onDismiss = vm::closeSearch,
            ).also { it.update(search) }
            else -> searchDialog?.update(search)
        }
    }

    private fun <T : View> view(id: Int): T = findViewById(id)

    private fun onClick(id: Int, action: () -> Unit) =
        view<View>(id).setOnClickListener { action() }
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
    private val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1)
    private var results: List<OpenSubtitlesClient.Result> = emptyList()

    private val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle(R.string.opensubtitles)
        .setView(content)
        .setNegativeButton(R.string.close, null)
        .setOnDismissListener { onDismiss() }
        .show()

    init {
        content.findViewById<EditText>(R.id.query).setText(defaultQuery)
        content.findViewById<Button>(R.id.search).setOnClickListener {
            onSearch(
                content.findViewById<EditText>(R.id.query).text.toString(),
                content.findViewById<EditText>(R.id.languages).text.toString().ifBlank { "en" },
            )
        }
        content.findViewById<ListView>(R.id.results).run {
            adapter = this@SearchDialog.adapter
            setOnItemClickListener { _, _, position, _ -> results.getOrNull(position)?.let(onPick) }
        }
    }

    fun update(search: SearchState) {
        results = search.results
        content.findViewById<ProgressBar>(R.id.progress).isVisible = search.searching
        content.findViewById<Button>(R.id.search).isEnabled = !search.searching
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
