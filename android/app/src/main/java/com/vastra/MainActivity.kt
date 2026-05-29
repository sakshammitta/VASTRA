package com.vastra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vastra.core.storage.TokenStore
import com.vastra.navigation.VastraNavGraph
import com.vastra.ui.theme.VastraTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VastraTheme {
                VastraNavGraph(isLoggedIn = tokenStore.isLoggedIn())
            }
        }
    }
}
