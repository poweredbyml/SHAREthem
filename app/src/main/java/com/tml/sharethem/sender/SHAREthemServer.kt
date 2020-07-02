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

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.TextUtils
import android.util.Log
import com.tml.sharethem.R
import org.json.JSONArray
import java.io.*

/**
 * A Tiny Http Server extended from [NanoHTTPD]. Once started, serves selected files from [SHAREthemActivity] on an assigned PORT.
 *
 *
 * Created by Sri on 18/12/16.
 */
internal class SHAREthemServer(host_name: String?, port: Int) : NanoHTTPD(host_name, port) {
    private lateinit var m_filesTobeHosted: Array<String>
    private var m_clientsFileTransferListener: FileTransferStatusListener? = null
    private var m_context: Context? = null

    constructor(context: Context?, statusListener: FileTransferStatusListener?, filesToBeHosted: Array<String>, port: Int) : this(null, port) {
        m_context = context
        m_clientsFileTransferListener = statusListener
        m_filesTobeHosted = filesToBeHosted
    }

    override fun serve(session: IHTTPSession): Response? {
        var res: Response? = null
        try {
            val url = session.uri
            Log.d(TAG, "request uri: $url")
            when {
                TextUtils.isEmpty(url) || url == "/" || "/open" in url!! -> {
                    res = createHtmlResponse()
                }
                url == "/status" -> {
                    res = Response(Response.Status.OK, MIME_PLAINTEXT, "Available")
                }
                url == "/apk" -> {
                    res = createApkResponse(session.headers?.get("http-client-ip"))
                }
                url == "/logo" || url == "/favicon.ico" -> {
                    res = createLogoResponse()
                }
                url == "/files" -> {
                    res = createFilePathsResponse()
                }
                "/file/" in url -> {
                    val index = url.replace("/file/", "").toInt()
                    if (index != -1) {
                        res = createFileResponse(m_filesTobeHosted[index], session.headers?.get("http-client-ip"))
                    }
                }
            }
        } catch (ioe: Exception) {
            ioe.printStackTrace()
            res = createErrorResponse(ioe.message)
        } finally {
            if (null == res) {
                res = createErrorResponse()
            }
        }
        res.addHeader("Accept-Ranges", "bytes")
        res.addHeader("Access-Control-Allow-Origin", "*")
        res.addHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        return res
    }
    // increasing targetSdkVersion version might impact behaviour of this library
    // if targetSdkVersion >= 23
    //      1. ShareActivity has to check for System Write permissions to proceed
    //      2. Get Wifi Scan results method needs GPS to be ON and COARSE location permission
    //      library checks the targetSdkVersion to take care of above scenarios
    // if targetSdkVersion > 20
    //      If an application's target SDK version is LOLLIPOP or newer, network communication may not use Wi-Fi even if Wi-Fi is connected;
    //  this might impact when Receiver connectivity to SHAREthem hotspot, library checks for this scenario and prompts user to disable data
    //      For more info: https://developer.android.com/reference/android/net/wifi/WifiManager.html#enableNetwork(int, boolean)
    /**
     * Creates an Error [com.tml.sharethem.sender.NanoHTTPD.Response] with
     *
     * @param status  error Status like `Response.Status.FORBIDDEN`
     * @param message error message
     * @return [com.tml.sharethem.sender.NanoHTTPD.Response]
     */
    private fun createErrorResponse(message: String? = "FORBIDDEN: Reading file failed."): Response {
        Log.e(TAG, "error while creating response: $message")
        return Response(Response.Status.FORBIDDEN, MIME_PLAINTEXT, message)
    }

    /**
     * Creates a success [com.tml.sharethem.sender.NanoHTTPD.Response] with Shared Files URLS data in @[com.google.gson.JsonArray] format
     *
     * @return [com.tml.sharethem.sender.NanoHTTPD.Response]
     */
    private fun createFilePathsResponse(): Response {
        return Response(Response.Status.OK, MIME_JSON, JSONArray(listOf(*m_filesTobeHosted)).toString())
    }

    /**
     * Creates a success `Response` with binary data of file in [SHAREthemServer.m_filesTobeHosted] with provided index.
     *
     * @param clientIp Receiver IP to which Response is intended for
     * @param fileUrl  url of file among Shared files array
     * @return [com.tml.sharethem.sender.NanoHTTPD.Response]
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun createFileResponse(fileUrl: String, clientIp: String?): Response {
        val file = File(fileUrl)
        Log.d(TAG, "resolve info found, file location: ${file.absolutePath}, file length: ${file.length()}, file name: ${file.name}")
        val res = Response(Response.Status.OK, MIME_FORCE_DOWNLOAD, clientIp, file, m_clientsFileTransferListener)
        res.addHeader("Content-Length", "${file.length()}")
        res.addHeader("Content-Disposition", "attachment; filename='${file.name}'")
        return res
    }

    private fun createHtmlResponse(): Response {
        val answer: String = m_context!!.assets.open("web_talk.html").bufferedReader().use {
            it.readText()
        }
        return Response(answer)
    }

    private fun createLogoResponse(): Response {
        try {
            val bitmap = BitmapFactory.decodeResource(m_context!!.resources, R.mipmap.ic_launcher)
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 0 /*ignored for PNG*/, bos)
            val bitmapdata = bos.toByteArray()
            val bs = ByteArrayInputStream(bitmapdata)
            val res = Response(Response.Status.OK, MIME_PNG, bs)
            res.addHeader("Accept-Ranges", "bytes")
            return res
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return createErrorResponse()
    }

    @Throws(IOException::class)
    private fun createApkResponse(ip: String?): Response? {
        var res: Response? = null
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        mainIntent.setPackage(m_context!!.packageName)
        val info = m_context!!.packageManager.resolveActivity(mainIntent, 0)
        if (null != info) {
            res = createFileResponse(info.activityInfo.applicationInfo.publicSourceDir, ip)
        }
        return res
    }

    companion object {
        private const val TAG = "ShareServer"
        private const val MIME_JSON = "application/json"
        private const val MIME_FORCE_DOWNLOAD = "application/force-download"
        private const val MIME_PNG = "image/png"
    }
}