package com.thepuzzlequest.puzzlequest.core

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * A text puzzle's Markdown, read into blocks for the app to draw, with the
 * same GitHub-flavored Markdown the web app shows. HTML in it shows as plain
 * text, as on the web, so an author can't put anything on screen that isn't
 * Markdown.
 */
sealed interface MarkdownBlock {
    data class Heading(
        val level: Int,
        val text: StyledText,
    ) : MarkdownBlock

    data class Paragraph(
        val text: StyledText,
    ) : MarkdownBlock

    /** A paragraph of nothing but pictures, shown as pictures. */
    data class Images(
        val images: List<MarkdownImage>,
    ) : MarkdownBlock

    /** Each item is blocks of its own. */
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<List<MarkdownBlock>>,
    ) : MarkdownBlock

    data class Quote(
        val blocks: List<MarkdownBlock>,
    ) : MarkdownBlock

    data class CodeBlock(
        val code: String,
    ) : MarkdownBlock

    data class Table(
        val table: MarkdownTable,
    ) : MarkdownBlock

    data object Rule : MarkdownBlock

    companion object {
        private val parser =
            Parser
                .builder()
                .extensions(
                    listOf(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()),
                ).build()

        /**
         * Reads Markdown.
         *
         * @param server the site, which relative links and pictures are on.
         */
        fun blocks(
            markdown: String,
            server: String,
        ): List<MarkdownBlock> = Reader(server).blocks(parser.parse(markdown).children())
    }
}

data class MarkdownImage(
    val url: String,
    /** The picture's description, for TalkBack. */
    val alt: String,
)

data class MarkdownTable(
    /** Each column's alignment; null for the default. */
    val alignments: List<Alignment?>,
    val header: List<StyledText>,
    val rows: List<List<StyledText>>,
) {
    enum class Alignment { START, CENTER, END }
}

/** Text with styles on parts of it: runs of text, each styled its own way. */
data class StyledText(
    val runs: List<Run>,
) {
    /** A run of text, all styled the same way. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strikethrough: Boolean = false,
        /** Where it links to, if it's a link. */
        val link: String? = null,
    )

    /** The text without its styles. */
    val plain: String get() = runs.joinToString("") { it.text }

    operator fun plus(other: StyledText) = StyledText(runs + other.runs)

    companion object {
        fun plain(text: String) = StyledText(listOf(Run(text)))
    }
}

private class Reader(
    private val server: String,
) {
    fun blocks(nodes: Sequence<Node>): List<MarkdownBlock> = nodes.mapNotNull(::block).toList()

    private fun block(node: Node): MarkdownBlock? =
        when (node) {
            is Heading -> {
                MarkdownBlock.Heading(node.level, inlines(node.children()))
            }

            is Paragraph -> {
                paragraph(node)
            }

            is BulletList -> {
                MarkdownBlock.ListBlock(ordered = false, start = 1, items = items(node.children()))
            }

            is OrderedList -> {
                MarkdownBlock.ListBlock(
                    ordered = true,
                    start = node.markerStartNumber ?: 1,
                    items = items(node.children()),
                )
            }

            is BlockQuote -> {
                MarkdownBlock.Quote(blocks(node.children()))
            }

            is FencedCodeBlock -> {
                MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"))
            }

            is IndentedCodeBlock -> {
                MarkdownBlock.CodeBlock(node.literal.removeSuffix("\n"))
            }

            is HtmlBlock -> {
                MarkdownBlock.Paragraph(StyledText.plain(node.literal.removeSuffix("\n")))
            }

            is TableBlock -> {
                table(node)
            }

            is ThematicBreak -> {
                MarkdownBlock.Rule
            }

            else -> {
                null
            }
        }

    /** A paragraph, or if it's nothing but pictures (and the spaces between), the pictures. */
    private fun paragraph(paragraph: Paragraph): MarkdownBlock {
        val children = paragraph.children().toList()
        val images = children.filterIsInstance<Image>()
        val onlyImages =
            images.isNotEmpty() &&
                children.all {
                    it is Image || it is SoftLineBreak || it is HardLineBreak || (it is Text && it.literal.isBlank())
                }
        if (onlyImages) {
            val found =
                images.mapNotNull { image ->
                    resolve(image.destination, server)?.let { MarkdownImage(it, plainText(image)) }
                }
            if (found.size == images.size) return MarkdownBlock.Images(found)
        }
        return MarkdownBlock.Paragraph(inlines(children.asSequence()))
    }

    private fun items(nodes: Sequence<Node>): List<List<MarkdownBlock>> =
        nodes
            .filterIsInstance<ListItem>()
            .map { item ->
                val children = item.children().toList()
                val marker = children.filterIsInstance<TaskListItemMarker>().firstOrNull()
                val content = blocks(children.asSequence().filter { it !is TaskListItemMarker }).toMutableList()
                if (marker != null) {
                    // A task list's box, as a character TalkBack reads.
                    val box = StyledText.plain(if (marker.isChecked) "☑ " else "☐ ")
                    val first = content.firstOrNull()
                    if (first is MarkdownBlock.Paragraph) {
                        content[0] = MarkdownBlock.Paragraph(box + first.text)
                    } else {
                        content.add(0, MarkdownBlock.Paragraph(box))
                    }
                }
                content
            }.toList()

    private fun table(table: TableBlock): MarkdownBlock {
        val head = table.children().filterIsInstance<TableHead>().firstOrNull()
        val headerCells =
            head
                ?.children()
                ?.filterIsInstance<TableRow>()
                ?.firstOrNull()
                ?.children()
                ?.filterIsInstance<TableCell>()
                ?.toList()
                .orEmpty()
        val rows =
            table
                .children()
                .filterIsInstance<TableBody>()
                .flatMap { it.children().filterIsInstance<TableRow>() }
                .map { row ->
                    row
                        .children()
                        .filterIsInstance<TableCell>()
                        .map { inlines(it.children()) }
                        .toList()
                }.toList()
        return MarkdownBlock.Table(
            MarkdownTable(
                alignments =
                    headerCells.map { cell ->
                        when (cell.alignment) {
                            TableCell.Alignment.LEFT -> MarkdownTable.Alignment.START
                            TableCell.Alignment.CENTER -> MarkdownTable.Alignment.CENTER
                            TableCell.Alignment.RIGHT -> MarkdownTable.Alignment.END
                            null -> null
                        }
                    },
                header = headerCells.map { inlines(it.children()) },
                rows = rows,
            ),
        )
    }

    /** Inline text, with its styles: each style is added to whatever the text inside already has. */
    private fun inlines(nodes: Sequence<Node>): StyledText = StyledText(nodes.flatMap { inline(it).runs }.toList())

    private fun inline(node: Node): StyledText =
        when (node) {
            is Text -> {
                StyledText.plain(node.literal)
            }

            is SoftLineBreak -> {
                StyledText.plain(" ")
            }

            is HardLineBreak -> {
                StyledText.plain("\n")
            }

            is Code -> {
                StyledText(listOf(StyledText.Run(node.literal, code = true)))
            }

            is Emphasis -> {
                styled(node) { it.copy(italic = true) }
            }

            is StrongEmphasis -> {
                styled(node) { it.copy(bold = true) }
            }

            is Strikethrough -> {
                styled(node) { it.copy(strikethrough = true) }
            }

            is Link -> {
                val url = resolve(node.destination, server, setOf("http", "https", "mailto"))
                styled(node) { if (url != null) it.copy(link = url) else it }
            }

            // A picture within a sentence shows as its description.
            is Image -> {
                StyledText.plain(plainText(node))
            }

            is HtmlInline -> {
                StyledText.plain(node.literal)
            }

            else -> {
                inlines(node.children())
            }
        }

    private fun styled(
        node: Node,
        style: (StyledText.Run) -> StyledText.Run,
    ) = StyledText(inlines(node.children()).runs.map(style))

    /** A node's text without its styles, such as a picture's description. */
    private fun plainText(node: Node): String = inlines(node.children()).plain
}

/** A node's children, in order. */
private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }
