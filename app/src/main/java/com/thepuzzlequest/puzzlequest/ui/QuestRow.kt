package com.thepuzzlequest.puzzlequest.ui

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.R
import com.thepuzzlequest.puzzlequest.core.QuestClock
import com.thepuzzlequest.puzzlequest.core.QuestStatus
import com.thepuzzlequest.puzzlequest.core.QuestSummary
import com.thepuzzlequest.puzzlequest.ui.theme.PuzzleQuestTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.Locale

/**
 * A quest in the player's list: its name, where it stands, and its time. It
 * says nothing about how many stops it has.
 */
@Composable
fun QuestRow(
    quest: QuestSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // mergeDescendants: TalkBack reads the row as one item.
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) {}
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(quest.displayName, style = MaterialTheme.typography.titleMedium)
        // Side by side, wrapping when the text is too large for one line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (quest.paused) {
                // In words and with an icon, not by color. The server's
                // description says "Paused" too, so it isn't shown twice.
                IconAndText(R.drawable.ic_paused, stringResource(R.string.paused), style)
            } else {
                Text(quest.statusDescription, style = style)
            }
            QuestTime(quest.progress, style)
        }
    }
}

/** A quest's total time (time spent plus penalties), counting up each second while its clock runs. */
@Composable
fun QuestTime(
    clock: QuestClock,
    style: TextStyle,
) {
    // Redraws on each second of the quest's own clock. TalkBack doesn't read
    // out the change; it reads the time when it reaches the row.
    val now by produceState(Instant.now(), clock) {
        while (clock.isCounting) {
            value = Instant.now()
            delay(1_000 - Math.floorMod(clock.elapsedMs(value), 1_000L))
        }
    }
    val milliseconds = clock.totalMs(now)
    val spoken = stringResource(R.string.quest_time, spoken(milliseconds))
    IconAndText(
        R.drawable.ic_timer,
        QuestClock.format(milliseconds),
        // Digits of equal width, so the time doesn't jiggle as it counts.
        style.copy(fontFeatureSettings = "tnum"),
        // "Time: 1 hour, 4 minutes, 5 seconds" rather than "1:04:05".
        Modifier.semantics { contentDescription = spoken },
    )
}

@Composable
private fun IconAndText(
    icon: Int,
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp), tint = style.color)
        Text(text, style = style)
    }
}

/** A time in words, in the phone's language, such as "1 hour, 4 minutes, 5 seconds". */
private fun spoken(milliseconds: Long): String {
    val seconds = maxOf(0, milliseconds / 1000)
    val parts =
        listOf(
            seconds / 3600 to MeasureUnit.HOUR,
            seconds / 60 % 60 to MeasureUnit.MINUTE,
            seconds % 60 to MeasureUnit.SECOND,
        ).filter { (amount, _) -> amount > 0 }
            .ifEmpty { listOf(0L to MeasureUnit.SECOND) }
            .map { (amount, unit) -> Measure(amount, unit) }
    return MeasureFormat
        .getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.WIDE)
        .formatMeasures(*parts.toTypedArray())
}

@Preview(showBackground = true)
@Composable
private fun QuestRowPreview() {
    PuzzleQuestTheme {
        Column {
            QuestRow(
                QuestSummary(
                    "harbor-walk",
                    "Harbor walk",
                    QuestStatus.IN_PROGRESS,
                    "In progress",
                    paused = false,
                    progress = QuestClock(true, Instant.now().minusSeconds(3845), 0, 60_000, 0),
                ),
                onClick = {},
            )
            QuestRow(
                QuestSummary(
                    "museum-mile",
                    "Museum mile",
                    QuestStatus.IN_PROGRESS,
                    "Paused",
                    paused = true,
                    progress = QuestClock(false, null, 600_000, 0, 600_000),
                ),
                onClick = {},
            )
        }
    }
}
