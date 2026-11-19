package com.cape100.coach

import android.app.Application
import com.cape100.coach.data.Store
import com.cape100.coach.work.Notifier
import com.cape100.coach.work.Reminders

class CoachApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        // Re-schedule reminders only when their settings changed since last time.
        val s = Store.get(this).settings
        val sig = listOf(s.morningEnabled, s.morningHour, s.morningMinute, s.eveningEnabled, s.eveningHour, s.eveningMinute,
            s.weighInReminder, s.weighInHour, s.weighInMinute, s.weighInWeekendHour, s.weighInWeekendMinute).joinToString()
        val prefs = getSharedPreferences("cape100_sched", MODE_PRIVATE)
        Reminders.schedule(this, s, replace = prefs.getString("sig", null) != sig)
        prefs.edit().putString("sig", sig).apply()
    }
}
