package com.cape100.coach.plan

import com.cape100.coach.data.Session

/** Plain-language how-tos for every exercise the home strength sessions use. */
object ExerciseGuides {
    data class Guide(val name: String, val steps: List<String>, val mistakes: String, val videoQuery: String)

    private val guides = listOf(
        Guide("Push-ups", listOf(
            "Hands just wider than shoulders, body in one straight line from head to heels.",
            "Lower your chest towards the floor, elbows at about 45° from your body.",
            "Push back up without letting your hips sag. Use your knees if needed.",
        ), "Hips sagging or piking up; elbows flaring straight out.", "how to do a push up correctly"),
        Guide("Bent-over row", listOf(
            "Dumbbell in one hand, other hand and knee on a chair or bench (or both feet on the floor, knees soft).",
            "Hinge at the hips so your back is flat and nearly parallel to the floor.",
            "Pull the dumbbell up to your hip, leading with the elbow, and squeeze your shoulder blade back.",
            "Lower slowly for 3 seconds until your arm is straight.",
        ), "Rounding the back; jerking the weight up; shrugging the shoulder to the ear.", "one arm dumbbell bent over row form"),
        Guide("Overhead press", listOf(
            "Stand tall, dumbbells at shoulder height, palms facing forward.",
            "Brace your stomach and squeeze your glutes.",
            "Press straight up until your arms are straight, then lower slowly to your shoulders.",
        ), "Arching the lower back; pushing the head forward.", "standing dumbbell shoulder press form"),
        Guide("Floor press", listOf(
            "Lie on your back, knees bent, a dumbbell in each hand above your chest.",
            "Lower until your upper arms touch the floor, elbows about 45° from your body.",
            "Press back up until your arms are straight.",
        ), "Letting the elbows bang the floor; flaring the elbows wide.", "dumbbell floor press form"),
        Guide("Plank", listOf(
            "Forearms on the floor, elbows under shoulders, legs straight.",
            "Squeeze glutes and stomach so your body is one straight line.",
            "Breathe slowly and hold.",
        ), "Hips too high or sagging; holding your breath.", "how to do a plank correctly"),
        Guide("Renegade row", listOf(
            "Push-up position on your hands, holding the dumbbells, feet wide for balance.",
            "Row one dumbbell to your hip while keeping your hips square to the floor.",
            "Lower it and swap sides.",
        ), "Twisting the hips; feet too close together.", "dumbbell renegade row form"),
        Guide("Curl-to-press", listOf(
            "Stand tall, dumbbells at your sides, palms forward.",
            "Curl the weights to your shoulders, then turn your palms forward and press overhead.",
            "Reverse the movement slowly.",
        ), "Swinging the body; arching the back on the press.", "dumbbell curl to press"),
        Guide("Triceps kickback", listOf(
            "Hinge forward with a flat back, upper arm tight against your side, elbow bent 90°.",
            "Straighten your arm behind you, squeeze the back of the arm.",
            "Lower slowly back to 90°.",
        ), "Letting the upper arm drop or swing.", "dumbbell tricep kickback form"),
        Guide("Reverse fly", listOf(
            "Hinge forward with a flat back, dumbbells hanging under your chest, slight bend in the elbows.",
            "Raise your arms out to the sides until they're level with your body, squeezing your shoulder blades.",
            "Lower slowly.",
        ), "Using momentum; shrugging up to the ears.", "dumbbell reverse fly form"),
        Guide("Side plank", listOf(
            "Lie on your side, elbow under your shoulder, feet stacked (or knees bent to make it easier).",
            "Lift your hips so your body is a straight line.",
            "Hold, then switch sides.",
        ), "Hips dropping; rolling forward.", "side plank correct form"),
        Guide("Dead bug", listOf(
            "Lie on your back, arms up to the ceiling, knees bent 90° above your hips.",
            "Press your lower back into the floor.",
            "Slowly lower the opposite arm and leg towards the floor, then return and switch.",
        ), "Lower back lifting off the floor; moving too fast.", "dead bug exercise form"),
        Guide("Single-leg calf raises", listOf(
            "Stand on one foot on a step edge, holding a wall for balance.",
            "Rise onto your toes as high as you can, then lower slowly below the step.",
        ), "Bouncing; bending the knee.", "single leg calf raise on step"),
        Guide("Glute bridges", listOf(
            "Lie on your back, knees bent, feet flat and hip-width apart.",
            "Push through your heels and lift your hips until your body is straight from knees to shoulders.",
            "Squeeze your glutes at the top, lower slowly.",
        ), "Arching the lower back instead of using the glutes.", "glute bridge form"),
    )

    /** Guides for the exercises mentioned in a session's description, in order. */
    fun forSession(s: Session): List<Guide> {
        val text = s.details.lowercase()
        return guides.filter { g -> key(g.name).any { text.contains(it) } }
    }

    private fun key(name: String): List<String> = when (name) {
        "Single-leg calf raises" -> listOf("calf raise")
        "Glute bridges" -> listOf("glute bridge")
        "Push-ups" -> listOf("push-up")
        else -> listOf(name.lowercase())
    }
}
