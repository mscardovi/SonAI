package com.scardracs.sonai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
fun WaveformComposable(
    magnitudes: List<Float>,
    modifier: Modifier = Modifier
) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier
        .fillMaxWidth()
        .height(100.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
        val step = width / (magnitudes.size.coerceAtLeast(1))

        magnitudes.forEachIndexed { index, magnitude ->
            val x = index * step
            val lineHeight = magnitude * height / 2
            drawLine(
                color = color,
                start = androidx.compose.ui.geometry.Offset(x, centerY - lineHeight / 2),
                end = androidx.compose.ui.geometry.Offset(x, centerY + lineHeight / 2),
                strokeWidth = 5f,
                cap = StrokeCap.Round
            )
        }
    }
}