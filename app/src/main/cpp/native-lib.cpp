#include <jni.h>
#include <android/log.h>
#include <map>
#include <string>
#include <cmath>
#include <algorithm>
#include <memory>

#define LOG_TAG "NativeFilter_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define CLAMP(x,a,b) std::fmax(a, std::fmin(b,(x)))

#include "filters/BlueArchitecture.hpp"
#include "filters/HardBoost.hpp"
#include "filters/LongBeachMorning.hpp"
#include "filters/LushGreen.hpp"
#include "filters/MagicHour.hpp"
#include "filters/NaturalBoost.hpp"
#include "filters/OrangeAndBlue.hpp"
#include "filters/SoftBlackAndWhite.hpp"
#include "filters/Waves.hpp"
#include "filters/BlueHour.hpp"
#include "filters/ColdChrome.hpp"
#include "filters/CrispAutumn.hpp"
#include "filters/DarkAndSomber.hpp"

static const int LUT_DIM = 33;
using LutDataPointer = const float (*)[LUT_DIM][LUT_DIM][LUT_DIM][3];
static LutDataPointer gCurrentLUT = nullptr;
static std::map<std::string, LutDataPointer> gFilterMap;

static void RGBAtoNV12(const uint8_t* rgba_in, uint8_t* nv12_out, int width, int height) {
    uint8_t* yPlane = nv12_out;
    uint8_t* uvPlane = nv12_out + width * height; // UV order
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            int idx = (y * width + x) * 4;
            int R = rgba_in[idx + 0];
            int G = rgba_in[idx + 1];
            int B = rgba_in[idx + 2];

            int Y = (( 66*R + 129*G +  25*B + 128) >> 8) + 16;
            int U = ((-38*R -  74*G + 112*B + 128) >> 8) + 128;
            int V = ((112*R -  94*G -  18*B + 128) >> 8) + 128;

            Y = std::clamp(Y, 0, 255);
            U = std::clamp(U, 0, 255);
            V = std::clamp(V, 0, 255);

            yPlane[y * width + x] = static_cast<uint8_t>(Y);

            if ((y & 1) == 0 && (x & 1) == 0) {
                int uv_index = (y / 2) * width + (x & ~1);
                uvPlane[uv_index + 0] = static_cast<uint8_t>(U); // U first
                uvPlane[uv_index + 1] = static_cast<uint8_t>(V); // V second
            }
        }
    }
}

static void apply_lut(float r, float g, float b, float out_rgb[3]) {
    if (!gCurrentLUT) { out_rgb[0]=r; out_rgb[1]=g; out_rgb[2]=b; return; }

    float rx = r * (LUT_DIM-1), gx = g * (LUT_DIM-1), bx = b * (LUT_DIM-1);
    int x = (int)rx, y = (int)gx, z = (int)bx;
    float dx = rx-x, dy=gx-y, dz=bx-z;
    int x1 = std::min(x+1, LUT_DIM-1);
    int y1 = std::min(y+1, LUT_DIM-1);
    int z1 = std::min(z+1, LUT_DIM-1);

    for (int c=0;c<3;++c){
        float c00 = (*gCurrentLUT)[z][y][x][c]*(1-dx) + (*gCurrentLUT)[z][y][x1][c]*dx;
        float c10 = (*gCurrentLUT)[z][y1][x][c]*(1-dx)+ (*gCurrentLUT)[z][y1][x1][c]*dx;
        float c01 = (*gCurrentLUT)[z1][y][x][c]*(1-dx)+ (*gCurrentLUT)[z1][y][x1][c]*dx;
        float c11 = (*gCurrentLUT)[z1][y1][x][c]*(1-dx)+(*gCurrentLUT)[z1][y1][x1][c]*dx;
        float c0 = c00*(1-dy) + c10*dy;
        float c1 = c01*(1-dy) + c11*dy;
        out_rgb[c] = CLAMP(c0*(1-dz) + c1*dz, 0.f, 1.f);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_nmerza_cameraapp_NativeFilter_processFrame(
        JNIEnv* env, jobject /*this*/,
        jobject yBuffer, jobject uBuffer, jobject vBuffer,
        jint width, jint height,
        jint yRowStride,
        jint uRowStride, jint vRowStride,
        jint uPixelStride, jint vPixelStride,
        jobject nv12OutputBuffer)
{
    auto* Yp  = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* Up  = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* Vp  = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* NV12= static_cast<uint8_t*>(env->GetDirectBufferAddress(nv12OutputBuffer));
    if (!Yp || !Up || !Vp || !NV12) return nullptr;

    const int num_px = width * height;
    const int rgba_sz = num_px * 4;
    std::unique_ptr<uint8_t[]> rgba(new (std::nothrow) uint8_t[rgba_sz]);
    if (!rgba) return nullptr;

    float L[3];
    for (int j=0;j<height;++j){
        const int yBase = j * yRowStride;
        const int uBase = (j/2) * uRowStride;
        const int vBase = (j/2) * vRowStride;

        for (int i=0;i<width;++i){
            const int yIdx = yBase + i;
            const int uIdx = uBase + (i/2) * uPixelStride;
            const int vIdx = vBase + (i/2) * vPixelStride;

            const float Y = (float)(Yp[yIdx] & 0xFF);
            const float U = (float)(Up[uIdx] & 0xFF);
            const float V = (float)(Vp[vIdx] & 0xFF);

            const float C = Y - 16.f;
            const float D = U - 128.f;
            const float E = V - 128.f;

            float r = (298.f*C + 409.f*E + 128.f) / 256.f;
            float g = (298.f*C - 100.f*D - 208.f*E + 128.f) / 256.f;
            float b = (298.f*C + 516.f*D + 128.f) / 256.f;

            r = CLAMP(r/255.f, 0.f, 1.f);
            g = CLAMP(g/255.f, 0.f, 1.f);
            b = CLAMP(b/255.f, 0.f, 1.f);

            apply_lut(r,g,b,L);

            const uint8_t R = (uint8_t)std::round(CLAMP(L[0],0.f,1.f)*255.f);
            const uint8_t G = (uint8_t)std::round(CLAMP(L[1],0.f,1.f)*255.f);
            const uint8_t B = (uint8_t)std::round(CLAMP(L[2],0.f,1.f)*255.f);

            const int o = (j*width + i)*4;
            rgba[o+0]=R; rgba[o+1]=G; rgba[o+2]=B; rgba[o+3]=255;
        }
    }

    RGBAtoNV12(rgba.get(), NV12, width, height);

    jbyteArray out = env->NewByteArray(rgba_sz);
    if (out) env->SetByteArrayRegion(out, 0, rgba_sz, reinterpret_cast<jbyte*>(rgba.get()));
    return out;
}

// ---- Filters map / JNI plumbing ----
static void initialize_lut_map() {
    gFilterMap["None"]              = nullptr;
    gFilterMap["Blue Architecture"] = &BlueArchitecture;
    gFilterMap["HardBoost"]         = &HardBoost;
    gFilterMap["LongBeachMorning"]  = &LongBeachMorning;
    gFilterMap["LushGreen"]         = &LushGreen;
    gFilterMap["MagicHour"]         = &MagicHour;
    gFilterMap["NaturalBoost"]      = &NaturalBoost;
    gFilterMap["OrangeAndBlue"]     = &OrangeAndBlue;
    gFilterMap["SoftBlackAndWhite"] = &SoftBlackAndWhite;
    gFilterMap["Waves"]             = &Waves;
    gFilterMap["BlueHour"]          = &BlueHour;
    gFilterMap["ColdChrome"]        = &ColdChrome;
    gFilterMap["CrispAutumn"]       = &CrispAutumn;
    gFilterMap["DarkAndSomber"]     = &DarkAndSomber;
    LOGD("Initialized %zu filters.", gFilterMap.size());
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { initialize_lut_map(); return JNI_VERSION_1_6; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nmerza_cameraapp_NativeFilter_setActiveFilter(JNIEnv* env, jobject, jstring name_) {
    const char* cs = env->GetStringUTFChars(name_, 0);
    std::string name(cs ? cs : "");
    if (cs) env->ReleaseStringUTFChars(name_, cs);
    auto it = gFilterMap.find(name);
    if (it != gFilterMap.end()) { gCurrentLUT = it->second; LOGD("Filter: %s", name.c_str()); return JNI_TRUE; }
    LOGD("Filter not found: %s", name.c_str()); return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nmerza_cameraapp_NativeFilter_loadLut(JNIEnv*, jobject, jbyteArray, jint) {
    LOGD("loadLut unused; static .hpp LUTs in use.");
    return JNI_TRUE;
}
