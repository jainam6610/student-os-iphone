package com.jainam.studentos.focusshield.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CategoryType {
    ESSENTIAL,
    STUDY,
    USER_ALLOWED,
    /**
     * Explicitly picked by the user to be blocked **during focus sessions
     * only**. Blocked in blocklist/STANDARD mode while a session is ACTIVE and
     * allowed again the moment it ends (outside a session only ALWAYS_BLOCKED
     * still blocks). This is where onboarding picks land; 24/7 blocking is the
     * separate ALWAYS_BLOCKED list.
     */
    BLOCKED,
    SOCIAL_MEDIA,
    ENTERTAINMENT,
    GAMES,
    SHOPPING,
    /**
     * Installed app the user has not classified. Allowed in blocklist mode
     * (the default) and blocked in strict/whitelist mode.
     */
    UNCATEGORIZED,
    /** Blocked during focus sessions AND during breaks — never accessible while a session exists. */
    ALWAYS_BLOCKED,
    /** Blocked during active sessions AND when break-blocking mode is enabled. Same as ALWAYS_BLOCKED for blocking purposes. */
    BREAK_BLOCKED
}

enum class LogicalState {
    ALLOWED,
    BLOCKED,
    ESSENTIAL
}

fun CategoryType.toLogicalState(): LogicalState = when (this) {
    CategoryType.ESSENTIAL -> LogicalState.ESSENTIAL
    // Not chosen by the user → not blocked. Only explicit picks block.
    CategoryType.USER_ALLOWED, CategoryType.STUDY,
    CategoryType.UNCATEGORIZED, CategoryType.SOCIAL_MEDIA,
    CategoryType.ENTERTAINMENT, CategoryType.GAMES,
    CategoryType.SHOPPING -> LogicalState.ALLOWED
    CategoryType.BLOCKED,
    CategoryType.ALWAYS_BLOCKED,
    CategoryType.BREAK_BLOCKED -> LogicalState.BLOCKED
}

@Entity(tableName = "app_categories")
data class AppCategory(
    @PrimaryKey val packageName: String,
    val appName: String,
    val categoryType: CategoryType,
    val isSystemApp: Boolean = false
)
