package com.thepuzzlequest.puzzlequest.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.thepuzzlequest.puzzlequest.core.MarkdownBlock
import com.thepuzzlequest.puzzlequest.core.MarkdownTable
import com.thepuzzlequest.puzzlequest.core.StyledText

/**
 * A text puzzle's Markdown, drawn with the app's own type, so it grows with
 * the phone's font size. The core library reads it into blocks
 * ([MarkdownBlock]); this draws each.
 */
@Composable
fun MarkdownView(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { Block(it) }
    }
}

@Composable
private fun Block(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            Text(
                annotated(block.text),
                style = headingStyle(block.level),
                modifier = Modifier.semantics { heading() },
            )
        }

        is MarkdownBlock.Paragraph -> {
            Text(annotated(block.text), style = MaterialTheme.typography.bodyLarge)
        }

        is MarkdownBlock.Images -> {
            block.images.forEach { RemoteImage(it.url, it.alt.ifEmpty { null }) }
        }

        is MarkdownBlock.ListBlock -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { index, item ->
                    // Read by TalkBack as one item, starting with its number.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {},
                    ) {
                        Text(
                            if (block.ordered) "${block.start + index}." else "•",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                        )
                        MarkdownView(item, Modifier.weight(1f))
                    }
                }
            }
        }

        is MarkdownBlock.Quote -> {
            // The bar beside a quotation, drawn as tall as what it quotes.
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline)
                        .clearAndSetSemantics {},
                )
                MarkdownView(block.blocks, Modifier.weight(1f))
            }
        }

        is MarkdownBlock.CodeBlock -> {
            Text(
                block.code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
            )
        }

        is MarkdownBlock.Table -> {
            MarkdownTableView(block.table)
        }

        MarkdownBlock.Rule -> {
            HorizontalDivider()
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle =
    when (level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.headlineSmall
        3 -> MaterialTheme.typography.titleLarge
        else -> MaterialTheme.typography.titleMedium
    }.copy(fontWeight = FontWeight.Bold)

/** A table, in a grid of columns, scrolling sideways if it's wider than the screen. */
@Composable
private fun MarkdownTableView(table: MarkdownTable) {
    val columns = table.header.size
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (column in 0 until columns) {
            val align =
                when (table.alignments.getOrNull(column)) {
                    MarkdownTable.Alignment.CENTER -> TextAlign.Center
                    MarkdownTable.Alignment.END -> TextAlign.End
                    else -> TextAlign.Start
                }
            Column(
                Modifier.width(IntrinsicSize.Max).widthIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    annotated(table.header[column]),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = align,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                table.rows.forEach { row ->
                    Text(
                        row.getOrNull(column)?.let { annotated(it) } ?: AnnotatedString(""),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Styled text as Compose draws it: each run with its styles, and links that
 * open in the browser (LinkAnnotation, which TalkBack reads as a link).
 */
@Composable
private fun annotated(text: StyledText): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        text.runs.forEach { run ->
            val style =
                SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else null,
                    fontStyle = if (run.italic) FontStyle.Italic else null,
                    fontFamily = if (run.code) FontFamily.Monospace else null,
                    background = if (run.code) codeBackground else androidx.compose.ui.graphics.Color.Unspecified,
                    textDecoration = if (run.strikethrough) TextDecoration.LineThrough else null,
                )
            val link = run.link
            if (link != null) {
                withLink(
                    LinkAnnotation.Url(
                        link,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    ),
                ) { withStyle(style) { append(run.text) } }
            } else {
                withStyle(style) { append(run.text) }
            }
        }
    }
}
