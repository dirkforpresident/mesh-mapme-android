package sh.mapme.mapper.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class MapmeSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return MapmeAutoScreen(carContext)
    }
}
