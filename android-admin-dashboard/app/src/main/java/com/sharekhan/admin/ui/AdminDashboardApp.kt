package com.sharekhan.admin.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sharekhan.admin.LocalAppContainer
import com.sharekhan.admin.ui.dashboard.DashboardScreen
import com.sharekhan.admin.ui.dashboard.DashboardViewModel
import com.sharekhan.admin.ui.login.LoginScreen
import com.sharekhan.admin.ui.login.LoginViewModel

@Composable
fun AdminDashboardApp() {
    val navController = rememberNavController()
    val repository = LocalAppContainer.current.repository

    NavHost(
        navController = navController,
        startDestination = AppDestinations.Login.route
    ) {
        composable(AppDestinations.Login.route) {
            val loginViewModel: LoginViewModel =
                viewModel(factory = LoginViewModel.factory(repository))

            LoginScreen(
                viewModel = loginViewModel,
                onLoggedIn = {
                    navController.navigate(AppDestinations.Dashboard.route) {
                        popUpTo(AppDestinations.Login.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppDestinations.Dashboard.route) {
            val dashboardViewModel: DashboardViewModel =
                viewModel(factory = DashboardViewModel.factory(repository))
            DashboardScreen(viewModel = dashboardViewModel)
        }
    }
}

private enum class AppDestinations(val route: String) {
    Login("login"),
    Dashboard("dashboard")
}
