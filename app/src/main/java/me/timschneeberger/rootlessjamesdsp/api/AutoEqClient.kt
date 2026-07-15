package me.timschneeberger.rootlessjamesdsp.api

import android.content.Context
import com.pluto.plugins.network.PlutoInterceptor
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

class AutoEqClient(
    private val context: Context,
    callTimeout: Long = 10,
    customBaseUrl: String? = null,
) : KoinComponent {

    private val preferences: Preferences.App by inject()

    private val http = OkHttpClient.Builder()
        .callTimeout(callTimeout, TimeUnit.SECONDS)
        .addInterceptor(PlutoInterceptor())
        .addInterceptor(UserAgentInterceptor("RootlessZachDSP v${BuildConfig.VERSION_NAME}"))
        .build()

    private val retrofit: Retrofit
    private val service: AutoEqService

    init {
        val apiUrl = customBaseUrl ?: preferences.get<String>(R.string.key_network_autoeq_api_url)
        Timber.i("Using API url: $apiUrl")
        CrashlyticsImpl.setCustomKey("aeq_api_url", apiUrl)

        retrofit = Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(http)
            .addConverterFactory(ScalarsConverterFactory.create())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        service = retrofit.create(AutoEqService::class.java)
    }

    fun queryProfiles(
        query: String,
        onResponse: (Array<AeqSearchResult>, Boolean) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
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
                Timber.e(error, "queryProfiles failed")
                onFailure?.invoke(networkError(error.localizedMessage))
            }
        })
    }

    fun getProfile(
        id: Long,
        onResponse: (String, AeqSearchResult) -> Unit,
        onFailure: ((String) -> Unit)?,
    ) {
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
                Timber.e(error, "getProfile failed")
                onFailure?.invoke(networkError(error.localizedMessage))
            }
        })
    }

    private fun networkError(details: String?): String = buildString {
        append(context.getString(R.string.rootless_zach_autoeq_network_error))
        if (!details.isNullOrBlank()) {
            append(' ')
            append(details.trim())
        }
    }

    companion object {
        private const val HEADER_PARTIAL_RESULT = "X-Partial-Result"
        private const val HEADER_PROFILE_ID = "X-Profile-Id"
        private const val HEADER_PROFILE_NAME = "X-Profile-Name"
        private const val HEADER_PROFILE_SOURCE = "X-Profile-Source"
        private const val HEADER_PROFILE_RANK = "X-Profile-Rank"
    }
}
