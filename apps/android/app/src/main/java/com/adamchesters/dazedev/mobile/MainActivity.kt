package com.adamchesters.dazedev.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.adamchesters.dazedev.mobile.ui.DazeDevMobileApp
import com.adamchesters.dazedev.mobile.ui.MainViewModel
import com.adamchesters.dazedev.mobile.ui.theme.DazeDevMobileTheme

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A share survives rotation in the view model; only a fresh start reads it from the intent.
        if (savedInstanceState == null) shared(intent)
        setContent {
            DazeDevMobileTheme {
                DazeDevMobileApp(mainViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    private fun shared(intent: Intent?) {
        val (uris, text) = ShareActivity.shared(intent) ?: return
        mainViewModel.shared(uris, text)
    }
}
