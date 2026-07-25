package com.syncsound.app.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.syncsound.app.data.SessionRepository

/**
 * Defines the complete MVP navigation graph: role selection, Host, and Client.
 */
@Composable
fun SyncSoundApp(repository: SessionRepository) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onCreateSession = { navController.navigate("host") },
                onJoinSession = { navController.navigate("client") },
            )
        }
        composable("host") {
            val host: HostViewModel = viewModel(factory = HostViewModelFactory(repository))
            HostScreen(
                viewModel = host,
                onBack = { navController.popBackStack() },
            )
        }
        composable("client") {
            val client: ClientViewModel = viewModel(factory = ClientViewModelFactory(repository))
            ClientScreen(
                viewModel = client,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
