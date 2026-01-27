package sh.mapme.mapper

/**
 * Native H3 bindings for Android
 * Built from Uber H3 C library via JNI
 */
object H3Native {

    init {
        System.loadLibrary("h3android")
    }

    /**
     * Convert latitude/longitude to H3 cell index string
     */
    external fun latLngToCell(lat: Double, lng: Double, resolution: Int): String

    /**
     * Get cell boundary as array of [lat0, lng0, lat1, lng1, ...]
     */
    external fun cellToBoundary(h3Index: String): DoubleArray?

    /**
     * Get cell center as [lat, lng]
     */
    external fun cellToLatLng(h3Index: String): DoubleArray?

    /**
     * Check if H3 index is valid
     */
    external fun isValidCell(h3Index: String): Boolean

    // Convenience methods

    fun getCellBoundary(h3Index: String): List<Pair<Double, Double>>? {
        val coords = cellToBoundary(h3Index) ?: return null
        val result = mutableListOf<Pair<Double, Double>>()
        for (i in coords.indices step 2) {
            if (i + 1 < coords.size) {
                result.add(Pair(coords[i], coords[i + 1]))
            }
        }
        return result
    }

    fun getCellCenter(h3Index: String): Pair<Double, Double>? {
        val coords = cellToLatLng(h3Index) ?: return null
        if (coords.size < 2) return null
        return Pair(coords[0], coords[1])
    }
}
