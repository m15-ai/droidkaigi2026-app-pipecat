package com.m15.pica.data.db.dao

import androidx.room.*
import com.m15.pica.data.db.MessageItem
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** Insert-or-update keyed on [MessageItem.messageId]; used for streaming transcripts. */
    @Upsert
    suspend fun upsert(m: MessageItem)

    @Query("SELECT * FROM MessageItem WHERE sessionId = :sid ORDER BY createdAt ASC")
    fun stream(sid: String): Flow<List<MessageItem>>

    /** No FK cascade — children are removed explicitly when a session is deleted. */
    @Query("DELETE FROM MessageItem WHERE sessionId = :sid")
    suspend fun deleteForSession(sid: String)

    @Query("DELETE FROM MessageItem WHERE sessionId IN (SELECT id FROM ChatSession WHERE saved = 0)")
    suspend fun deleteOrphanUnsaved()
}
