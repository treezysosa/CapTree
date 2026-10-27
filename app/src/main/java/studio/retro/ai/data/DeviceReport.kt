package studio.retro.ai.data

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import org.json.JSONArray
import org.json.JSONObject
import studio.retro.ai.BuildConfig
import java.security.MessageDigest

/** An explicit local report. Never includes identifiers, paths, projects or server settings. */
object DeviceReport {
    @Suppress("DEPRECATION")
    fun collect(context:Context):String {
        val memory=ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info=context.packageManager.getPackageInfo(context.packageName,flags)
        val certificates=if(Build.VERSION.SDK_INT>=28)info.signingInfo?.apkContentsSigners.orEmpty() else info.signatures.orEmpty()
        val encoders=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.supportedTypes.any { type -> type.equals("video/avc",ignoreCase=true) } }.map { it.name }.sorted()
        return JSONObject().apply {
            put("report_version",1); put("application_id",context.packageName)
            put("version_name",info.versionName); put("version_code",if(Build.VERSION.SDK_INT>=28)info.longVersionCode else info.versionCode.toLong())
            put("build_type",BuildConfig.BUILD_TYPE); put("development_build",BuildConfig.DEBUG)
            put("android_api",Build.VERSION.SDK_INT); put("cpu_abis",JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("page_size_bytes",Os.sysconf(OsConstants._SC_PAGESIZE))
            put("app_heap_limit_bytes",Runtime.getRuntime().maxMemory())
            put("system_available_memory_bytes",memory.availMem); put("system_low_memory",memory.lowMemory)
            put("private_storage_available_bytes",StatFs(context.filesDir.path).availableBytes)
            put("h264_encoders",JSONArray(encoders))
            put("signer_sha256",JSONArray(certificates.map { signature -> MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }))
            put("notice","Local snapshot only. Available memory/storage can change. Codec presence and page size do not certify AI model compatibility. No media or connection data is included. Nothing is uploaded.")
        }.toString(2)
    }
}
