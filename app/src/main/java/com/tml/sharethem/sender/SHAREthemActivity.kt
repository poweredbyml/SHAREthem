/*
 * Copyright 2017 Srihari Yachamaneni
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tml.sharethem.sender

import android.Manifest
import android.annotation.TargetApi
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.provider.Settings
import android.text.Html
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tml.sharethem.R
import com.tml.sharethem.databinding.ActivitySenderBinding
import com.tml.sharethem.sender.SHAREthemService.ShareIntents
import com.tml.sharethem.utils.*
import com.tml.sharethem.utils.HotspotControl.WifiClientConnectionListener
import com.tml.sharethem.utils.HotspotControl.WifiScanResult
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.util.*

/**
 * Controls Hotspot service to share files passed through intent.<br></br>
 * Displays sender IP and name for receiver to connect to when turned on
 *
 *
 * Created by Sri on 18/12/16.
 */
class SHAREthemActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySenderBinding
    var m_sender_wifi_info: TextView? = null
    var m_noReceiversText: TextView? = null
    var m_receivers_list_layout: RelativeLayout? = null
    var m_receiversList: RecyclerView? = null
    var m_apControlSwitch: SwitchCompat? = null
    var m_showShareList: TextView? = null
    var m_toolbar: Toolbar? = null
    private var m_receiversListAdapter: ReceiversListingAdapter? = null
    private var m_sender_ap_switch_listener: CompoundButton.OnCheckedChangeListener? = null
    private var m_uiUpdateHandler: ShareUIHandler? = null
    private var m_p2pServerUpdatesListener: BroadcastReceiver? = null
    private var hotspotControl: HotspotControl? = null
    private var isApEnabled = false
    private var shouldAutoConnect = true
    private var m_sharedFilePaths: Array<String?>? = null

    //region: Activity Methods
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySenderBinding.inflate(layoutInflater)

        //init UI
        m_sender_wifi_info = binding.p2pSenderWifiHint
        m_noReceiversText = binding.p2pNoReceiversText
        m_showShareList = binding.p2pSenderItemsLabel
        binding.p2pSenderItemsLabel.setOnClickListener { showSharedFilesDialog() }
        m_receivers_list_layout = binding.p2pReceiversListLayout
        m_receiversList = binding.p2pReceiversList
        m_apControlSwitch = binding.p2pSenderApSwitch
        m_toolbar = binding.toolbar
        binding.toolbar.title = getString(R.string.send_title)
        setSupportActionBar(m_toolbar)
        supportActionBar!!.setDisplayShowTitleEnabled(true)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        hotspotControl = HotspotControl.getInstance(applicationContext)
        binding.p2pReceiversList.layoutManager = LinearLayoutManager(applicationContext)
        binding.p2pReceiversList.addItemDecoration(DividerItemDecoration(resources.getDrawable(R.drawable.list_divider)))

        //if file paths are found, save'em into preferences. OR find them in prefs
        if (null != intent && intent.hasExtra(SHAREthemService.EXTRA_FILE_PATHS)) {
            m_sharedFilePaths = intent.getStringArrayExtra(SHAREthemService.EXTRA_FILE_PATHS)
        }
        val prefs = getSharedPreferences(packageName, Context.MODE_PRIVATE)
        if (null == m_sharedFilePaths) {
            m_sharedFilePaths = Utils.toStringArray(prefs.getString(PREFERENCES_KEY_SHARED_FILE_PATHS, null))
        } else prefs.edit().putString(PREFERENCES_KEY_SHARED_FILE_PATHS, JSONArray(listOf(*m_sharedFilePaths!!)).toString()).apply()
        m_receiversListAdapter = ReceiversListingAdapter(ArrayList(), m_sharedFilePaths)
        binding.p2pReceiversList.adapter = m_receiversListAdapter
        m_sender_ap_switch_listener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Utils.isOreoOrAbove) {
                    //If target version is MM and beyond, you need to check System Write permissions to proceed.
                    if (Build.VERSION.SDK_INT >= 23 && // if targetSdkVersion >= 23
                            //     ShareActivity has to check for System Write permissions to proceed
                            Utils.getTargetSDKVersion(applicationContext) >= 23 && !Settings.System.canWrite(this@SHAREthemActivity)) {
                        changeApControlCheckedStatus(false)
                        showMessageDialogWithListner(getString(R.string.p2p_sender_system_settings_permission_prompt), showNegative = false, finishCurrentActivity = true, listener = DialogInterface.OnClickListener { _, _ ->
                            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            intent.data = Uri.parse("package:$packageName")
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivityForResult(intent, REQUEST_WRITE_SETTINGS)
                        })
                        return@OnCheckedChangeListener
                    } else if (!getSharedPreferences(packageName, Context.MODE_PRIVATE).getBoolean(PREFERENCES_KEY_DATA_WARNING_SKIP, false) && Utils.isMobileDataEnabled(applicationContext)) {
                        changeApControlCheckedStatus(false)
                        showDataWarningDialog()
                        return@OnCheckedChangeListener
                    }
                } else if (!checkLocationPermission()) {
                    changeApControlCheckedStatus(false)
                    return@OnCheckedChangeListener
                }
                enableAp()
            } else {
                changeApControlCheckedStatus(true)
                showMessageDialogWithListner(
                        getString(R.string.p2p_sender_close_warning),
                        showNegative = true,
                        finishCurrentActivity = false,
                        listener = DialogInterface.OnClickListener { _, _ ->
                            Log.d(TAG, "sending intent to service to stop p2p..")
                            resetSenderUi(true)
                        }
                )
            }
        }
        binding.p2pSenderApSwitch.setOnCheckedChangeListener(m_sender_ap_switch_listener)
        m_p2pServerUpdatesListener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isFinishing) return
                when (intent.getIntExtra(ShareIntents.TYPE, 0)) {
                    ShareIntents.Types.FILE_TRANSFER_STATUS -> {
                        updateReceiverListItem(
                                intent.getStringExtra(ShareIntents.SHARE_CLIENT_IP)!!,
                                intent.getIntExtra(ShareIntents.SHARE_TRANSFER_PROGRESS, -1),
                                intent.getStringExtra(ShareIntents.SHARE_SERVER_UPDATE_TEXT)!!,
                                intent.getStringExtra(ShareIntents.SHARE_SERVER_UPDATE_FILE_NAME)!!
                        )
                    }
                    ShareIntents.Types.AP_DISABLED_ACKNOWLEDGEMENT -> {
                        shouldAutoConnect = false
                        resetSenderUi(false)
                    }
                }
            }
        }
        registerReceiver(m_p2pServerUpdatesListener, IntentFilter(ShareIntents.SHARE_SERVER_UPDATES_INTENT_ACTION))
    }

    override fun onResume() {
        super.onResume()
        //If service is already running, change UI and display info for receiver
        when {
            Utils.isShareServiceRunning(applicationContext) -> {
                if (!m_apControlSwitch!!.isChecked) {
                    Log.e(TAG, "p2p service running, changing switch status and start handler for ui changes")
                    changeApControlCheckedStatus(true)
                }
                refreshApData()
                m_receivers_list_layout!!.visibility = View.VISIBLE
            }
            m_apControlSwitch!!.isChecked -> {
                changeApControlCheckedStatus(false)
                resetSenderUi(false)
            }
            shouldAutoConnect -> {
                m_apControlSwitch!!.isChecked = true
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (null != m_p2pServerUpdatesListener) unregisterReceiver(m_p2pServerUpdatesListener)
        if (null != m_uiUpdateHandler) m_uiUpdateHandler!!.removeCallbacksAndMessages(null)
        m_uiUpdateHandler = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableAp()
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    showMessageDialogWithListner(getString(R.string.p2p_receiver_gps_permission_warning), showNegative = true, finishCurrentActivity = true, listener = DialogInterface.OnClickListener { _, _ -> checkLocationPermission() })
                } else {
                    showMessageDialogWithListner(getString(R.string.p2p_receiver_gps_no_permission_prompt), showNegative = true, finishCurrentActivity = true, listener = DialogInterface.OnClickListener { _, _ ->
                        try {
                            val intent = Intent()
                            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                            intent.data = Uri.fromParts("package", packageName, null)
                            startActivity(intent)
                        } catch (anf: ActivityNotFoundException) {
                            Toast.makeText(applicationContext, "Settings activity not found", Toast.LENGTH_SHORT).show()
                        }
                    })
                }
            }
        }
    }

    //endregion: Activity Methods
    //region: Dialog utilities
    fun showMessageDialogWithListner(message: String?,
                                     showNegative: Boolean, finishCurrentActivity: Boolean,
                                     listener: DialogInterface.OnClickListener?) {
        if (isFinishing) return
        val builder = AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle).apply {
            setCancelable(false)
            setMessage(Html.fromHtml(message))
            setPositiveButton(getString(R.string.Action_Ok), listener)
            if (showNegative) setNegativeButton(getString(R.string.Action_cancel)) { dialog, _ -> if (finishCurrentActivity) finish() else dialog.dismiss() }
        }
        builder.show()
    }

    @TargetApi(23)
    private fun checkLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION
            )
            return false
        }
        return true
    }

    fun showDataWarningDialog() {
        if (isFinishing) return
        val builder = AlertDialog.Builder(this, R.style.AppCompatAlertDialogStyle).apply {
            setCancelable(false)
            setMessage(getString(R.string.sender_data_on_warning))

            setPositiveButton(getString(R.string.label_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
            setNegativeButton(getString(R.string.label_thats_ok)) { _, _ ->
                changeApControlCheckedStatus(true)
                enableAp()
            }
            setNeutralButton(getString(R.string.label_dont_ask)) { _, _ ->
                val prefs = getSharedPreferences(packageName, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(PREFERENCES_KEY_DATA_WARNING_SKIP, true).apply()
                changeApControlCheckedStatus(true)
                enableAp()
            }
        }
        builder.show()
    }

    /**
     * Shows shared File urls
     */
    fun showSharedFilesDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Shared Files")
        builder.setItems(m_sharedFilePaths, null)
        builder.setPositiveButton("OK") { dialogInterface, _ -> dialogInterface.dismiss() }
        builder.show()
    }

    //endregion: Activity Methods
    //region: Hotspot Control
    private fun enableAp() {
        m_sender_wifi_info!!.text = getString(R.string.p2p_sender_hint_connecting)
        startP2pSenderWatchService()
        refreshApData()
        m_receivers_list_layout!!.visibility = View.VISIBLE
    }

    private fun disableAp() {
        //Send STOP action to service
        val p2pServiceIntent = Intent(applicationContext, SHAREthemService::class.java)
        p2pServiceIntent.action = SHAREthemService.WIFI_AP_ACTION_STOP
        startService(p2pServiceIntent)
        isApEnabled = false
    }

    /**
     * Starts [SHAREthemService] with intent action [SHAREthemService.WIFI_AP_ACTION_START] to turnOnPreOreoHotspot Hotspot and start [SHAREthemServer].
     */
    private fun startP2pSenderWatchService() {
        val p2pServiceIntent = Intent(applicationContext, SHAREthemService::class.java)
        p2pServiceIntent.putExtra(SHAREthemService.EXTRA_FILE_PATHS, m_sharedFilePaths)
        if (null != intent) {
            p2pServiceIntent.putExtra(SHAREthemService.EXTRA_PORT, if (Utils.isOreoOrAbove) Utils.DEFAULT_PORT_OREO else intent.getIntExtra(SHAREthemService.EXTRA_PORT, 0))
            p2pServiceIntent.putExtra(SHAREthemService.EXTRA_SENDER_NAME, intent.getStringExtra(SHAREthemService.EXTRA_SENDER_NAME))
        }
        p2pServiceIntent.action = SHAREthemService.WIFI_AP_ACTION_START
        startService(p2pServiceIntent)
    }

    /**
     * Starts [SHAREthemService] with intent action [SHAREthemService.WIFI_AP_ACTION_START_CHECK] to make [SHAREthemService] constantly check for Hotspot status. (Sometimes Hotspot tend to stop if stayed idle for long enough. So this check makes sure [SHAREthemService] is only alive if Hostspot is enaled.)
     */
    private fun startHostspotCheckOnService() {
        val p2pServiceIntent = Intent(applicationContext, SHAREthemService::class.java)
        p2pServiceIntent.action = SHAREthemService.WIFI_AP_ACTION_START_CHECK
        startService(p2pServiceIntent)
    }

    /**
     * Calls methods - [SHAREthemActivity.updateApStatus] & [SHAREthemActivity.listApClients] which are responsible for displaying Hotpot information and Listing connected clients to the same
     */
    private fun refreshApData() {
        if (null == m_uiUpdateHandler) m_uiUpdateHandler = ShareUIHandler(this)
        updateApStatus()
        listApClients()
    }

    /**
     * Updates Hotspot configuration info like Name, IP if enabled.<br></br> Posts a message to [ShareUIHandler] to call itself every 1500ms
     */
    private fun updateApStatus() {
        if (!HotspotControl.isSupported) {
            m_sender_wifi_info!!.text = "Warning: Hotspot mode not supported!\n"
        }
        if (hotspotControl!!.isEnabled) {
            if (!isApEnabled) {
                isApEnabled = true
                startHostspotCheckOnService()
            }
            val config = hotspotControl!!.configuration
            var ip = if (Build.VERSION.SDK_INT >= 23) WifiUtils.hostIpAddress else hotspotControl!!.hostIpAddress
            ip = if (TextUtils.isEmpty(ip)) "" else ip!!.replace("/", "")
            m_toolbar!!.subtitle = getString(R.string.p2p_sender_subtitle)
            m_sender_wifi_info!!.text = getString(R.string.p2p_sender_hint_wifi_connected, config!!.SSID, config.preSharedKey, "http://$ip:${hotspotControl!!.shareServerListeningPort}")
            if (m_showShareList!!.visibility == View.GONE) {
                m_showShareList!!.append(m_sharedFilePaths!!.size.toString())
                m_showShareList!!.visibility = View.VISIBLE
            }
        }
        if (null != m_uiUpdateHandler) {
            m_uiUpdateHandler!!.removeMessages(ShareUIHandler.UPDATE_AP_STATUS)
            m_uiUpdateHandler!!.sendEmptyMessageDelayed(ShareUIHandler.UPDATE_AP_STATUS, 1500)
        }
    }

    /**
     * Calls [HotspotControl.getConnectedWifiClients] to get Clients connected to Hotspot.<br></br>
     * Constantly adds/updates receiver items on [SHAREthemActivity.m_receiversList]
     * <br></br> Posts a message to [ShareUIHandler] to call itself every 1000ms
     */
    @Synchronized
    private fun listApClients() {
        if (hotspotControl == null) return
        hotspotControl!!.getConnectedWifiClients(2000,
                object : WifiClientConnectionListener {
                    override fun onClientConnectionAlive(c: WifiScanResult) {
                        runOnUiThread { addReceiverListItem(c) }
                    }

                    override fun onClientConnectionDead(c: WifiScanResult) {
                        Log.e(TAG, "onClientConnectionDead: ${c.ip}")
                        runOnUiThread { onReceiverDisconnected(c.ip) }
                    }

                    override fun onWifiClientsScanComplete() {
                        runOnUiThread {
                            if (null != m_uiUpdateHandler) {
                                m_uiUpdateHandler!!.removeMessages(ShareUIHandler.LIST_API_CLIENTS)
                                m_uiUpdateHandler!!.sendEmptyMessageDelayed(ShareUIHandler.LIST_API_CLIENTS, 1000)
                            }
                        }
                    }
                }
        )
    }

    private fun resetSenderUi(disableAP: Boolean) {
        m_uiUpdateHandler!!.removeCallbacksAndMessages(null)
        m_sender_wifi_info!!.text = getString(R.string.p2p_sender_hint_text)
        m_receivers_list_layout!!.visibility = View.GONE
        m_showShareList!!.visibility = View.GONE
        m_toolbar!!.subtitle = ""
        if (disableAP) {
            disableAp()
        } else {
            changeApControlCheckedStatus(false)
        }
        if (null != m_receiversListAdapter) m_receiversListAdapter!!.clear()
        m_noReceiversText!!.visibility = View.VISIBLE
    }

    /**
     * Changes checked status without invoking listener. Removes @[android.widget.CompoundButton.OnCheckedChangeListener] on @[SwitchCompat] button before changing checked status
     *
     * @param checked if `true`, sets @[SwitchCompat] checked.
     */
    private fun changeApControlCheckedStatus(checked: Boolean) {
        m_apControlSwitch!!.setOnCheckedChangeListener(null)
        m_apControlSwitch!!.isChecked = checked
        m_apControlSwitch!!.setOnCheckedChangeListener(m_sender_ap_switch_listener)
        shouldAutoConnect = checked
    }
    //endregion: Hotspot Control
    //region: Wifi Clients Listing
    /**
     * Finds the [View] tagged with `ip` and updates file transfer status of a shared File
     *
     * @param ip         Receiver IP
     * @param progress   File transfer progress
     * @param updatetext Text to be displayed. Could be Speed, Transferred size or an Error Status
     * @param fileName   name of File shared
     */
    private fun updateReceiverListItem(ip: String, progress: Int, updatetext: String, fileName: String) {
        val taskListItem = m_receiversList!!.findViewWithTag<View>(ip)
        if (null != taskListItem) {
            val holder = ReceiversListItemHolder(taskListItem)
            if ("Error in file transfer" in updatetext) {
                holder.resetTransferInfo(fileName)
                return
            }
            holder.update(fileName, updatetext, progress)
        } else {
            Log.e(TAG, "no list item found with this IP******")
        }
    }

    /**
     * Adds a [HotspotControl.WifiScanResult] item to [SHAREthemActivity.m_receiversListAdapter] if not already added
     *
     * @param wifiScanResult Connected Receiver item
     */
    private fun addReceiverListItem(wifiScanResult: WifiScanResult) {
        val wifiScanResults = m_receiversListAdapter?.objects
        if (null != wifiScanResults && wifiScanResult in wifiScanResults) {
            Log.e(TAG, "duplicate client, try updating connection status")
            val taskListItem = m_receiversList!!.findViewWithTag<View>(wifiScanResult.ip) ?: return
            val holder = ReceiversListItemHolder(taskListItem)
            if (holder.isDisconnected) {
                Log.d(TAG, "changing disconnected ui to connected: ${wifiScanResult.ip}")
                holder.setConnectedUi(wifiScanResult)
            }
        } else {
            m_receiversListAdapter!!.add(wifiScanResult)
            if (m_noReceiversText!!.visibility == View.VISIBLE) m_noReceiversText!!.visibility = View.GONE
        }
    }

    private fun onReceiverDisconnected(ip: String?) {
        val taskListItem = m_receiversList!!.findViewWithTag<View>(ip)
        if (null != taskListItem) {
            val holder = ReceiversListItemHolder(taskListItem)
            if (!holder.isDisconnected) holder.setDisconnectedUi()
            //            m_receiversListAdapter.remove(new WifiApControl.Client(ip, null, null));
        }
        if (m_receiversListAdapter!!.itemCount == 0) m_noReceiversText!!.visibility = View.VISIBLE
    }

    internal class ReceiversListItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var title: TextView = itemView.findViewById(R.id.p2p_receiver_title)
        var connection_status: TextView = itemView.findViewById(R.id.p2p_receiver_connection_status)
        fun setConnectedUi(wifiScanResult: WifiScanResult) {
            title.text = wifiScanResult.ip
            connection_status.text = "Connected"
            connection_status.setTextColor(Color.GREEN)
        }

        fun resetTransferInfo(fileName: String?) {
            val v = itemView.findViewWithTag<View>(fileName)
            if (null == v) {
                Log.e(TAG, "resetTransferInfo - no view found with file name tag!!")
                return
            }
            (v as TextView).text = ""
        }

        fun update(fileName: String?, transferData: String?, progress: Int) {
            val v = itemView.findViewWithTag<View>(fileName)
            if (null == v) {
                Log.e(TAG, "update - no view found with file name tag!!")
                return
            }
            if (v.visibility == View.GONE) v.visibility = View.VISIBLE
            (v as TextView).text = transferData
        }

        fun setDisconnectedUi() {
            connection_status.text = "Disconnected"
            connection_status.setTextColor(Color.RED)
        }

        val isDisconnected: Boolean
            get() = "Disconnected".equals(connection_status.text.toString(), ignoreCase = true)

    }

    private class ReceiversListingAdapter internal constructor(objects: MutableList<WifiScanResult?>, var sharedFiles: Array<String?>?) : RecyclerViewArrayAdapter<WifiScanResult?, ReceiversListItemHolder?>(objects) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReceiversListItemHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.listitem_receivers, parent, false) as LinearLayout
            //Add at least those many textviews of shared files list size so that if a receiver decides to download them all at once, list item can manage to show status of all file downloads
            if (null != sharedFiles && sharedFiles!!.isNotEmpty()) {
                for (i in sharedFiles!!.indices) {
                    val statusView = LayoutInflater.from(parent.context).inflate(R.layout.include_sender_list_item, parent, false) as TextView
                    statusView.tag = sharedFiles!![i]!!.substring(sharedFiles!![i]!!.lastIndexOf('/') + 1)
                    statusView.visibility = View.GONE
                    statusView.setTextColor(Utils.randomColor)
                    itemView.addView(statusView)
                }
            }
            return ReceiversListItemHolder(itemView)
        }

        override fun onBindViewHolder(holder: ReceiversListItemHolder, position: Int) {
            val receiver = mObjects[position] ?: return
            holder.itemView.tag = receiver.ip
            holder.setConnectedUi(receiver)
        }

    }

    //endregion: Wifi Clients Listing
    //region: UI Handler
    internal class ShareUIHandler(activity: SHAREthemActivity) : Handler() {
        private var mActivity: WeakReference<SHAREthemActivity> = WeakReference(activity)
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            val activity = mActivity.get()
            if (null == activity || !activity.m_apControlSwitch!!.isChecked) return
            if (msg.what == LIST_API_CLIENTS) {
                activity.listApClients()
            } else if (msg.what == UPDATE_AP_STATUS) {
                activity.updateApStatus()
            }
        }

        companion object {
            const val LIST_API_CLIENTS = 100
            const val UPDATE_AP_STATUS = 101
        }

    } //endregion: UI Handler

    companion object {
        const val TAG = "ShareActivity"

        /* Saves the shared files list in preferences. Useful when activity is opened(from notification or hotspot is already ON) without setting files info on intent */
        const val PREFERENCES_KEY_SHARED_FILE_PATHS = "sharethem_shared_file_paths"
        const val PREFERENCES_KEY_DATA_WARNING_SKIP = "sharethem_data_warning_skip"
        private const val REQUEST_WRITE_SETTINGS = 1
        private const val PERMISSIONS_REQUEST_CODE_ACCESS_COARSE_LOCATION = 100
    }
}