package dev.yazin.keyboard.engine

/** Where a finger touched the keyboard, in px. */
data class Touch(val x: Float, val y: Float)

/**
 * Key centres of the visible layout, for spatial correction. Keys are grouped by their folded character,
 * so ة and ه (or ى and ي) both count as evidence for the same letter. [pitchX] and [pitchY] are the
 * distances between neighbouring keys; costs are measured in those units.
 */
class KeyModel(centers: Map<Char, List<Touch>>, val pitchX: Float, val pitchY: Float) {
    private val byKey: Map<Char, List<Touch>> =
        centers.entries.groupBy({ Fold.key(it.key) }, { it.value }).mapValues { (_, lists) -> lists.flatten() }

    fun has(c: Char): Boolean = Fold.key(c) in byKey

    /** Every key centre that types [c] (folded), empty when no key does. */
    fun centers(c: Char): List<Touch> = byKey[Fold.key(c)] ?: emptyList()

    /**
     * Negative log-likelihood, up to a constant, that a touch at [t] was meant for [c]. It is 0 at the key centre
     * and grows with squared distance (a Gaussian touch model of width [sigma] pitches), capped at [far].
     */
    fun cost(t: Touch, c: Char, sigma: Float, far: Float): Float {
        val keys = byKey[Fold.key(c)] ?: return far
        var best = far
        for (k in keys) {
            val dx = (t.x - k.x) / pitchX
            val dy = (t.y - k.y) / pitchY
            best = minOf(best, (dx * dx + dy * dy) / (2 * sigma * sigma))
        }
        return best
    }
}
