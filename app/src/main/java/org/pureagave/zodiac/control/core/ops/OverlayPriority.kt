package org.pureagave.zodiac.control.core.ops

import org.pureagave.zodiac.control.core.model.CockpitConcept

/**
 * Whether the top-centre "ENTERING <street>" flash may draw. It sits exactly
 * where DRIVER draws `! COLLISION COURSE !`, so while that banner is up the
 * street name yields: a collision warning is never covered by navigation
 * trivia. Other concepts draw no centre collision banner, so they always show it.
 */
fun streetPopupVisible(
    concept: CockpitConcept,
    brakeAdvised: Boolean,
): Boolean = !(concept == CockpitConcept.DRIVER && brakeAdvised)
