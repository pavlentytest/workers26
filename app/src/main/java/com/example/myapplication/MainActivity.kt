package com.example.myapplication

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhotoProcessingScreen()
                }
            }
        }
    }
}



class CompressPhotoWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        delay(2200) // имитация сжатия
        val originalPath = inputData.getString(KEY_INPUT_PATH) ?: return Result.failure()

        val compressedPath = "/storage/emulated/0/Pictures/compressed_${System.currentTimeMillis()}.jpg"

        return Result.success(workDataOf(
            KEY_COMPRESSED_PATH to compressedPath
        ))
    }

    companion object {
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_COMPRESSED_PATH = "compressed_path"
    }
}

class AddWatermarkWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        delay(1800) // hard work!

        val compressedPath = inputData.getString(CompressPhotoWorker.KEY_COMPRESSED_PATH)
            ?: return Result.failure()

        val watermarkedPath = compressedPath.replace(".jpg", "_watermarked.jpg")

        return Result.success(workDataOf(
            KEY_WATERMARKED_PATH to watermarkedPath
        ))
    }

    companion object {
        const val KEY_WATERMARKED_PATH = "watermarked_path"
    }
}

class UploadToCloudWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        delay(3000) // имитация загрузки

        val finalPath = inputData.getString(AddWatermarkWorker.KEY_WATERMARKED_PATH)
            ?: return Result.failure()

        Log.d("UploadWorker", "Файл загружен: $finalPath")

        return Result.success(workDataOf(
            KEY_FINAL_URL to "https://cloud.example.com/uploaded_${System.currentTimeMillis()}.jpg"
        ))
    }

    companion object {
        const val KEY_FINAL_URL = "final_url"
    }
}



@Composable
fun PhotoProcessingScreen() {
    val context = LocalContext.current
    val workManager = remember { WorkManager.getInstance(context) }

    var status by remember { mutableStateOf("Готов к обработке") }
    var isProcessing by remember { mutableStateOf(false) }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var uploadWorkId by remember { mutableStateOf<UUID?>(null) }

    val workInfo by uploadWorkId?.let { id ->
        workManager.getWorkInfoByIdLiveData(id)
    }?.observeAsState(initial = null) ?: remember { mutableStateOf(null) }

    LaunchedEffect(workInfo) {
        val info = workInfo ?: return@LaunchedEffect

        when (info.state) {
            WorkInfo.State.ENQUEUED -> {
                status = "В очереди..."
                isProcessing = true
            }
            WorkInfo.State.RUNNING -> {
                status = "Обработка и загрузка фото..."
                isProcessing = true
            }
            WorkInfo.State.SUCCEEDED -> {
                resultUrl = info.outputData.getString(UploadToCloudWorker.KEY_FINAL_URL)
                status = "Фото успешно загружено!"
                isProcessing = false
            }
            WorkInfo.State.FAILED -> {
                error = "Ошибка при обработке или загрузке"
                status = "Не удалось завершить"
                isProcessing = false
            }
            WorkInfo.State.CANCELLED -> {
                status = "Обработка отменена"
                isProcessing = false
            }
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        if (isProcessing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(16.dp))
            Text("Это может занять несколько секунд...", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(48.dp))

        resultUrl?.let { url ->
            Text(
                text = "Ссылка на загруженное фото:\n$url",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
        }

        error?.let { err ->
            Text(
                text = err,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(64.dp))

        Button(
            onClick = {
                val fakePath = "/storage/emulated/0/Pictures/my_photo.jpg"

                val compress = OneTimeWorkRequestBuilder<CompressPhotoWorker>()
                    .setInputData(workDataOf(CompressPhotoWorker.KEY_INPUT_PATH to fakePath))
                    .build()

                val watermark = OneTimeWorkRequestBuilder<AddWatermarkWorker>().build()

                val upload = OneTimeWorkRequestBuilder<UploadToCloudWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .build()

                // Запоминаем ID последнего worker'а
                uploadWorkId = upload.id

                workManager
                    .beginWith(compress)
                    .then(watermark)
                    .then(upload)
                    .enqueue()

                status = "Запущена обработка..."
                isProcessing = true
                resultUrl = null
                error = null
            },
            enabled = !isProcessing
        ) {
            Text("Начать обработку и загрузку")
        }
    }
}