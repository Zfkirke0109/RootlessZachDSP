package me.timschneeberger.rootlessjamesdsp.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.pluto.plugins.network.PlutoInterceptor
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import okhttp3.Call as OkHttpCall
import okhttp3.Callback as OkHttpCallback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import org.kamranzafar.jtar.TarInputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class AutoEqClient(
    private val context: Context,
    callTimeout: Long = 10,
    customBaseUrl: String? = null,
) : KoinComponent {

    private val preferences: Preferences.App by inject()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .callTimeout(callTimeout, TimeUnit.SECONDS)
        .addInterceptor(PlutoInterceptor())
        .addInterceptor(UserAgentInterceptor("RootlessZachDSP v${BuildConfig.VERSION_NAME}"))
        .build()

    private val apiUrl = customBaseUrl ?: preferences.get<String>(R.string.key_network_autoeq_api_url)
    private val usePackageBackend = isPackageBackend(apiUrl)
    private val service: AutoEqService?
    @Volatile private var packageIndex: Array<AeqSearchResult>? = null

    init {
        Timber.i("Using AutoEQ source: $apiUrl (packageBackend=$usePackageBackend)")
        CrashlyticsImpl.setCustomKey("aeq_api_url", apiUrl)

        service = if (usePackageBackend) {
            null
        } else {
            runCatching {
                Retrofit.Builder()
                    .baseUrl(apiUrl.ensureTrailingSlash())
                    .client(http)
                    .addConverterFactory(ScalarsConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AutoEqService::class.java)
            }.onFailure {
                Timber.e(it, "Unable to initialize the configured AutoEQ API")
            }.getOrNull()
        }
    }

    fun queryProfiles(
        query: String,
        onResponse: (Array<AeqSearchResult>, Boolean) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        if (usePackageBackend || service == null) {
            queryPackageIndex(query, onResponse, onFailure)
            return
        }

        service.queryProfiles(query).enqueue(object : Callback<Array<AeqSearchResult>> {
            override fun onResponse(
                call: Call<Array<AeqSearchResult>>,
                response: Response<Array<AeqSearchResult>>,
            ) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        onResponse(body, response.headers()[HEADER_PARTIAL_RESULT] == "1")
                    } else {
                        onFailure?.invoke(networkError("The server returned an empty response."))
                    }
                } else if (response.code() == 404) {
                    Timber.w("Configured AutoEQ API returned 404; using GitHub package fallback")
                    queryPackageIndex(query, onResponse, onFailure)
                } else {
                    val details = context.getString(
                        R.string.geq_api_network_error_details_code,
                        response.code(),
                    )
                    onFailure?.invoke(networkError(details))
                    Timber.e(
                        "queryProfiles: Server responded with %d (%s)",
                        response.code(),
                        response.errorBody()?.string(),
                    )
                }
            }

            override fun onFailure(call: Call<Array<AeqSearchResult>>, error: Throwable) {
                Timber.e(error, "queryProfiles failed; using GitHub package fallback")
                queryPackageIndex(query, onResponse, onFailure)
            }
        })
    }

    fun getProfile(
        id: Long,
        onResponse: (String, AeqSearchResult) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        if (usePackageBackend || service == null) {
            getPackageProfile(id, onResponse, onFailure)
            return
        }

        service.getProfile(id).enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        val result = AeqSearchResult(
                            response.headers()[HEADER_PROFILE_NAME],
                            response.headers()[HEADER_PROFILE_SOURCE],
                            response.headers()[HEADER_PROFILE_RANK]?.toIntOrNull(),
                            response.headers()[HEADER_PROFILE_ID]?.toLongOrNull(),
                        )
                        onResponse(body, result)
                    } else {
                        onFailure?.invoke(networkError("The server returned an empty profile."))
                    }
                } else if (response.code() == 404) {
                    Timber.w("Configured AutoEQ API returned 404; using GitHub package fallback")
                    getPackageProfile(id, onResponse, onFailure)
                } else {
                    val details = context.getString(
                        R.string.geq_api_network_error_details_code,
                        response.code(),
                    )
                    onFailure?.invoke(networkError(details))
                    Timber.e(
                        "getProfile: Server responded with %d (%s)",
                        response.code(),
                        response.errorBody()?.string(),
                    )
                }
            }

            override fun onFailure(call: Call<String>, error: Throwable) {
                Timber.e(error, "getProfile failed; using GitHub package fallback")
                getPackageProfile(id, onResponse, onFailure)
            }
        })
    }

    private fun queryPackageIndex(
        query: String,
        onResponse: (Array<AeqSearchResult>, Boolean) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        ensurePackageIndex(
            onSuccess = { index ->
                http.dispatcher.executorService.execute {
                    val terms = query
                        .trim()
                        .lowercase(Locale.ROOT)
                        .split(Regex("\\s+"))
                        .filter(String::isNotBlank)

                    val matches = index
                        .asSequence()
                        .filter { result ->
                            val haystack = "${result.name.orEmpty()} ${result.source.orEmpty()}"
                                .lowercase(Locale.ROOT)
                            terms.all(haystack::contains)
                        }
                        .sortedWith(
                            compareBy<AeqSearchResult> {
                                (it.rank ?: -1).takeIf { rank -> rank >= 0 } ?: Int.MAX_VALUE
                            }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.source.orEmpty() }
                        )
                        .toList()

                    val partial = matches.size > MAX_QUERY_RESULTS
                    postSuccess {
                        onResponse(matches.take(MAX_QUERY_RESULTS).toTypedArray(), partial)
                    }
                }
            },
            onFailure = onFailure,
        )
    }

    private fun getPackageProfile(
        id: Long,
        onResponse: (String, AeqSearchResult) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        ensurePackageIndex(
            onSuccess = { index ->
                val result = index.firstOrNull { it.id == id }
                if (result?.name.isNullOrBlank() || result?.source.isNullOrBlank()) {
                    postFailure(onFailure, "The selected AutoEQ profile is no longer available.")
                    return@ensurePackageIndex
                }

                loadPackageArchive(
                    onSuccess = { archive ->
                        http.dispatcher.executorService.execute {
                            runCatching {
                                extractGraphicEq(
                                    archive = archive,
                                    profileName = requireNotNull(result?.name),
                                    profileSource = requireNotNull(result?.source),
                                )
                            }.onSuccess { profile ->
                                postSuccess { onResponse(profile, requireNotNull(result)) }
                            }.onFailure { error ->
                                Timber.e(error, "Unable to read AutoEQ profile from package")
                                postFailure(onFailure, error.localizedMessage)
                            }
                        }
                    },
                    onFailure = onFailure,
                )
            },
            onFailure = onFailure,
        )
    }

    private fun ensurePackageIndex(
        onSuccess: (Array<AeqSearchResult>) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        packageIndex?.let(onSuccess) ?: http.newCall(
            Request.Builder().url(PACKAGE_INDEX_URL).get().build()
        ).enqueue(object : OkHttpCallback {
            override fun onFailure(call: OkHttpCall, error: IOException) {
                Timber.e(error, "Unable to download AutoEQ package index")
                postFailure(onFailure, error.localizedMessage)
            }

            override fun onResponse(call: OkHttpCall, response: OkHttpResponse) {
                response.use {
                    if (!it.isSuccessful) {
                        postFailure(onFailure, "AutoEQ package index returned HTTP ${it.code}.")
                        return
                    }

                    val body = it.body?.string()
                    if (body.isNullOrBlank()) {
                        postFailure(onFailure, "The AutoEQ package index was empty.")
                        return
                    }

                    runCatching {
                        gson.fromJson(body, Array<AeqSearchResult>::class.java)
                    }.onSuccess { index ->
                        packageIndex = index
                        onSuccess(index)
                    }.onFailure { error ->
                        Timber.e(error, "Unable to parse AutoEQ package index")
                        postFailure(onFailure, error.localizedMessage)
                    }
                }
            }
        })
    }

    private fun loadPackageArchive(
        onSuccess: (File) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
        val archive = File(context.cacheDir, PACKAGE_ARCHIVE_FILE)
        val isFresh = archive.isFile && archive.length() > 0L &&
            System.currentTimeMillis() - archive.lastModified() < PACKAGE_CACHE_MAX_AGE_MS
        if (isFresh) {
            onSuccess(archive)
            return
        }

        http.newCall(Request.Builder().url(PACKAGE_ARCHIVE_URL).get().build())
            .enqueue(object : OkHttpCallback {
                override fun onFailure(call: OkHttpCall, error: IOException) {
                    Timber.e(error, "Unable to download AutoEQ package archive")
                    postFailure(onFailure, error.localizedMessage)
                }

                override fun onResponse(call: OkHttpCall, response: OkHttpResponse) {
                    response.use {
                        if (!it.isSuccessful) {
                            postFailure(onFailure, "AutoEQ package archive returned HTTP ${it.code}.")
                            return
                        }

                        val body = it.body
                        if (body == null) {
                            postFailure(onFailure, "The AutoEQ package archive was empty.")
                            return
                        }

                        val temporary = File(context.cacheDir, "$PACKAGE_ARCHIVE_FILE.tmp")
                        runCatching {
                            temporary.delete()
                            body.byteStream().use { input ->
                                FileOutputStream(temporary).use { output ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    var total = 0L
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        total += count
                                        if (total > MAX_ARCHIVE_DOWNLOAD_BYTES) {
                                            throw IOException("AutoEQ package archive exceeds the safety limit")
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                    output.fd.sync()
                                }
                            }
                            if (!temporary.renameTo(archive)) {
                                temporary.copyTo(archive, overwrite = true)
                                temporary.delete()
                            }
                            archive
                        }.onSuccess(onSuccess).onFailure { error ->
                            temporary.delete()
                            Timber.e(error, "Unable to cache AutoEQ package archive")
                            postFailure(onFailure, error.localizedMessage)
                        }
                    }
                }
            })
    }

    private fun extractGraphicEq(
        archive: File,
        profileName: String,
        profileSource: String,
    ): String {
        val target = "$profileName/$profileSource/graphic.txt"
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { gzip ->
            TarInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val entryName = entry.name.removePrefix("./")
                    if (entryName != target) continue

                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = tar.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_PROFILE_BYTES) {
                            throw IOException("AutoEQ profile exceeds the safety limit")
                        }
                        output.write(buffer, 0, count)
                    }
                    return output.toString(Charsets.UTF_8.name())
                }
            }
        }
        throw IOException("AutoEQ profile file was not found in the package")
    }

    private fun postFailure(onFailure: ((String) -> Unit)?, details: String?) {
        onFailure ?: return
        postSuccess { onFailure(networkError(details)) }
    }

    private fun postSuccess(callback: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) callback() else mainHandler.post(callback)
    }

    private fun networkError(details: String?): String = buildString {
        append(context.getString(R.string.rootless_zach_autoeq_network_error))
        if (!details.isNullOrBlank()) {
            append(' ')
            append(details.trim())
        }
    }

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    companion object {
        private const val LEGACY_API_URL = "https://aeq.timschneeberger.me/"
        private const val PACKAGE_BASE_URL =
            "https://raw.githubusercontent.com/timschneeb/AutoEqPackages/main/"
        private const val PACKAGE_INDEX_URL = "${PACKAGE_BASE_URL}index.json"
        private const val PACKAGE_ARCHIVE_URL =
            "https://github.com/timschneeb/AutoEqPackages/releases/latest/download/archive.tar.gz"
        private const val PACKAGE_ARCHIVE_FILE = "autoeq-packages.tar.gz"
        private const val MAX_QUERY_RESULTS = 100
        private const val MAX_PROFILE_BYTES = 2 * 1024 * 1024
        private const val MAX_ARCHIVE_DOWNLOAD_BYTES = 1024L * 1024L * 1024L
        private const val PACKAGE_CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

        private const val HEADER_PARTIAL_RESULT = "X-Partial-Result"
        private const val HEADER_PROFILE_ID = "X-Profile-Id"
        private const val HEADER_PROFILE_NAME = "X-Profile-Name"
        private const val HEADER_PROFILE_SOURCE = "X-Profile-Source"
        private const val HEADER_PROFILE_RANK = "X-Profile-Rank"

        private fun isPackageBackend(url: String): Boolean {
            val normalized = url.trim().ensureTrailingSlash()
            return normalized.equals(LEGACY_API_URL, ignoreCase = true) ||
                normalized.equals(PACKAGE_BASE_URL, ignoreCase = true)
        }

        private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"
    }
}
