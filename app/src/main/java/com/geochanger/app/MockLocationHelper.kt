package com.geochanger.app

import android.content.Context
import android.location.Criteria
import android.location.LocationManager

object MockLocationHelper {

    /**
     * true, если приложение выбрано в «Настройки → Для разработчиков →
     * Приложение для фиктивных местоположений». Иначе addTestProvider бросит SecurityException.
     */
    fun canMock(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return try {
            lm.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                Criteria.POWER_LOW, Criteria.ACCURACY_FINE
            )
            lm.removeTestProvider(LocationManager.GPS_PROVIDER)
            true
        } catch (_: SecurityException) {
            false
        }
    }
}
