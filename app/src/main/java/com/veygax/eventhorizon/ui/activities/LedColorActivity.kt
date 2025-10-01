package com.veygax.eventhorizon.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.veygax.eventhorizon.system.TweakService
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.launch
import java.io.File

class LedColorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val useDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ctx = LocalContext.current
                if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (useDarkTheme) darkColorScheme() else lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LedColorScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedColorScreen() {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val sharedPrefs = remember { context.getSharedPreferences("eventhorizon_prefs", Context.MODE_PRIVATE) }

    var red by remember { mutableStateOf(sharedPrefs.getInt("led_red", 255).toFloat()) }
    var green by remember { mutableStateOf(sharedPrefs.getInt("led_green", 255).toFloat()) }
    var blue by remember { mutableStateOf(sharedPrefs.getInt("led_blue", 255).toFloat()) }

    val currentColor = Color(red.toInt(), green.toInt(), blue.toInt())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom LED Color") },
                navigationIcon = {
                    IconButton(onClick = { activity?.finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.padding(innerPadding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(color = currentColor, shape = MaterialTheme.shapes.medium)
            )

            ColorSlider(label = "Red", value = red, onValueChange = { red = it }, color = Color.Red)
            ColorSlider(label = "Green", value = green, onValueChange = { green = it }, color = Color.Green)
            ColorSlider(label = "Blue", value = blue, onValueChange = { blue = it }, color = Color.Blue)

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    // Save preferences
                    sharedPrefs.edit()
                        .putInt("led_red", red.toInt())
                        .putInt("led_green", green.toInt())
                        .putInt("led_blue", blue.toInt())
                        .putBoolean("custom_led_active", true)
                        .apply()

                    // Create an intent with the color data to pass back
                    val resultIntent = Intent().apply {
                        putExtra("RED", red.toInt())
                        putExtra("GREEN", green.toInt())
                        putExtra("BLUE", blue.toInt())
                    }
                    
                    // Send the data back and finish immediately
                    activity?.setResult(Activity.RESULT_OK, resultIntent)
                    activity?.finish()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Set Color")
            }
        }
    }
}

@Composable
fun ColorSlider(label: String, value: Float, onValueChange: (Float) -> Unit, color: Color) {
    Column {
        Text(text = "$label: ${value.toInt()}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..255f,
            steps = 254,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color
            )
        )
    }
}