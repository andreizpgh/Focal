// Run the native benchmark in the same Android process that prepared the text.
#include <jni.h>
int main(int argc, char **argv);

JNIEXPORT void JNICALL Java_AndroidTextBench_replay(JNIEnv *env, jclass klass, jobjectArray args) {
    (void)klass;
    const jsize count = (*env)->GetArrayLength(env, args);
    if (count != 5) return;
    jstring items[5];
    char *argv[5];
    for (jsize i = 0; i < count; ++i) {
        items[i] = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        argv[i] = (char *)(*env)->GetStringUTFChars(env, items[i], NULL);
        if (!argv[i]) return;
    }
    int status = main(count, argv);
    for (jsize i = 0; i < count; ++i) (*env)->ReleaseStringUTFChars(env, items[i], argv[i]);
    if (status) (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Replay failed");
}
