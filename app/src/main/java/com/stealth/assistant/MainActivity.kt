package com.stealth.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.stealth.assistant.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        var sharedResumeText: String = ""
        var sharedCustomContext: String = ""
        var isPdfDocument: Boolean = false
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleSelectedFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkBasicPermissions()

        // 1. Resume ఫైల్ పికర్ (PDF & Docs)
        binding.btnPickResume.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        // 2. ఫ్లోటింగ్ ఓవర్‌లే లాంచ్
        binding.btnLaunchOverlay.setOnClickListener {
            checkOverlayAndStartService()
        }

        // 3. GitHub Releases ద్వారా యాప్ అప్‌డేట్
        binding.btnUpdateApp.setOnClickListener {
            AppUpdater.checkForUpdate(this) { statusMessage ->
                runOnUiThread {
                    Toast.makeText(this, statusMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            var fileName = "Selected Document"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            binding.tvResumeStatus.text = fileName

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                isPdfDocument = fileName.endsWith(".pdf", ignoreCase = true)

                sharedResumeText = if (isPdfDocument) {
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            }
            Toast.makeText(this, "Resume context loaded!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            binding.tvResumeStatus.text = "Error reading file content"
            e.printStackTrace()
        }
    }

    private fun checkBasicPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun checkOverlayAndStartService() {
        // 1. ఆండ్రాయిడ్ 14+ క్రాష్ అవ్వకుండా మైక్రోఫోన్ పర్మిషన్ చెక్
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Please grant Microphone permission first", Toast.LENGTH_SHORT).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 102)
            return
        }

        // 2. Overlay Permission Check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        sharedCustomContext = binding.etCustomContext.text.toString().trim()

        val serviceIntent = Intent(this, OverlayService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        moveTaskToBack(true)
    }
}