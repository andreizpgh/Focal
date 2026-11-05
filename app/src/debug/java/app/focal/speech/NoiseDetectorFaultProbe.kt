package app.focal.speech

/** Debug-only hook used by the device test to verify graph failure handling. */
object NoiseDetectorFaultProbe {
    external fun arm(handle: Long)
}
