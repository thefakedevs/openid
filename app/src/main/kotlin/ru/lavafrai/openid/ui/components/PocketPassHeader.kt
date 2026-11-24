package ru.lavafrai.openid.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R

/**
 * The rounded brand card shown at the top of the import flow (legacy "PocketPass"). Reused across
 * every first-run step so the automatic and manual flows share one look: a small app label, a
 * screen title and the tilted QR badge.
 */
@Composable
fun PocketPassHeader(
    title: String,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.app_name),
    @DrawableRes icon: Int = Symbols.drawable.materialsymbols_ic_qr_code_2_rounded,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Surface(
                modifier = Modifier.rotate(-10f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                    PocketIcon(
                        icon,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}
