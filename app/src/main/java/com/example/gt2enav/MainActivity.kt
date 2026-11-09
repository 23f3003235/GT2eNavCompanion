package com.example.gt2enav

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {
    private val channel = "navigation_alerts"
    private lateinit var status: TextView
    private lateinit var destination: EditText
    private lateinit var notificationManager: NotificationManager
    private var steps = mutableListOf<Step>()
    private var navigating = false
    private val handler = Handler(Looper.getMainLooper())
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private var lastStep = -1
    data class Step(val instruction: String, val latitude: Double, val longitude: Double, val distance: Double)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(NotificationChannel(channel, "GT2e navigation alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Turn directions forwarded by Huawei Health when supported" })
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 48, 36, 36) }
        fun button(label: String, action: () -> Unit) { root.addView(Button(this).apply { text = label; setOnClickListener { action() } }, ViewGroup.LayoutParams(-1, -2)) }
        root.addView(TextView(this).apply { text = "GT2e Navigation Companion"; textSize = 24f })
        status = TextView(this).apply { text = "First enable this app in Huawei Health > GT 2e > Notifications, then test."; textSize = 16f; setPadding(0, 18, 0, 18) }
        root.addView(status)
        button("1. Request notification permission") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
        button("2. Send test notification") { notifyWatch("TEST: Turn right in 200 m", 1001) }
        button("3. Open app notification settings") { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        root.addView(TextView(this).apply { text = "Destination coordinates (latitude,longitude)" })
        destination = EditText(this).apply { hint = "13.0827,80.2707"; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        root.addView(destination)
        button("4. Request location permission") { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 102) }
        button("5. Calculate walking route") { calculateRoute() }
        button("6. Start navigation (app must remain open)") { startNavigation() }
        button("Stop navigation") { navigating = false; handler.removeCallbacksAndMessages(null); status.text = "Navigation stopped" }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun notifyWatch(message: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) { status.text = "Grant notification permission first"; return }
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_dialog_map).setContentTitle("GT2e Navigation").setContentText(message).setStyle(Notification.BigTextStyle().bigText(message)).setContentIntent(intent).setAutoCancel(true).build()
        notificationManager.notify(id, notification)
        status.text = "Sent phone notification: $message. Check the watch."
    }
    private fun currentLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { locationManager.getLastKnownLocation(it) }.maxByOrNull { it.time }?.takeIf { System.currentTimeMillis() - it.time < 120000 } } catch (_: Exception) { null }
    }
    private fun calculateRoute() {
        val parts = destination.text.toString().split(',')
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull(); val lon = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) { status.text = "Enter valid latitude,longitude"; return }
        val here = currentLocation() ?: run { status.text = "No recent location fix. Enable GPS, allow location and open a maps app briefly."; return }
        status.text = "Fetching route from OSRM (internet required)..."
        thread {
            try {
                val address = "https://router.project-osrm.org/route/v1/foot/${here.longitude},${here.latitude};$lon,$lat?steps=true&overview=false"
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.connectTimeout = 12000; connection.readTimeout = 12000
                val json = connection.inputStream.bufferedReader().use { it.readText() }; connection.disconnect()
                val routes = JSONObject(json).getJSONArray("routes")
                if (routes.length() == 0) error("No route available")
                val route = routes.getJSONObject(0)
                val legs = route.getJSONArray("legs")
                val result = mutableListOf<Step>()
                for (i in 0 until legs.length()) {
                    val arr = legs.getJSONObject(i).getJSONArray("steps")
                    for (j in 0 until arr.length()) {
                        val s = arr.getJSONObject(j); val maneuver = s.getJSONObject("maneuver")
                        val type = maneuver.optString("type", "continue"); val modifier = maneuver.optString("modifier", "")
                        val name = s.optString("name", ""); val loc = maneuver.getJSONArray("location")
                        val message = (if (type == "arrive") "Arrive at destination" else "$type $modifier".trim().replaceFirstChar { it.uppercase() }) + (if (name.isNotBlank()) " onto $name" else "")
                        result.add(Step(message, loc.getDouble(1), loc.getDouble(0), s.optDouble("distance", 0.0)))
                    }
                }
                runOnUiThread { steps = result; lastStep = -1; status.text = "Route loaded: ${route.optInt("distance")} m, ${steps.size} instructions. Keep app open; routing is experimental." }
            } catch (e: Exception) { runOnUiThread { status.text = "Routing failed: ${e.message}" } }
        }
    }
    private fun startNavigation() {
        if (steps.isEmpty()) { status.text = "Calculate route first"; return }
        if (currentLocation() == null) { status.text = "Fresh location required"; return }
        navigating = true
        handler.removeCallbacksAndMessages(null)
        val task = object : Runnable {
            override fun run() {
                if (!navigating) return
                val loc = currentLocation()
                if (loc != null) {
                    val index = steps.indices.minByOrNull { i ->
                        val d = FloatArray(1); Location.distanceBetween(loc.latitude, loc.longitude, steps[i].latitude, steps[i].longitude, d); d[0]
                    } ?: 0
                    val step = steps[index]
                    val dist = FloatArray(1); Location.distanceBetween(loc.latitude, loc.longitude, step.latitude, step.longitude, dist)
                    status.text = "Nearest maneuver: ${step.instruction} (${dist[0].toInt()} m away). Experimental: nearest-step matching may be incorrect on overlapping routes."
                    if (index != lastStep && dist[0] < 500) { lastStep = index; notifyWatch("${step.instruction} in ${dist[0].toInt()} m", 2000 + index) }
                } else status.text = "Waiting for fresh GPS fix"
                handler.postDelayed(this, 5000)
            }
        }
        handler.post(task)
    }
    override fun onDestroy() { navigating = false; handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
