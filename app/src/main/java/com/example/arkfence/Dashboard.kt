package com.example.arkfence

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.example.arkfence.ui.theme.ArkfenceTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.net.HttpURLConnection
import java.net.URL

class Dashboard : ComponentActivity() {

    private lateinit var appUpdateService: AppUpdateService
    private var connectivityReceiver: ConnectivityReceiver? = null

    @SuppressLint("HardwareIds")
    private fun retrieveDeviceId(): String {
        return try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown-device"
        } catch (e: Exception) {
            Log.e("Dashboard", "Error getting device identifier: ${e.message}", e)
            "unknown-device"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appUpdateService = AppUpdateService(this)
        setContent {
            ArkfenceTheme {
                DashboardContent(deviceId = retrieveDeviceId())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        checkForUpdates()
    }

    override fun onPause() {
        super.onPause()
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered
            }
        }
    }

    private fun registerReceiver() {
        connectivityReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Not registered
            }
        }
        connectivityReceiver = ConnectivityReceiver {
            checkForUpdates()
        }
        val filter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        registerReceiver(connectivityReceiver, filter)
    }

    private fun checkForUpdates() {
        if (NetworkUtils.isNetworkAvailable(this)) {
            appUpdateService.checkForAppUpdate()
        }
    }

    inner class ConnectivityReceiver(private val onNetworkAvailable: () -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (NetworkUtils.isNetworkAvailable(context)) {
                onNetworkAvailable()
            }
        }
    }

    @Composable
    fun rememberUrlWithFallback(primaryUrl: String, fallbackUrl: String): String {
        val cacheKey = "$primaryUrl|$fallbackUrl"
        var currentUrl by remember(cacheKey) { mutableStateOf(primaryUrl) }
        var resolved by remember(cacheKey) { mutableStateOf(false) }

        LaunchedEffect(cacheKey) {
            if (!resolved) {
                val primaryDeferred = async(Dispatchers.IO) { isUrlReachable(primaryUrl) }
                val fallbackDeferred = async(Dispatchers.IO) { isUrlReachable(fallbackUrl) }
                val firstAvailableUrl = select<String?> {
                    primaryDeferred.onAwait { reachable -> if (reachable) primaryUrl else null }
                    fallbackDeferred.onAwait { reachable -> if (reachable) fallbackUrl else null }
                }
                currentUrl = firstAvailableUrl ?: primaryUrl
                resolved = true
            }
        }
        return currentUrl
    }

    suspend fun isUrlReachable(url: String): Boolean {
        return try {
            withTimeout(2000) {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.responseCode == HttpURLConnection.HTTP_OK
            }
        } catch (e: Exception) {
            false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun DashboardContent(deviceId: String) {
        val context = LocalContext.current
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        var currentLanguage by remember {
            mutableStateOf(prefs.getString("languageFlag", "en") ?: "en")
        }
        var employeeData by remember { mutableStateOf<EmployeeData?>(null) }
        var isLoading by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(deviceId) {
            isLoading = true
            errorMessage = null
            RetrofitClient.instance.getProfile(deviceId).enqueue(object : Callback<ProfileResponse> {
                override fun onResponse(call: Call<ProfileResponse>, response: Response<ProfileResponse>) {
                    isLoading = false
                    if (response.isSuccessful && response.body()?.success == true) {
                        employeeData = response.body()?.employee
                        employeeData?.languageFlag?.let { langFlag ->
                            val lang = when (langFlag) {
                                "1" -> "en"
                                "2" -> "ja"
                                else -> "en"
                            }
                            if (lang != currentLanguage) {
                                currentLanguage = lang
                                prefs.edit().putString("languageFlag", lang).apply()
                            }
                        }
                    } else {
                        errorMessage = response.body()?.error ?: "Failed to load profile"
                    }
                }
                override fun onFailure(call: Call<ProfileResponse>, t: Throwable) {
                    isLoading = false
                    errorMessage = t.message ?: "Network error"
                }
            })
        }

        fun updateLanguagePreference(language: String) {
            val languageFlag = when (language) {
                "en" -> "1"
                "ja" -> "2"
                else -> "1"
            }
            prefs.edit().putString("languageFlag", language).apply()
            currentLanguage = language
            employeeData?.let { employee ->
                RetrofitClient.instance.updateLanguageFlag(employee.idNumber, languageFlag)
                    .enqueue(object : Callback<BasicResponse> {
                        override fun onResponse(call: Call<BasicResponse>, response: Response<BasicResponse>) {
                            if (!response.isSuccessful || response.body()?.success != true) {
                                Log.e("Dashboard", "Failed to update language flag: ${response.body()?.error}")
                            }
                        }
                        override fun onFailure(call: Call<BasicResponse>, t: Throwable) {
                            Log.e("Dashboard", "Network error updating language flag", t)
                        }
                    })
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    modifier = Modifier.width(280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF2053B3))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(vertical = 20.dp)
                                    .align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(100.dp),
                                        color = Color.White
                                    )
                                } else if (errorMessage != null) {
                                    Image(
                                        painter = painterResource(id = R.drawable.profile_placeholder),
                                        contentDescription = "Error loading profile",
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(CircleShape)
                                            .background(Color.Gray),
                                        contentScale = ContentScale.Crop
                                    )
                                    Text(
                                        text = "Error",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                    )
                                } else {
                                    val imageUrl = employeeData?.picture?.let { picture ->
                                        rememberUrlWithFallback(
                                            "http://192.168.254.163/V4/11-A%20Employee%20List%20V2/profilepictures/$picture",
                                            "http://113.19.11.218/V4/11-A%20Employee%20List%20V2/profilepictures/$picture"
                                        )
                                    }

                                    AsyncImage(
                                        model = imageUrl ?: "",
                                        contentDescription = "Profile Image",
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(CircleShape)
                                            .background(Color.Gray),
                                        contentScale = ContentScale.Crop,
                                        placeholder = painterResource(id = R.drawable.profile_placeholder),
                                        error = painterResource(id = R.drawable.profile_placeholder)
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = employeeData?.let { "${it.firstName} ${it.surName}" } ?: "User Name",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )

                                    Spacer(modifier = Modifier.height(4.dp))

                                    Text(
                                        text = employeeData?.let { "ID: ${it.idNumber}" } ?: "ID: unknown",
                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (currentLanguage == "ja") 35.dp else 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLanguage == "ja") "言語" else "Language",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )

                            Spacer(modifier = Modifier.width(25.dp))

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { updateLanguagePreference("en") }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(R.drawable.americanflag),
                                    contentDescription = "English",
                                    modifier = Modifier.size(40.dp)
                                )
                                if (currentLanguage == "en") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(30.dp))

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { updateLanguagePreference("ja") }
                            ) {
                                Image(
                                    painter = rememberAsyncImagePainter(R.drawable.japaneseflag),
                                    contentDescription = "Japanese",
                                    modifier = Modifier.size(40.dp)
                                )
                                if (currentLanguage == "ja") {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(40.dp)
                                            .height(2.dp)
                                            .background(Color.Blue)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (currentLanguage == "ja") "キーボード" else "Keyboard",
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                            )

                            Spacer(modifier = Modifier.width(15.dp))

                            IconButton(
                                onClick = {
                                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    imm.showInputMethodPicker()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Keyboard,
                                    contentDescription = "Keyboard",
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    Column(
                        modifier = Modifier
                            .statusBarsPadding()
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF3452B4))
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color.Red, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = { (context as? ComponentActivity)?.finishAffinity() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Close App",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }

                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "ArkFence",
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 27.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF3452B4)
                            )
                        )
                    }
                }
            ) { paddingValues ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    color = MaterialTheme.colorScheme.background
                ) {
                }
            }
        }
    }
}