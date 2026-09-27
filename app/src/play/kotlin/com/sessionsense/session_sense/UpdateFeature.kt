package com.sessionsense.session_sense

/** Play builds never update themselves (Play policy); Play's own in-app update API can slot in here later. */
object UpdateFeature {
    fun create(app: SessionSenseApp): UpdateController? = null
}
