package com.mvrk.vrka.mi5g.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mvrk.vrka.VrkaMonoFamily
import com.mvrk.vrka.VrkaOutlinedButton
import com.mvrk.vrka.VrkaTokens
import com.mvrk.vrka.mi5g.trim.TrimSpec

/** Mi5G Dl: optional "Trim" editor shared by the Download screen and the share sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrimEditor(
    start: String,
    end: String,
    precise: Boolean,
    durationSeconds: Double?,
    probing: Boolean,
    probeMessage: String?,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit,
    onPreciseChange: (Boolean) -> Unit,
    onProbe: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val validation = TrimSpec.validate(start, end, durationSeconds)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = start,
                onValueChange = { onStartChange(it.filter { c -> c.isDigit() || c == ':' || c == '.' }.take(12)) },
                label = { Text("Start") },
                placeholder = { Text("0:00") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = end,
                onValueChange = { onEndChange(it.filter { c -> c.isDigit() || c == ':' || c == '.' }.take(12)) },
                label = { Text("End") },
                placeholder = { Text(durationSeconds?.let(TrimSpec::format) ?: "end") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "mm:ss or hh:mm:ss · leave End empty to keep until the end",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = VrkaTokens.TextTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (durationSeconds != null && durationSeconds > 0) {
            val s = (TrimSpec.parseTime(start) ?: 0.0).coerceIn(0.0, durationSeconds).toFloat()
            val e = (TrimSpec.parseTime(end) ?: durationSeconds).coerceIn(0.0, durationSeconds).toFloat()
            RangeSlider(
                value = minOf(s, e)..maxOf(s, e),
                onValueChange = { range ->
                    onStartChange(if (range.start <= 0.5f) "" else TrimSpec.format(Math.round(range.start).toDouble()))
                    onEndChange(if (range.endInclusive >= durationSeconds.toFloat() - 0.5f) "" else TrimSpec.format(Math.round(range.endInclusive).toDouble()))
                },
                valueRange = 0f..durationSeconds.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = VrkaTokens.Accent,
                    activeTrackColor = VrkaTokens.Accent,
                    inactiveTrackColor = VrkaTokens.SurfaceInset,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0:00", fontFamily = VrkaMonoFamily, fontSize = 11.sp, color = VrkaTokens.TextTertiary)
                Text(
                    "Selected ${TrimSpec.format((e - s).toDouble().coerceAtLeast(0.0))}",
                    fontFamily = VrkaMonoFamily, fontSize = 11.sp, color = VrkaTokens.AccentLight,
                )
                Text(TrimSpec.format(durationSeconds), fontFamily = VrkaMonoFamily, fontSize = 11.sp, color = VrkaTokens.TextTertiary)
            }
        } else if (onProbe != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    probeMessage ?: "Fetch the length to use a range slider.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VrkaTokens.TextSecondary,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                VrkaOutlinedButton(
                    text = if (probing) "Fetching…" else "Get length",
                    onClick = onProbe,
                    enabled = !probing,
                    height = 32.dp,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Precise cut", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Re-encodes at the cut points (frame-accurate, slower). Off = fast keyframe cut.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = VrkaTokens.TextTertiary,
                )
            }
            Checkbox(
                checked = precise,
                onCheckedChange = onPreciseChange,
                colors = CheckboxDefaults.colors(checkedColor = VrkaTokens.Accent, checkmarkColor = Color.White),
            )
        }
        when (validation) {
            is TrimSpec.Validation.Invalid -> Text(
                validation.message,
                color = VrkaTokens.Error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            is TrimSpec.Validation.Ok -> Text(
                TrimSpec.label(start, end, precise).orEmpty(),
                color = VrkaTokens.AccentLight,
                fontFamily = VrkaMonoFamily,
                fontSize = 12.sp,
            )
            TrimSpec.Validation.Off -> Unit
        }
    }
}
