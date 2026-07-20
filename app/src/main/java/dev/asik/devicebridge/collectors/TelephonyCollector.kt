package dev.asik.devicebridge.collectors

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import dev.asik.devicebridge.hub.StreamHub
import dev.asik.devicebridge.model.TelephonyReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TelephonyCollector(
    private val context: Context,
    private val hub: StreamHub,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job != null) return
        publishOnce()
        job = scope.launch {
            while (isActive) {
                delay(30_000)
                publishOnce()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun publishOnce() {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val dataType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkTypeName(tm.dataNetworkType)
            } else {
                @Suppress("DEPRECATION")
                networkTypeName(tm.networkType)
            }
            val simState = when (tm.simState) {
                TelephonyManager.SIM_STATE_READY -> "ready"
                TelephonyManager.SIM_STATE_ABSENT -> "absent"
                TelephonyManager.SIM_STATE_PIN_REQUIRED -> "pin_required"
                TelephonyManager.SIM_STATE_PUK_REQUIRED -> "puk_required"
                TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "network_locked"
                else -> "unknown"
            }
            val phoneType = when (tm.phoneType) {
                TelephonyManager.PHONE_TYPE_GSM -> "gsm"
                TelephonyManager.PHONE_TYPE_CDMA -> "cdma"
                TelephonyManager.PHONE_TYPE_SIP -> "sip"
                TelephonyManager.PHONE_TYPE_NONE -> "none"
                else -> "unknown"
            }
            hub.publishTelephony(
                TelephonyReading(
                    network_operator_name = tm.networkOperatorName,
                    sim_operator_name = tm.simOperatorName,
                    data_network_type = dataType,
                    phone_type = phoneType,
                    sim_state = simState,
                    restricted = false,
                ),
            )
        } catch (se: SecurityException) {
            hub.publishTelephony(
                TelephonyReading(
                    restricted = true,
                    note = se.message ?: "SecurityException reading telephony",
                ),
            )
        } catch (e: Exception) {
            hub.publishTelephony(
                TelephonyReading(
                    restricted = true,
                    note = e.message ?: "telephony unavailable",
                ),
            )
        }
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
    }
}
