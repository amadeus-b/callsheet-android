package io.github.amadeusb.callsheet.sync

import io.github.amadeusb.callsheet.data.Clock

/** The conflict rules. They mirror the server's; both sides must agree. */
object Merge {

    /**
     * Is `a` after `b`? Parsed as an instant — comparing the strings would be
     * wrong as soon as two zone offsets are in play, which happens the moment
     * the phone crosses a border.
     */
    fun isNewer(a: String?, b: String?): Boolean {
        val left = Clock.millis(a) ?: return false
        val right = Clock.millis(b) ?: return true
        return left > right
    }

    /** The block is never lifted, whichever side is younger. */
    const val BLOCKED = "do_not_call"
}
