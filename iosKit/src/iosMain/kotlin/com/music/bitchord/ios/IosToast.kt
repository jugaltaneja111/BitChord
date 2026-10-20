package com.music.bitchord.ios

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One line of feedback at a time — "Added to queue" — over the bottom bars, as the phone's snackbar. */
internal object IosToast {
    private data class Shown(val text: String, val id: Long)

    private var shown by mutableStateOf<Shown?>(null)
    private var count = 0L

    fun show(text: String) {
        shown = Shown(text, ++count)
    }

    @Composable
    fun Host(modifier: Modifier = Modifier) {
        val current = shown
        var visible by remember { mutableStateOf(false) }
        // The last text stays drawn while it fades out.
        var text by remember { mutableStateOf("") }
        LaunchedEffect(current?.id) {
            current ?: return@LaunchedEffect
            text = current.text
            visible = true
            delay(2_400)
            visible = false
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = modifier,
        ) {
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .background(Color(0xFF2C2C2E), shape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), shape)
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}
