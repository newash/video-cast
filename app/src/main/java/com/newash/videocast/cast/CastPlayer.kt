package com.newash.videocast.cast

import android.content.Context
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient

/**
 * Thin wrapper around the Cast SDK: session tracking, loading media with an
 * optional VTT text track, and transport controls. All calls must happen on
 * the main thread (a Cast SDK requirement). Connection state is read from
 * [progress] polling; the session listener exists only for the terminal
 * end-of-session signal.
 */
class CastPlayer(
    context: Context,
    /**
     * Fired on every terminal session outcome — ended, or a failed start/resume
     * (after which the SDK never calls onSessionEnded). Not on transient suspensions.
     */
    private val onSessionTerminated: () -> Unit,
) {

    data class Progress(
        val connected: Boolean = false,
        val hasMedia: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    )

    private val castContext: CastContext = CastContext.getSharedInstance(context.applicationContext)

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionTerminated()
        override fun onSessionStartFailed(session: CastSession, error: Int) = onSessionTerminated()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionTerminated()
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
    }

    init {
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Detach from the process-wide CastContext (the ViewModel is being destroyed). */
    fun release() =
        castContext.sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)

    private val remote: RemoteMediaClient?
        get() = castContext.sessionManager.currentCastSession?.remoteMediaClient

    fun load(videoUrl: String, mime: String, title: String, subtitleUrl: String?, subtitleLanguage: String) {
        val client = checkNotNull(remote) { "No Chromecast session" }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val tracks = listOfNotNull(
            subtitleUrl?.let {
                MediaTrack.Builder(SUBTITLE_TRACK_ID, MediaTrack.TYPE_TEXT)
                    .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                    .setContentId(it)
                    .setContentType("text/vtt")
                    .setName("Subtitles")
                    .setLanguage(subtitleLanguage)
                    .build()
            }
        )
        val mediaInfo = MediaInfo.Builder(videoUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mime)
            .setMetadata(metadata)
            .setMediaTracks(tracks)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            // Activating the track in the load request is far more reliable
            // than toggling it afterwards on the default receiver.
            .setActiveTrackIds(if (subtitleUrl != null) longArrayOf(SUBTITLE_TRACK_ID) else longArrayOf())
            .build()
        client.load(request)
    }

    fun togglePlayPause() {
        remote?.togglePlayback()
    }

    fun seekBy(deltaMs: Long) {
        val client = remote ?: return
        // The receiver clamps the upper bound itself (streamDuration may be unknown).
        seekTo((client.approximateStreamPosition + deltaMs).coerceAtLeast(0))
    }

    fun seekTo(positionMs: Long) {
        remote?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    fun stop() {
        remote?.stop()
    }

    fun progress(): Progress {
        val client = remote
        return Progress(
            connected = castContext.sessionManager.currentCastSession?.isConnected == true,
            hasMedia = client?.hasMediaSession() == true,
            playing = client?.isPlaying == true,
            positionMs = client?.approximateStreamPosition ?: 0,
            durationMs = client?.streamDuration ?: 0,
        )
    }

    private companion object {
        const val SUBTITLE_TRACK_ID = 1L
    }
}
