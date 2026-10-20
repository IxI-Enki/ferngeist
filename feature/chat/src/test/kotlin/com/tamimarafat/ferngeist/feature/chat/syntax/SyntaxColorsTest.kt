package com.tamimarafat.ferngeist.feature.chat.syntax

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.tamimarafat.ferngeist.core.common.ui.SyntaxColors
import com.tamimarafat.ferngeist.core.common.ui.syntaxColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class SyntaxColorsTest {
    @Test
    fun `roles follow the scheme accents`() {
        val scheme =
            lightColorScheme(
                primary = Color(0xFF112233),
                secondary = Color(0xFF223344),
                tertiary = Color(0xFF334455),
                error = Color(0xFFAABBCC),
            )
        val colors = syntaxColorsFor(scheme)

        assertEquals(scheme.primary, colors.keyword)
        assertEquals(scheme.secondary, colors.number)
        assertEquals(scheme.tertiary, colors.type)
        assertEquals(scheme.error, colors.escape)
        assertEquals(scheme.onSurface, colors.variable)
        assertEquals(scheme.onSurfaceVariant, colors.comment)
    }

    @Test
    fun `every role stays readable on the default schemes`() {
        for (scheme in listOf(lightColorScheme(), darkColorScheme())) {
            val background = scheme.surfaceContainerHighest
            syntaxColorsFor(scheme).allRoles().forEach { (role, color) ->
                val ratio = contrastRatio(color, background)
                assertTrue("$role contrast is $ratio", ratio >= 4.5)
            }
        }
    }

    private fun SyntaxColors.allRoles(): List<Pair<String, Color>> =
        listOf(
            "keyword" to keyword,
            "string" to string,
            "number" to number,
            "comment" to comment,
            "function" to function,
            "type" to type,
            "property" to property,
            "variable" to variable,
            "operator" to operator,
            "punctuation" to punctuation,
            "annotation" to annotation,
            "tag" to tag,
            "attribute" to attribute,
            "constant" to constant,
            "escape" to escape,
            "markup" to markup,
        )

    private fun contrastRatio(
        foreground: Color,
        background: Color,
    ): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
            (min(foregroundLuminance, backgroundLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channel(value: Float): Double {
            val linear = value.toDouble()
            return if (linear <= 0.03928) linear / 12.92 else ((linear + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
