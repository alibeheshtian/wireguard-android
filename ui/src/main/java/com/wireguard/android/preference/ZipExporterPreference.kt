/*
 * Copyright © 2017-2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.preference

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.AttributeSet
import android.util.Log
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.BiometricAuthenticator
import com.wireguard.android.util.DownloadsFileSaver
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.FragmentUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Preference implementing a button that asynchronously exports config zips.
 */
@ExperimentalCoroutinesApi
class ZipExporterPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs), CoroutineScope {
    private var exportedFilePath: String? = null
    private val job = Job()
    override val coroutineContext
        get() = job + Dispatchers.IO

    private suspend fun exportZip() {
        exportZip(Application.getTunnelManager().getTunnelsAsync())
    }

    private suspend fun exportZip(tunnels: List<ObservableTunnel>) = withContext(Dispatchers.IO) {
        val deferredConfigs = tunnels.map { it.getConfigDeferred() }.toList()
        if (deferredConfigs.isEmpty()) {
            exportZipComplete(null, IllegalArgumentException(
                    context.getString(R.string.no_tunnels_error)))
            return@withContext
        }
        val result = CompletableDeferred<String?>()
        try {
            deferredConfigs.awaitAll().let {
                val outputFile = DownloadsFileSaver.save(context, "wireguard-export.zip", "application/zip", true)
                try {
                    ZipOutputStream(outputFile.outputStream).use { zip ->
                        deferredConfigs.forEachIndexed { i, deferred ->
                            zip.putNextEntry(ZipEntry("${tunnels[i].name}.conf"))
                            zip.write(deferred.await().toWgQuickString().toByteArray(Charsets.UTF_8))
                        }
                        zip.closeEntry()
                    }
                } catch (e: Exception) {
                    outputFile.delete()
                    throw e
                }
                result.complete(outputFile.fileName)
            }
        } catch (e: Exception) {
            result.completeExceptionally(e)
        }
        result.getCompletionExceptionOrNull().let {
            if (it == null)
                exportZipComplete(result.await(), null)
            else
                exportZipComplete(null, it)
        }
    }

    private suspend fun exportZipComplete(filePath: String?, throwable: Throwable?) = withContext(Dispatchers.Main) {
        if (throwable != null) {
            val error = ErrorMessages[throwable]
            val message = context.getString(R.string.zip_export_error, error)
            Log.e(TAG, message, throwable)
            Snackbar.make(
                    FragmentUtils.getPrefActivity(this@ZipExporterPreference).findViewById(android.R.id.content),
                    message, Snackbar.LENGTH_LONG).show()
            isEnabled = true
        } else {
            exportedFilePath = filePath
            notifyChanged()
        }
    }

    override fun getSummary() = if (exportedFilePath == null) context.getString(R.string.zip_export_summary) else context.getString(R.string.zip_export_success, exportedFilePath)

    override fun getTitle() = context.getString(R.string.zip_export_title)

    override fun onClick() {
        val prefActivity = FragmentUtils.getPrefActivity(this)
        val fragment = prefActivity.supportFragmentManager.fragments.first()
        BiometricAuthenticator.authenticate(R.string.biometric_prompt_zip_exporter_title, fragment) {
            when (it) {
                // When we have successful authentication, or when there is no biometric hardware available.
                is BiometricAuthenticator.Result.Success, is BiometricAuthenticator.Result.HardwareUnavailableOrDisabled -> {
                    prefActivity.ensurePermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)) { _, grantResults ->
                        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                            isEnabled = false
                            launch { exportZip() }
                        }
                    }
                }
                is BiometricAuthenticator.Result.Failure -> {
                    Snackbar.make(
                            prefActivity.findViewById(android.R.id.content),
                            it.message,
                            Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/ZipExporterPreference"
    }
}
