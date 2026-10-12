package com.adamchesters.dazedev.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.adamchesters.dazedev.mobile.ui.MainViewModel
import com.adamchesters.dazedev.mobile.ui.DazeDevMobileApp
import com.adamchesters.dazedev.mobile.ui.theme.DazeDevMobileTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DazeDevMobileTheme {
                val mainViewModel: MainViewModel = viewModel()
                DazeDevMobileApp(mainViewModel)
            }
        }
    }
}
