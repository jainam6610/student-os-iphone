package com.jainam.studentos.focusshield.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.jainam.studentos.focusshield.data.datastore.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun toCategoryType(value: String): CategoryType {
        return CategoryType.valueOf(value)
    }

    @TypeConverter
    fun fromCategoryType(type: CategoryType): String {
        return type.name
    }
}

@Database(
    entities = [AppCategory::class, DistractionLog::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appCategoryDao(): AppCategoryDao
    abstract fun distractionDao(): DistractionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE distraction_logs ADD COLUMN blockingMode TEXT NOT NULL DEFAULT 'STANDARD'")
            db.execSQL("ALTER TABLE distraction_logs ADD COLUMN timeRemainingSeconds INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE distraction_logs ADD COLUMN dayTimestamp INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE distraction_logs SET dayTimestamp = timestamp WHERE dayTimestamp = 0")
        }

        /**
         * v2 → v3: the scanner used to persist *every* installed app as BLOCKED,
         * while the engine used whitelist semantics — so any session blocked all
         * unlisted apps. Those rows were scanner output, not user choices, so
         * they become UNCATEGORIZED (allowed in the new blocklist default).
         */
        val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL("UPDATE app_categories SET categoryType = 'UNCATEGORIZED' WHERE categoryType = 'BLOCKED'")
        }

        @JvmStatic
        fun clearAllPersistedData(context: Context, onComplete: (String?) -> Unit) {
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                try {
                    getDatabase(appContext).clearAllTables()
                    appContext.dataStore.edit { preferences -> preferences.clear() }
                    onComplete(null)
                } catch (error: Exception) {
                    onComplete(error.message ?: "Failed to clear native database")
                }
            }
        }

        @JvmStatic
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focus_shield_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
