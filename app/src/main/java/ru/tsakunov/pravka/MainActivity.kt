package ru.tsakunov.pravka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import ru.tsakunov.pravka.ui.PravkaRoot
import ru.tsakunov.pravka.ui.theme.PravkaColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Палитра до первого кадра, иначе на книжке мигнёт тёплая тема и только потом переключится.
        val app = application as PravkaApp
        PravkaColors.palette = PravkaColors.paletteFor(app.settings.state.value.readerMode)
        // Палитра приложения всегда светлая, поэтому иконки строки состояния всегда тёмные.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            PravkaRoot(app)
        }
    }

    override fun onStop() {
        super.onStop()
        // Ушли из приложения: отправить результаты Бори на GitHub, пока телефон не заснул.
        (application as PravkaApp).sync.autoSync()
    }
}
