package com.example.socketdronesimulation

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

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

        initWebSocket()

        val joystickLeft = findViewById<JoystickView>(R.id.joystickLeft)
        val joystickRight = findViewById<JoystickView>(R.id.joystickRight)
        val btnV = findViewById<Button>(R.id.btnV)
        val btnC = findViewById<Button>(R.id.btnC)
        val btnM = findViewById<Button>(R.id.btnM)
        val btnB = findViewById<Button>(R.id.btnB)

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

    private fun initWebSocket() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        // 대상 웹소켓 서버 주소 기입 (예: 드론 시뮬레이터 서버 주소)
        val request = Request.Builder()
            //.url("wss://webbasedronesimulation.onrender.com") // 에뮬레이터에서 클라우드 플랫폼 접속
            .url("ws://192.168.1.3:8080") // 에뮬레이터에서 PC 로컬 호스트 접속 시
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Connected to Server")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Error : " + t.message)
            }
        }

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
