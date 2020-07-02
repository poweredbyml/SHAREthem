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
package com.tml.sharethem.receiver

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tml.sharethem.R
import com.tml.sharethem.databinding.FragmentFilesListingBinding
import com.tml.sharethem.receiver.FilesListingFragment.ContactSenderAPITask
import com.tml.sharethem.utils.DividerItemDecoration
import com.tml.sharethem.utils.RecyclerViewArrayAdapter
import java.io.*
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.*

/**
 * Lists all files available to download by making network calls using [ContactSenderAPITask]
 *
 *
 * Functionalities include:
 *
 *  * Adds file downloads to [DownloadManager]'s Queue
 * *  * Checks Sender API availability and throws error after certain retry limit
 *
 *
 *
 * Created by Sri on 21/12/16.
 */
class FilesListingFragment : Fragment(R.layout.fragment_files_listing) {
    private lateinit var binding: FragmentFilesListingBinding
    var senderIp: String? = null
        private set
    var senderSSID: String? = null
        private set
    private var mUrlsTask: ContactSenderAPITask? = null
    private var mStatusCheckTask: ContactSenderAPITask? = null
    private var mPort: String? = null
    private var mSenderName: String? = null
    lateinit var mLoading: ProgressBar
    lateinit var mEmptyListText: TextView
    private var mFilesAdapter: SenderFilesListingAdapter? = null
    private var uiUpdateHandler: UiUpdateHandler? = null
    private var senderDownloadsFetchRetry = SENDER_DATA_FETCH_RETRY_LIMIT
    private var senderStatusCheckRetryLimit = SENDER_DATA_FETCH_RETRY_LIMIT

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = FragmentFilesListingBinding.bind(view)

        with(binding) {
            mLoading = loading
            mEmptyListText = emptyListingText

            emptyListingText.setOnClickListener {
                emptyListingText.visibility = View.GONE
                loading.visibility = View.VISIBLE
                fetchSenderFiles()
            }
            filesList.layoutManager = LinearLayoutManager(activity)
            filesList.addItemDecoration(
                    DividerItemDecoration(ContextCompat.getDrawable(requireContext(), R.drawable.list_divider))
            )
            mFilesAdapter = SenderFilesListingAdapter(ArrayList())
            filesList.adapter = mFilesAdapter
        }

        uiUpdateHandler = UiUpdateHandler(this)
    }

    override fun onAttach(activity: Context) {
        super.onAttach(activity)
        if (null != arguments) {
            senderIp = requireArguments().getString("senderIp")
            senderSSID = requireArguments().getString("ssid")
            mPort = requireArguments().getString("port")
            mSenderName = requireArguments().getString("name")
            Log.d(TAG, "sender ip: $senderIp")
        }
    }

    override fun onResume() {
        super.onResume()
        fetchSenderFiles()
        checkSenderAPIAvailablity()
    }

    private fun fetchSenderFiles() {
        mLoading.visibility = View.VISIBLE
        mUrlsTask?.cancel(true)
        mUrlsTask = ContactSenderAPITask(SENDER_DATA_FETCH)
        mUrlsTask!!.execute(String.format(PATH_FILES, senderIp, mPort))
    }

    private fun checkSenderAPIAvailablity() {
        mStatusCheckTask?.cancel(true)
        mStatusCheckTask = ContactSenderAPITask(CHECK_SENDER_STATUS)
        mStatusCheckTask!!.execute(String.format(PATH_STATUS, senderIp, mPort))
    }

    override fun onPause() {
        super.onPause()
        mUrlsTask?.cancel(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiUpdateHandler?.removeCallbacksAndMessages(null)
        mStatusCheckTask?.cancel(true)
    }

    private fun loadListing(contentAsString: String?) {
        val collectionType = object : TypeToken<List<String?>?>() {}.type
        val files: ArrayList<String> = Gson().fromJson(contentAsString, collectionType)
        mLoading.visibility = View.GONE
        if (files.size == 0) {
            mEmptyListText.text = "No Downloads found.\n Tap to Retry"
            mEmptyListText.visibility = View.VISIBLE
        } else {
            mEmptyListText.visibility = View.GONE
            mFilesAdapter?.updateData(files)
        }
    }

    private fun onDataFetchError() {
        mLoading.visibility = View.GONE
        mEmptyListText.visibility = View.VISIBLE
        mEmptyListText.text = "Error occurred while fetching data.\n Tap to Retry"
    }

    private fun postDownloadRequestToDM(uri: Uri, fileName: String): Long {

        // Create request for android download manager
        val downloadManager = requireActivity().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(uri)

        //Setting title of request
        request.setTitle(fileName)

        //Setting description of request
        request.setDescription("ShareThem")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        //Set the local destination for the downloaded file to a path
        //within the application's external files directory
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName)

        //Enqueue download and save into referenceId
        return downloadManager.enqueue(request)
    }

    private inner class SenderFilesListingAdapter internal constructor(objects: MutableList<String?>) : RecyclerViewArrayAdapter<String?, SenderFilesListItemHolder?>(objects) {
        fun updateData(objects: List<String>) {
            clear()
            mObjects = objects.toMutableList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SenderFilesListItemHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.listitem_file, parent, false)
            return SenderFilesListItemHolder(itemView)
        }

        override fun onBindViewHolder(holder: SenderFilesListItemHolder, position: Int) {
            val senderFile = mObjects[position]!!
            holder.itemView.tag = senderFile
            val fileName = senderFile.substring(senderFile.lastIndexOf('/') + 1)
            holder.title.text = fileName
            holder.download.setOnClickListener {
                postDownloadRequestToDM(Uri.parse(String.format(PATH_FILE_DOWNLOAD, senderIp, mPort, mObjects.indexOf(senderFile))), fileName)
                Toast.makeText(activity, "Downloading $fileName...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal class SenderFilesListItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var title: TextView = itemView.findViewById(R.id.sender_list_item_name)
        var download: ImageButton = itemView.findViewById(R.id.sender_list_start_download)

    }

    /**
     * Performs network calls to fetch data/status from Sender.
     * Retries on error for times bases on values of [FilesListingFragment.senderDownloadsFetchRetry]
     */
    @SuppressLint("StaticFieldLeak")
    private inner class ContactSenderAPITask internal constructor(var mode: Int) : AsyncTask<String?, Void?, String?>() {
        var error = false

        override fun doInBackground(vararg urls: String?): String? {
            error = false
            return try {
                urls[0]?.let { downloadDataFromSender(it) }
            } catch (e: IOException) {
                e.printStackTrace()
                error = true
                Log.e(TAG, "Exception: ${e.message}")
                null
            }
        }

        // onPostExecute displays the results of the AsyncTask.
        override fun onPostExecute(result: String?) {
            when (mode) {
                SENDER_DATA_FETCH -> if (error) {
                    senderDownloadsFetchRetry = if (senderDownloadsFetchRetry >= 0) {
                        --senderDownloadsFetchRetry
                        if (view == null || activity == null || null == uiUpdateHandler) return
                        uiUpdateHandler!!.removeMessages(SENDER_DATA_FETCH)
                        uiUpdateHandler!!.sendMessageDelayed(uiUpdateHandler!!.obtainMessage(mode), 800)
                        return
                    } else SENDER_DATA_FETCH_RETRY_LIMIT
                    if (null != view) onDataFetchError()
                } else if (null != view) loadListing(result) else Log.e(TAG, "fragment may have been removed, File fetch")
                CHECK_SENDER_STATUS -> when {
                    error -> when {
                        senderStatusCheckRetryLimit > 1 -> {
                            --senderStatusCheckRetryLimit
                            uiUpdateHandler?.removeMessages(CHECK_SENDER_STATUS)
                            uiUpdateHandler?.sendMessageDelayed(uiUpdateHandler!!.obtainMessage(CHECK_SENDER_STATUS), 800)
                        }
                        activity is ReceiverActivity -> {
                            senderStatusCheckRetryLimit = SENDER_DATA_FETCH_RETRY_LIMIT
                            (activity as ReceiverActivity).resetSenderSearch()
                            Toast.makeText(activity, getString(R.string.p2p_receiver_error_sender_disconnected), Toast.LENGTH_SHORT).show()
                        }
                        else -> Log.e(TAG, "Activity is not instance of ReceiverActivity")
                    }
                    null != view -> {
                        senderStatusCheckRetryLimit = SENDER_DATA_FETCH_RETRY_LIMIT
                        uiUpdateHandler?.removeMessages(CHECK_SENDER_STATUS)
                        uiUpdateHandler?.sendMessageDelayed(uiUpdateHandler!!.obtainMessage(CHECK_SENDER_STATUS), 1000)
                    }
                    else -> Log.e(TAG, "fragment may have been removed: Sender api check")
                }
            }
        }

        @Throws(IOException::class)
        private fun downloadDataFromSender(apiUrl: String): String {
            val url = URL(apiUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.readTimeout = 10000
            conn.connectTimeout = 15000
            conn.requestMethod = "GET"
            conn.doInput = true
            // Starts the query
            conn.connect()
            //                int response =
            conn.responseCode
            //                Log.d(TAG, "The response is: " + response);
            return conn.inputStream.use {
                // Convert the InputStream into a string
                it.bufferedReader().readText()
            }
        }
    }

    private class UiUpdateHandler internal constructor(fragment: FilesListingFragment) : Handler() {
        var mFragment: WeakReference<FilesListingFragment> = WeakReference(fragment)
        override fun handleMessage(msg: Message) {
            val fragment = mFragment.get()
            when (msg.what) {
                CHECK_SENDER_STATUS -> fragment?.checkSenderAPIAvailablity()
                SENDER_DATA_FETCH -> fragment?.fetchSenderFiles()
            }
        }
    }

    companion object {
        private const val TAG = "FilesListingFragment"
        const val PATH_FILES = "http://%s:%s/files"
        const val PATH_STATUS = "http://%s:%s/status"
        const val PATH_FILE_DOWNLOAD = "http://%s:%s/file/%s"
        const val CHECK_SENDER_STATUS = 100
        const val SENDER_DATA_FETCH = 101
        private const val SENDER_DATA_FETCH_RETRY_LIMIT = 3
        fun getInstance(senderIp: String?, ssid: String?, senderName: String?, port: String?): FilesListingFragment {
            val fragment = FilesListingFragment()
            val data = Bundle().apply {
                putString("senderIp", senderIp)
                putString("ssid", ssid)
                putString("name", senderName)
                putString("port", port)
            }
            fragment.arguments = data
            return fragment
        }
    }
}