/**
 * Minecraft Data Bridge - MINIMAL STUB VERSION
 * Just stub implementations to test if library loading works
 */

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "MinecraftBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// External reference from awt_bridge.c
extern JavaVM* runtimeJavaVMPtr;

/**
 * Set obfuscated class name mapping - STUB
 */
JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_multidisplay_bridge_NativeBridge_nativeSetMapping(
        JNIEnv* env, jclass clazz,
        jstring minecraftClass, jstring localPlayerClass,
        jstring inventoryClass, jstring itemStackClass, jstring foodDataClass) {
    LOGI("nativeSetMapping called (stub)");
}

/**
 * Check if the Minecraft JVM is ready - STUB
 */
JNIEXPORT jboolean JNICALL
Java_net_kdt_pojavlaunch_multidisplay_bridge_NativeBridge_nativeIsReady(JNIEnv* env, jclass clazz) {
    return runtimeJavaVMPtr != NULL ? JNI_TRUE : JNI_FALSE;
}

/**
 * Get player data - STUB (returns NULL)
 */
JNIEXPORT jfloatArray JNICALL
Java_net_kdt_pojavlaunch_multidisplay_bridge_NativeBridge_nativeGetPlayerData(JNIEnv* env, jclass clazz) {
    return NULL;
}

/**
 * Get selected hotbar slot - STUB
 */
JNIEXPORT jint JNICALL
Java_net_kdt_pojavlaunch_multidisplay_bridge_NativeBridge_nativeGetSelectedSlot(JNIEnv* env, jclass clazz) {
    return 0;
}
