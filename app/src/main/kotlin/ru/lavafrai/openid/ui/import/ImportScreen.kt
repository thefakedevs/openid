package ru.lavafrai.openid.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.rounded.R as Symbols
import ru.lavafrai.openid.R
import ru.lavafrai.openid.ui.ImportForm
import ru.lavafrai.openid.ui.components.PocketIcon
import ru.lavafrai.openid.ui.components.PocketPassHeader
import ru.lavafrai.openid.ui.components.PrimaryButton
import ru.lavafrai.openid.ui.components.ScreenHeading

@Composable
fun ImportScreen(
    form: ImportForm,
    replacing: Boolean,
    busy: Boolean,
    onLinkChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canContinue = !busy && form.link.isNotBlank() && form.password.isNotEmpty()
    val description = stringResource(R.string.import_description)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (replacing) {
            ScreenHeading(stringResource(R.string.profile_new), description)
        } else {
            PocketPassHeader(title = stringResource(R.string.profile_add))
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = form.link,
                onValueChange = onLinkChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.import_link_label)) },
                placeholder = { Text(stringResource(R.string.import_link_hint)) },
                leadingIcon = { PocketIcon(Symbols.drawable.materialsymbols_ic_link_rounded) },
                shape = RoundedCornerShape(20.dp),
                enabled = !busy,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
            )
            OutlinedTextField(
                value = form.password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.import_password_label)) },
                leadingIcon = { PocketIcon(Symbols.drawable.materialsymbols_ic_key_rounded) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }, enabled = !busy) {
                        PocketIcon(
                            if (passwordVisible) {
                                Symbols.drawable.materialsymbols_ic_visibility_off_rounded
                            } else {
                                Symbols.drawable.materialsymbols_ic_visibility_rounded
                            },
                            description = stringResource(
                                if (passwordVisible) R.string.password_hide else R.string.password_show,
                            ),
                        )
                    }
                },
                shape = RoundedCornerShape(20.dp),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canContinue) onNext() }),
                enabled = !busy,
                singleLine = true,
            )
        }
        PrimaryButton(stringResource(R.string.action_continue), onNext, enabled = canContinue)
    }
}
