package com.graciousgazelles.solarlab.core.math

import kotlin.math.PI

fun Double.degToRad(): Double = this * PI / 180.0

fun Double.radToDeg(): Double = this * 180.0 / PI
