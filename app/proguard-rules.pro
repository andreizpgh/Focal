# libfocal_speech.so binds its JNI functions by name (Java_app_focal_speech_SherpaBridge_*).
# The bridge calls nothing back in app code: it only builds java.lang arrays and exceptions.
-keepclasseswithmembernames,includedescriptorclasses class app.focal.speech.SherpaBridge {
    native <methods>;
}
