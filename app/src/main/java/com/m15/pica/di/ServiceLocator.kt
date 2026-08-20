package com.m15.pica.di

import android.content.Context
import android.media.AudioManager
import com.m15.pica.data.db.AppDatabase
import com.m15.pica.data.repo.ConversationRepository

object ServiceLocator {
    private var initialized = false

    lateinit var repo: ConversationRepository
    lateinit var audioManager: AudioManager
    lateinit var appContext: Context

    fun init(ctx: Context) {
        if (initialized) return

        appContext = ctx.applicationContext
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val db = AppDatabase.get(appContext)
        repo = ConversationRepository(db)

        // No token client: Pica POSTs its SDP offer straight to the selected
        // agent's full offer endpoint (…/api/offer) — the SmallWebRTC
        // transport sends to that URL verbatim and does NOT append a path.
        // No on-device capture: Pipecat's SmallWebRTC transport owns the mic.

        initialized = true
    }
}
