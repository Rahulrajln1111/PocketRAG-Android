package com.pdfapk.ui.navigation

import androidx.compose.animation.*
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pdfapk.ui.screens.ChatScreen
import com.pdfapk.ui.screens.HomeScreen
import com.pdfapk.ui.screens.ModelDownloadScreen
import com.pdfapk.ui.screens.PdfViewerScreen
import com.pdfapk.ui.screens.SettingsScreen
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val HOME = "home"
    const val PDF_VIEWER = "pdf_viewer/{pdfUri}?page={page}"
    const val CHAT = "chat/{pdfUri}"
    const val SETTINGS = "settings"
    const val MODEL_DOWNLOAD = "model_download"

    fun pdfViewer(pdfUri: String, page: Int = 0): String =
        "pdf_viewer/${URLEncoder.encode(pdfUri, "UTF-8")}?page=$page"

    fun chat(pdfUri: String): String =
        "chat/${URLEncoder.encode(pdfUri, "UTF-8")}"
}

@Composable
fun PDFApkNavGraph(
    navController: NavHostController,
    initialPdfUri: String? = null
) {
    val startDestination = if (initialPdfUri != null) {
        Routes.pdfViewer(initialPdfUri)
    } else {
        Routes.HOME
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { slideInHorizontally { it } + fadeIn() },
        exitTransition = { slideOutHorizontally { -it / 3 } + fadeOut() },
        popEnterTransition = { slideInHorizontally { -it / 3 } + fadeIn() },
        popExitTransition = { slideOutHorizontally { it } + fadeOut() }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onPdfSelected = { uri ->
                    navController.navigate(Routes.pdfViewer(uri))
                },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onDownloadModel = { navController.navigate(Routes.MODEL_DOWNLOAD) }
            )
        }

        composable(
            route = Routes.PDF_VIEWER,
            arguments = listOf(
                navArgument("pdfUri") { type = NavType.StringType },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val pdfUri = URLDecoder.decode(
                backStackEntry.arguments?.getString("pdfUri") ?: "",
                "UTF-8"
            )
            val startPage = backStackEntry.arguments?.getInt("page") ?: 0
            PdfViewerScreen(
                pdfUri = pdfUri,
                startPage = startPage,
                onBack = { navController.popBackStack() },
                onChat = { navController.navigate(Routes.chat(pdfUri)) }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("pdfUri") { type = NavType.StringType })
        ) { backStackEntry ->
            val pdfUri = URLDecoder.decode(
                backStackEntry.arguments?.getString("pdfUri") ?: "",
                "UTF-8"
            )
            ChatScreen(
                pdfUri = pdfUri,
                onBack = { navController.popBackStack() },
                onOpenPage = { page ->
                    navController.navigate(Routes.pdfViewer(pdfUri, page))
                }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onDownloadModel = { navController.navigate(Routes.MODEL_DOWNLOAD) }
            )
        }

        composable(Routes.MODEL_DOWNLOAD) {
            ModelDownloadScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
