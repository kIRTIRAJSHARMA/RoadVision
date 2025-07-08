package com.example.roadvision

import android.Manifest
import android.annotation.TargetApi
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.*
import android.provider.MediaStore
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class VideoRecordActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var recordBtn: Button
    private lateinit var backBtn: ImageButton

    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private var isRecording = false
    private var videoUri: android.net.Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_record)

        surfaceView = findViewById(R.id.surfaceView)
        recordBtn = findViewById(R.id.recordBtn)
        backBtn = findViewById(R.id.backButton)

        surfaceView.holder.addCallback(this)
        surfaceView.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS)

        recordBtn.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }

        backBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isRecording) {
                enterPiPMode()
            } else {
                finish()
            }
        }

        checkPermissions()
    }

    private fun checkPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO)

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), 101)
        }
    }

    private fun startRecording() {
        try {
            camera = Camera.open()
            camera?.setDisplayOrientation(90)
            camera?.unlock()

            val fileName = "recording_${System.currentTimeMillis()}.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RoadVision")
            }

            val resolver = contentResolver
            videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            val fd = videoUri?.let { resolver.openFileDescriptor(it, "w")?.fileDescriptor }

            mediaRecorder = MediaRecorder().apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setOutputFile(fd)
                setPreviewDisplay(surfaceView.holder.surface)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setOrientationHint(90)
                prepare()
                start()
            }

            isRecording = true
            recordBtn.text = "⏹ Stop"
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            camera?.apply {
                stopPreview()
                release()
            }
            Toast.makeText(this, "Saved to: Movies/RoadVision", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Stop error: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            mediaRecorder = null
            camera = null
            isRecording = false
            recordBtn.text = "⏺ Record"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            camera = Camera.open()
            camera?.setDisplayOrientation(90)
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!isInPictureInPictureMode) {
            stopRecording()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun onUserLeaveHint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isRecording && !isInPictureInPictureMode) {
            enterPiPMode()
        }
    }

    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isRecording && !isInPictureInPictureMode) {
            enterPiPMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun enterPiPMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        toggleUiVisibility(isInPictureInPictureMode)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        toggleUiVisibility(isInPictureInPictureMode)
    }

    private fun toggleUiVisibility(isInPip: Boolean) {
        recordBtn.visibility = if (isInPip) View.GONE else View.VISIBLE
        backBtn.visibility = if (isInPip) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            stopRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
    }
}
