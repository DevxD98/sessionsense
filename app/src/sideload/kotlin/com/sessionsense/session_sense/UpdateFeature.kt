package com.sessionsense.session_sense

/** Sideload builds update themselves from GitHub Releases. */
object UpdateFeature {
    fun create(app: SessionSenseApp): UpdateController? = SelfUpdater.get(app)
}
