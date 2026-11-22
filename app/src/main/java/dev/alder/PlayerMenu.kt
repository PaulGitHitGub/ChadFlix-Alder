package dev.alder

// ChadFlix: what the player's top line and panels decide, kept apart from the views so it can be tested.

/** When the title ends in wall-clock ms, or null for a trailer, live TV or a length not known. */
internal fun endsAtTime(now: Long, duration: Long, position: Long, speed: Float, trailer: Boolean, live: Boolean): Long? =
    if (trailer || live || duration <= 0) null else now + ((duration - position).coerceAtLeast(0) / speed).toLong()
