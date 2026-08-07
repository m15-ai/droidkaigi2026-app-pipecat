package com.m15.pica.data.repo

import com.m15.pica.data.db.AppDatabase
import com.m15.pica.data.db.ChatSession
import com.m15.pica.data.db.MessageItem
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationRepository(private val db: AppDatabase) {
    /** Create a session, stamping a snapshot of the agent it talks to (see [ChatSession]). */
    suspend fun newSession(
        title: String,
        agentId: String,
        agentTitle: String,
        agentAccent: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        db.sessionDao().upsert(
            ChatSession(
                id = id,
                title = title,
                createdAt = System.currentTimeMillis(),
                saved = false,
                agentId = agentId,
                agentTitle = agentTitle,
                agentAccent = agentAccent,
            )
        )
        return id
    }

    /**
     * Insert-or-update a message keyed on the transcript [messageId]. Transcripts arrive
     * incrementally (the same id grows from the first word to the full utterance), so each
     * segment overwrites the prior row, leaving exactly one row per message with the final
     * text. [createdAt] is the first-seen timestamp so ordering stays stable as text grows.
     */
    suspend fun upsertMessage(
        sessionId: String,
        messageId: String,
        role: String,
        text: String,
        createdAt: Long,
    ) {
        db.messageDao().upsert(
            MessageItem(messageId, sessionId, role, text, createdAt)
        )
    }

    // ---- History (saved conversations) --------------------------------------

    /** Drawer feed: only explicitly saved conversations, newest first. */
    val savedSessions: Flow<List<ChatSession>> get() = db.sessionDao().savedSessions()

    /** Transcript for the read-only viewer. */
    fun messages(sid: String): Flow<List<MessageItem>> = db.messageDao().stream(sid)

    suspend fun saveSession(id: String, title: String) = db.sessionDao().markSaved(id, title)

    suspend fun renameSession(id: String, title: String) = db.sessionDao().updateTitle(id, title)

    /** Delete a session and its messages — children first (no FK cascade). */
    suspend fun deleteSession(id: String) {
        db.messageDao().deleteForSession(id)
        db.sessionDao().deleteById(id)
    }

    /**
     * Remove every unsaved session and its messages. Run at startup so a crash / OS kill
     * that bypassed the on-exit cleanup can't leave the drawer's DB littered with junk.
     */
    suspend fun purgeUnsaved() {
        db.messageDao().deleteOrphanUnsaved()
        db.sessionDao().deleteAllUnsaved()
    }
}
