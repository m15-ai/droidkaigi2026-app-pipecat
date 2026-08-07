package com.m15.pica.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity
data class ChatSession(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    /** Only saved sessions surface in the history drawer; unsaved ones are purged. */
    val saved: Boolean = false,
    // Agent snapshot taken at creation so saved history stays correctly labeled/colored
    // even after the agent is renamed or deleted from the registry.
    val agentId: String = "",
    val agentTitle: String = "",
    val agentAccent: Long = 0xFF888888L,
)

@Entity(indices = [Index("sessionId")])
data class MessageItem(
    @PrimaryKey val messageId: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long
)
