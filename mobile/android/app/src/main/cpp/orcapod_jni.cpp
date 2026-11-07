// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors
//
// JNI layer of the Android app over the engine bridge's C API
// (mobile/bridge/engine-cxx/orca_bridge.h). Each function maps one Kotlin
// `external fun` in org.orcapod.app.engine.Native onto bridge calls, converts
// bridge-owned results into Java objects, and turns a failed orca_result into
// an org.orcapod.app.engine.OrcaException.

#include "orca_bridge.h"

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr const char *kPkg = "org/orcapod/app/engine/";

// The bed box only drives plate assignment; the engine validates the
// printer's printable_height itself when slicing (same as orcapod-slice).
constexpr double kBedBoxHeight = 10000.0;

std::string cls(const char *name) { return std::string(kPkg) + name; }

// Throws OrcaException(code, diagnostic) when `r` failed; always disposes it.
// Returns true on success.
bool check(JNIEnv *env, orca_result r)
{
    const bool ok = r.code == ORCA_STATUS_OK;
    if (!ok && !env->ExceptionCheck()) {
        std::string msg = r.diagnostic.data ? std::string(r.diagnostic.data, size_t(r.diagnostic.length)) : std::string();
        if (msg.empty())
            msg = "engine call failed (status " + std::to_string(int(r.code)) + ")";
        jclass ex = env->FindClass(cls("OrcaException").c_str());
        jmethodID ctor = env->GetMethodID(ex, "<init>", "(ILjava/lang/String;)V");
        jstring jmsg = env->NewStringUTF(msg.c_str());
        env->Throw(static_cast<jthrowable>(env->NewObject(ex, ctor, jint(r.code), jmsg)));
    }
    orca_result_dispose(&r);
    return ok;
}

void throw_state(JNIEnv *env, const char *message)
{
    jclass ex = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(ex, message);
}

// Java strings are modified UTF-8; the bridge wants plain UTF-8 paths and
// names, so convert through UTF-16 to handle characters outside the BMP.
std::string utf8(JNIEnv *env, jstring s)
{
    if (!s)
        return {};
    const jsize len = env->GetStringLength(s);
    const jchar *chars = env->GetStringChars(s, nullptr);
    std::string out;
    out.reserve(size_t(len));
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            ++i;
        }
        if (c < 0x80) {
            out += char(c);
        } else if (c < 0x800) {
            out += char(0xC0 | (c >> 6));
            out += char(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += char(0xE0 | (c >> 12));
            out += char(0x80 | ((c >> 6) & 0x3F));
            out += char(0x80 | (c & 0x3F));
        } else {
            out += char(0xF0 | (c >> 18));
            out += char(0x80 | ((c >> 12) & 0x3F));
            out += char(0x80 | ((c >> 6) & 0x3F));
            out += char(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(s, chars);
    return out;
}

// Plain UTF-8 back into a Java string (the reverse of utf8()).
jstring jstr(JNIEnv *env, const char *data, size_t length)
{
    std::u16string u;
    u.reserve(length);
    for (size_t i = 0; i < length;) {
        const auto b = static_cast<unsigned char>(data[i]);
        uint32_t c;
        size_t n;
        if (b < 0x80) { c = b; n = 1; }
        else if ((b >> 5) == 0x6) { c = b & 0x1F; n = 2; }
        else if ((b >> 4) == 0xE) { c = b & 0x0F; n = 3; }
        else if ((b >> 3) == 0x1E) { c = b & 0x07; n = 4; }
        else { u += u'�'; ++i; continue; }
        if (i + n > length) { u += u'�'; break; }
        for (size_t k = 1; k < n; ++k)
            c = (c << 6) | (static_cast<unsigned char>(data[i + k]) & 0x3F);
        i += n;
        if (c >= 0x10000) {
            c -= 0x10000;
            u += char16_t(0xD800 + (c >> 10));
            u += char16_t(0xDC00 + (c & 0x3FF));
        } else {
            u += char16_t(c);
        }
    }
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), jsize(u.size()));
}

jstring jstr(JNIEnv *env, const orca_owned_string &s)
{
    return jstr(env, s.data ? s.data : "", s.data ? size_t(s.length) : 0);
}

jstring take(JNIEnv *env, orca_owned_string &s)
{
    jstring out = jstr(env, s);
    orca_owned_string_dispose(&s);
    return out;
}

jfloatArray floats(JNIEnv *env, const float *data, jsize n)
{
    jfloatArray a = env->NewFloatArray(n);
    if (a && n > 0)
        env->SetFloatArrayRegion(a, 0, n, data);
    return a;
}

template <typename T> T *ptr(jlong handle) { return reinterpret_cast<T *>(static_cast<intptr_t>(handle)); }
jlong handle(const void *p) { return static_cast<jlong>(reinterpret_cast<intptr_t>(p)); }

orca_presets *presets_of(jlong h) { return ptr<orca_presets>(h); }
orca_plater *plater_of(jlong h) { return ptr<orca_plater>(h); }

// --- progress callbacks ----------------------------------------------------

struct ProgressCtx
{
    JavaVM *vm;
    jobject listener; // global ref
    jmethodID method;
};

// The bridge calls progress on the slicing thread; attach defensively in
// case an engine worker thread ever reports.
void on_progress(int32_t percent, const char *message, void *raw)
{
    auto *ctx = static_cast<ProgressCtx *>(raw);
    JNIEnv *env = nullptr;
    bool attached = false;
    if (ctx->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
#if defined(__ANDROID__)
        const jint attach = ctx->vm->AttachCurrentThread(&env, nullptr);
#else // the JDK's jni.h declares the env out-parameter as void **
        const jint attach = ctx->vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr);
#endif
        if (attach != JNI_OK)
            return;
        attached = true;
    }
    jstring jmsg = jstr(env, message ? message : "", message ? strlen(message) : 0);
    env->CallVoidMethod(ctx->listener, ctx->method, jint(percent), jmsg);
    if (env->ExceptionCheck())
        env->ExceptionClear(); // a listener failure must not abort the slice
    env->DeleteLocalRef(jmsg);
    if (attached)
        ctx->vm->DetachCurrentThread();
}

} // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_org_orcapod_app_engine_Native_##name

// --- versions --------------------------------------------------------------

JNI_FN(jint, abiVersion)(JNIEnv *, jobject) { return jint(orca_bridge_abi_version()); }
JNI_FN(jint, headerAbiVersion)(JNIEnv *, jobject) { return jint(ORCA_BRIDGE_ABI_VERSION); }

// --- presets ---------------------------------------------------------------

JNI_FN(jlong, presetsNew)(JNIEnv *env, jobject, jstring data_dir)
{
    orca_presets *p = nullptr;
    if (!check(env, orca_presets_new(utf8(env, data_dir).c_str(), &p)))
        return 0;
    return handle(p);
}

JNI_FN(void, presetsFree)(JNIEnv *, jobject, jlong p) { orca_presets_free(presets_of(p)); }

JNI_FN(jobjectArray, presetsList)(JNIEnv *env, jobject, jlong p, jint kind)
{
    int32_t count = 0;
    if (!check(env, orca_presets_list_count(presets_of(p), kind, &count)))
        return nullptr;
    jclass entry_cls = env->FindClass(cls("PresetEntry").c_str());
    jmethodID ctor = env->GetMethodID(entry_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;ZI)V");
    jobjectArray out = env->NewObjectArray(count, entry_cls, nullptr);
    for (int32_t i = 0; i < count; ++i) {
        orca_preset_entry e{};
        if (!check(env, orca_presets_list_entry(presets_of(p), kind, i, &e)))
            return nullptr;
        jstring name = jstr(env, e.name), vendor = jstr(env, e.vendor);
        jobject obj = env->NewObject(entry_cls, ctor, name, vendor, jboolean(e.compatible != 0), jint(e.origin));
        env->SetObjectArrayElement(out, i, obj);
        env->DeleteLocalRef(obj);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(vendor);
        orca_preset_entry_dispose(&e);
    }
    return out;
}

JNI_FN(void, presetsSelectMachine)(JNIEnv *env, jobject, jlong p, jstring machine)
{
    check(env, orca_presets_select_machine(presets_of(p), utf8(env, machine).c_str()));
}

JNI_FN(void, presetsSelect)(JNIEnv *env, jobject, jlong p, jstring machine, jstring filament, jstring process)
{
    check(env, orca_presets_select(presets_of(p), utf8(env, machine).c_str(), utf8(env, filament).c_str(),
                                   utf8(env, process).c_str()));
}

JNI_FN(jstring, presetsCurrent)(JNIEnv *env, jobject, jlong p, jint kind)
{
    orca_owned_string name{};
    if (!check(env, orca_presets_current(presets_of(p), kind, &name)))
        return nullptr;
    return take(env, name);
}

JNI_FN(jdoubleArray, presetsPrintVolume)(JNIEnv *env, jobject, jlong p)
{
    double v[4] = {};
    if (!check(env, orca_presets_print_volume(presets_of(p), &v[0], &v[1], &v[2], &v[3])))
        return nullptr;
    jdoubleArray out = env->NewDoubleArray(4);
    env->SetDoubleArrayRegion(out, 0, 4, v);
    return out;
}

JNI_FN(jstring, presetsResolvedConfig)(JNIEnv *env, jobject, jlong p)
{
    orca_owned_string json{};
    if (!check(env, orca_presets_resolved_config(presets_of(p), &json)))
        return nullptr;
    return take(env, json);
}

JNI_FN(void, presetsSetValue)(JNIEnv *env, jobject, jlong p, jint kind, jstring key, jstring value)
{
    check(env, orca_presets_set_value(presets_of(p), kind, utf8(env, key).c_str(), utf8(env, value).c_str()));
}

JNI_FN(void, presetsResetKind)(JNIEnv *env, jobject, jlong p, jint kind)
{
    check(env, orca_presets_reset_kind(presets_of(p), kind));
}

JNI_FN(void, presetsSaveUser)(JNIEnv *env, jobject, jlong p, jint kind, jstring name)
{
    check(env, orca_presets_save_user(presets_of(p), kind, utf8(env, name).c_str(), 0));
}

JNI_FN(void, presetsDeleteUser)(JNIEnv *env, jobject, jlong p, jint kind, jstring name)
{
    check(env, orca_presets_delete_user(presets_of(p), kind, utf8(env, name).c_str()));
}

// --- plater ----------------------------------------------------------------

JNI_FN(jlong, platerNew)(JNIEnv *env, jobject)
{
    orca_plater *pl = nullptr;
    if (!check(env, orca_plater_new(&pl)))
        return 0;
    return handle(pl);
}

JNI_FN(void, platerFree)(JNIEnv *, jobject, jlong pl) { orca_plater_free(plater_of(pl)); }

JNI_FN(void, platerSetBed)(JNIEnv *env, jobject, jlong pl, jdouble x, jdouble y, jdouble w, jdouble d, jdouble)
{
    check(env, orca_plater_set_bed(plater_of(pl), x, y, w, d, kBedBoxHeight, nullptr));
}

JNI_FN(jlongArray, platerImport)(JNIEnv *env, jobject, jlong pl, jstring path, jdouble cx, jdouble cy)
{
    orca_object_id first = 0;
    uint32_t count = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_import_model(plater_of(pl), utf8(env, path).c_str(), cx, cy, &first, &count, &rev)))
        return nullptr;
    const jlong v[2] = {jlong(first), jlong(count)};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, v);
    return out;
}

JNI_FN(void, platerProjectOpen)(JNIEnv *env, jobject, jlong pl, jlong p, jstring path)
{
    orca_project_info info{};
    check(env, orca_project_open(plater_of(pl), presets_of(p), utf8(env, path).c_str(), &info));
}

JNI_FN(jobject, platerSnapshot)(JNIEnv *env, jobject, jlong pl, jlongArray known_ids, jlongArray known_revs)
{
    std::unordered_map<uint64_t, uint64_t> known;
    if (known_ids && known_revs) {
        const jsize n = env->GetArrayLength(known_ids);
        std::vector<jlong> ids(static_cast<size_t>(n)), revs(static_cast<size_t>(n));
        env->GetLongArrayRegion(known_ids, 0, n, ids.data());
        env->GetLongArrayRegion(known_revs, 0, n, revs.data());
        for (jsize i = 0; i < n; ++i)
            known[uint64_t(ids[size_t(i)])] = uint64_t(revs[size_t(i)]);
    }

    orca_scene_snapshot snap{};
    if (!check(env, orca_plater_snapshot(plater_of(pl), &snap)))
        return nullptr;

    jclass inst_cls = env->FindClass(cls("SceneInstance").c_str());
    jmethodID inst_ctor = env->GetMethodID(inst_cls, "<init>", "(J[D[FZZI)V");
    jclass obj_cls = env->FindClass(cls("SceneObject").c_str());
    jmethodID obj_ctor = env->GetMethodID(obj_cls, "<init>",
        ("(JJLjava/lang/String;[F[I[F[F[L" + cls("SceneInstance") + ";)V").c_str());
    jclass plate_cls = env->FindClass(cls("Plate").c_str());
    jmethodID plate_ctor = env->GetMethodID(plate_cls, "<init>", "(JILjava/lang/String;[F[FI)V");
    jclass snap_cls = env->FindClass(cls("SceneSnapshot").c_str());
    jmethodID snap_ctor = env->GetMethodID(snap_cls, "<init>",
        ("(J[L" + cls("SceneObject") + ";[L" + cls("Plate") + ";III)V").c_str());

    jobjectArray objects = env->NewObjectArray(snap.object_count, obj_cls, nullptr);
    for (int32_t i = 0; i < snap.object_count; ++i) {
        const orca_scene_object &o = snap.objects[i];
        jobjectArray insts = env->NewObjectArray(o.instance_count, inst_cls, nullptr);
        for (int32_t k = 0; k < o.instance_count; ++k) {
            const orca_scene_instance &in = o.instances[k];
            const auto &t = in.transform;
            const double tv[9] = {t.offset_x, t.offset_y, t.offset_z, t.rotation_x, t.rotation_y,
                                  t.rotation_z, t.scale_x, t.scale_y, t.scale_z};
            jdoubleArray jt = env->NewDoubleArray(9);
            env->SetDoubleArrayRegion(jt, 0, 9, tv);
            float m[16];
            for (int j = 0; j < 16; ++j)
                m[j] = float(in.matrix[j]);
            jfloatArray jm = floats(env, m, 16);
            jobject ji = env->NewObject(inst_cls, inst_ctor, jlong(in.id), jt, jm, jboolean(in.mirrored != 0),
                                        jboolean(in.printable != 0), jint(in.plate));
            env->SetObjectArrayElement(insts, k, ji);
            env->DeleteLocalRef(ji);
            env->DeleteLocalRef(jt);
            env->DeleteLocalRef(jm);
        }
        jfloatArray positions = nullptr;
        jintArray indices = nullptr;
        auto it = known.find(o.id);
        if (it == known.end() || it->second != o.mesh_revision) {
            positions = floats(env, o.positions, o.position_count * 3);
            indices = env->NewIntArray(o.index_count);
            if (o.index_count > 0)
                env->SetIntArrayRegion(indices, 0, o.index_count, reinterpret_cast<const jint *>(o.indices));
        }
        jstring name = jstr(env, o.name);
        jfloatArray bmin = floats(env, o.bbox_min, 3), bmax = floats(env, o.bbox_max, 3);
        jobject jo = env->NewObject(obj_cls, obj_ctor, jlong(o.id), jlong(o.mesh_revision), name, positions, indices,
                                    bmin, bmax, insts);
        env->SetObjectArrayElement(objects, i, jo);
        for (jobject r : {jo, static_cast<jobject>(name), static_cast<jobject>(bmin), static_cast<jobject>(bmax),
                          static_cast<jobject>(insts), static_cast<jobject>(positions), static_cast<jobject>(indices)})
            if (r)
                env->DeleteLocalRef(r);
        if (env->ExceptionCheck())
            break; // out of memory on a large mesh
    }

    jobjectArray plates = env->NewObjectArray(snap.plate_count, plate_cls, nullptr);
    for (int32_t i = 0; i < snap.plate_count && !env->ExceptionCheck(); ++i) {
        const orca_scene_plate &p = snap.plates[i];
        jstring name = jstr(env, p.name);
        jfloatArray origin = floats(env, p.origin, 3), size = floats(env, p.size, 3);
        jobject jp = env->NewObject(plate_cls, plate_ctor, jlong(p.id), jint(p.index), name, origin, size,
                                    jint(p.object_count));
        env->SetObjectArrayElement(plates, i, jp);
        env->DeleteLocalRef(jp);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(origin);
        env->DeleteLocalRef(size);
    }

    jobject out = nullptr;
    if (!env->ExceptionCheck())
        out = env->NewObject(snap_cls, snap_ctor, jlong(snap.revision), objects, plates, jint(snap.current_plate),
                             jint(snap.undo_count), jint(snap.redo_count));
    orca_scene_snapshot_dispose(&snap);
    return out;
}

JNI_FN(void, platerInstanceSet)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jdoubleArray t, jstring label)
{
    if (env->GetArrayLength(t) != 9) {
        throw_state(env, "transform needs 9 values");
        return;
    }
    double v[9];
    env->GetDoubleArrayRegion(t, 0, 9, v);
    const orca_instance_transform tr{v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8]};
    orca_revision rev = 0;
    check(env, orca_plater_instance_set(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), &tr,
                                        utf8(env, label).c_str(), &rev));
}

JNI_FN(void, platerInstanceRotate)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jint axis, jdouble radians)
{
    orca_revision rev = 0;
    check(env, orca_plater_instance_rotate(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), axis, radians,
                                           &rev));
}

JNI_FN(void, platerInstanceAdd)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_instance_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_instance_add(plater_of(pl), orca_object_id(obj), &id, &rev));
}

JNI_FN(void, platerInstanceDelete)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst)
{
    orca_revision rev = 0;
    check(env, orca_plater_instance_delete(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), &rev));
}

JNI_FN(void, platerObjectDelete)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_revision rev = 0;
    check(env, orca_plater_object_delete(plater_of(pl), orca_object_id(obj), &rev));
}

JNI_FN(void, platerClear)(JNIEnv *env, jobject, jlong pl)
{
    orca_revision rev = 0;
    check(env, orca_plater_clear(plater_of(pl), &rev));
}

JNI_FN(void, platerUndo)(JNIEnv *env, jobject, jlong pl)
{
    orca_revision rev = 0;
    check(env, orca_plater_undo(plater_of(pl), &rev));
}

JNI_FN(void, platerRedo)(JNIEnv *env, jobject, jlong pl)
{
    orca_revision rev = 0;
    check(env, orca_plater_redo(plater_of(pl), &rev));
}

JNI_FN(jint, platerArrange)(JNIEnv *env, jobject, jlong pl, jlong p)
{
    uint32_t unprintable = 0;
    orca_revision rev = 0;
    check(env, orca_plater_arrange(plater_of(pl), presets_of(p), 1, &unprintable, &rev));
    return jint(unprintable);
}

JNI_FN(void, platerOrient)(JNIEnv *env, jobject, jlong pl, jlong p, jlong obj, jlong inst)
{
    orca_revision rev = 0;
    check(env, orca_plater_orient(plater_of(pl), presets_of(p), orca_object_id(obj), orca_instance_id(inst), &rev));
}

JNI_FN(void, platerScaleToFit)(JNIEnv *env, jobject, jlong pl, jlong p, jlong obj, jlong inst)
{
    orca_revision rev = 0;
    check(env, orca_plater_scale_to_fit(plater_of(pl), presets_of(p), orca_object_id(obj), orca_instance_id(inst),
                                        &rev));
}

JNI_FN(void, platerLayFlat)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jdouble nx, jdouble ny, jdouble nz)
{
    orca_revision rev = 0;
    check(env, orca_plater_instance_lay_flat(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), nx, ny, nz,
                                             &rev));
}

JNI_FN(void, platerExport3mf)(JNIEnv *env, jobject, jlong pl, jstring path)
{
    check(env, orca_project_export_3mf(plater_of(pl), utf8(env, path).c_str()));
}

// --- slicing ---------------------------------------------------------------

JNI_FN(jlong, jobNew)(JNIEnv *env, jobject)
{
    orca_slice_job *job = nullptr;
    if (!check(env, orca_slice_job_new(&job)))
        return 0;
    return handle(job);
}

JNI_FN(void, jobCancel)(JNIEnv *, jobject, jlong job) { orca_slice_job_cancel(ptr<orca_slice_job>(job)); }
JNI_FN(void, jobFree)(JNIEnv *, jobject, jlong job) { orca_slice_job_free(ptr<orca_slice_job>(job)); }

JNI_FN(jobject, platerSlice)(JNIEnv *env, jobject, jlong pl, jlong p, jstring path, jlong job, jobject listener)
{
    ProgressCtx ctx{};
    env->GetJavaVM(&ctx.vm);
    ctx.listener = env->NewGlobalRef(listener);
    ctx.method = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ILjava/lang/String;)V");

    orca_slice_report report{};
    const bool ok = check(env, orca_plater_slice_to_gcode_with_presets(plater_of(pl), presets_of(p),
                                                                       utf8(env, path).c_str(), on_progress, &ctx,
                                                                       ptr<orca_slice_job>(job), &report));
    env->DeleteGlobalRef(ctx.listener);
    if (!ok)
        return nullptr;

    jclass warn_cls = env->FindClass(cls("SliceWarning").c_str());
    jmethodID warn_ctor = env->GetMethodID(warn_cls, "<init>",
                                           "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    jobjectArray warnings = env->NewObjectArray(jsize(report.warning_count), warn_cls, nullptr);
    for (uint32_t i = 0; i < report.warning_count; ++i) {
        const orca_slice_warning &w = report.warnings[i];
        jstring code = jstr(env, w.code), msg = jstr(env, w.message), detail = jstr(env, w.detail);
        jobject jw = env->NewObject(warn_cls, warn_ctor, jint(w.level), code, msg, detail);
        env->SetObjectArrayElement(warnings, jsize(i), jw);
        env->DeleteLocalRef(jw);
        env->DeleteLocalRef(code);
        env->DeleteLocalRef(msg);
        env->DeleteLocalRef(detail);
    }
    jclass rep_cls = env->FindClass(cls("SliceReport").c_str());
    jmethodID rep_ctor = env->GetMethodID(rep_cls, "<init>", ("(DJ[L" + cls("SliceWarning") + ";)V").c_str());
    jobject out = env->NewObject(rep_cls, rep_ctor, jdouble(report.time_estimate_s), jlong(report.gcode_bytes),
                                 warnings);
    orca_slice_report_dispose(&report);
    return out;
}

// Extrusion moves only (EMoveType::Extrude); travels and markers are left out
// of the layer preview.
JNI_FN(jobject, toolpathLoad)(JNIEnv *env, jobject, jstring path)
{
    constexpr uint8_t kMoveExtrude = 10;
    orca_gcode *gcode = nullptr;
    if (!check(env, orca_gcode_load(utf8(env, path).c_str(), &gcode)))
        return nullptr;
    orca_toolpath tp{};
    const bool ok = check(env, orca_gcode_toolpath(gcode, &tp));
    orca_gcode_free(gcode);
    if (!ok)
        return nullptr;

    std::vector<float> pos;
    std::vector<jbyte> roles;
    std::vector<float> layer_z;
    std::vector<jint> layer_first, layer_count;
    pos.reserve(size_t(tp.segment_count) * 6);
    roles.reserve(tp.segment_count);
    for (uint32_t l = 0; l < tp.layer_count; ++l) {
        const orca_tp_layer &layer = tp.layers[l];
        const auto first = jint(roles.size());
        const uint32_t end = std::min(layer.first_segment + layer.segment_count, tp.segment_count);
        for (uint32_t s = layer.first_segment; s < end; ++s) {
            const orca_tp_segment &seg = tp.segments[s];
            if (seg.move_type != kMoveExtrude)
                continue;
            pos.insert(pos.end(), {seg.start[0], seg.start[1], seg.start[2], seg.end[0], seg.end[1], seg.end[2]});
            roles.push_back(jbyte(seg.role));
        }
        const jint count = jint(roles.size()) - first;
        if (count == 0)
            continue;
        layer_z.push_back(layer.z);
        layer_first.push_back(first);
        layer_count.push_back(count);
    }

    jfloatArray jpos = floats(env, pos.data(), jsize(pos.size()));
    jbyteArray jroles = env->NewByteArray(jsize(roles.size()));
    if (jroles && !roles.empty())
        env->SetByteArrayRegion(jroles, 0, jsize(roles.size()), roles.data());
    jfloatArray jz = floats(env, layer_z.data(), jsize(layer_z.size()));
    jintArray jfirst = env->NewIntArray(jsize(layer_first.size()));
    jintArray jcount = env->NewIntArray(jsize(layer_count.size()));
    if (env->ExceptionCheck()) {
        orca_toolpath_dispose(&tp);
        return nullptr;
    }
    env->SetIntArrayRegion(jfirst, 0, jsize(layer_first.size()), layer_first.data());
    env->SetIntArrayRegion(jcount, 0, jsize(layer_count.size()), layer_count.data());

    jclass tp_cls = env->FindClass(cls("Toolpath").c_str());
    jmethodID ctor = env->GetMethodID(tp_cls, "<init>", "([F[B[F[I[IFDFF)V");
    jobject out = env->NewObject(tp_cls, ctor, jpos, jroles, jz, jfirst, jcount, jfloat(tp.estimated_time_seconds),
                                 jdouble(tp.extruder_count > 0 ? tp.total_volumes[0] : 0.0),
                                 jfloat(tp.extruder_count > 0 ? tp.filament_densities[0] : 0.f),
                                 jfloat(tp.extruder_count > 0 ? tp.filament_diameters[0] : 0.f));
    orca_toolpath_dispose(&tp);
    return out;
}
