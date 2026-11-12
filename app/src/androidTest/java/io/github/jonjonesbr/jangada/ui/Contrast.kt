package io.github.jonjonesbr.jangada.ui

import android.graphics.Color

/** Razão de contraste WCAG (1 a 21) entre duas cores: texto e fundo, por exemplo. */
object Contrast {
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val claro = maxOf(la, lb)
        val escuro = minOf(la, lb)
        return (claro + 0.05) / (escuro + 0.05)
    }

    /** Luminância relativa, com os canais sRGB linearizados como manda o WCAG. */
    private fun luminance(color: Int): Double {
        fun linear(c: Int): Double {
            val v = c / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(Color.red(color)) +
            0.7152 * linear(Color.green(color)) +
            0.0722 * linear(Color.blue(color))
    }
}
