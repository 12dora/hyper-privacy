package io.github.hyperprivacy.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HyperPrivacyTheme {
                val state = viewModel.state.collectAsStateWithLifecycle()
                MainScreen(state = state.value, onToggle = viewModel::setEnabled)
            }
        }
    }
}
