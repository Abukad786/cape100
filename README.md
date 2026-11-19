# Cape 100 Coach

Personal Android app that coaches you to the **CAPE 100** (100 km road ultra, Hermanus → Stellenbosch,
Sunday 5 September 2027, ~900 m climb, 05:00 start, 15 h cut-off).

## What it does
- **Garmin Connect sync**: training readiness, HRV, sleep, Body Battery, resting HR, stress, training status, VO2max and your last 40 activities.
- **Daily "should I train?" decision**: combines Garmin data with a morning check-in (feel, soreness, sick/injured) and automatically softens, shortens or replaces the day's session.
- **AI coach (Claude)**: reads all of the above plus your plan and gives a specific session, reasoning, fuelling and a focus cue. There is also an "Ask your coach" box.
- **46-week periodised plan**: Aerobic Base → Qualifier Block → **sub-5:00 qualifying marathon** (default 21 Mar 2027; must be done by 31 May 2027) → Reset → Ultra Build (back-to-backs, run-walk, fuelling, 50 km tune-up) → Peak (pre-dawn starts, 45–55 km long runs) → Taper → race.
- **Flip cards** everywhere: readiness, today's session, AI advice, week strip, plan days and activities. Tap a card to see the detail on the back.
- **Races tab**: pulls upcoming marathons, ultras and halves from RunningCalendar.co.za. The old Runners' Guide site no longer has a calendar. Races are ranked by how well they fit your plan (qualifier, backup qualifier, tune-up ultra, supported long runs, fitness-test halves) and by distance from your home town or location. "Make my qualifier" and "Make my tune-up" move those plan weeks to the race date.
- **Plan check**: compares your last 4 weeks of Garmin running (hours and km) with the plan. If you're ≥20% above or ≥25% below, a card on Today (and a Sunday-evening notification) offers to rebuild the plan from your real volume.
- **Reminders**: a morning briefing (syncs Garmin and tells you the verdict and session) and an evening nudge if the session isn't logged. On Sundays you also get a preview of the week ahead.

## Install
`app/build/outputs/apk/debug/app-debug.apk`: copy it to your phone and open it (allow "install unknown apps").
Or plug in your phone with USB debugging on and run `gradlew installDebug`.

## Setup in the app
1. On first launch, enter your current weekly km, longest recent run, goal time and qualifier date.
2. **Settings → Garmin Connect**: sign in with your Garmin email and password. MFA is supported. Your password goes only to Garmin; the app stores just an encrypted token.
3. **Settings → AI coach**: paste a Claude API key from console.anthropic.com. The default model is `claude-opus-5-5`.

## Notes
- Garmin has no public API for personal use, so the sync uses the same unofficial login flow as the open-source `garth` library. If Garmin changes it, the sync may stop working. The app still runs on your check-ins in that case.
- Build: open in Android Studio, or run `gradlew assembleDebug` with Android Studio's JDK (`jbr`).
