package com.example.roadvision

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.roadvision.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.firebase.database.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var googleMap: GoogleMap
    private lateinit var databaseRef: DatabaseReference
    private var vehicleMarker: Marker? = null

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastZ = 0f

    private var fusedLocationClient: FusedLocationProviderClient? = null
    private lateinit var locationCallback: LocationCallback

    private var surveyStarted = false
    private var startTime = 0L
    private var endTime = 0L
    private var startLatLng: LatLng? = null
    private var endLatLng: LatLng? = null
    private val surveyData = mutableListOf<String>()



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.openVideoPageButton.setOnClickListener {
            val intent = Intent(this, VideoRecordActivity::class.java)
            startActivity(intent)
        }


        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        databaseRef = FirebaseDatabase.getInstance().getReference("vehicles/$deviceId")

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        binding.startSurveyButton.setOnClickListener {
            surveyStarted = true
            startTime = System.currentTimeMillis()
            startLatLng = vehicleMarker?.position
            surveyData.clear()
            binding.startSurveyButton.isEnabled = false
            binding.endSurveyButton.isEnabled = true
            Toast.makeText(this, "🚀 Survey Started", Toast.LENGTH_SHORT).show()
        }

        binding.endSurveyButton.setOnClickListener {
            if (!surveyStarted) {
                Toast.makeText(this, "Start survey first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            surveyStarted = false
            endTime = System.currentTimeMillis()
            endLatLng = vehicleMarker?.position
            binding.startSurveyButton.isEnabled = true
            binding.endSurveyButton.isEnabled = false

            checkAndSavePDF()
        }

        setupLocationCallback()
        checkAllPermissions()
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    updateLocationOnFirebase(location)
                }
            }
        }
    }

    private fun updateLocationOnFirebase(location: Location) {
        val lat = location.latitude
        val lng = location.longitude
        val speed = location.speed.toInt()

        databaseRef.child("latitude").setValue(lat)
        databaseRef.child("longitude").setValue(lng)
        databaseRef.child("speed").setValue(speed)
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 2000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient?.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient?.removeLocationUpdates(locationCallback)
    }

    private fun checkAllPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1000)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1000) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
                startLocationUpdates()
            } else {
                Toast.makeText(this, "❌ Permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        googleMap.uiSettings.isZoomControlsEnabled = true

        databaseRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = snapshot.child("latitude").getValue(Double::class.java)
                val lng = snapshot.child("longitude").getValue(Double::class.java)
                val speed = snapshot.child("speed").getValue(Int::class.java) ?: 0
                val condition = snapshot.child("roadCondition").getValue(String::class.java) ?: "Unknown"

                if (lat != null && lng != null) {
                    val location = LatLng(lat, lng)
                    binding.latText.text = "Latitude: $lat"
                    binding.lngText.text = "Longitude: $lng"
                    binding.speedText.text = "Speed: $speed km/h"
                    binding.conditionText.text = "Road Condition: $condition"

                    val color = when (condition.lowercase()) {
                        "pothole" -> BitmapDescriptorFactory.HUE_RED
                        "slippery" -> BitmapDescriptorFactory.HUE_BLUE
                        "smooth" -> BitmapDescriptorFactory.HUE_GREEN
                        else -> BitmapDescriptorFactory.HUE_YELLOW
                    }

                    if (vehicleMarker == null) {
                        vehicleMarker = googleMap.addMarker(
                            MarkerOptions().position(location).title("Vehicle")
                                .icon(BitmapDescriptorFactory.defaultMarker(color))
                        )
                    } else {
                        vehicleMarker?.position = location
                        vehicleMarker?.setIcon(BitmapDescriptorFactory.defaultMarker(color))
                    }

                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 17f))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(this@MainActivity, "Firebase Error: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val z = event.values[2]
            val diff = kotlin.math.abs(z - lastZ)

            val roadCondition = when {
                diff > 12f -> "Pothole"
                diff in 5f..12f -> "Slippery"
                else -> "Smooth"
            }

            if (surveyStarted) {
                val ts = Date(System.currentTimeMillis())
                vehicleMarker?.position?.let { pos ->
                    surveyData.add("[${ts}] Lat: ${pos.latitude}, Lng: ${pos.longitude}, Condition: $roadCondition")
                }
            }

            databaseRef.child("roadCondition").setValue(roadCondition)
            lastZ = z
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        sensorManager.unregisterListener(this)
        stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    private fun checkAndSavePDF() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        } else {
            generateSurveyPDF()
        }
    }

    private fun generateSurveyPDF() {
        val pdf = PdfDocument()
        val paint = Paint().apply { textSize = 12f }
        val boldPaint = Paint().apply {
            textSize = 14f
            isFakeBoldText = true
        }

        val pageWidth = 595
        val pageHeight = 842
        val rowHeight = 25
        val margin = 20
        var y = margin + 20
        var pageNumber = 1

        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdf.startPage(pageInfo)
        var canvas = page.canvas

        canvas.drawText("📄 Road Survey Report", pageWidth / 2f - 70, y.toFloat(), boldPaint)
        y += 30

        canvas.drawText("Start Time: ${Date(startTime)}", margin.toFloat(), y.toFloat(), paint)
        y += 20
        canvas.drawText("End Time: ${Date(endTime)}", margin.toFloat(), y.toFloat(), paint)
        y += 20
        canvas.drawText("Start Location: ${startLatLng?.latitude}, ${startLatLng?.longitude}", margin.toFloat(), y.toFloat(), paint)
        y += 20
        canvas.drawText("End Location: ${endLatLng?.latitude}, ${endLatLng?.longitude}", margin.toFloat(), y.toFloat(), paint)
        y += 30

        val col1 = margin
        val col2 = col1 + 250
        val col3 = col2 + 130
        val col4 = col3 + 100

        canvas.drawText("Time", col1.toFloat(), y.toFloat(), boldPaint)
        canvas.drawText("Latitude", col2.toFloat(), y.toFloat(), boldPaint)
        canvas.drawText("Longitude", col3.toFloat(), y.toFloat(), boldPaint)
        canvas.drawText("Condition", col4.toFloat(), y.toFloat(), boldPaint)
        y += rowHeight

        for (entry in surveyData) {
            val regex = """\[(.*?)\] Lat: (.*?), Lng: (.*?), Condition: (.*)""".toRegex()
            val match = regex.find(entry)
            if (match != null) {
                val (time, lat, lng, cond) = match.destructured
                if (y > pageHeight - margin - rowHeight) {
                    pdf.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdf.startPage(pageInfo)
                    canvas = page.canvas
                    y = margin + 30
                }

                canvas.drawText(time, col1.toFloat(), y.toFloat(), paint)
                canvas.drawText(lat, col2.toFloat(), y.toFloat(), paint)
                canvas.drawText(lng, col3.toFloat(), y.toFloat(), paint)
                canvas.drawText(cond, col4.toFloat(), y.toFloat(), paint)
                y += rowHeight
            }
        }

        pdf.finishPage(page)

        val fileName = "SurveyReport_${System.currentTimeMillis()}.pdf"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { output ->
                        pdf.writeTo(output)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                    Toast.makeText(this, "✅ PDF saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
                pdf.writeTo(FileOutputStream(file))
                Toast.makeText(this, "✅ PDF saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "❌ Error saving PDF: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            pdf.close()
        }
    }
}
