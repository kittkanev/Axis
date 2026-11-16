package io.github.kittkanev.axis

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

@Composable
internal fun AssistantPanel(app: AxisApplication, actions: AssistantController, pin: (() -> Unit)? = null) {
    val prefs by app.preferences.state.collectAsState()
    val input by actions.state.collectAsState()
    val speaking by actions.voice.speaking.collectAsState()
    val voiceLabel = when {
        input.listening -> "Слушаю"
        input.finishingSpeech -> "Распознаю речь"
        input.waiting -> "Модель готовит ответ"
        speaking -> "Озвучиваю ответ"
        else -> "Голосовой режим"
    }
    val orbAction = when {
        input.listening -> "Завершить запись"
        input.finishingSpeech || input.waiting -> "Остановить запрос"
        speaking -> "Остановить озвучивание"
        else -> "Начать запись"
    }

    AxisTheme(prefs, darkOverlay = true) {
        Box(
            Modifier.fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = prefs.dim * 0.5f),
                        0.48f to Color.Black.copy(alpha = prefs.dim * 0.75f),
                        1f to Color.Black.copy(alpha = (prefs.dim + 0.34f).coerceAtMost(0.97f)),
                    ),
                )
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Column(
                Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Axis", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(voiceLabel, color = Color.White.copy(alpha = .80f), style = MaterialTheme.typography.bodyMedium)
                if (pin != null) TextButton(onClick = pin) { Text(stringResource(R.string.display_over_apps)) }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (input.reply.isNotEmpty()) {
                    Surface(
                        color = Color(0xEB242429),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            MarkdownMessage(input.reply, color = Color.White)
                            if (!input.waiting) Row {
                                CopyReply(input.reply)
                                TextButton(onClick = actions::speakReply) { Text(if (speaking) "Остановить голос" else "Озвучить") }
                            }
                        }
                    }
                }

                if (input.error != null || input.draft.isNotBlank() || input.showEditor) {
                    Surface(color = Color(0xEF242429), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            input.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (input.draft.isNotBlank() || input.showEditor) {
                                OutlinedTextField(
                                    value = input.draft, onValueChange = actions::edit, label = { Text(stringResource(R.string.message_field)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 3, enabled = !input.waiting && !input.listening && !input.finishingSpeech,
                                )
                                Button(
                                    onClick = actions::send,
                                    enabled = input.draft.isNotBlank() && !input.waiting && !input.listening && !input.finishingSpeech,
                                    modifier = Modifier.align(Alignment.End),
                                ) { Text(stringResource(R.string.send_action)) }
                            } else {
                                TextButton(onClick = { actions.showEditor() }) { Text(stringResource(R.string.enter_text)) }
                            }
                        }
                    }
                } else if (input.reply.isEmpty()) {
                    TextButton(onClick = actions::showEditor, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(R.string.enter_text))
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PanelIcon("Открыть Axis", true, actions::openApp)
                        if (pin != null) IconButton(onClick = pin) {
                            Image(painterResource(R.drawable.ic_axis_settings), "Настройки оверлея",
                                Modifier.size(23.dp), colorFilter = ColorFilter.tint(Color.White))
                        }
                    }
                    IconButton(
                        onClick = actions::startOrStop,
                        modifier = Modifier.size(prefs.orbSize.dp + 12.dp).semantics { contentDescription = orbAction },
                    ) {
                        AxisOrb(
                            Modifier.size(prefs.orbSize.dp),
                            prefs.accent,
                            prefs.motion,
                            input.listening || input.finishingSpeech || input.waiting || speaking,
                            input.audioLevel, prefs.tint,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicrophoneButton(input.listening || input.finishingSpeech, actions::toggleMicrophone)
                        PanelIcon("Закрыть оверлей", false, actions::close)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PanelIcon(label: String, plus: Boolean, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(50.dp).semantics { contentDescription = label }) {
        Canvas(Modifier.size(24.dp)) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
                Color.White, Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2),
                strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round,
            )
            if (plus) {
                line(.17f, .5f, .83f, .5f)
                line(.5f, .17f, .5f, .83f)
            } else {
                line(.23f, .23f, .77f, .77f)
                line(.23f, .77f, .77f, .23f)
            }
        }
    }
}

@Composable
internal fun AxisOrb(
    modifier: Modifier,
    accent: Accent,
    motion: Boolean,
    active: Boolean,
    audioLevel: Float = 0f,
    color: Long = accent.color,
) {
    val transition = if (motion) rememberInfiniteTransition(label = "Сфера Axis") else null
    val phase = if (transition != null) {
        val position by transition.animateFloat(
            0f, 1f, infiniteRepeatable(tween(if (active) 1700 else 3300, easing = LinearEasing)),
            label = "Вращение света",
        )
        position
    } else 0.36f
    val level by animateFloatAsState(if (motion) audioLevel.coerceIn(0f, 1f) else 0f, spring(), label = "Уровень речи")
    val spin = phase * (2 * PI).toFloat()
    val pulse = if (motion) (sin(spin * 2f) + 1f) / 2f else 0f
    val scale = 0.93f + pulse * if (active) 0.09f else 0.045f
    val tint = Color(color)
    Canvas(modifier.graphicsLayer {
        scaleX = scale + level * .1f
        scaleY = scaleX
    }.clip(CircleShape)) {
        drawRect(Brush.linearGradient(listOf(tint, Color(accent.darkInk), tint), Offset.Zero, Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(
            listOf(Color.White, tint.copy(alpha = .89f), Color.Transparent),
            Offset(size.width * (.52f + .35f * cos(spin)), size.height * (.57f + .26f * sin(spin))),
            size.width * (.68f + .14f * pulse + .12f * level),
        ))
        drawRect(Brush.radialGradient(
            listOf(Color.White.copy(alpha = .65f), Color.Transparent),
            Offset(size.width * (.52f - .32f * cos(spin)), size.height * (.66f - .30f * sin(spin))),
            size.width * .53f,
        ))
    }
}


@Composable
internal fun MicrophoneButton(active: Boolean, onClick: () -> Unit) {
    val label = if (active) "Выключить микрофон" else "Включить микрофон"
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Canvas(Modifier.size(23.dp)) {
            val stroke = 1.8.dp.toPx()
            drawRoundRect(tint, Offset(size.width * .36f, size.height * .08f),
                androidx.compose.ui.geometry.Size(size.width * .28f, size.height * .55f),
                androidx.compose.ui.geometry.CornerRadius(size.width * .15f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(tint, 0f, 180f, false, Offset(size.width * .22f, size.height * .32f),
                androidx.compose.ui.geometry.Size(size.width * .56f, size.height * .47f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawLine(tint, Offset(size.width * .5f, size.height * .8f), Offset(size.width * .5f, size.height * .95f), stroke)
            if (!active) {
                drawLine(tint, Offset(size.width * .10f, size.height * .12f), Offset(size.width * .90f, size.height * .90f), stroke)
            }
        }
    }
}
