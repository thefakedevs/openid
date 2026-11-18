package ru.lavafrai.openid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.lavafrai.openid.R
import ru.lavafrai.openid.data.repositories.maximport.api.RootImportStage
import ru.lavafrai.openid.domain.models.QrGenerationStage
import ru.lavafrai.openid.navigation.PocketDestination
import ru.lavafrai.openid.ui.PocketUiState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LoadingStatus(state: PocketUiState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LoadingIndicator(Modifier.size(40.dp))
        Text(
            when (state.destination) {
                PocketDestination.IMPORT -> stringResource(R.string.loading_import_check)

                PocketDestination.ROOT_SOURCE ->
                    state.rootStage?.let { rootStageLabel(it) } ?: stringResource(R.string.root_checking)

                PocketDestination.SETUP -> stringResource(R.string.loading_import_save)

                PocketDestination.SETTINGS -> stringResource(R.string.loading_settings)

                else -> state.qrStage?.label() ?: stringResource(R.string.loading_sign_in)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Shared user-facing label for an automatic-import stage, used by the inline row and the overlay. */
@Composable
internal fun rootStageLabel(stage: RootImportStage): String = when (stage) {
    RootImportStage.CHECKING_ROOT -> stringResource(R.string.root_stage_check)
    RootImportStage.LOCATING_MAX -> stringResource(R.string.root_stage_locate)
    RootImportStage.READING_PREFERENCES -> stringResource(R.string.root_stage_prefs)
    RootImportStage.VERIFYING_SESSION -> stringResource(R.string.root_stage_session)
    RootImportStage.PREPARING_DIGITAL_ID -> stringResource(R.string.root_stage_preparing)
    RootImportStage.READING_DIGITAL_ID -> stringResource(R.string.root_stage_material)
    RootImportStage.SCANNING_MEMORY -> stringResource(R.string.root_stage_memory)
}

@Composable
private fun QrGenerationStage.label(): String = when (this) {
    QrGenerationStage.UNLOCKING_ACCOUNT -> stringResource(R.string.loading_account_unlock)
    QrGenerationStage.CONNECTING_MAX -> stringResource(R.string.loading_connect)
    QrGenerationStage.LOGGING_IN -> stringResource(R.string.loading_login)
    QrGenerationStage.RESOLVING_BOT -> stringResource(R.string.loading_resolve_bot)
    QrGenerationStage.GETTING_LAUNCH -> stringResource(R.string.loading_launch)
    QrGenerationStage.CHECKING_SECURITY -> stringResource(R.string.loading_security)
    QrGenerationStage.GETTING_PIN -> stringResource(R.string.loading_pin_read)
    QrGenerationStage.AUTHORIZING -> stringResource(R.string.loading_authorize)
    QrGenerationStage.SAVING_PIN -> stringResource(R.string.loading_pin_save)
    QrGenerationStage.GENERATING_QR -> stringResource(R.string.loading_qr_generate)
    QrGenerationStage.REFRESHING_QR -> stringResource(R.string.loading_qr_refresh)
}
