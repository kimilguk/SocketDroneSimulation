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
    private var isCameraMove = false

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
        val btnE = findViewById<Button>(R.id.btnE)
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
            currentControls.leftSwitch = 0 // 드론 조이스틱 조작 시 폭탄 재장착 (leftSwitch = 0)
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
            currentControls.leftSwitch = 0 // 드론 조이스틱 조작 시 폭탄 재장착 (leftSwitch = 0)
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

        // E: 출발지점으로 재시작 (resetDrone / Key E)
        btnE.setOnClickListener {
            if (isCameraMove) {
                isCameraMove = false
                sendPulseControlData(key = "V", action = "toggleCameraMoveMode")
            }

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

            sendPulseControlData(key = "E", action = "reset")
            currentControls.gear = 0
        }

        // V: 카메라 이동 (toggleCameraMoveMode / Key V 펄스 전송 후 V 신호 자동 클리어)
        btnV.setOnClickListener {
            isCameraMove = !isCameraMove
            if (isCameraMove) {
                isFpvCamera = true // drone.js에서 cameraMoveMode 켜질 때 droneView = true로 설정되므로 상태 동기화
                isBottomCamera = false
            } else {
                isFpvCamera = true //false
                isBottomCamera = false
                currentControls.gear = 15 //100
            }

            sendPulseControlData(key = "V", action = "toggleCameraMoveMode")
            if (!isCameraMove) {
                currentControls.gear = 0
            }
        }

        // C: 1인칭시점 카메라 (toggleDroneView / gear: 10~20)
        btnC.setOnClickListener {
            if (isCameraMove) {
                isCameraMove = false
                sendPulseControlData(key = "V", action = "toggleCameraMoveMode")
            }

            isFpvCamera = !isFpvCamera
            isBottomCamera = false

            currentControls.gear = if (isFpvCamera) 15 else 100
            sendPulseControlData(key = "C", action = "toggleDroneView")
            if (!isFpvCamera) {
                currentControls.gear = 0
            }
        }

        // M: 하부카메라 (toggleBottomView / gear: 20~50)
        btnM.setOnClickListener {
            if (isCameraMove) {
                isCameraMove = false
                sendPulseControlData(key = "V", action = "toggleCameraMoveMode")
            }

            isBottomCamera = !isBottomCamera
            isFpvCamera = false

            currentControls.gear = if (isBottomCamera) 30 else 100
            sendPulseControlData(key = "M", action = "toggleBottomView")
            if (!isBottomCamera) {
                currentControls.gear = 0
            }
        }

        // B: 폭탄투하 (dropBomb / leftSwitch: -1 유지하여 폭탄 낙하 수행)
        btnB.setOnClickListener {
            currentControls.leftSwitch = -1
            sendControlData(key = "B", action = "dropBomb")
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

    // 펄스 신호 전송 후 50ms 뒤 V/키 상태 해제 패킷 자동 송신
    private fun sendPulseControlData(key: String, action: String) {
        sendControlData(key = key, action = action)
        mainHandler.postDelayed({
            sendControlData()
        }, 50)
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
                put(key, true)
            }
            if (action != null) {
                put("action", action)
                if (action == "reset") {
                    put("reset", true)
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
