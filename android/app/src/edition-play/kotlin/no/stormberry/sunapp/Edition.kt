package no.stormberry.sunapp

/**
 * Which edition of SunApp this is, fixed when the app is built.
 *
 * This is the GOOGLE PLAY edition (`no.stormberry.sunapp.play`, built with `-PplayBuild=true`):
 * the sun times WITHOUT the alarm feature. No alarm permission, receiver, service or screen is
 * in the app; `src/edition-play/AndroidManifest.xml` removes them from the merged manifest, and
 * [ALARMS] being false lets R8 delete the code behind them. The sovereign edition has its own
 * copy of this file in `src/edition-sovereign`, where [ALARMS] is true. The two files differ in
 * nothing else.
 */
object Edition {
    /** True when the alarm feature is part of this build. */
    const val ALARMS: Boolean = false
}
