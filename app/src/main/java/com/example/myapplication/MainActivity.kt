package com.example.myapplication

import android.Manifest
import android.R
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.ArrayCreatingInputMerger
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.setInputMerger
import androidx.work.workDataOf
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.random.Random
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WeatherReportScreen()
                }
            }
        }
    }
}


// Worker для загрузки одного города

class WeatherCityWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val city = inputData.getString(KEY_CITY) ?: return Result.failure()

        setForegroundAsync(createProgressNotification(applicationContext, "Загружаем погоду для $city..."))

        delay(2500 + (Random.nextLong(1000, 4000))) // имитация разной длительности

        val temp = Random.nextInt(-10, 25)
        val condition = listOf("ясно", "облачно", "дождь", "снег").random()

        return Result.success(workDataOf(
            KEY_CITY to city,
            KEY_TEMP to temp,
            KEY_CONDITION to condition
        ))
    }

    companion object {
        const val KEY_CITY = "city"
        const val KEY_TEMP = "temperature"
        const val KEY_CONDITION = "condition"
    }
}

// Финальный worker — объединение

class CombineWeatherReportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override suspend fun doWork(): Result {
        setForegroundAsync(createProgressNotification(applicationContext, "Формируем итоговый отчёт..."))

        delay(1800)

        val citiesData = inputData.keyValueMap
            .filterKeys { it.startsWith("city_") }
            .map { (key, value) ->
                val city = value as? String ?: ""
                val temp = inputData.getInt("${key}_temp", 0)
                val cond = inputData.getString("${key}_condition") ?: ""
                Triple(city, temp, cond)
            }

        if (citiesData.isEmpty()) return Result.failure()

        val avgTemp = citiesData.map { it.second }.average().toInt()
        val reportText = buildString {
            appendLine("Прогноз погоды:")
            citiesData.forEach { (city, temp, cond) ->
                appendLine("$city: $temp°C, $cond")
            }
            appendLine("\nСредняя температура: $avgTemp°C")
        }

        showFinalNotification(applicationContext, reportText)

        return Result.success(workDataOf("report" to reportText))
    }
}

// Уведомления

private fun createProgressNotification(
    context: Context,           // ← добавляем параметр
    message: String
): ForegroundInfo {
    val channelId = "weather_progress"
    createNotificationChannel(channelId, context)  // тоже передаём контекст, если нужно

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_menu_myplaces)
        .setContentTitle("Сбор прогноза погоды")
        .setContentText(message)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    return ForegroundInfo(1001, notification)
}
@RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
private fun showFinalNotification(
    context: Context,
    report: String
) {
    val channelId = "weather_progress"

    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("weather_report", report)
    }
    val pendingIntent = PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_menu_myplaces)
        .setContentTitle("Прогноз готов")
        .setContentText("Нажмите, чтобы посмотреть")
        .setStyle(NotificationCompat.BigTextStyle().bigText(report))
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()

    NotificationManagerCompat.from(context)
        .notify(1002, notification)
}

private fun createNotificationChannel(channelId: String, context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            channelId,
            "Прогресс погоды",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}


fun startWeatherChain(workManager: WorkManager) {
    val cities = listOf("Москва", "Лондон", "Нью-Йорк")

    val requests = cities.map { city ->
        OneTimeWorkRequestBuilder<WeatherCityWorker>()
            .setInputData(workDataOf(WeatherCityWorker.KEY_CITY to city))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag("weather-task")
            .addTag("city:$city")
            .build()
    }

    val combineRequest = OneTimeWorkRequestBuilder<CombineWeatherReportWorker>()
        .setInputMerger(ArrayCreatingInputMerger::class)
        .build()

    workManager
        .beginWith(requests)
        .then(combineRequest)
        .enqueue()
}

data class CityStatus(
    val status: String,
    val temperature: Int?,
    val condition: String?
)
@Composable
fun WeatherReportScreen() {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    // Состояния
    var isWorking by remember { mutableStateOf(false) }
    var overallStatus by remember { mutableStateOf("Готов начать") }
    var finalReport by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Пример списка городов (можно сделать динамическим)
    val cities = remember { listOf("Москва", "Лондон", "Нью-Йорк") }

    // Состояние по каждому городу
    val cityStates = remember { mutableStateMapOf<String, CityStatus>() }

    // Инициализация состояний городов
    LaunchedEffect(Unit) {
        cities.forEach { city ->
            cityStates[city] = CityStatus("Ожидание", null, null)
        }
    }

    // Наблюдение за всеми работами по тегу
    val workInfos by workManager.getWorkInfosByTagLiveData("weather-task")
        .observeAsState(initial = emptyList())

    LaunchedEffect(workInfos) {
        if (workInfos.isEmpty()) {
            isWorking = false
            return@LaunchedEffect
        }

        val allSucceeded = workInfos.all { it.state == WorkInfo.State.SUCCEEDED }
        val anyFailed = workInfos.any { it.state == WorkInfo.State.FAILED }
        val runningCount = workInfos.count { it.state == WorkInfo.State.RUNNING }

        // Обновляем статусы городов
        workInfos.forEach { info ->
            val city = info.tags.find { it.startsWith("city:") }?.removePrefix("city:") ?: return@forEach

            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    cityStates[city] = CityStatus("Загружается...", null, null)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val temp = info.outputData.getInt(WeatherCityWorker.KEY_TEMP, -999)
                    val cond = info.outputData.getString(WeatherCityWorker.KEY_CONDITION) ?: "—"
                    cityStates[city] = CityStatus("Готово", temp, cond)
                }
                WorkInfo.State.FAILED -> {
                    cityStates[city] = CityStatus("Ошибка", null, null)
                }
                else -> {}
            }
        }

        // Общий статус
        overallStatus = when {
            anyFailed -> "Ошибка при загрузке одного или нескольких городов"
            allSucceeded -> "Все данные получены!"
            runningCount > 0 -> "Загрузка... ($runningCount в процессе)"
            else -> "Ожидание..."
        }

        isWorking = !allSucceeded && !anyFailed

        // Финальный отчёт (можно также смотреть на Combine worker отдельно)
        if (allSucceeded) {
            val temps = cityStates.values.mapNotNull { it.temperature }.ifEmpty { listOf(0) }
            val avg = temps.average().toInt()

            finalReport = buildString {
                append("Итоговый прогноз:\n")
                cityStates.forEach { (city, status) ->
                    if (status.temperature != null) {
                        append("$city: ${status.temperature}°C, ${status.condition}\n")
                    }
                }
                append("\nСредняя температура: $avg°C")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Прогноз погоды",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = overallStatus,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                errorMessage != null -> MaterialTheme.colorScheme.error
                isWorking -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Карточки городов
        cities.forEach { city ->
            val state = cityStates[city] ?: CityStatus("—", null, null)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = city,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (state.status) {
                                "Готово" -> MaterialTheme.colorScheme.primary
                                "Ошибка" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }

                    if (state.status == "Загружается...") {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    } else if (state.status == "Готово" && state.temperature != null) {
                        Text(
                            text = "${state.temperature}°C",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (state.status == "Ошибка") {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Ошибка",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Итоговый отчёт
        finalReport?.let { report ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = report,
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                startWeatherChain(workManager)
                isWorking = true
                overallStatus = "Запущена загрузка..."
                finalReport = null
                errorMessage = null
                // Сброс состояний городов
                cities.forEach { city ->
                    cityStates[city] = CityStatus("Ожидание", null, null)
                }
            },
            enabled = !isWorking,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            Text(if (isWorking) "В процессе..." else "Собрать прогноз")
        }

        if (isWorking) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { workManager.cancelAllWorkByTag("weather-task") },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                Text("Отменить")
            }
        }
    }
}

