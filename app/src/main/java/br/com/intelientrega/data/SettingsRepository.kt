package br.com.intelientrega.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import br.com.intelientrega.domain.CostSettings
import br.com.intelientrega.domain.VehicleType
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "config")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object K {
        val FUEL = doublePreferencesKey("fuel_price")
        val KML = doublePreferencesKey("km_per_liter")
        val OTHER = doublePreferencesKey("other_costs_km")
        val HOUR = doublePreferencesKey("target_hour")
        val GROSS_KM = doublePreferencesKey("target_gross_km")
        val WAIT = doublePreferencesKey("extra_wait_min")
        val WAIT_INCLUDED = booleanPreferencesKey("wait_included_default")
        val REPO_KM = doublePreferencesKey("reposition_km")
        val REPO_MIN = doublePreferencesKey("reposition_min")
        val CONFIGURED = booleanPreferencesKey("configured")
        val VEHICLE = stringPreferencesKey("vehicle_type")
        val BATTERY_KWH = doublePreferencesKey("battery_kwh")
        val KWH_PRICE = doublePreferencesKey("kwh_price")
        val RANGE_KM = doublePreferencesKey("range_km")
        val MIN_BATTERY = doublePreferencesKey("min_battery_pct")
    }

    val settings: Flow<CostSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
            val d = CostSettings()
            CostSettings(
                vehicleType = p[K.VEHICLE]?.let { v -> VehicleType.entries.firstOrNull { it.name == v } } ?: d.vehicleType,
                batteryKwh = p[K.BATTERY_KWH] ?: d.batteryKwh,
                energyPricePerKwh = p[K.KWH_PRICE] ?: d.energyPricePerKwh,
                rangeKm = p[K.RANGE_KM] ?: d.rangeKm,
                minBatteryReservePct = p[K.MIN_BATTERY] ?: d.minBatteryReservePct,
                fuelPricePerLiter = p[K.FUEL] ?: d.fuelPricePerLiter,
                kmPerLiter = p[K.KML] ?: d.kmPerLiter,
                otherCostsPerKm = p[K.OTHER] ?: d.otherCostsPerKm,
                targetSurplusPerHour = p[K.HOUR] ?: d.targetSurplusPerHour,
                targetGrossPerKm = p[K.GROSS_KM],
                extraWaitMin = p[K.WAIT] ?: d.extraWaitMin,
                waitIncludedByDefault = p[K.WAIT_INCLUDED] ?: d.waitIncludedByDefault,
                defaultRepositionKm = p[K.REPO_KM] ?: d.defaultRepositionKm,
                defaultRepositionMin = p[K.REPO_MIN] ?: d.defaultRepositionMin,
            )
        }

    /** O usuário já salvou as configurações pelo menos uma vez? */
    val isConfigured: Flow<Boolean> = store.data
        .catch { emit(emptyPreferences()) }
        .map { it[K.CONFIGURED] ?: false }

    suspend fun save(s: CostSettings) {
        require(s.validate().isEmpty()) { "Configurações inválidas" }
        store.edit { p ->
            p[K.VEHICLE] = s.vehicleType.name
            p[K.BATTERY_KWH] = s.batteryKwh
            p[K.KWH_PRICE] = s.energyPricePerKwh
            p[K.RANGE_KM] = s.rangeKm
            p[K.MIN_BATTERY] = s.minBatteryReservePct
            p[K.FUEL] = s.fuelPricePerLiter
            p[K.KML] = s.kmPerLiter
            p[K.OTHER] = s.otherCostsPerKm
            p[K.HOUR] = s.targetSurplusPerHour
            if (s.targetGrossPerKm != null) p[K.GROSS_KM] = s.targetGrossPerKm else p.remove(K.GROSS_KM)
            p[K.WAIT] = s.extraWaitMin
            p[K.WAIT_INCLUDED] = s.waitIncludedByDefault
            p[K.REPO_KM] = s.defaultRepositionKm
            p[K.REPO_MIN] = s.defaultRepositionMin
            p[K.CONFIGURED] = true
        }
    }
}
