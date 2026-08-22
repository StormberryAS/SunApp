package no.stormberry.sunapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import no.stormberry.sunapp.ui.Sun
import no.stormberry.sunapp.ui.SunApp
import no.stormberry.sunapp.ui.SunAppTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SunAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Sun.Background,
                ) {
                    SunApp(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .consumeWindowInsets(WindowInsets.systemBars),
                    )
                }
            }
        }
    }
}
