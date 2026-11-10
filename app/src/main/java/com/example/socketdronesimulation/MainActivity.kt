package com.example.socketdronesimulation

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initWebSocket()

        val joystickLeft = findViewById<JoystickView>(R.id.joystickLeft)
        val joystickRight = findViewById<JoystickView>(R.id.joystickRight)
        val btnReset = findViewById<Button>(R.id.btnReset)
        val btnDropBomb = findViewById<Button>(R.id.btnDropBomb)

        // 왼쪽 조이스틱: 수직 제어부 (Yaw / Throttle)
        joystickLeft.onJoystickMoveListener = { x, y ->
            currentControls.yaw = mapJoystickAxis(x, 19, 244)
            currentControls.throttle = mapJoystickAxis(y, 20, 244)
            sendControlData()
        }
        joystickLeft.onJoystickReleased = {
            currentControls.yaw = 134
            currentControls.throttle = 134
            sendControlData()
        }

        // 오른쪽 조이스틱: 수평 제어부 (Roll / Pitch)
        joystickRight.onJoystickMoveListener = { x, y ->
            currentControls.roll = mapJoystickAxis(x, 19, 241)
            currentControls.pitch = mapJoystickAxis(y, 17, 243)
            sendControlData()
        }
        joystickRight.onJoystickReleased = {
            currentControls.roll = 134
            currentControls.pitch = 134
            sendControlData()
        }

        // 초기자세 조정 버튼
        btnReset.setOnClickListener {
            currentControls.roll = 134
            currentControls.pitch = 134
            currentControls.yaw = 134
            currentControls.throttle = 134
            currentControls.leftSwitch = 0
            currentControls.gear = 0

            joystickLeft.resetJoystick()
            joystickRight.resetJoystick()

            sendControlData(action = "reset")
        }

        // 폭탄투하 버튼
        btnDropBomb.setOnClickListener {
            currentControls.leftSwitch = 1
            sendControlData(action = "dropBomb")
            currentControls.leftSwitch = 0
        }
    }

    private fun initWebSocket() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        // 대상 웹소켓 서버 주소 기입 (예: 드론 시뮬레이터 서버 주소)
        val request = Request.Builder()
            .url("wss://webbasedronesimulation.onrender.com") // 에뮬레이터에서 PC 로컬 호스트 접속 시 주소 ws://192.168.123.168:8080
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
    private fun sendControlData(action: String? = null) {
        val json = JSONObject().apply {
            put("roll", currentControls.roll)
            put("pitch", currentControls.pitch)
            put("yaw", currentControls.yaw)
            put("throttle", currentControls.throttle)
            put("leftSwitch", currentControls.leftSwitch)
            put("gear", currentControls.gear)
            if (action != null) {
                put("action", action)
                if (action == "dropBomb") {
                    put("bomb", true)
                }
            }
        }
        webSocket?.send(json.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
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
