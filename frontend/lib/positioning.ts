/**
 * Where J'Toye operates, in words, defined once (SW-CLR-03 and issue 878
 * UXT-119, persona close-out).
 *
 * OWNER RULING 2026-11-04: the platform is UK-wide, launching first in
 * Birmingham and the West Midlands. It is NOT a London pilot and not a pilot
 * for one community: the earlier copy ("established West African food
 * businesses in London", "You trade in London") is superseded.
 *
 * Every public surface that says where J'Toye operates (/for-operators and its
 * metadata, /about, /contact#apply, the merchant terms) reads these constants,
 * so a later ruling is one edit and no page can drift. Demo and seed shop data
 * that uses London addresses as fixtures is not positioning and is untouched.
 */

/** The launch area, as a noun phrase. */
export const LAUNCH_AREA = "Birmingham and the West Midlands"

/** The positioning sentence. */
export const POSITIONING_LINE = `UK-wide, launching first in ${LAUNCH_AREA}.`
