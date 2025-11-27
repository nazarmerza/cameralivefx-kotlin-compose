#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_nmerza_cameraapp_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_nmerza_cameraapp_NativeFilter_processFrame(JNIEnv *env, jobject thiz, jobject y_buffer,
                                                    jobject u_buffer, jobject v_buffer, jint width,
                                                    jint height, jint stride_y, jint stride_uv) {
    // TODO: implement processFrame()
}