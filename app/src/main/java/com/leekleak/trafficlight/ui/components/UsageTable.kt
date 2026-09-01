package com.leekleak.trafficlight.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.leekleak.trafficlight.R
import com.leekleak.trafficlight.ui.theme.Spacing
import com.leekleak.trafficlight.util.DataSize
import com.leekleak.trafficlight.util.formatted

/**
 * Shared column widths for every usage table in the app (History, Apps, App Detail) - one
 * set of numbers so a "Download" column lines up the same way no matter which screen it's
 * on, rather than each screen picking its own widths.
 */
private val NumericColumnWidth: Dp = 60.dp
private val TotalColumnWidth: Dp = 72.dp

/**
 * The header row for a usage table: a leading label (e.g. "Date" or "App") plus
 * Download/Upload/Total column headers at the same fixed widths [UsageTableRow] uses for
 * its values, so header and data always line up.
 */
@Composable
fun UsageTableHeader(
    leadingLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = leadingLabel,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ColumnHeaderText(stringResource(R.string.download), NumericColumnWidth)
        ColumnHeaderText(stringResource(R.string.upload), NumericColumnWidth)
        ColumnHeaderText(stringResource(R.string.total), TotalColumnWidth)
    }
}

@Composable
private fun ColumnHeaderText(text: String, width: Dp) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        style = MaterialTheme.typography.labelMedium,
        color = colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.End,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One data row of a usage table: whatever identifies the row (a date label, or an app's
 * icon+name) in a flexible leading area, then Download/Upload/Total at fixed widths so the
 * numbers stay aligned down the whole list regardless of how wide the leading content is.
 * Large values (999.9 GB, 1.24 TB) and long leading labels never break this: the leading
 * area truncates with an ellipsis and the numeric columns never wrap.
 */
@Composable
fun UsageTableRow(
    download: Long,
    upload: Long,
    total: Long,
    totalColor: Color,
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            content = leading,
        )
        Text(
            text = DataSize(download).formatted(),
            modifier = Modifier.width(NumericColumnWidth),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = DataSize(upload).formatted(),
            modifier = Modifier.width(NumericColumnWidth),
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = DataSize(total).formatted(),
            modifier = Modifier.width(TotalColumnWidth),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = totalColor,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
