package com.jainam.studentos.focusshield.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppCategoryDao {
    @Query("SELECT * FROM app_categories")
    fun getAllCategoriesFlow(): Flow<List<AppCategory>>

    @Query("SELECT * FROM app_categories")
    suspend fun getAllCategories(): List<AppCategory>

    @Query("SELECT * FROM app_categories WHERE packageName = :packageName LIMIT 1")
    suspend fun getCategoryForPackage(packageName: String): AppCategory?

    @Query("SELECT * FROM app_categories WHERE categoryType = 'ALWAYS_BLOCKED' OR categoryType = 'BREAK_BLOCKED'")
    suspend fun getAlwaysBlockedCategories(): List<AppCategory>

    @Query("SELECT * FROM app_categories WHERE categoryType = 'ALWAYS_BLOCKED'")
    fun getAlwaysBlockedFlow(): Flow<List<AppCategory>>

    @Query("SELECT * FROM app_categories WHERE categoryType = 'ALWAYS_BLOCKED'")
    suspend fun getAlwaysBlockedOnly(): List<AppCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(appCategory: AppCategory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(appCategories: List<AppCategory>)

    @Delete
    suspend fun deleteCategory(appCategory: AppCategory)

    @Query("DELETE FROM app_categories WHERE packageName = :packageName")
    suspend fun deleteCategoryByPackage(packageName: String)

    @Query("DELETE FROM app_categories WHERE categoryType = 'USER_ALLOWED'")
    suspend fun clearUserAllowed()

    @Query("DELETE FROM app_categories WHERE categoryType = 'ALWAYS_BLOCKED' OR categoryType = 'BREAK_BLOCKED'")
    suspend fun clearAlwaysBlocked()

    @Query("DELETE FROM app_categories WHERE categoryType = 'ALWAYS_BLOCKED'")
    suspend fun clearAlwaysBlockedOnly()

    @Query("DELETE FROM app_categories WHERE categoryType = 'BREAK_BLOCKED'")
    suspend fun clearBreakBlockedOnly()

    @Query("DELETE FROM app_categories WHERE categoryType = 'BLOCKED'")
    suspend fun clearBlockedOnly()

    @Query("SELECT * FROM app_categories WHERE categoryType = 'BLOCKED'")
    suspend fun getBlockedOnly(): List<AppCategory>

    @Query("SELECT * FROM app_categories WHERE categoryType = 'BREAK_BLOCKED'")
    suspend fun getBreakBlockedCategories(): List<AppCategory>

    @androidx.room.Transaction
    suspend fun replaceUserAllowedWhitelist(categories: List<AppCategory>) {
        // ALWAYS_BLOCKED / BREAK_BLOCKED rows live in this same table and share
        // the packageName primary key. Inserting a USER_ALLOWED row for a package
        // the user explicitly blocked silently REPLACED the block row (REPLACE on
        // conflict), so the app the user picked to block became allowed. The deny
        // list wins: skip every package that is currently blocked.
        val blocked = getAllCategories()
            .asSequence()
            .filter {
                it.categoryType == CategoryType.ALWAYS_BLOCKED ||
                    it.categoryType == CategoryType.BREAK_BLOCKED ||
                    it.categoryType == CategoryType.BLOCKED
            }
            .mapTo(HashSet()) { it.packageName }
        clearUserAllowed()
        insertCategories(categories.filter { it.packageName !in blocked })
    }

    /**
     * Replaces ONLY the ALWAYS_BLOCKED rows. Previously this deleted both
     * ALWAYS_BLOCKED and BREAK_BLOCKED rows, so saving one list silently wiped
     * the other (startSession() writes the break list, the settings UI writes
     * the always list — they erased each other).
     */
    @androidx.room.Transaction
    suspend fun replaceAlwaysBlockedList(categories: List<AppCategory>) {
        clearAlwaysBlockedOnly()
        insertCategories(categories)
    }

    /** Replaces ONLY the BREAK_BLOCKED rows. See [replaceAlwaysBlockedList]. */
    @androidx.room.Transaction
    suspend fun replaceBreakBlockedList(categories: List<AppCategory>) {
        clearBreakBlockedOnly()
        insertCategories(categories)
    }

    /**
     * Replaces ONLY the BLOCKED rows (session-only blocks: active in STANDARD
     * mode, allowed when no session is running). Kept separate from the 24/7
     * and break lists so saving one list never wipes another — all three share
     * the packageName primary key.
     */
    @androidx.room.Transaction
    suspend fun replaceBlockedList(categories: List<AppCategory>) {
        clearBlockedOnly()
        insertCategories(categories)
    }
}
