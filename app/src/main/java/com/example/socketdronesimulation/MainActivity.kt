package com.example.socketdronesimulation

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "DroneSimPrefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_URL = "wss://webbasedronesimulation.onrender.com" //ws://192.168.1.3:8080
    }

    private lateinit var client: OkHttpClient
    private var webSocket: WebSocket? = null

    // drone.js 기준 기본값 데이터 구조체
    private var currentControls = DroneControls()

    private var isFpvCamera = false
    private var isBottomCamera = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSendingLoopRunning = false
    private var isLeftJoystickActive = false
    private var isRightJoystickActive = false

    private val sendLoopRunnable = object : Runnable {
        override fun run() {
            if (isLeftJoystickActive || isRightJoystickActive) {
                sendControlData()
                mainHandler.postDelayed(this, 50) // 50ms (20Hz) 주기적 연속 송신
            } else {
                isSendingLoopRunning = false
            }
        }
    }

    private fun startSendingLoop() {
        if (!isSendingLoopRunning) {
            isSendingLoopRunning = true
            mainHandler.post(sendLoopRunnable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val joystickLeft = findViewById<JoystickView>(R.id.joystickLeft)
        val joystickRight = findViewById<JoystickView>(R.id.joystickRight)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val btnV = findViewById<Button>(R.id.btnV)
        val btnC = findViewById<Button>(R.id.btnC)
        val btnM = findViewById<Button>(R.id.btnM)
        val btnB = findViewById<Button>(R.id.btnB)

        // 서버 연결 설정 버튼
        btnConnect.setOnClickListener {
            showConnectDialog()
        }

        // 앱 시작 시 저장된 URL 접속 또는 대화상자 표시
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        if (savedUrl == null) {
            showConnectDialog()
        } else {
            connectWebSocket(savedUrl)
        }

        // 왼쪽 조이스틱: 수직 제어부 (Yaw / Throttle)
        joystickLeft.onJoystickMoveListener = { x, y ->
            currentControls.yaw = mapJoystickAxis(x, 19, 244)
            currentControls.throttle = mapJoystickAxis(y, 20, 244)
            isLeftJoystickActive = true
            startSendingLoop()
        }
        joystickLeft.onJoystickReleased = {
            currentControls.yaw = 134
            currentControls.throttle = 134
            isLeftJoystickActive = false
            sendControlData()
        }

        // 오른쪽 조이스틱: 수평 제어부 (Roll / Pitch)
        joystickRight.onJoystickMoveListener = { x, y ->
            currentControls.roll = mapJoystickAxis(x, 19, 241)
            currentControls.pitch = mapJoystickAxis(y, 17, 243)
            isRightJoystickActive = true
            startSendingLoop()
        }
        joystickRight.onJoystickReleased = {
            currentControls.roll = 134
            currentControls.pitch = 134
            isRightJoystickActive = false
            sendControlData()
        }

        // V (출발지점으로 재시작) 버튼 추가 및 클릭 리스너 설정
        btnV.setOnClickListener {
            // 1. 앱 내부 조이스틱 및 컨트롤 변수 초기화 (134 = 원점)
            currentControls.roll = 134
            currentControls.pitch = 134
            currentControls.yaw = 134
            currentControls.throttle = 134
            currentControls.leftSwitch = 0
            currentControls.gear = 100 // 카메라 및 시점 초기화 범위 값

            isFpvCamera = false
            isBottomCamera = false

            joystickLeft.resetJoystick()
            joystickRight.resetJoystick()

            // 2. drone.js에 resetDrone() 실행을 요청하는 웹소켓 전송
            sendControlData(key = "V", action = "reset")
            currentControls.gear = 0
        }

        // C: 1인칭시점 카메라 (toggleDroneView / gear: 10~20)
        btnC.setOnClickListener {
            isFpvCamera = !isFpvCamera
            isBottomCamera = false

            currentControls.gear = if (isFpvCamera) 15 else 100
            sendControlData(key = "C", action = "toggleDroneView")
            if (!isFpvCamera) {
                currentControls.gear = 0
            }
        }

        // M: 하부카메라 (toggleBottomView / gear: 20~50)
        btnM.setOnClickListener {
            isBottomCamera = !isBottomCamera
            isFpvCamera = false

            currentControls.gear = if (isBottomCamera) 30 else 100
            sendControlData(key = "M", action = "toggleBottomView")
            if (!isBottomCamera) {
                currentControls.gear = 0
            }
        }

        // B: 폭탄투하 (dropBomb / leftSwitch: -1)
        btnB.setOnClickListener {
            currentControls.leftSwitch = -1
            sendControlData(key = "B", action = "dropBomb")
            currentControls.leftSwitch = 0
        }
    }

    private fun showConnectDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL

        val input = EditText(this).apply {
            setText(savedUrl)
            setSelection(text.length)
            hint = "ws://192.168.1.3:8080 또는 wss://webbasedronesimulation.onrender.com"
            setPadding(50, 40, 50, 40)
        }

        AlertDialog.Builder(this)
            .setTitle("웹소켓 서버 주소 입력")
            .setMessage("접속할 드론 시뮬레이터 서버 주소를 입력하세요.")
            .setView(input)
            .setPositiveButton("연결") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    prefs.edit { putString(KEY_SERVER_URL, url) }
                    connectWebSocket(url)
                } else {
                    Toast.makeText(this, "주소를 입력해주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun connectWebSocket(url: String) {
        webSocket?.close(1000, "Reconnecting")
        webSocket = null

        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Connected to Server: $url")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "서버 연결 성공:\n$url", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Error : " + t.message)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "서버 연결 실패:\n${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        Toast.makeText(this, "서버 연결 시도: $url", Toast.LENGTH_SHORT).show()
        webSocket = client.newWebSocket(request, listener)
    }

    // drone.js 내부의 mapJoystickAxis 함수 로직 구현
    private fun mapJoystickAxis(value: Float, negativeValue: Int, positiveValue: Int): Int {
        return if (value < 0) {
            (134 + -value * (negativeValue - 134)).roundToInt()
        } else {
            (134 + value * (positiveValue - 134)).roundToInt()
        }
    }

    // JSON 객체를 생성하여 웹소켓으로 송신
    private fun sendControlData(key: String? = null, action: String? = null) {
        val json = JSONObject().apply {
            put("roll", currentControls.roll)
            put("pitch", currentControls.pitch)
            put("yaw", currentControls.yaw)
            put("throttle", currentControls.throttle)
            put("leftSwitch", currentControls.leftSwitch)
            put("gear", currentControls.gear)
            if (key != null) {
                put("key", key)
            }
            if (action != null) {
                put("action", action)
                if (action == "reset") {
                    put("V", true)
                }
                if (action == "dropBomb") {
                    put("bomb", true)
                }
            }
        }
        webSocket?.send(json.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Activity Destroyed")
        client.dispatcher.executorService.shutdown()
    }

    // 드론 제어 상태 데이터 클래스
    data class DroneControls(
        var roll: Int = 134,
        var pitch: Int = 134,
        var yaw: Int = 134,
        var throttle: Int = 134,
        var leftSwitch: Int = 0,
        var gear: Int = 0
    )
}
