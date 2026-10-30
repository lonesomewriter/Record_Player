package dev.recorddisc.player

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.recorddisc.player.ui.PhoneApp

class MainActivity : ComponentActivity() {
    private val model get() = (application as RecordDiscApplication).model

    /** Android's own folder picker; the app only ever sees the folder chosen here. */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            model.message("No folder chosen")
            return@registerForActivityResult
        }
        // Keep read access after restarts. Write access is asked for when tag editing arrives.
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        model.scan(uri.toString())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so keep the system bar icons light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)

        setContent {
            PhoneApp(model, onChooseFolder = { pickFolder.launch(null) })
        }
    }
}
