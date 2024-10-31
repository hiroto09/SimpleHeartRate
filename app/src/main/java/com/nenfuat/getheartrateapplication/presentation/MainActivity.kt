package com.nenfuat.getheartrateapplication.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.nenfuat.getheartrateapplication.presentation.theme.GetHeartRateApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity(), SensorEventListener {
    // センサマネージャ
    private lateinit var sensorManager: SensorManager
    private var HeartRateSensor: Sensor? = null

    // Wear OS固有のID(Android ID)
    private val androidId by lazy { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
    //心拍取得の状態管理
    private var isHeartRateMonitoring: MutableState<Boolean> = mutableStateOf(false)

    // 心拍数表示用
    var heartRateData: MutableState<String> = mutableStateOf("タップで計測開始")
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // パーミッションが許可された場合、センサーの登録を行う
                HeartRateSensor?.also { heartRateSensor ->
                    sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
                }
            } else {
                // パーミッションが拒否された場合
                Log.e("MainActivity", "パーミッションが拒否されました。")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearApp(heartRateData, ::GetStart)
        }
        //画面が勝手に切れないように
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // センサの初期設定
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        HeartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        Log.d("AndroidId",androidId)

        // パーミッションの確認とリクエスト
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BODY_SENSORS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // パーミッションが既に許可されている場合、センサーの登録
            HeartRateSensor?.also { heartRateSensor ->
                sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } else {
            // パーミッションをリクエスト
            requestPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        }
    }



    // センサの値が変更されたときに呼ばれる
    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && isHeartRateMonitoring.value ) {
            if (event.sensor.type == Sensor.TYPE_HEART_RATE) {
                heartRateData.value = "${event.values[0]}"
            }
            sendHeartRateToServer(heartRateData.value)
        }
    }

    // センサの精度が変更されたときに呼ばれる(今回は何もしない)
    override fun onAccuracyChanged(event: Sensor?, p1: Int) {}

    override fun onResume() {
        super.onResume()
        HeartRateSensor?.also { heartRateSensor ->
            sensorManager.registerListener(this, heartRateSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }


    // 心拍情報を送信する処理
    private fun sendHeartRateToServer(heartRate: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://hartlink-websocket-api.onrender.com/data")

                with(url.openConnection() as HttpURLConnection) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; utf-8")

                    // JSONオブジェクトに整数型で心拍数を追加
                    val jsonInputString = JSONObject().apply {
                        put("player", androidId)
                        put("heartRate", heartRate)
                    }.toString()

                    outputStream.use { os ->
                        val input = jsonInputString.toByteArray(Charsets.UTF_8)
                        os.write(input, 0, input.size)
                    }

                    //心拍情報に対するレスポンス
                    val response = inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                        br.readLines().joinToString("0")
                    }

                    val jsonResponse = JSONObject(response)



                    if (jsonResponse.getString("status") == "end") {

                    }
                }
            }
            catch (e: Exception) {
                Log.e("HTTP Error", e.message ?: "Unknown error")
            }
        }
    }

    private fun GetStart() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!isHeartRateMonitoring.value) {
                try {
                    val url = URL("https://hartlink-websocket-api.onrender.com/id")
                    with(url.openConnection() as HttpURLConnection) {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; utf-8")

                        //jsonオブジェクトにAndroidIdを追加
                        val jsonInputString = JSONObject().apply {
                            put("id", androidId)
                        }.toString()

                        outputStream.use { os ->
                            val input = jsonInputString.toByteArray(Charsets.UTF_8)
                            os.write(input, 0, input.size)
                        }
                    }
                }
                //送信できなかったらエラー
                catch (e: Exception) {
                    Log.e("HTTP Error", e.message ?: "Unknown error")
                }
                delay(1000)
                isHeartRateMonitoring.value = true
                heartRateData.value = "0.0"
            } else if (isHeartRateMonitoring.value) {
                isHeartRateMonitoring.value = false
                heartRateData.value = "計測終了"
                delay(1000)
                heartRateData.value = "タップで計測開始"
            }
        }
    }



}


@Composable
fun WearApp(
    heartRateData: MutableState<String>,
    GetStart:() -> Unit,

) {
    GetHeartRateApplicationTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .clickable {GetStart() }  ,
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            Greeting(heartRateData = heartRateData)
        }
    }
}

@Composable
fun Greeting(heartRateData: MutableState<String>) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colors.primary,
        text = heartRateData.value
    )
}