package de.jrpie.android.launcher.telemetry

enum class GestureRule(val id: String, val triangleHeight: Int, val shapeFirst: Boolean) {
    CURRENT("current", 250, false),
    V175("v175", 175, true);

    companion object {
        fun byId(id: String?): GestureRule? = entries.firstOrNull { it.id == id }
    }
}
