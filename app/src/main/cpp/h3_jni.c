#include <jni.h>
#include <string.h>
#include "h3api.h"

// Convert lat/lng to H3 cell address (string)
JNIEXPORT jstring JNICALL
Java_sh_mapme_mapper_H3Native_latLngToCell(JNIEnv *env, jobject thiz, jdouble lat, jdouble lng, jint resolution) {
    LatLng location;
    location.lat = degsToRads(lat);
    location.lng = degsToRads(lng);

    H3Index h3Index;
    if (latLngToCell(&location, resolution, &h3Index) != E_SUCCESS) {
        return (*env)->NewStringUTF(env, "");
    }

    char h3String[17];
    h3ToString(h3Index, h3String, sizeof(h3String));

    return (*env)->NewStringUTF(env, h3String);
}

// Get cell boundary (returns array of lat/lng pairs)
JNIEXPORT jdoubleArray JNICALL
Java_sh_mapme_mapper_H3Native_cellToBoundary(JNIEnv *env, jobject thiz, jstring h3IndexStr) {
    const char *h3Str = (*env)->GetStringUTFChars(env, h3IndexStr, NULL);

    H3Index h3Index;
    if (stringToH3(h3Str, &h3Index) != E_SUCCESS) {
        (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);

    CellBoundary boundary;
    if (cellToBoundary(h3Index, &boundary) != E_SUCCESS) {
        return NULL;
    }

    // Create array: [lat0, lng0, lat1, lng1, ...]
    int numVerts = boundary.numVerts;
    jdoubleArray result = (*env)->NewDoubleArray(env, numVerts * 2);
    if (result == NULL) {
        return NULL;
    }

    jdouble *coords = (*env)->GetDoubleArrayElements(env, result, NULL);
    for (int i = 0; i < numVerts; i++) {
        coords[i * 2] = radsToDegs(boundary.verts[i].lat);
        coords[i * 2 + 1] = radsToDegs(boundary.verts[i].lng);
    }
    (*env)->ReleaseDoubleArrayElements(env, result, coords, 0);

    return result;
}

// Get cell center
JNIEXPORT jdoubleArray JNICALL
Java_sh_mapme_mapper_H3Native_cellToLatLng(JNIEnv *env, jobject thiz, jstring h3IndexStr) {
    const char *h3Str = (*env)->GetStringUTFChars(env, h3IndexStr, NULL);

    H3Index h3Index;
    if (stringToH3(h3Str, &h3Index) != E_SUCCESS) {
        (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);
        return NULL;
    }
    (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);

    LatLng center;
    if (cellToLatLng(h3Index, &center) != E_SUCCESS) {
        return NULL;
    }

    jdoubleArray result = (*env)->NewDoubleArray(env, 2);
    if (result == NULL) {
        return NULL;
    }

    jdouble coords[2];
    coords[0] = radsToDegs(center.lat);
    coords[1] = radsToDegs(center.lng);
    (*env)->SetDoubleArrayRegion(env, result, 0, 2, coords);

    return result;
}

// Check if H3 index is valid
JNIEXPORT jboolean JNICALL
Java_sh_mapme_mapper_H3Native_isValidCell(JNIEnv *env, jobject thiz, jstring h3IndexStr) {
    const char *h3Str = (*env)->GetStringUTFChars(env, h3IndexStr, NULL);

    H3Index h3Index;
    if (stringToH3(h3Str, &h3Index) != E_SUCCESS) {
        (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);
        return JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, h3IndexStr, h3Str);

    return isValidCell(h3Index) ? JNI_TRUE : JNI_FALSE;
}
