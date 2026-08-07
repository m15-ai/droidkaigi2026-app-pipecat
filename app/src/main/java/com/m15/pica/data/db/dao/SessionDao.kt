package com.m15.pica.data.db.dao

import androidx.room.*
import com.m15.pica.data.db.ChatSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSession)

    @Query("SELECT * FROM ChatSession ORDER BY createdAt DESC LIMIT 20")
    fun recent(): Flow<List<ChatSession>>

    /** History drawer feed — only explicitly saved conversations, newest first. */
    @Query("SELECT * FROM ChatSession WHERE saved = 1 ORDER BY createdAt DESC")
    fun savedSessions(): Flow<List<ChatSession>>

    @Query("SELECT * FROM ChatSession WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ChatSession?

    @Query("UPDATE ChatSession SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE ChatSession SET saved = 1, title = :title WHERE id = :id")
    suspend fun markSaved(id: String, title: String)

    @Query("DELETE FROM ChatSession WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM ChatSession WHERE saved = 0")
    suspend fun deleteAllUnsaved()
}
