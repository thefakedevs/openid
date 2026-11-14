package ru.lavafrai.openid.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.models.pin.PinProtection
import ru.lavafrai.openid.ui.components.InfoCard
import ru.lavafrai.openid.ui.components.PinProtectionChoices
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

@Composable
fun SetupScreen(
    pinProtection: PinProtection,
    busy: Boolean,
    replacing: Boolean,
    tokenProfile: Boolean,
    onPinProtection: (PinProtection) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        ScreenHeading(stringResource(R.string.sign_in_method), stringResource(R.string.setup_description))
        if (tokenProfile) {
            InfoCard(
                stringResource(R.string.setup_token_profile),
                icon = Symbols.drawable.materialsymbols_ic_key_rounded,
            )
        } else {
            PinProtectionChoices(pinProtection, !busy, onPinProtection)
            if (pinProtection == PinProtection.BIOMETRIC) {
                Text(
                    stringResource(R.string.setup_biometric_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (replacing) {
            Text(
                stringResource(R.string.setup_replace_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PrimaryButton(
            stringResource(if (replacing) R.string.profile_replace else R.string.profile_save),
            onSave,
            enabled = !busy,
        )
    }
}
