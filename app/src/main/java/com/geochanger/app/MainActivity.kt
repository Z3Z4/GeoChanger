package com.geochanger.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent {
            MaterialTheme {
                MapScreen(
                    initialLat = prefs.getFloat(PREF_LAT, DEFAULT_LAT).toDouble(),
                    initialLon = prefs.getFloat(PREF_LON, DEFAULT_LON).toDouble(),
                    initialZoom = prefs.getFloat(PREF_ZOOM, DEFAULT_ZOOM)
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(PREF_LAT, MapMemory.lat.toFloat())
            .putFloat(PREF_LON, MapMemory.lon.toFloat())
            .putFloat(PREF_ZOOM, MapMemory.zoom.toFloat())
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "geochanger"
        private const val PREF_LAT = "lat"
        private const val PREF_LON = "lon"
        private const val PREF_ZOOM = "zoom"
        private const val DEFAULT_LAT = 55.7558f
        private const val DEFAULT_LON = 37.6173f
        private const val DEFAULT_ZOOM = 10.0f
    }
}

/** Последняя позиция карты — используется, чтобы сохранить её при выходе. */
object MapMemory {
    @Volatile var lat: Double = 55.7558
    @Volatile var lon: Double = 37.6173
    @Volatile var zoom: Double = 10.0

    fun save(map: MapView) {
        lat = map.mapCenter.latitude
        lon = map.mapCenter.longitude
        zoom = map.zoomLevelDouble
    }
}

@Composable
fun MapScreen(initialLat: Double, initialLon: Double, initialZoom: Float) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val running by MockState.isRunning.collectAsState()

    var center by remember { mutableStateOf(GeoPoint(initialLat, initialLon)) }
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<NominatimClient.Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var showDevDialog by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val map = remember {
        MapView(context).apply {
            Configuration.getInstance().userAgentValue = context.packageName
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(initialZoom.toDouble())
            controller.setCenter(GeoPoint(initialLat, initialLon))
            MapMemory.save(this)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    center = GeoPoint(this@apply.mapCenter.latitude, this@apply.mapCenter.longitude)
                    MapMemory.save(this@apply)
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    MapMemory.save(this@apply)
                    return false
                }
            })
        }
    }

    fun startMock() {
        if (!MockLocationHelper.canMock(context)) {
            showDevDialog = true
            return
        }
        val target = map.mapCenter
        ContextCompat.startForegroundService(
            context,
            Intent(context, MockLocationService::class.java)
                .putExtra(MockLocationService.EXTRA_LAT, target.latitude)
                .putExtra(MockLocationService.EXTRA_LON, target.longitude)
        )
        keyboard?.hide()
        focusManager.clearFocus()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startMock()
        } else {
            Toast.makeText(
                context,
                "Без доступа к местоположению подмена не заработает (требование Android 14+)",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Карта на весь экран, элементы управления — плавающие поверх неё
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())

        // перекрестие: окружность в центре + пин, чей кончик указывает на центр
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.25f),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.9f)),
            modifier = Modifier
                .size(38.dp)
                .align(Alignment.Center)
        ) {}
        Icon(
            Icons.Default.LocationOn,
            contentDescription = "Выбранная точка",
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-15).dp)
                .size(30.dp)
        )

        // Поиск: плавающая карточка под статус-баром
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextField(
                    value = query,
                    onValueChange = { q ->
                        query = q
                        searchJob?.cancel()
                        suggestions = emptyList()
                        if (q.length >= 2) {
                            searchJob = scope.launch {
                                delay(600)
                                searching = true
                                try {
                                    suggestions = NominatimClient.search(q)
                                } catch (_: Exception) {
                                } finally {
                                    searching = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Поиск города или адреса", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    trailingIcon = {
                        if (searching) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (query.isNotEmpty()) {
                            IconButton({ query = ""; suggestions = emptyList() }) {
                                Icon(Icons.Default.Close, contentDescription = "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }

            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(suggestions, key = { "${it.lat}_${it.lon}" }) { place ->
                            Text(
                                place.displayName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        map.controller.setZoom(11.5)
                                        map.controller.animateTo(GeoPoint(place.lat, place.lon))
                                        query = ""
                                        suggestions = emptyList()
                                        keyboard?.hide()
                                        focusManager.clearFocus()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        }

        // Координаты + кнопка: плавающие над панелью навигации и клавиатурой
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.60f),
                contentColor = Color.White
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        if (running) "Подмена активна: %.5f, %.5f" else "%.5f, %.5f",
                        center.latitude, center.longitude
                    ),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    if (running) {
                        context.stopService(Intent(context, MockLocationService::class.java))
                    } else if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        startMock()
                    } else {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        )
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = if (running) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) else ButtonDefaults.buttonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    if (running) "Остановить" else "Установить позицию",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    if (showDevDialog) {
        AlertDialog(
            onDismissRequest = { showDevDialog = false },
            title = { Text("Нужна настройка телефона") },
            text = {
                Text(
                    "1. Настройки → О телефоне → нажмите 7 раз на «Номер сборки».\n\n" +
                        "2. Настройки → Для разработчиков → «Приложение для фиктивных местоположений» " +
                        "(иногда называется «Выбрать приложение для фиктивных местоположений»).\n\n" +
                        "3. Выберите GeoChanger.\n\n" +
                        "4. Вернитесь в приложение и нажмите «Установить позицию» ещё раз."
                )
            },
            confirmButton = {
                TextButton({ showDevDialog = false }) { Text("Понятно") }
            }
        )
    }
}
