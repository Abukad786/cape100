package com.cape100.coach.plan

import com.cape100.coach.data.CheckIn
import com.cape100.coach.data.DayMetric
import com.cape100.coach.data.GarminSnapshot
import com.cape100.coach.data.Session
import com.cape100.coach.data.SessionType
import kotlin.math.roundToInt

enum class Verdict(val label: String, val color: Long) {
    GO("Green light – train as planned", 0xFF22C55E),
    ADJUST("Train, but dial it back", 0xFFF59E0B),
    RECOVER("Recovery day", 0xFFF97316),
    REST("Rest today", 0xFFEF4444),
}

data class Decision(
    val verdict: Verdict,
    val score: Int,
    val reasons: List<String>,
    val planned: Session?,
    val session: Session?,
    /** Set when the coach changed today's session after your update. */
    val coachNote: String? = null,
) {
    val modified: Boolean get() = planned != null && session != planned
}

/** Rule-based coach that works offline. The AI coach builds on top of this. */
object ReadinessEngine {

    fun decide(planned: Session?, snap: GarminSnapshot?, checkIn: CheckIn?, history: List<DayMetric>): Decision {
        val reasons = mutableListOf<String>()
        var score = snap?.readinessScore?.toDouble() ?: 70.0
        if (snap?.readinessScore != null) reasons += "Garmin Training Readiness ${snap.readinessScore}" +
            (snap.readinessLevel?.let { " (${it.lowercase()})" } ?: "")
        else reasons += "No Garmin readiness today – using your check-in"

        when (snap?.hrvStatus?.uppercase()) {
            "LOW", "POOR" -> { score -= 15; reasons += "HRV is ${snap.hrvStatus!!.lowercase()} – nervous system under strain" }
            "UNBALANCED" -> { score -= 5; reasons += "HRV unbalanced" }
            "BALANCED" -> reasons += "HRV balanced"
        }
        snap?.sleepScore?.let {
            if (it < 50) { score -= 10; reasons += "Poor sleep (score $it)" }
            else if (it >= 80) { score += 3; reasons += "Great sleep (score $it)" }
        }
        snap?.bodyBattery?.let { if (it < 30) { score -= 10; reasons += "Body Battery low ($it)" } }

        val rhrBase = history.mapNotNull { it.rhr }.takeLast(14).dropLast(1).takeIf { it.size >= 4 }?.average()
        val rhr = snap?.restingHr
        if (rhrBase != null && rhr != null && rhr > rhrBase + 5) {
            score -= 10; reasons += "Resting HR $rhr is ${(rhr - rhrBase).roundToInt()} bpm above your normal"
        }
        snap?.recoveryTimeHours?.let { if (it > 36) { score -= 8; reasons += "Garmin recovery time still ${it}h" } }

        checkIn?.let { c ->
            when (c.feel) { 1 -> { score -= 15; reasons += "You feel awful" }; 2 -> { score -= 8; reasons += "You feel flat" }; 5 -> score += 5 }
            when (c.soreness) { 4 -> { score -= 10; reasons += "Legs quite sore" }; 5 -> { score -= 18; reasons += "Legs very sore" } }
        }

        val s = score.roundToInt().coerceIn(0, 100)
        if (checkIn?.sick == true) {
            return Decision(Verdict.REST, s, listOf("You reported illness/injury – rest is training today") + reasons, planned, planned?.toRest())
        }
        if (planned == null || planned.type == SessionType.REST) {
            return Decision(Verdict.REST, s, listOf("Planned rest day") + reasons, planned, planned)
        }
        if (planned.type == SessionType.RACE || planned.type == SessionType.TUNEUP_RACE || planned.type == SessionType.EVENT) {
            return Decision(if (s >= 25) Verdict.GO else Verdict.ADJUST, s, listOf("Race day – go with your plan, start conservatively") + reasons, planned, planned)
        }

        val verdict = when {
            s >= 70 -> Verdict.GO
            s >= 45 -> if (planned.type.hard) Verdict.ADJUST else Verdict.GO
            s >= 25 -> Verdict.RECOVER
            else -> Verdict.REST
        }
        val session = when {
            verdict == Verdict.GO -> planned
            // Home strength on a low day: keep it, just lighter – it's not a running load.
            planned.type == SessionType.STRENGTH && verdict != Verdict.REST -> planned.copy(
                title = planned.title.replace("25 min", "15 min"), durationMin = 15,
                details = "Lighter today because readiness is low: do 1–2 rounds instead of 3, slow and controlled, " +
                    "and stop if anything feels off.\n\n" + planned.details,
            )
            verdict == Verdict.ADJUST -> planned.softened()
            verdict == Verdict.RECOVER -> planned.toRecovery()
            else -> planned.toRest()
        }
        return Decision(verdict, s, reasons, planned, session)
    }

    private fun Session.softened(): Session = when (type) {
        SessionType.LONG, SessionType.BACK_TO_BACK -> {
            val km = PlanGenerator.roundHalf(distanceKm * 0.7)
            copy(title = "Shortened long run · ${PlanGenerator.k(km)} km", distanceKm = km, durationMin = (durationMin * 0.7).roundToInt(),
                vertM = (vertM * 0.7).roundToInt(), intensity = "Easy only",
                details = "Readiness is down, so today's long run is cut to ~70% and kept fully easy. Still practise fuelling.\n\nOriginal plan: $details")
        }
        else -> {
            val km = PlanGenerator.roundHalf(distanceKm * 0.75)
            copy(type = SessionType.EASY, title = "Easy aerobic · ${PlanGenerator.k(km)} km", distanceKm = km,
                durationMin = (durationMin * 0.75).roundToInt(), intensity = "Easy, conversational",
                details = "Swap the hard workout for easy running today. Hard efforts on a tired body add fatigue without fitness. " +
                    "The workout can move to a better day this week if you feel good.\n\nOriginal plan: $details")
        }
    }

    private fun Session.toRecovery() = copy(
        type = SessionType.RECOVERY, title = "Recovery: 30 min jog or walk", distanceKm = 4.0, durationMin = 30, vertM = 0,
        intensity = "Very easy (Z1)",
        details = "Your body is asking for recovery. 20–30 min very easy jog or brisk walk, then mobility. Hydrate and get to bed early.\n\nOriginal plan: $details",
    )

    private fun Session.toRest() = copy(
        type = SessionType.REST, title = "Rest day", distanceKm = 0.0, durationMin = 0, vertM = 0, intensity = "",
        details = "Take the day off. One missed session costs nothing; training while depleted or ill can cost weeks.\n\nOriginal plan: $title",
    )
}
