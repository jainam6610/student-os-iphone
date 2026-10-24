package com.jainam.studentos.focusshield

import java.util.Calendar

object MotivationEngine {

    private val APP_MESSAGES = mapOf(
        "com.instagram.android" to "Instagram can wait.\nYour goals can't.",
        "com.facebook.katana" to "Your feed will still be there.\nYour focus won't.",
        "com.facebook.orca" to "Messages can wait.\nYour session matters.",
        "com.twitter.android" to "Nothing happened in 3 minutes.\nFinish your session first.",
        "com.twitter.android.lite" to "Nothing happened in 3 minutes.\nFinish your session first.",
        "com.snapchat.android" to "Snaps can wait.\nYour focus is now.",
        "com.reddit.frontpage" to "The front page will refresh.\nYour focus won't.",
        "com.reddit.reddit" to "The front page will refresh.\nYour focus won't.",
        "com.tiktok.android" to "One video becomes thirty.\nFinish your session first.",
        "com.zhiliaoapp.musically" to "One video becomes thirty.\nFinish your session first.",
        "com.discord" to "Your friends will still be there.\nYour focus won't.",
        "com.slack" to "Work can wait 3 minutes.\nYou're in a session.",
        "com.whatsapp" to "That message can wait.\nYou're in focus mode.",
        "com.whatsapp.w4b" to "That message can wait.\nYou're in focus mode.",
        "com.tencent.mm" to "That message can wait.\nYou're in focus mode.",
        "com.google.android.youtube" to "One video becomes one hour.\nFinish your session first.",
        "com.google.android.apps.youtube.music" to "Music can wait.\nYour focus is now.",
        "com.netflix.mediaclient" to "Your show will still be there.\nYour session won't.",
        "com.spotify.music" to "Music can wait.\nYour focus is now.",
        "com.spotify.lite" to "Music can wait.\nYour focus is now.",
        "com.amazon.amazonvideo.livingroom" to "Your show will still be there.\nYour session won't.",
        "com.primevideo" to "Your show will still be there.\nYour session won't.",
        "com.pinterest" to "Your board will still be there.\nYour focus won't.",
        "com.twitch.android.tv" to "The stream will still be there.\nYour focus won't.",
        "com.ubercab" to "You're not going anywhere.\nStay focused.",
        "com.uber" to "You're not going anywhere.\nStay focused.",
        "com.deliveroo.orderapp" to "Your food is on its way.\nStay focused.",
        "com.uber.eats" to "Your food is on its way.\nStay focused.",
        "com.android.chrome" to "The internet can wait.\nYou're in a session.",
        "org.mozilla.firefox" to "The internet can wait.\nYou're in a session.",
        "org.mozilla.focus" to "The internet can wait.\nYou're in a session.",
        "com.microsoft.emmx" to "The internet can wait.\nYou're in a session.",
        "com.duckduckgo.mobile.android" to "The internet can wait.\nYou're in a session.",
        "com.opera.browser" to "The internet can wait.\nYou're in a session.",
        "com.opera.mini.native" to "The internet can wait.\nYou're in a session.",
        "com.android.vending" to "Install apps later.\nFocus now.",
        "com.google.android.gm" to "Your inbox can wait.\nYou're in a session.",
        "com.google.android.apps.inbox" to "Your inbox can wait.\nYou're in a session.",
        "com.microsoft.office.outlook" to "Your inbox can wait.\nYou're in a session.",
        "com.samsung.android.email.provider" to "Your inbox can wait.\nYou're in a session.",
    )

    private val GENERIC_TITLES = listOf(
        "Stay focused.",
        "You're in a session.",
        "This can wait.",
        "Your focus matters.",
        "Not now.",
        "Come back later.",
        "One thing at a time.",
        "You're making progress.",
        "Don't break your flow.",
    )

    private val STREAK_MESSAGES = listOf(
        "You're on fire. Don't stop now.",
        "Consistency beats intensity.",
        "Every session counts.",
        "You're building something important.",
        "Keep showing up.",
    )

    fun getAppTitle(packageName: String): String {
        return APP_MESSAGES[packageName] ?: GENERIC_TITLES.random()
    }

    fun getEncouragement(streak: Int): String {
        return when {
            streak > 20 -> "Unstoppable."
            streak > 10 -> "Incredible consistency."
            streak > 5 -> "You're building a strong habit."
            streak > 2 -> "Keep the streak alive."
            else -> STREAK_MESSAGES.random()
        }
    }

    fun getTimeOfDayGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning."
            in 12..16 -> "Good afternoon."
            in 17..20 -> "Good evening."
            else -> "Late night session. Respect."
        }
    }
    fun getMotivationalMessage(category: String): Pair<String, String> {
        return when (category) {
            "start" -> Pair("Your future self is watching", "One focused session today changes everything tomorrow.")
            "grind" -> Pair("You're in the zone", "Keep the momentum. Don't stop now.")
            "streak" -> Pair("Your streak is real", "Consistency is the rarest skill. You have it.")
            "comeback" -> Pair("Every champion has a comeback story", "Yours starts right now, today, this session.")
            "exam" -> Pair("Exam season is your season", "You've been building to this. Trust your preparation.")
            "late_night" -> Pair("Burning the midnight oil", "Respect. Just make sure sleep is in the plan too.")
            "rest" -> Pair("Your brain needs fuel too", "Take a proper break. Come back stronger.")
            "win" -> Pair("Session complete. That's a win.", "Every completed session is a deposit in your future.")
            else -> Pair("Focus is a superpower", "Every distraction you resist makes you stronger.")
        }
    }
}
