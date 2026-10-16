package ru.lavafrai.openid.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** UI state keeps resource references so text follows the current configuration. */
data class UiText(
    @StringRes val resourceId: Int,
    val arguments: List<Any> = emptyList(),
) {
    fun resolve(context: Context): String = context.getString(
        resourceId,
        *arguments.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
    )
}

@Composable
fun UiText.resolve(): String = stringResource(
    resourceId,
    *arguments.map { if (it is UiText) it.resolve() else it }.toTypedArray(),
)
