package ru.lavafrai.openid

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import org.koin.androidx.viewmodel.ext.android.viewModel
import ru.lavafrai.openid.ui.PocketApp
import ru.lavafrai.openid.ui.PocketViewModel
import ru.lavafrai.openid.ui.theme.OpenIDTheme

class MainActivity : FragmentActivity() {
    private val pocketViewModel: PocketViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OpenIDTheme {
                PocketApp(pocketViewModel)
            }
        }
    }
}
