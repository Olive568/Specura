package com.example.specuraprototype

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MenuActivity : AppCompatActivity() {

    private val requestReadImagesPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadRecentSpecuraImages()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_menu)

        // Requires your root layout to have android:id="@+id/main"
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Bottom nav: History
        findViewById<ImageButton>(R.id.btnNavHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Bottom nav: Camera
        findViewById<ImageButton>(R.id.btnNavCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // Bottom nav: Settings
        findViewById<ImageButton>(R.id.btnNavSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        ensureGalleryPermissionThenLoad()
    }

    override fun onResume() {
        super.onResume()
        // refresh when coming back from Camera after taking a photo
        ensureGalleryPermissionThenLoad()
    }

    private fun ensureGalleryPermissionThenLoad() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadRecentSpecuraImages()
        } else {
            requestReadImagesPermission.launch(permission)
        }
    }

    private fun loadRecentSpecuraImages() {
        val uris = queryLatestSpecuraImages(limit = 6)

        val slots = listOf(
            findViewById<ImageView>(R.id.imgRecent1),
            findViewById<ImageView>(R.id.imgRecent2),
            findViewById<ImageView>(R.id.imgRecent3),
            findViewById<ImageView>(R.id.imgRecent4),
            findViewById<ImageView>(R.id.imgRecent5),
            findViewById<ImageView>(R.id.imgRecent6),
        )

        // Clear all slots first
        for (img in slots) img.setImageDrawable(null)

        // Fill with latest images
        for (i in uris.indices) {
            slots[i].setImageURI(uris[i])
        }
    }

    private fun queryLatestSpecuraImages(limit: Int): List<Uri> {
        val results = mutableListOf<Uri>()

        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED
        )

        // Must match EXACT folder used in CameraActivity:
        // put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Specura")
        // MediaStore stores it with trailing slash:
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf("Pictures/Specura/")

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)

            while (cursor.moveToNext() && results.size < limit) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, id.toString())
                results.add(uri)
            }
        }

        return results
    }
}
