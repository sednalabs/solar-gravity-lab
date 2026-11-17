package com.sednalabs.solarlab

/** Equivalent finite decimal/exponent spellings are not physical changes. */
internal fun sameLabNumber(left: String, right: String): Boolean {
    val a = left.toDoubleOrNull() ?: return false
    val b = right.toDoubleOrNull() ?: return false
    return a.isFinite() && b.isFinite() && a == b
}

/** Preserve exact native bits when a displayed quantity has not changed. */
internal fun labEditedScalar(
    text: String,
    initialText: String,
    original: Double,
    scale: Double,
    offset: Double,
): Double = if (sameLabNumber(text, initialText)) original else text.toDouble() * scale + offset

/** Preserve a saved source/physical mass ratio unless the actual input changes. */
internal fun labEditedSourceMass(
    massText: String,
    initialMassText: String,
    exertsGravity: Boolean,
    initiallyExertedGravity: Boolean,
    originalSourceMassKg: Double,
): Double = when {
    exertsGravity == initiallyExertedGravity && sameLabNumber(massText, initialMassText) -> originalSourceMassKg
    exertsGravity -> massText.toDouble()
    else -> 0.0
}
