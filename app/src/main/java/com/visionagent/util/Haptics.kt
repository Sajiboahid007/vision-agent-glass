package com.visionagent.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Accessibility haptic feedback manager for blind and low-vision users.
 * Supports Android API 26 through API 35+, automatically selecting between
 * [VibratorManager] and legacy [Vibrator].
 *
 * Provides specialized tactile feedback for key app events:
 * - Action trigger (start of photo capture or speech listening)
 * - Answer speaking start (double vibration)
 * - Error notification (long vibration)
 * - Periodic background thinking tick (subtle pulse)
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Checks if the device has an operable vibration motor.
     */
    val hasVibrator: Boolean
        get() = vibrator?.hasVibrator() == true

    /**
     * Short vibration (~80ms) when a user action begins (e.g. Describe, Read Text, Ask).
     */
    fun vibrateActionStart() {
        if (!hasVibrator) return
        try {
            val effect = VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
            // Ignore vibration failures if hardware is busy
        }
    }

    /**
     * Double vibration (pulse 50ms, sleep 60ms, pulse 50ms) when the AI answer begins speaking.
     */
    fun vibrateAnswerStart() {
        if (!hasVibrator) return
        try {
            val timings = longArrayOf(0, 50, 60, 50)
            val amplitudes = intArrayOf(
                0,
                VibrationEffect.DEFAULT_AMPLITUDE,
                0,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {
            // Fallback to simple one-shot if waveform is unsupported
            try {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Exception) {}
        }
    }

    /**
     * Long vibration (~400ms) indicating an error or warning.
     */
    fun vibrateError() {
        if (!hasVibrator) return
        try {
            val effect = VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {}
    }

    /**
     * Gentle subtle pulse (~25-30ms) for periodic feedback while Gemini is processing.
     * Uses [VibrationEffect.EFFECT_TICK] on API 29+ when available.
     */
    fun vibrateGentleTick() {
        if (!hasVibrator) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    return
                } catch (_: Exception) {
                    // Predefined effect may not be supported on all devices, fall back below
                }
            }

            val amplitude = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasAmplitudeControl() == true) {
                80 // Subtle gentle amplitude
            } else {
                VibrationEffect.DEFAULT_AMPLITUDE
            }
            val effect = VibrationEffect.createOneShot(25, amplitude)
            vibrator?.vibrate(effect)
        } catch (_: Exception) {}
    }

    /**
     * Cancels any active vibration immediately.
     */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (_: Exception) {}
    }
}
