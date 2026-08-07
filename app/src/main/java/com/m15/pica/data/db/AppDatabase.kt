package com.m15.pica.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.m15.pica.data.db.dao.MessageDao
import com.m15.pica.data.db.dao.SessionDao

@Database(
    entities = [ChatSession::class, MessageItem::class],
    version = 2
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile private var inst: AppDatabase? = null
        fun get(ctx: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(ctx, AppDatabase::class.java, "lika.db")
                    .fallbackToDestructiveMigration()
                    .build().also { inst = it }
            }
    }
}
