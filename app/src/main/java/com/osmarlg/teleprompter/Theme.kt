package com.osmarlg.teleprompter

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Una paleta de colores de la app (se elige en Ajustes → Apariencia). */
data class Palette(
    val id: String,
    val nombre: String,
    val dark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    /** Fondo de miniaturas, botones redondos y barras de avance. */
    val raised: Color,
    val text: Color,
    val soft: Color,
    val muted: Color,
    val info: Color,
    val ok: Color,
    val warn: Color,
    val error: Color,
)

/** Cómo se ven las tarjetas: con relleno y muy redondeadas, solo contorno, o compactas. */
enum class CardStyle(val nombre: String) { Suave("Suave"), Contorno("Contorno"), Compacto("Compacto") }

object AppTheme {
    val palettes = listOf(
        Palette(
            "elroi", "ELROI", true,
            accent = Color(0xFFF43F5E), onAccent = Color.White,
            background = Color(0xFF0B0F17), surface = Color(0xFF141A24), raised = Color(0xFF1F2937),
            text = Color(0xFFF3F4F6), soft = Color(0xFFD1D5DB), muted = Color(0xFF9CA3AF),
            info = Color(0xFF60A5FA), ok = Color(0xFF34D399), warn = Color(0xFFFBBF24), error = Color(0xFFF87171),
        ),
        Palette(
            "violeta", "Medianoche", true,
            accent = Color(0xFFA78BFA), onAccent = Color(0xFF1A1033),
            background = Color(0xFF0D0A18), surface = Color(0xFF181329), raised = Color(0xFF261F3D),
            text = Color(0xFFF5F3FF), soft = Color(0xFFDDD6FE), muted = Color(0xFF9F98B8),
            info = Color(0xFF93C5FD), ok = Color(0xFF6EE7B7), warn = Color(0xFFFCD34D), error = Color(0xFFFCA5A5),
        ),
        Palette(
            "oceano", "Océano", true,
            accent = Color(0xFF22D3EE), onAccent = Color(0xFF032830),
            background = Color(0xFF05131A), surface = Color(0xFF0C2029), raised = Color(0xFF14313D),
            text = Color(0xFFECFEFF), soft = Color(0xFFCFFAFE), muted = Color(0xFF8AA9B3),
            info = Color(0xFF7DD3FC), ok = Color(0xFF5EEAD4), warn = Color(0xFFFDE68A), error = Color(0xFFFDA4AF),
        ),
        Palette(
            "bosque", "Bosque", true,
            accent = Color(0xFF34D399), onAccent = Color(0xFF052E1F),
            background = Color(0xFF08130E), surface = Color(0xFF112019), raised = Color(0xFF1B3127),
            text = Color(0xFFECFDF5), soft = Color(0xFFD1FAE5), muted = Color(0xFF8FAA9C),
            info = Color(0xFF7DD3FC), ok = Color(0xFFA7F3D0), warn = Color(0xFFFCD34D), error = Color(0xFFFCA5A5),
        ),
        Palette(
            "atardecer", "Atardecer", true,
            accent = Color(0xFFFB923C), onAccent = Color(0xFF2A1203),
            background = Color(0xFF140D09), surface = Color(0xFF221712), raised = Color(0xFF33231B),
            text = Color(0xFFFFF7ED), soft = Color(0xFFFED7AA), muted = Color(0xFFB0998A),
            info = Color(0xFF93C5FD), ok = Color(0xFF86EFAC), warn = Color(0xFFFDE047), error = Color(0xFFFCA5A5),
        ),
        Palette(
            "claro", "Claro", false,
            accent = Color(0xFFE11D48), onAccent = Color.White,
            background = Color(0xFFF4F5F7), surface = Color.White, raised = Color(0xFFE5E7EB),
            text = Color(0xFF111827), soft = Color(0xFF374151), muted = Color(0xFF6B7280),
            info = Color(0xFF2563EB), ok = Color(0xFF059669), warn = Color(0xFFB45309), error = Color(0xFFDC2626),
        ),
        Palette(
            "arena", "Arena", false,
            accent = Color(0xFF0F766E), onAccent = Color.White,
            background = Color(0xFFF5F0E8), surface = Color(0xFFFFFCF7), raised = Color(0xFFE9E1D4),
            text = Color(0xFF1C1917), soft = Color(0xFF44403C), muted = Color(0xFF78716C),
            info = Color(0xFF1D4ED8), ok = Color(0xFF15803D), warn = Color(0xFFB45309), error = Color(0xFFB91C1C),
        ),
    )

    var palette by mutableStateOf(palettes.first())
    var cardStyle by mutableStateOf(CardStyle.Suave)

    fun load(context: Context) {
        val sp = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
        palette = palettes.firstOrNull { it.id == sp.getString("palette", null) } ?: palettes.first()
        cardStyle = runCatching { CardStyle.valueOf(sp.getString("cardStyle", null)!!) }.getOrDefault(CardStyle.Suave)
    }

    fun choose(context: Context, palette: Palette = this.palette, cardStyle: CardStyle = this.cardStyle) {
        this.palette = palette
        this.cardStyle = cardStyle
        context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE).edit()
            .putString("palette", palette.id).putString("cardStyle", cardStyle.name).apply()
    }

    val cardShape: Shape
        get() = RoundedCornerShape(
            when (cardStyle) {
                CardStyle.Suave -> 20.dp
                CardStyle.Contorno -> 14.dp
                CardStyle.Compacto -> 10.dp
            },
        )

    val cardPadding: Dp get() = if (cardStyle == CardStyle.Compacto) 12.dp else 16.dp
    val listGap: Dp get() = if (cardStyle == CardStyle.Compacto) 8.dp else 12.dp
}

@Composable
fun TeleprompterTheme(content: @Composable () -> Unit) {
    val p = AppTheme.palette
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.info,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.raised, onSurfaceVariant = p.soft,
            secondaryContainer = p.accent.copy(alpha = 0.25f), onSecondaryContainer = p.text,
            outline = p.muted, outlineVariant = p.raised, error = p.error,
            surfaceContainerHigh = p.surface, surfaceContainerHighest = p.raised,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.info,
            background = p.background, onBackground = p.text,
            surface = p.surface, onSurface = p.text, surfaceVariant = p.raised, onSurfaceVariant = p.soft,
            secondaryContainer = p.accent.copy(alpha = 0.18f), onSecondaryContainer = p.text,
            outline = p.muted, outlineVariant = p.raised, error = p.error,
            surfaceContainerHigh = p.surface, surfaceContainerHighest = p.raised,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Tarjeta con el estilo elegido (relleno, contorno o compacta) y una franja de color opcional a la izquierda. */
@Composable
fun StyledCard(
    modifier: Modifier = Modifier,
    stripe: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = AppTheme.palette
    val outlined = AppTheme.cardStyle == CardStyle.Contorno
    Card(
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clip(AppTheme.cardShape).clickable(onClick = onClick) else it },
        shape = AppTheme.cardShape,
        colors = CardDefaults.cardColors(containerColor = if (outlined) p.background else p.surface, contentColor = p.text),
        border = if (outlined) BorderStroke(1.dp, p.raised) else null,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (stripe != null) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(stripe))
            }
            Column(Modifier.weight(1f).padding(AppTheme.cardPadding), content = content)
        }
    }
}

/** Pastilla pequeña de color (estado, «Tuyo», etc.). */
@Composable
fun Badge(text: String, color: Color, filled: Boolean = false) {
    val p = AppTheme.palette
    Text(
        text,
        fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        color = if (filled) (if (p.dark) p.background else Color.White) else color,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Selector de dos o más opciones en una barra (p. ej. «Por grabar / Terminadas»). */
@Composable
fun Segmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val p = AppTheme.palette
    Row(
        modifier.clip(RoundedCornerShape(50)).background(p.raised.copy(alpha = if (p.dark) 0.7f else 1f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (active) p.accent else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    color = if (active) p.onAccent else p.soft,
                )
            }
        }
    }
}

/** Botón redondo con un símbolo (recargar, ajustes, volver). */
@Composable
fun RoundIcon(symbol: String, onClick: () -> Unit, tint: Color = AppTheme.palette.text) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(AppTheme.palette.raised.copy(alpha = 0.8f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 18.sp, color = tint)
    }
}

/** Selección de paleta y estilo, con vista previa en vivo (se guarda al tocar). */
@Composable
fun AppearancePicker(context: Context) {
    val current = AppTheme.palette
    Text("Apariencia", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
    Text("Paleta", fontSize = 13.sp, color = current.muted)
    Spacer(Modifier.height(6.dp))
    AppTheme.palettes.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { palette ->
                val active = palette.id == current.id
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .border(if (active) 2.dp else 1.dp, if (active) palette.accent else current.raised, RoundedCornerShape(12.dp))
                        .clickable { AppTheme.choose(context, palette = palette) }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(palette.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(palette.accent))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(palette.surface))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(palette.info))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(palette.nombre, fontSize = 11.sp, maxLines = 1, color = if (active) current.text else current.muted, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Text("Estilo de tarjetas", fontSize = 13.sp, color = current.muted)
    Spacer(Modifier.height(6.dp))
    Segmented(
        CardStyle.entries.map { it.nombre },
        CardStyle.entries.indexOf(AppTheme.cardStyle),
        Modifier.fillMaxWidth(),
    ) { AppTheme.choose(context, cardStyle = CardStyle.entries[it]) }
}
