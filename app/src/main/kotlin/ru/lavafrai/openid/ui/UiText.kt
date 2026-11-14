package ru.lavafrai.openid.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** UI state keeps resource references so text follows the current configuration. */
data class UiText(
    @StringRes val resourceId: Int = 0,
    val arguments: List<Any> = emptyList(),
    val direct: String? = null,
) {
    fun resolve(context: Context): String = direct ?: context.getString(
        resourceId,
        *arguments.map { if (it is UiText) it.resolve(context) else it }.toTypedArray(),
    )
}

@Composable
fun UiText.resolve(): String = direct ?: stringResource(
    resourceId,
    *arguments.map { if (it is UiText) it.resolve() else it }.toTypedArray(),
)
