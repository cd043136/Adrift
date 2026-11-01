package dev.cd.adrift.utils

// Shims that let shared code use the Odin 0.3.6+ API on older Odin versions.
//? if <26.2 {
/*import com.odtheking.odin.events.RenderEvent

/** Odin 0.3.6 renamed `RenderEvent.Extract` to `RenderExtractEvent`. */
typealias RenderExtractEvent = RenderEvent.Extract
*///?}
