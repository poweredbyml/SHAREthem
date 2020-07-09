package com.tml.sharethem.demo

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.github.angads25.filepicker.controller.DialogSelectionListener
import com.github.angads25.filepicker.model.DialogConfigs
import com.github.angads25.filepicker.model.DialogProperties
import com.github.angads25.filepicker.view.FilePickerDialog
import com.tml.sharethem.R
import com.tml.sharethem.receiver.ReceiverActivity
import com.tml.sharethem.sender.SHAREthemActivity
import com.tml.sharethem.sender.SHAREthemService
import com.tml.sharethem.utils.HotspotControl
import com.tml.sharethem.utils.Utils
import java.io.File

class DemoActivity : AppCompatActivity() {
    var dialog: FilePickerDialog? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_demo)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.title = getString(R.string.app_name)

        val send = findViewById<Button>(R.id.sendButton)
        val  receive = findViewById<Button>(R.id.receiveButton)

        send.setOnClickListener {
            sendFiles()
        }

        receive.setOnClickListener {
            receiveFiles()
        }
    }

    fun sendFiles() {
        if (Utils.isShareServiceRunning(applicationContext)) {
            startActivity(Intent(applicationContext, SHAREthemActivity::class.java))
            return
        }
        val properties = DialogProperties()
        properties.selection_mode = DialogConfigs.MULTI_MODE
        properties.selection_type = DialogConfigs.FILE_SELECT
        properties.root = File(DialogConfigs.DEFAULT_DIR)
        properties.error_dir = File(DialogConfigs.DEFAULT_DIR)
        properties.extensions = null
        dialog = FilePickerDialog(this, properties)
        dialog!!.setTitle("Select files to share")
        dialog!!.setDialogSelectionListener(DialogSelectionListener { files ->
            if (null == files || files.isEmpty()) {
                Toast.makeText(
                        this@DemoActivity,
                        "Select at least one file to start Share Mode",
                        Toast.LENGTH_SHORT
                ).show()
                return@DialogSelectionListener
            }
            val intent = Intent(applicationContext, SHAREthemActivity::class.java)
            intent.putExtra(SHAREthemService.EXTRA_FILE_PATHS, files)
            // PORT value is hardcoded for Oreo & above since it's not possible to set SSID with
            // which port info can be extracted on Receiver side.
            intent.putExtra(SHAREthemService.EXTRA_PORT, Utils.DEFAULT_PORT_OREO)
            // Sender name can't be relayed to receiver for Oreo & above
            intent.putExtra(SHAREthemService.EXTRA_SENDER_NAME, "Sri")
            startActivity(intent)
        })
        dialog!!.show()
    }

    fun receiveFiles() {
        val hotspotControl = HotspotControl.getInstance(applicationContext)
        if (null != hotspotControl && hotspotControl.isEnabled) {
            val builder = AlertDialog.Builder(this)
            builder.setMessage("Sender(Hotspot) mode is active. Please disable it to proceed with Receiver mode")
            builder.setNeutralButton("OK") { dialogInterface, _ -> dialogInterface.cancel() }
            builder.show()
            return
        }
        startActivity(Intent(applicationContext, ReceiverActivity::class.java))
    }

    //Add this method to show Dialog when the required permission has been granted to the app.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            FilePickerDialog.EXTERNAL_READ_PERMISSION_GRANT -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    //Show dialog if the read permission has been granted.
                    dialog?.show()
                } else {
                    //Permission has not been granted. Notify the user.
                    Toast.makeText(this, "Permission is Required for getting list of files", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}