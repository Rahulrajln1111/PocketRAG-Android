package com.pdfapk.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pdfapk.rag.SourceRef
import com.pdfapk.viewmodel.ChatViewModel.ChatMessage

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onSourceClick: (Int) -> Unit = {}
) {
    val isUser = message.role == ChatMessage.Role.USER
    val isAssistant = message.role == ChatMessage.Role.ASSISTANT
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val shape = if (isUser) {
        RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 4.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
    }
    val containerColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalAlignment = alignment
    ) {
        // Role label
        Text(
            text = if (isUser) "You" else if (message.isLoading) "Thinking…" else "AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )

        // Message content
        Surface(
            shape = shape,
            color = containerColor,
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            if (message.isLoading) {
                // Loading shimmer
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⏳",
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Generating…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f)
                    )
                }
            } else {
                SelectionContainer {
                    Text(
                        text = message.content.ifEmpty { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }

        // Page citations: tap to open that page of the PDF.
        if (message.sources.isNotEmpty()) {
            SourceChipRow(message.sources, onSourceClick)
        }
    }
}

/**
 * Citation chips for a grounded answer. Each chip is the page the excerpt came
 * from, so a claim can be checked in two taps instead of a manual search.
 */
@Composable
fun SourceChipRow(
    sources: List<SourceRef>,
    onSourceClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        sources.forEach { source ->
            AssistChip(
                onClick = { onSourceClick(source.pageIndex) },
                label = {
                    Text(
                        text = "p.${source.pageIndex + 1}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }
    }
}

/**
 * A streaming message variant that shows the partial text as it arrives
 * with a subtle cursor animation.
 */
@Composable
fun StreamingMessageBubble(
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "AI",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )

        Surface(
            shape = RoundedCornerShape(
                topStart = 4.dp,
                topEnd = 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            SelectionContainer {
                Text(
                    text = text + " ▌",  // cursor
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
