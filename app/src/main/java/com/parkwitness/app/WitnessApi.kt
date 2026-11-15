package com.parkwitness.app
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class WitnessApi(private val base: String, private val token: String) {
    fun call(path: String, method: String = "GET", body: JSONObject? = null): String {
        val url=URL(base.trimEnd('/')+path)
        val localDemoHttp = BuildConfig.FLAVOR == "demo" &&
            url.protocol == "http" && url.host == "10.0.2.2"
        require(url.protocol == "https" || localDemoHttp || (BuildConfig.DEBUG && url.protocol == "http")) {
            "HTTPS 서버 주소를 사용하세요"
        }
        val connection=url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.requestMethod=method;connection.connectTimeout=8000;connection.readTimeout=8000
            if(token.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null) { connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code=connection.responseCode
            val text=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if(code !in 200..299) error("서버 응답 $code: "+runCatching { JSONObject(text).optString("detail") }.getOrDefault("연결을 확인하세요"))
            return text
        } finally { connection.disconnect() }
    }
}
