package com.graciousgazelles.solarlab.core.math

import kotlin.math.sqrt

data class Vector3d(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vector3d): Vector3d = Vector3d(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vector3d): Vector3d = Vector3d(x - other.x, y - other.y, z - other.z)

    operator fun unaryMinus(): Vector3d = Vector3d(-x, -y, -z)

    operator fun times(scalar: Double): Vector3d = Vector3d(x * scalar, y * scalar, z * scalar)

    operator fun div(scalar: Double): Vector3d = Vector3d(x / scalar, y / scalar, z / scalar)

    fun dot(other: Vector3d): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3d): Vector3d = Vector3d(
        x = y * other.z - z * other.y,
        y = z * other.x - x * other.z,
        z = x * other.y - y * other.x,
    )

    fun magnitudeSquared(): Double = dot(this)

    fun magnitude(): Double = sqrt(magnitudeSquared())

    fun normalized(): Vector3d {
        val magnitude = magnitude()
        return if (magnitude == 0.0) ZERO else this / magnitude
    }

    fun distanceSquaredTo(other: Vector3d): Double = (this - other).magnitudeSquared()

    fun distanceTo(other: Vector3d): Double = sqrt(distanceSquaredTo(other))

    companion object {
        val ZERO: Vector3d = Vector3d(0.0, 0.0, 0.0)
    }
}

operator fun Double.times(vector: Vector3d): Vector3d = vector * this
