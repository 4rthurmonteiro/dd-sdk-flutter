/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */
package com.datadoghq.flutter

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.datadog.android.Datadog
import com.datadog.android.DatadogSite
import com.datadog.android._InternalProxy
import com.datadog.android.core.configuration.Configuration
import com.datadog.android.log.model.LogEvent
import com.datadog.android.privacy.TrackingConsent
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DatadogSdkPlugin : FlutterPlugin, MethodCallHandler {
    companion object {
        const val CONTRACT_VIOLATION = "DatadogSdk:ContractViolation"
        const val INVALID_OPERATION = "DatadogSdk:InvalidOperation"
        const val ARG_VALUE = "value"

        // Flutter can destroy / recreate the plugin object if the engine detaches. If you use the
        // back button on the first screen, for example, this will detach the Flutter engine
        // but the application will still be running. We keep the configuration separate
        // from the plugin to warn about reinitialization.
        var previousConfiguration: Map<String, Any?>? = null
    }

    data class ConfigurationTelemetryOverrides(
        var trackViewsManually: Boolean = true,
        var trackInteractions: Boolean = false,
        var trackErrors: Boolean = false,
        var trackNetworkRequests: Boolean = false,
        var trackNativeViews: Boolean = false,
        var trackCrossPlatformLongTasks: Boolean = false,
        var trackFlutterPerformance: Boolean = false,
        var dartVersion: String? = null
    )

    private lateinit var channel: MethodChannel
    private var binding: FlutterPlugin.FlutterPluginBinding? = null

    internal val telemetryOverrides = ConfigurationTelemetryOverrides()

    // Only used to shutdown Datadog in debug builds
    private val executor: ExecutorService = ThreadPoolExecutor(
        0,
        1,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue()
    )

    val logsPlugin: DatadogLogsPlugin = DatadogLogsPlugin()
    val rumPlugin: DatadogRumPlugin = DatadogRumPlugin()

    override fun onAttachedToEngine(
        flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "datadog_sdk_flutter")
        channel.setMethodCallHandler(this)

        binding = flutterPluginBinding

        logsPlugin.attachToEngine(flutterPluginBinding)
        rumPlugin.attachToEngine(flutterPluginBinding)
    }

    @Suppress("LongMethod")
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "initialize" -> {
                val configArg = call.argument<Map<String, Any?>>("configuration")
                val trackingConsent = call.argument<String>("trackingConsent")?.let {
                    parseTrackingConsent(it)
                }
                if (configArg != null && trackingConsent != null) {
                    if (!Datadog.isInitialized()) {
                        initialize(configArg, trackingConsent)
                        previousConfiguration = configArg
                    } else if (configArg != previousConfiguration) {
                        // Maybe use DevLogger instead?
                        Log.e(DATADOG_FLUTTER_TAG, MESSAGE_INVALID_REINITIALIZATION)
                    }
                    telemetryOverrides.dartVersion = call.argument<String>("dartVersion")
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
//            "attachToExisting" -> {
//                if (Datadog.isInitialized()) {
//                    val attachResult = attachToExisting()
//                    result.success(attachResult)
//                } else {
//                    Log.e(DATADOG_FLUTTER_TAG, MESSAGE_NO_EXISTING_INSTANCE)
//                    result.success(null)
//                }
//            }
            "setSdkVerbosity" -> {
                val value = call.argument<String>(ARG_VALUE)
                if (value != null) {
                    val verbosity = parseCoreLoggerLevel(value)
                    Datadog.setVerbosity(verbosity)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "setTrackingConsent" -> {
                val value = call.argument<String>(ARG_VALUE)
                if (value != null) {
                    val trackingConsent = parseTrackingConsent(value)
                    Datadog.setTrackingConsent(trackingConsent)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "setUserInfo" -> {
                val id = call.argument<String>("id")
                val name = call.argument<String>("name")
                val email = call.argument<String>("email")
                val extraInfo = call.argument<Map<String, Any?>>("extraInfo")
                if (extraInfo != null) {
                    Datadog.setUserInfo(id, name, email, extraInfo)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "addUserExtraInfo" -> {
                val extraInfo = call.argument<Map<String, Any?>>("extraInfo")
                if (extraInfo != null) {
                    Datadog.addUserProperties(extraInfo)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "telemetryDebug" -> {
                val message = call.argument<String>("message")
                if (message != null) {
                    Datadog._internalProxy()._telemetry.debug(message)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "telemetryError" -> {
                val message = call.argument<String>("message")
                if (message != null) {
                    val stack = call.argument<String>("stack")
                    val kind = call.argument<String>("kind")
                    Datadog._internalProxy()._telemetry.error(message, stack, kind)
                    result.success(null)
                } else {
                    result.missingParameter(call.method)
                }
            }
            "updateTelemetryConfiguration" -> {
                updateTelemetryConfiguration(call)
                result.success(null)
            }
            "getInternalVar" -> {
                val name = call.argument<String>("name")
                if (name != null) {
                    val value = getInternalVar(name)
                    result.success(value)
                } else {
                    result.success(null)
                }
            }
            "flushAndDeinitialize" -> {
                invokePrivateShutdown(result)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    fun initialize(encoded: Map<String, Any?>, trackingConsent: TrackingConsent) {
        var configBuilder = configurationBuilderFromEncoded(encoded)
        if (configBuilder == null) {
            return
        }

        binding?.let {
            Datadog.initialize(
                it.applicationContext,
                configBuilder.build(),
                trackingConsent
            )
        }
    }

    @Suppress("ComplexMethod", "FunctionMaxLength")
    fun configurationBuilderFromEncoded(encoded: Map<String, Any?>): Configuration.Builder? {
        val clientToken = encoded["clientToken"] as? String
        val env = encoded["env"] as? String
        if (clientToken == null || env == null) {
            return null
        }

        val service = encoded["service"] as? String
        var needsClearTextHttp = false
        var variant = ""
        (encoded["additionalConfig"] as? Map<String, Any?>)?.let {
            variant = (it["_dd.variant"] as? String) ?: ""
            needsClearTextHttp = (it["_dd.needsClearTextHttp"] as? Boolean) ?: false
        }

        var builder = Configuration.Builder(clientToken, env, variant, service)

        (encoded["site"] as? String)?.let {
            builder = builder.useSite(parseSite(it))
        }

        if (needsClearTextHttp) {
            builder = _InternalProxy.allowClearTextHttp(builder)
        }

        (encoded["batchSize"] as? String)?.let {
            builder = builder.setBatchSize(parseBatchSize(it))
        }
        (encoded["uploadFrequency"] as? String)?.let {
            builder = builder.setUploadFrequency(parseUploadFrequency(it))
        }
        (encoded["additionalConfig"] as? Map<String, Any>)?.let {
            builder = builder.setAdditionalConfiguration(it)
        }
        (encoded["nativeCrashReportEnabled"] as? Boolean)?.let {
            builder = builder.setCrashReportsEnabled(it)
        }

        return builder
    }

//    private fun attachToExisting(): Map<String, Any> {
//        var rumEnabled = false
//        if (GlobalRum.isRegistered()) {
//            rumEnabled = true
//            rumPlugin.attachToExistingSdk()
//        }
//
//        return mapOf<String, Any>(
//            "rumEnabled" to rumEnabled
//        )
//    }

    private fun updateTelemetryConfiguration(call: MethodCall) {
        var isValid = true

        val option = call.argument<String>("option")
        val value = call.argument<Boolean>(ARG_VALUE)
        if (option != null && value != null) {
            when (option) {
                "trackViewsManually" -> telemetryOverrides.trackViewsManually = value
                "trackInteractions" -> telemetryOverrides.trackInteractions = value
                "trackErrors" -> telemetryOverrides.trackErrors = value
                "trackNetworkRequests" -> telemetryOverrides.trackNetworkRequests = value
                "trackNativeViews" -> telemetryOverrides.trackNativeViews = value
                "trackCrossPlatformLongTasks" ->
                    telemetryOverrides.trackCrossPlatformLongTasks = value
                "trackFlutterPerformance" -> telemetryOverrides.trackFlutterPerformance = value
                else -> isValid = false
            }
        } else {
            isValid = false
        }

        if (isValid) {
            rumPlugin.telemetryOverrides = telemetryOverrides
        } else {
            Datadog._internalProxy()._telemetry.debug(
                String.format(MESSAGE_BAD_TELEMETRY_CONFIG, option, value)
            )
        }
    }

    private fun simpleInvokeOn(methodName: String, target: Any) {
        val klass = target.javaClass
        val method = klass.declaredMethods.firstOrNull {
            it.name == methodName || it.name == "$methodName\$dd_sdk_android_core_release"
        }
        method?.let {
            it.isAccessible = true
            it.invoke(target)
        }
    }

//    private fun attachEventMappers(
//        config: DatadogFlutterConfiguration,
//        configBuilder: Configuration.Builder
//    ) {
//        if (config.attachLogMapper) {
//            configBuilder.setLogEventMapper(
//                object : EventMapper<LogEvent> {
//                    override fun map(event: LogEvent): LogEvent? {
//                        return mapLogEvent(event)
//                    }
//                }
//            )
//        }
//
//        config.rumConfiguration?.let {
//            if (it.attachViewEventMapper) {
//                configBuilder.setRumViewEventMapper(
//                    object : ViewEventMapper {
//                        override fun map(event: ViewEvent): ViewEvent {
//                            return rumPlugin.mapViewEvent(event)
//                        }
//                    }
//                )
//            }
//            if (it.attachActionEventMapper) {
//                configBuilder.setRumActionEventMapper(
//                    object : EventMapper<ActionEvent> {
//                        override fun map(event: ActionEvent): ActionEvent? {
//                            return rumPlugin.mapActionEvent(event)
//                        }
//                    }
//                )
//            }
//            if (it.attachResourceEventMapper) {
//                configBuilder.setRumResourceEventMapper(
//                    object : EventMapper<ResourceEvent> {
//                        override fun map(event: ResourceEvent): ResourceEvent? {
//                            return rumPlugin.mapResourceEvent(event)
//                        }
//                    }
//                )
//            }
//            if (it.attachErrorEventMapper) {
//                configBuilder.setRumErrorEventMapper(
//                    object : EventMapper<ErrorEvent> {
//                        override fun map(event: ErrorEvent): ErrorEvent? {
//                            return rumPlugin.mapErrorEvent(event)
//                        }
//                    }
//                )
//            }
//            if (it.attachLongTaskEventMapper) {
//                configBuilder.setRumLongTaskEventMapper(
//                    object : EventMapper<LongTaskEvent> {
//                        override fun map(event: LongTaskEvent): LongTaskEvent? {
//                            return rumPlugin.mapLongTaskEvent(event)
//                        }
//                    }
//                )
//            }
//        }
//    }

    @Suppress("TooGenericExceptionCaught")
    internal fun mapLogEvent(event: LogEvent): LogEvent? {
        val jsonEvent = event.toJson().asMap()
        var modifiedJson: Map<String, Any?>? = null

        val latch = CountDownLatch(1)

        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                channel.invokeMethod(
                    "mapLogEvent",
                    mapOf(
                        "event" to jsonEvent
                    ),
                    object : Result {
                        override fun success(result: Any?) {
                            @Suppress("UNCHECKED_CAST")
                            modifiedJson = result as? Map<String, Any?>
                            latch.countDown()
                        }

                        override fun error(
                            errorCode: String,
                            errorMessage: String?,
                            errorDetails: Any?
                        ) {
                            // No telemetry needed, this is likely an issue in user code
                            latch.countDown()
                        }

                        override fun notImplemented() {
                            Datadog._internalProxy()._telemetry.error(
                                "mapLogEvent returned notImplemented."
                            )
                            latch.countDown()
                        }
                    }
                )
            } catch (e: Exception) {
                Datadog._internalProxy()._telemetry.error("Attempting call mapLogEvent failed.", e)
                latch.countDown()
            }
        }

        try {
            // Stalls until the method channel finishes
            if (!latch.await(1, TimeUnit.SECONDS)) {
                Datadog._internalProxy()._telemetry.debug("logMapper timed out")
                return event
            }

            return modifiedJson?.let {
                if (!it.containsKey("_dd.mapper_error")) {
                    val modifiedEvent = LogEvent.fromJsonObject(modifiedJson!!.toJsonObject())

                    event.status = modifiedEvent.status
                    event.message = modifiedEvent.message
                    event.ddtags = modifiedEvent.ddtags
                    event.logger.name = modifiedEvent.logger.name

                    event.additionalProperties.clear()
                    event.additionalProperties.putAll(modifiedEvent.additionalProperties)
                }
                event
            }
        } catch (e: Exception) {
            Datadog._internalProxy()._telemetry.error(
                "Attempt to deserialize mapped log event failed, or latch await was interrupted." +
                    " Returning unmodified event.",
                e
            )
            return event
        }
    }

    private fun getInternalVar(name: String): Any? {
        return when (name) {
            "mapperPerformance" ->
                mapOf(
                    "total" to mapOf(
                        "minMs" to rumPlugin.mapperPerf.minInMs,
                        "maxMs" to rumPlugin.mapperPerf.maxInMs,
                        "avgMs" to rumPlugin.mapperPerf.avgInMs
                    ),
                    "mainThread" to mapOf(
                        "minMs" to rumPlugin.mapperPerfMainThread.minInMs,
                        "maxMs" to rumPlugin.mapperPerfMainThread.maxInMs,
                        "avgMs" to rumPlugin.mapperPerfMainThread.avgInMs
                    ),
                    "mapperTimeouts" to rumPlugin.mapperTimeouts
                )
            else -> null
        }
    }

    private fun invokePrivateShutdown(result: Result) {
        executor.submit {
            simpleInvokeOn("flushAndShutdownExecutors", Datadog)
            Datadog.stopInstance()

            // TODO: deregister logs / RUM plugin
        }.get()

        result.success(null)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)

        logsPlugin.detachFromEngine()
        rumPlugin.detachFromEngine()
    }
}

internal fun parseTrackingConsent(trackingConsent: String): TrackingConsent {
    return when (trackingConsent) {
        "TrackingConsent.granted" -> TrackingConsent.GRANTED
        "TrackingConsent.notGranted" -> TrackingConsent.NOT_GRANTED
        "TrackingConsent.pending" -> TrackingConsent.PENDING
        else -> TrackingConsent.PENDING
    }
}

internal fun parseSite(site: String): DatadogSite {
    return when (site) {
        "DatadogSite.us1" -> DatadogSite.US1
        "DatadogSite.us3" -> DatadogSite.US3
        "DatadogSite.us5" -> DatadogSite.US5
        "DatadogSite.eu1" -> DatadogSite.EU1
        "DatadogSite.us1Fed" -> DatadogSite.US1_FED
        "DatadogSite.ap1" -> DatadogSite.AP1
        else -> DatadogSite.US1
    }
}

internal fun parseCoreLoggerLevel(level: String): Int {
    return when (level) {
        "CoreLoggerLevel.debug" -> Log.DEBUG
        "CoreLoggerLevel.warn" -> Log.WARN
        "CoreLoggerLevel.error" -> Log.ERROR
        "CoreLoggerLevel.critical" -> Log.ASSERT
        else -> Log.INFO
    }
}

internal const val DATADOG_FLUTTER_TAG = "DatadogFlutter"

internal const val MESSAGE_INVALID_REINITIALIZATION =
    "🔥 Reinitializing the DatadogSDK with different options, even after a hot restart, is not" +
        " supported. Cold restart your application to change your current configuration."

internal const val MESSAGE_NO_EXISTING_INSTANCE =
    "🔥 attachToExisting was called, but no existing instance of the Datadog SDK exists." +
        " Make sure to initialize the Native Datadog SDK before calling attachToExisting."

internal const val MESSAGE_BAD_TELEMETRY_CONFIG =
    "Attempting to set telemetry configuration option '%s' to '%s', which is invalid."
