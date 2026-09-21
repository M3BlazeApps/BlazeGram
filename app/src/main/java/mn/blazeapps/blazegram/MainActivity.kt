package mn.blazeapps.blazegram

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import mn.blazeapps.blazegram.ui.screens.HomeScreen
import mn.blazeapps.blazegram.ui.screens.SettingsScreen
import mn.blazeapps.blazegram.ui.theme.BlazeGramTheme
import mn.blazeapps.blazegram.ui.viewmodel.TelegramViewModel
import mn.blazeapps.blazegram.ui.viewmodel.UploadViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            BlazeGramTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BlazeGramApp()
                }
            }
        }
    }
}

@Composable
fun BlazeGramApp(
    viewModel: TelegramViewModel = viewModel(),
    uploadViewModel: UploadViewModel = viewModel()
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                uploadViewModel = uploadViewModel,
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                uploadViewModel = uploadViewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}