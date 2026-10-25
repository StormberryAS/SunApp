package no.stormberry.sunapp

/**
 * Which edition of SunApp this is, fixed when the app is built.
 *
 * This is the SOVEREIGN edition (`no.stormberry.sunapp`: GitHub Releases, Obtainium, Zapstore):
 * the sun times AND the alarm feature, with its nine install-time permissions. The Google Play
 * edition (`no.stormberry.sunapp.play`, built with `-PplayBuild=true`) has its own copy of this
 * file in `src/edition-play`, where [ALARMS] is false. The two files differ in nothing else.
 *
 * A `const val`, not a runtime setting, on purpose: Kotlin inlines it, so in the Play edition
 * every `if (Edition.ALARMS)` is dead code that R8 deletes, together with the alarm classes only
 * it reached. What the Play edition cannot do it does not contain.
 */
object Edition {
    /** True when the alarm feature is part of this build. */
    const val ALARMS: Boolean = true
}
