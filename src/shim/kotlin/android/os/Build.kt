package android.os

/** SDK level 0 keeps the core on its oldest, platform-neutral code paths. */
object Build {
    @JvmField val MANUFACTURER: String = "desktop"
    @JvmField val MODEL: String = "desktop"

    object VERSION {
        @JvmField val SDK_INT: Int = 0
    }
}
