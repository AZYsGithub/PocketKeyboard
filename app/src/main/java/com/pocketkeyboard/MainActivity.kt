package com.pocketkeyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.pocketkeyboard.ui.screens.MainScreen
import com.pocketkeyboard.ui.theme.PocketKeyboardTheme
import com.pocketkeyboard.viewmodel.KeyboardViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: KeyboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PocketKeyboardTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
