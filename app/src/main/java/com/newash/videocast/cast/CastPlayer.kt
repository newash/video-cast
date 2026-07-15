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
 * the main thread (a Cast SDK requirement).
 */
class CastPlayer(context: Context, private val onSessionChanged: (connected: Boolean) -> Unit) {

    data class Progress(
        val connected: Boolean = false,
        val hasMedia: Boolean = false,
        val playing: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    )

    private val castContext: CastContext = CastContext.getSharedInstance(context.applicationContext)

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = onSessionChanged(true)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = onSessionChanged(true)
        override fun onSessionEnded(session: CastSession, error: Int) = onSessionChanged(false)
        override fun onSessionSuspended(session: CastSession, reason: Int) = onSessionChanged(false)
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) = onSessionChanged(false)
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onSessionChanged(false)
    }

    init {
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    private val remote: RemoteMediaClient?
        get() = castContext.sessionManager.currentCastSession?.remoteMediaClient

    private val isConnected: Boolean
        get() = castContext.sessionManager.currentCastSession?.isConnected == true

    fun load(videoUrl: String, mime: String, title: String, subtitleUrl: String?, subtitleLanguage: String) {
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
        remote?.load(request)
    }

    fun togglePlayPause() {
        remote?.togglePlayback()
    }

    fun seekBy(deltaMs: Long) = remote?.let {
        seekTo((it.approximateStreamPosition + deltaMs).coerceIn(0, it.streamDuration))
    } ?: Unit

    fun seekTo(positionMs: Long) {
        remote?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    fun stop() {
        remote?.stop()
    }

    fun progress(): Progress = Progress(
        connected = isConnected,
        hasMedia = remote?.hasMediaSession() == true,
        playing = remote?.isPlaying == true,
        positionMs = remote?.approximateStreamPosition ?: 0,
        durationMs = remote?.streamDuration ?: 0,
    )

    private companion object {
        const val SUBTITLE_TRACK_ID = 1L
    }
}
