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

jintArray ints(JNIEnv *env, const uint32_t *data, jsize n)
{
    jintArray a = env->NewIntArray(n);
    if (a && n > 0)
        env->SetIntArrayRegion(a, 0, n, reinterpret_cast<const jint *>(data));
    return a;
}

jstring take_json(JNIEnv *env, orca_result r, orca_owned_string &s)
{
    if (!check(env, r)) {
        orca_owned_string_dispose(&s);
        return nullptr;
    }
    return take(env, s);
}

template <typename T> T *ptr(jlong handle) { return reinterpret_cast<T *>(static_cast<intptr_t>(handle)); }
jlong handle(const void *p) { return static_cast<jlong>(reinterpret_cast<intptr_t>(p)); }

orca_presets *presets_of(jlong h) { return ptr<orca_presets>(h); }
orca_plater *plater_of(jlong h) { return ptr<orca_plater>(h); }
orca_step *step_of(jlong h) { return ptr<orca_step>(h); }

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

// --- STEP import -------------------------------------------------------------

JNI_FN(jlong, stepNew)(JNIEnv *env, jobject, jstring path)
{
    orca_step *step = nullptr;
    if (!check(env, orca_step_new(utf8(env, path).c_str(), &step)))
        return 0;
    return handle(step);
}

JNI_FN(jlongArray, stepLoad)(JNIEnv *env, jobject, jlong st)
{
    orca_step_info info{};
    if (!check(env, orca_step_load(step_of(st), &info)))
        return nullptr;
    const jlong v[2] = {jlong(info.has_original_names), jlong(info.has_multi_body_compound)};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, v);
    return out;
}

JNI_FN(void, stepFree)(JNIEnv *, jobject, jlong st) { orca_step_free(step_of(st)); }

JNI_FN(void, stepCancel)(JNIEnv *, jobject, jlong st) { orca_step_cancel(step_of(st)); }

JNI_FN(jint, stepProgress)(JNIEnv *, jobject, jlong st) { return jint(orca_step_progress(step_of(st))); }

JNI_FN(jlong, stepTriangleCount)(JNIEnv *env, jobject, jlong st, jdouble linear, jdouble angle)
{
    uint32_t count = 0;
    if (!check(env, orca_step_triangle_count(step_of(st), linear, angle, &count)))
        return 0;
    return jlong(count);
}

JNI_FN(void, stepMesh)(JNIEnv *env, jobject, jlong st, jdouble linear, jdouble angle, jboolean split_compound,
                       jboolean keep_names, jboolean as_assembly, jboolean sort_names)
{
    orca_step_import_options options{};
    options.linear_deflection = linear;
    options.angle_deflection = angle;
    options.split_compound = split_compound ? 1 : 0;
    options.keep_names = keep_names ? 1 : 0;
    options.load_as_assembly = as_assembly ? 1 : 0;
    options.sort_names = sort_names ? 1 : 0;
    check(env, orca_step_mesh(step_of(st), &options));
}

// A null `replacement` is the default ("_"); "" removes the characters.
JNI_FN(jstring, stepNameChanges)(JNIEnv *env, jobject, jlong st, jstring replacement)
{
    const std::string with = replacement ? utf8(env, replacement) : std::string();
    orca_owned_string json{};
    if (!check(env, orca_step_name_changes(step_of(st), replacement ? with.c_str() : nullptr, &json)))
        return nullptr;
    jstring out = jstr(env, json);
    orca_owned_string_dispose(&json);
    return out;
}

JNI_FN(jlongArray, platerImportStep)(JNIEnv *env, jobject, jlong pl, jlong st, jstring replacement,
                                     jdouble cx, jdouble cy)
{
    const std::string with = replacement ? utf8(env, replacement) : std::string();
    orca_object_id first = 0;
    uint32_t count = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_import_step(plater_of(pl), step_of(st), replacement ? with.c_str() : nullptr,
                                            cx, cy, &first, &count, &rev)))
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
    jclass vol_cls = env->FindClass(cls("SceneVolume").c_str());
    jmethodID vol_ctor = env->GetMethodID(vol_cls, "<init>", "(JLjava/lang/String;II[F[F[F[IIII)V");
    jclass obj_cls = env->FindClass(cls("SceneObject").c_str());
    jmethodID obj_ctor = env->GetMethodID(obj_cls, "<init>",
        ("(JJLjava/lang/String;[F[I[F[F[L" + cls("SceneInstance") + ";[L" + cls("SceneVolume") + ";II)V").c_str());
    jclass plate_cls = env->FindClass(cls("Plate").c_str());
    jmethodID plate_ctor = env->GetMethodID(plate_cls, "<init>", "(JILjava/lang/String;[F[FIZ)V");
    jclass snap_cls = env->FindClass(cls("SceneSnapshot").c_str());
    jmethodID snap_ctor = env->GetMethodID(snap_cls, "<init>",
        ("(J[L" + cls("SceneObject") + ";[L" + cls("Plate") + ";IIILjava/lang/String;Ljava/lang/String;)V").c_str());

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
        // Volume meshes change with the object's mesh revision, so they ride along with it.
        const bool send_mesh = it == known.end() || it->second != o.mesh_revision;
        if (send_mesh) {
            positions = floats(env, o.positions, o.position_count * 3);
            indices = ints(env, o.indices, o.index_count);
        }
        jobjectArray vols = env->NewObjectArray(o.volume_count, vol_cls, nullptr);
        for (int32_t k = 0; k < o.volume_count && !env->ExceptionCheck(); ++k) {
            const orca_scene_volume &v = o.volumes[k];
            jfloatArray vpos = nullptr;
            jintArray vidx = nullptr;
            if (send_mesh && v.positions) {
                vpos = floats(env, v.positions, v.position_count * 3);
                vidx = ints(env, v.indices, v.index_count);
            }
            jstring vname = jstr(env, v.name);
            jfloatArray vmin = floats(env, v.bbox_min, 3), vmax = floats(env, v.bbox_max, 3);
            jobject jv = env->NewObject(vol_cls, vol_ctor, jlong(v.id), vname, jint(v.type), jint(v.extruder), vmin,
                                        vmax, vpos, vidx, jint(v.merged_triangle_start),
                                        jint(v.merged_triangle_count), jint(v.override_count));
            env->SetObjectArrayElement(vols, k, jv);
            for (jobject r : {jv, static_cast<jobject>(vname), static_cast<jobject>(vmin), static_cast<jobject>(vmax),
                              static_cast<jobject>(vpos), static_cast<jobject>(vidx)})
                if (r)
                    env->DeleteLocalRef(r);
        }
        jstring name = jstr(env, o.name);
        jfloatArray bmin = floats(env, o.bbox_min, 3), bmax = floats(env, o.bbox_max, 3);
        jobject jo = env->NewObject(obj_cls, obj_ctor, jlong(o.id), jlong(o.mesh_revision), name, positions, indices,
                                    bmin, bmax, insts, vols, jint(o.override_count), jint(o.extruder));
        env->SetObjectArrayElement(objects, i, jo);
        for (jobject r : {jo, static_cast<jobject>(name), static_cast<jobject>(bmin), static_cast<jobject>(bmax),
                          static_cast<jobject>(insts), static_cast<jobject>(vols), static_cast<jobject>(positions),
                          static_cast<jobject>(indices)})
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
                                    jint(p.object_count), jboolean(p.locked != 0));
        env->SetObjectArrayElement(plates, i, jp);
        env->DeleteLocalRef(jp);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(origin);
        env->DeleteLocalRef(size);
    }

    jobject out = nullptr;
    if (!env->ExceptionCheck()) {
        jstring undo = jstr(env, snap.undo_label), redo = jstr(env, snap.redo_label);
        out = env->NewObject(snap_cls, snap_ctor, jlong(snap.revision), objects, plates, jint(snap.current_plate),
                             jint(snap.undo_count), jint(snap.redo_count), undo, redo);
    }
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

// Slice targets of platerSlice.
constexpr jint kSlicePlate = 0;     // the selected plate, to G-code
constexpr jint kSliceAll = 1;       // every non-empty plate, one G-code
constexpr jint kSliced3mf = 2;      // every plate, G-code embedded in a 3MF
constexpr jint kSliced3mfPlate = 3; // the selected plate, as a sliced 3MF

JNI_FN(jobject, platerSlice)(JNIEnv *env, jobject, jlong pl, jlong p, jstring path, jlong job, jobject listener,
                             jint target, jint plate_index)
{
    ProgressCtx ctx{};
    env->GetJavaVM(&ctx.vm);
    ctx.listener = env->NewGlobalRef(listener);
    ctx.method = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ILjava/lang/String;)V");

    orca_slice_report report{};
    const std::string file = utf8(env, path);
    auto *j = ptr<orca_slice_job>(job);
    orca_result r{};
    switch (target) {
    case kSliceAll:
        r = orca_plater_slice_all_to_gcode_with_presets(plater_of(pl), presets_of(p), file.c_str(), on_progress, &ctx,
                                                        j, &report);
        break;
    case kSliced3mf:
        r = orca_plater_export_gcode_3mf(plater_of(pl), presets_of(p), file.c_str(), on_progress, &ctx, j, &report);
        break;
    case kSliced3mfPlate:
        r = orca_plater_export_gcode_3mf_plate(plater_of(pl), presets_of(p), plate_index, file.c_str(), on_progress,
                                               &ctx, j, &report);
        break;
    default:
        r = orca_plater_slice_to_gcode_with_presets(plater_of(pl), presets_of(p), file.c_str(), on_progress, &ctx, j,
                                                    &report);
        break;
    }
    (void)kSlicePlate;
    const bool ok = check(env, r);
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

// Desktop preview data: every extrusion, travel and wipe as a line segment in
// file order, per layer, with the attributes the desktop view types colour
// by; retractions, seams, tool and colour changes, pauses and custom G-code
// as point markers. Layers without extrusions (trailing travels) are dropped.
JNI_FN(jobject, toolpathLoad)(JNIEnv *env, jobject, jstring path)
{
    constexpr uint8_t kMoveTravel = 8, kMoveExtrude = 10;
    constexpr int kAttrs = 12; // keep in step with Toolpath.Attr
    orca_gcode *gcode = nullptr;
    if (!check(env, orca_gcode_load(utf8(env, path).c_str(), &gcode)))
        return nullptr;
    orca_toolpath tp{};
    const bool ok = check(env, orca_gcode_toolpath(gcode, &tp));
    orca_gcode_free(gcode);
    if (!ok)
        return nullptr;

    std::vector<float> pos, attrs, times, layer_z, layer_time, point_pos;
    std::vector<jbyte> moves, roles, extruders, point_types;
    std::vector<jint> layer_first, layer_count, point_layers;
    pos.reserve(size_t(tp.segment_count) * 6);
    attrs.reserve(size_t(tp.segment_count) * kAttrs);
    for (uint32_t l = 0; l < tp.layer_count; ++l) {
        const orca_tp_layer &layer = tp.layers[l];
        const size_t first = moves.size(), first_point = point_types.size();
        bool extrudes = false;
        const uint32_t end = std::min(layer.first_segment + layer.segment_count, tp.segment_count);
        for (uint32_t s = layer.first_segment; s < end; ++s) {
            const orca_tp_segment &seg = tp.segments[s];
            if (seg.move_type < kMoveTravel) {
                if (seg.move_type == 0)
                    continue;
                point_pos.insert(point_pos.end(), {seg.end[0], seg.end[1], seg.end[2]});
                point_types.push_back(jbyte(seg.move_type));
                continue;
            }
            if (seg.move_type > kMoveExtrude)
                continue;
            extrudes |= seg.move_type == kMoveExtrude;
            pos.insert(pos.end(), {seg.start[0], seg.start[1], seg.start[2], seg.end[0], seg.end[1], seg.end[2]});
            moves.push_back(jbyte(seg.move_type));
            roles.push_back(jbyte(seg.role));
            extruders.push_back(jbyte(seg.extruder));
            times.push_back(seg.time_seconds);
            attrs.insert(attrs.end(), {seg.feedrate, seg.actual_feedrate, seg.height, seg.width, seg.fan_speed,
                                       seg.temperature, seg.volumetric_rate, seg.actual_volumetric_rate,
                                       seg.layer_duration, seg.acceleration, seg.jerk, seg.pressure_advance});
        }
        if (!extrudes) {
            // Roll the layer back: nothing in it is printed.
            pos.resize(first * 6);
            attrs.resize(first * kAttrs);
            moves.resize(first);
            roles.resize(first);
            extruders.resize(first);
            times.resize(first);
            point_pos.resize(first_point * 3);
            point_types.resize(first_point);
            continue;
        }
        point_layers.resize(point_types.size(), jint(layer_z.size()));
        layer_z.push_back(layer.z);
        layer_time.push_back(layer.time_seconds);
        layer_first.push_back(jint(first));
        layer_count.push_back(jint(moves.size() - first));
    }

    auto byte_array = [env](const std::vector<jbyte> &v) {
        jbyteArray a = env->NewByteArray(jsize(v.size()));
        if (a && !v.empty())
            env->SetByteArrayRegion(a, 0, jsize(v.size()), v.data());
        return a;
    };
    auto int_array = [env](const std::vector<jint> &v) {
        jintArray a = env->NewIntArray(jsize(v.size()));
        if (a && !v.empty())
            env->SetIntArrayRegion(a, 0, jsize(v.size()), v.data());
        return a;
    };
    const uint32_t extruder_count = std::min<uint32_t>(tp.extruder_count, ORCA_TP_MAX_EXTRUDERS);
    const float totals[2] = {tp.estimated_time_seconds, tp.estimated_stealth_seconds};
    jdoubleArray volumes = env->NewDoubleArray(jsize(extruder_count));
    if (volumes)
        env->SetDoubleArrayRegion(volumes, 0, jsize(extruder_count), tp.total_volumes);
    jobject args[] = {
        floats(env, pos.data(), jsize(pos.size())), byte_array(moves), byte_array(roles), byte_array(extruders),
        floats(env, attrs.data(), jsize(attrs.size())), floats(env, times.data(), jsize(times.size())),
        floats(env, layer_z.data(), jsize(layer_z.size())), int_array(layer_first), int_array(layer_count),
        floats(env, layer_time.data(), jsize(layer_time.size())),
        floats(env, point_pos.data(), jsize(point_pos.size())), byte_array(point_types), int_array(point_layers),
        floats(env, totals, 2), volumes,
        floats(env, tp.filament_densities, jsize(extruder_count)),
        floats(env, tp.filament_diameters, jsize(extruder_count)),
        floats(env, tp.filament_costs, jsize(extruder_count)),
    };
    orca_toolpath_dispose(&tp);
    if (env->ExceptionCheck())
        return nullptr;
    jclass tp_cls = env->FindClass(cls("Toolpath").c_str());
    jmethodID ctor = env->GetMethodID(tp_cls, "<init>", "([F[B[B[B[F[F[F[I[I[F[F[B[I[F[D[F[F[F)V");
    return env->NewObject(tp_cls, ctor, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7],
                          args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16],
                          args[17]);
}

// --- per-layer custom G-code (desktop layer-slider ticks) ----------------------

JNI_FN(jobjectArray, customGcodes)(JNIEnv *env, jobject, jlong p)
{
    int32_t count = 0;
    if (!check(env, orca_plater_custom_gcode_count(plater_of(p), &count)))
        return nullptr;
    jclass item_cls = env->FindClass(cls("CustomGcode").c_str());
    jmethodID ctor = env->GetMethodID(item_cls, "<init>", "(DIILjava/lang/String;Ljava/lang/String;)V");
    jobjectArray out = env->NewObjectArray(count, item_cls, nullptr);
    for (int32_t i = 0; i < count && out; ++i) {
        orca_custom_gcode_item item{};
        if (!check(env, orca_plater_custom_gcode_item(plater_of(p), i, &item)))
            return nullptr;
        jstring colour = jstr(env, item.color), extra = jstr(env, item.extra);
        jobject jitem = env->NewObject(item_cls, ctor, jdouble(item.print_z), jint(item.type), jint(item.extruder),
                                       colour, extra);
        orca_custom_gcode_item_dispose(&item);
        env->SetObjectArrayElement(out, i, jitem);
        env->DeleteLocalRef(jitem);
        env->DeleteLocalRef(colour);
        env->DeleteLocalRef(extra);
    }
    return out;
}

JNI_FN(void, customGcodeAdd)(JNIEnv *env, jobject, jlong p, jdouble z, jint type, jint extruder, jstring colour,
                             jstring extra)
{
    orca_revision rev = 0;
    check(env, orca_plater_custom_gcode_add(plater_of(p), z, type, extruder, utf8(env, colour).c_str(),
                                            utf8(env, extra).c_str(), &rev));
}

JNI_FN(void, customGcodeDelete)(JNIEnv *env, jobject, jlong p, jdouble z)
{
    orca_revision rev = 0;
    check(env, orca_plater_custom_gcode_delete(plater_of(p), z, &rev));
}

// --- settings editors ------------------------------------------------------

JNI_FN(jstring, optionDefs)(JNIEnv *env, jobject, jstring keys_json)
{
    orca_owned_string json{};
    const std::string keys = utf8(env, keys_json);
    return take_json(env, orca_config_option_defs(keys_json ? keys.c_str() : nullptr, &json), json);
}

JNI_FN(jstring, presetsConfigDump)(JNIEnv *env, jobject, jlong p, jint kind, jboolean edited)
{
    orca_owned_string json{};
    return take_json(env, orca_presets_config_dump(presets_of(p), kind, edited ? 1 : 0, &json), json);
}

JNI_FN(void, presetsResetValue)(JNIEnv *env, jobject, jlong p, jint kind, jstring key)
{
    check(env, orca_presets_reset_value(presets_of(p), kind, utf8(env, key).c_str()));
}

JNI_FN(void, presetsSaveUserDetached)(JNIEnv *env, jobject, jlong p, jint kind, jstring name, jboolean detach)
{
    check(env, orca_presets_save_user(presets_of(p), kind, utf8(env, name).c_str(), detach ? 1 : 0));
}

// --- filament slots and plate type -----------------------------------------

/** Returns [name0, colour0, name1, colour1, ...]. */
JNI_FN(jobjectArray, presetsFilamentSlots)(JNIEnv *env, jobject, jlong p)
{
    orca_filament_slots slots{};
    if (!check(env, orca_presets_filament_slots(presets_of(p), &slots)))
        return nullptr;
    jobjectArray out = env->NewObjectArray(jsize(slots.count * 2), env->FindClass("java/lang/String"), nullptr);
    for (uint32_t i = 0; i < slots.count; ++i) {
        jstring name = jstr(env, slots.preset_names[i]), colour = jstr(env, slots.colours[i]);
        env->SetObjectArrayElement(out, jsize(2 * i), name);
        env->SetObjectArrayElement(out, jsize(2 * i + 1), colour);
        env->DeleteLocalRef(name);
        env->DeleteLocalRef(colour);
    }
    orca_filament_slots_dispose(&slots);
    return out;
}

JNI_FN(void, presetsSetFilamentCount)(JNIEnv *env, jobject, jlong p, jint count, jstring colour)
{
    const std::string c = utf8(env, colour);
    check(env, orca_presets_set_filament_count(presets_of(p), uint32_t(count), colour ? c.c_str() : nullptr));
}

JNI_FN(void, presetsSelectFilamentSlot)(JNIEnv *env, jobject, jlong p, jint slot, jstring name)
{
    check(env, orca_presets_select_filament(presets_of(p), uint32_t(slot), utf8(env, name).c_str()));
}

JNI_FN(void, presetsSetFilamentColour)(JNIEnv *env, jobject, jlong p, jint slot, jstring colour)
{
    check(env, orca_presets_set_filament_colour(presets_of(p), uint32_t(slot), utf8(env, colour).c_str()));
}

/** Returns [current, machine default, count, selectable (0/1), supported mask]. */
JNI_FN(jintArray, presetsBedType)(JNIEnv *env, jobject, jlong p)
{
    orca_bed_type_state st{};
    if (!check(env, orca_presets_bed_type(presets_of(p), &st)))
        return nullptr;
    const jint v[5] = {st.current, st.machine_default, st.count, jint(st.selectable), jint(st.supported_mask)};
    jintArray out = env->NewIntArray(5);
    env->SetIntArrayRegion(out, 0, 5, v);
    return out;
}

JNI_FN(void, presetsSetBedType)(JNIEnv *env, jobject, jlong p, jint bed_type)
{
    check(env, orca_presets_set_bed_type(presets_of(p), bed_type));
}

// --- per-object / per-part settings -----------------------------------------

JNI_FN(jstring, settingsKeys)(JNIEnv *env, jobject, jboolean for_volume)
{
    orca_owned_string json{};
    return take_json(env, orca_plater_settings_keys(for_volume ? 1 : 0, &json), json);
}

JNI_FN(jstring, settingsDump)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol)
{
    orca_owned_string json{};
    return take_json(env, orca_plater_settings_dump(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), &json),
                     json);
}

JNI_FN(void, settingsSet)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jstring key, jstring value)
{
    orca_revision rev = 0;
    check(env, orca_plater_settings_set(plater_of(pl), orca_object_id(obj), orca_volume_id(vol),
                                        utf8(env, key).c_str(), utf8(env, value).c_str(), &rev));
}

JNI_FN(void, settingsErase)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jstring key)
{
    orca_revision rev = 0;
    check(env, orca_plater_settings_erase(plater_of(pl), orca_object_id(obj), orca_volume_id(vol),
                                          utf8(env, key).c_str(), &rev));
}

JNI_FN(void, settingsApply)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jobjectArray keys,
                            jobjectArray values)
{
    const jsize n = env->GetArrayLength(keys);
    if (env->GetArrayLength(values) != n) {
        throw_state(env, "keys and values differ in length");
        return;
    }
    std::vector<std::string> k(static_cast<size_t>(n)), v(static_cast<size_t>(n));
    std::vector<const char *> kp(static_cast<size_t>(n)), vp(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto *jk = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        auto *jv = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        k[size_t(i)] = utf8(env, jk);
        v[size_t(i)] = utf8(env, jv);
        env->DeleteLocalRef(jk);
        env->DeleteLocalRef(jv);
    }
    for (size_t i = 0; i < size_t(n); ++i) {
        kp[i] = k[i].c_str();
        vp[i] = v[i].c_str();
    }
    orca_revision rev = 0;
    check(env, orca_plater_settings_apply(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), kp.data(),
                                          vp.data(), n, &rev));
}

// --- objects and parts -----------------------------------------------------

JNI_FN(void, objectRename)(JNIEnv *env, jobject, jlong pl, jlong obj, jstring name)
{
    orca_revision rev = 0;
    check(env, orca_plater_object_rename(plater_of(pl), orca_object_id(obj), utf8(env, name).c_str(), &rev));
}

JNI_FN(jlong, addShapeObject)(JNIEnv *env, jobject, jlong pl, jint shape, jdouble size, jdouble cx, jdouble cy)
{
    orca_object_id id = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_add_shape_object(plater_of(pl), shape, size, cx, cy, &id, &rev)))
        return 0;
    return jlong(id);
}

JNI_FN(jlong, volumeAddShape)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jint type, jint shape,
                              jdouble size)
{
    orca_volume_id id = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_volume_add_shape(plater_of(pl), orca_object_id(obj), orca_instance_id(inst),
                                                 uint8_t(type), shape, size, &id, &rev)))
        return 0;
    return jlong(id);
}

JNI_FN(jlong, volumeAddFile)(JNIEnv *env, jobject, jlong pl, jlong obj, jint type, jstring path)
{
    orca_volume_id id = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_volume_add_file(plater_of(pl), orca_object_id(obj), uint8_t(type),
                                                utf8(env, path).c_str(), &id, &rev)))
        return 0;
    return jlong(id);
}

JNI_FN(void, volumeSetType)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint type)
{
    orca_revision rev = 0;
    check(env, orca_plater_volume_set_type(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(type),
                                           &rev));
}

JNI_FN(void, volumeDelete)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol)
{
    orca_revision rev = 0;
    check(env, orca_plater_volume_delete(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), &rev));
}

JNI_FN(void, volumeRename)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jstring name)
{
    orca_revision rev = 0;
    check(env, orca_plater_volume_rename(plater_of(pl), orca_object_id(obj), orca_volume_id(vol),
                                         utf8(env, name).c_str(), &rev));
}

JNI_FN(jint, splitObjects)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_object_id first = 0;
    uint32_t count = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_split_objects(plater_of(pl), orca_object_id(obj), &first, &count, &rev)))
        return 0;
    return jint(count);
}

// Desktop cut gizmo: plane (engine world point and normal) and the kept result.
// Returns the number of objects the cut produced.
JNI_FN(jint, objectCut)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jdoubleArray plane, jint keep)
{
    double v[6] = {};
    env->GetDoubleArrayRegion(plane, 0, 6, v);
    orca_object_id first = 0;
    uint32_t count = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_object_cut(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), v[0], v[1], v[2],
                                           v[3], v[4], v[5], keep, &first, &count, &rev)))
        return 0;
    return jint(count);
}

/** `geometry` is position xyz and normal xyz; `sizes` radius, height and their tolerances; `kinds` type, style, shape. */
JNI_FN(void, cutConnectorAdd)(JNIEnv *env, jobject, jlong pl, jlong obj, jdoubleArray geometry, jfloatArray sizes,
                              jintArray kinds)
{
    double g[6] = {};
    float sz[4] = {};
    jint k[3] = {};
    env->GetDoubleArrayRegion(geometry, 0, 6, g);
    env->GetFloatArrayRegion(sizes, 0, 4, sz);
    env->GetIntArrayRegion(kinds, 0, 3, k);
    orca_cut_connector c{};
    for (int i = 0; i < 3; ++i) {
        c.position[i] = g[i];
        c.normal[i] = g[3 + i];
    }
    c.radius = sz[0];
    c.height = sz[1];
    c.radius_tolerance = sz[2];
    c.height_tolerance = sz[3];
    c.type = k[0];
    c.style = k[1];
    c.shape = k[2];
    orca_revision rev = 0;
    check(env, orca_plater_object_cut_connector_add(plater_of(pl), orca_object_id(obj), &c, &rev));
}

/** Removes the object's connectors, if it has any (the bridge refuses an empty clear). */
JNI_FN(void, cutConnectorsClear)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_cut_connector *list = nullptr;
    uint32_t count = 0;
    if (!check(env, orca_plater_object_cut_connectors(plater_of(pl), orca_object_id(obj), &list, &count)))
        return;
    orca_cut_connectors_dispose(list);
    if (count == 0)
        return;
    orca_revision rev = 0;
    check(env, orca_plater_object_cut_connectors_clear(plater_of(pl), orca_object_id(obj), &rev));
}

// --- painting (desktop painter gizmos) ------------------------------------------

/** One paint layer of a volume: [positions (9 floats per triangle, world), states (1 byte each)]. */
JNI_FN(jobjectArray, volumePaint)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint layer)
{
    orca_paint_mesh mesh{};
    if (!check(env, orca_plater_volume_paint(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(layer), &mesh)))
        return nullptr;
    std::vector<float> pos(size_t(mesh.count) * 9);
    std::vector<jbyte> states(mesh.count);
    for (uint32_t i = 0; i < mesh.count; ++i) {
        std::copy(mesh.triangles[i].positions, mesh.triangles[i].positions + 9, pos.begin() + size_t(i) * 9);
        states[i] = jbyte(mesh.triangles[i].state);
    }
    orca_paint_mesh_dispose(&mesh);
    jfloatArray jpos = floats(env, pos.data(), jsize(pos.size()));
    jbyteArray jstates = env->NewByteArray(jsize(states.size()));
    if (jstates && !states.empty())
        env->SetByteArrayRegion(jstates, 0, jsize(states.size()), states.data());
    jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/Object"), nullptr);
    if (env->ExceptionCheck())
        return nullptr;
    env->SetObjectArrayElement(out, 0, jpos);
    env->SetObjectArrayElement(out, 1, jstates);
    return out;
}

/** `ray` is the world hit point then the view direction. */
JNI_FN(void, volumePaintSphere)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint layer, jdoubleArray ray,
                                jdouble radius, jint state)
{
    double r[6] = {};
    env->GetDoubleArrayRegion(ray, 0, 6, r);
    orca_revision rev = 0;
    check(env, orca_plater_volume_paint_sphere(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(layer),
                                               r[0], r[1], r[2], r[3], r[4], r[5], radius, state, &rev));
}

JNI_FN(void, volumePaintFill)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint layer, jdoubleArray ray,
                              jdouble angle, jboolean propagate, jint state)
{
    double r[6] = {};
    env->GetDoubleArrayRegion(ray, 0, 6, r);
    orca_revision rev = 0;
    check(env, orca_plater_volume_paint_fill(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(layer),
                                             r[0], r[1], r[2], r[3], r[4], r[5], angle, propagate ? 1 : 0, state, &rev));
}

JNI_FN(jlong, volumePaintStroke)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint layer, jdoubleArray hits,
                                 jdoubleArray views, jdouble radius, jint state, jboolean continueStroke)
{
    const jsize n = env->GetArrayLength(hits);
    if (n % 3 != 0 || env->GetArrayLength(views) != n) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "hits and views need three doubles per dab");
        return 0;
    }
    std::vector<double> h(static_cast<size_t>(n)), v(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(hits, 0, n, h.data());
    env->GetDoubleArrayRegion(views, 0, n, v.data());
    orca_revision rev = 0;
    check(env, orca_plater_volume_paint_stroke(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(layer),
                                               h.data(), v.data(), uint32_t(n / 3), radius, state,
                                               continueStroke ? 1 : 0, &rev));
    return jlong(rev);
}

JNI_FN(void, volumePaintReset)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint layer)
{
    orca_revision rev = 0;
    check(env, orca_plater_volume_paint_reset(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), uint8_t(layer), &rev));
}

// --- variable layer height (desktop layers editing) -------------------------------

static jdoubleArray take_pairs(JNIEnv *env, bool ok, double *pairs, uint32_t count)
{
    if (!ok)
        return nullptr;
    jdoubleArray out = env->NewDoubleArray(jsize(count));
    if (out && count)
        env->SetDoubleArrayRegion(out, 0, jsize(count), pairs);
    orca_height_profile_dispose(pairs);
    return out;
}

/** Flat (z, layer height) pairs; empty when the object follows the process layer height. */
JNI_FN(jdoubleArray, heightProfile)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    double *pairs = nullptr;
    uint32_t count = 0;
    const bool ok = check(env, orca_plater_object_height_profile(plater_of(pl), orca_object_id(obj), &pairs, &count));
    return take_pairs(env, ok, pairs, count);
}

JNI_FN(void, setHeightProfile)(JNIEnv *env, jobject, jlong pl, jlong obj, jdoubleArray pairs)
{
    const jsize n = env->GetArrayLength(pairs);
    std::vector<double> v(static_cast<size_t>(n));
    if (n)
        env->GetDoubleArrayRegion(pairs, 0, n, v.data());
    orca_revision rev = 0;
    check(env, orca_plater_object_set_height_profile(plater_of(pl), orca_object_id(obj), n ? v.data() : nullptr,
                                                     uint32_t(n), &rev));
}

JNI_FN(jdoubleArray, heightProfileAdaptive)(JNIEnv *env, jobject, jlong pl, jlong pr, jlong obj, jdouble quality)
{
    double *pairs = nullptr;
    uint32_t count = 0;
    orca_revision rev = 0;
    const bool ok = check(env, orca_plater_object_height_profile_adaptive(plater_of(pl), presets_of(pr), orca_object_id(obj),
                                                                          quality, &pairs, &count, &rev));
    return take_pairs(env, ok, pairs, count);
}

JNI_FN(jdoubleArray, heightProfileSmooth)(JNIEnv *env, jobject, jlong pl, jlong pr, jlong obj, jint radius, jboolean keep_min)
{
    double *pairs = nullptr;
    uint32_t count = 0;
    orca_revision rev = 0;
    const bool ok = check(env, orca_plater_object_height_profile_smooth(plater_of(pl), presets_of(pr), orca_object_id(obj),
                                                                        uint32_t(radius), keep_min ? 1 : 0, &pairs, &count, &rev));
    return take_pairs(env, ok, pairs, count);
}

JNI_FN(void, heightProfileAdjust)(JNIEnv *env, jobject, jlong pl, jlong pr, jlong obj, jdouble z, jdouble delta,
                                  jdouble band, jint action)
{
    orca_revision rev = 0;
    check(env, orca_plater_object_height_profile_adjust(plater_of(pl), presets_of(pr), orca_object_id(obj), z, delta, band,
                                                        action, &rev));
}

// --- measure (desktop measure gizmo) ----------------------------------------------

JNI_FN(jlong, measureCreate)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_measure *m = nullptr;
    if (!check(env, orca_plater_object_measure(plater_of(pl), orca_object_id(obj), &m)))
        return 0;
    return handle(m);
}

JNI_FN(void, measureFree)(JNIEnv *, jobject, jlong m) { orca_measure_dispose(ptr<orca_measure>(m)); }

// A feature travels as 15 doubles: type, point a, point b, normal, radius, area, plane index.
static constexpr jsize FEATURE_SIZE = 15;

static jdoubleArray feature_array(JNIEnv *env, const orca_surface_feature &f)
{
    double v[FEATURE_SIZE] = {double(f.type)};
    for (int k = 0; k < 3; ++k) {
        v[1 + k] = f.point_a[k];
        v[4 + k] = f.point_b[k];
        v[7 + k] = f.normal[k];
    }
    v[10] = f.radius;
    v[11] = f.area;
    v[12] = double(f.plane_index);
    jdoubleArray out = env->NewDoubleArray(FEATURE_SIZE);
    if (out)
        env->SetDoubleArrayRegion(out, 0, FEATURE_SIZE, v);
    return out;
}

static orca_surface_feature feature_of(JNIEnv *env, jdoubleArray a)
{
    double v[FEATURE_SIZE] = {};
    env->GetDoubleArrayRegion(a, 0, FEATURE_SIZE, v);
    orca_surface_feature f{};
    f.type = uint8_t(v[0]);
    for (int k = 0; k < 3; ++k) {
        f.point_a[k] = v[1 + k];
        f.point_b[k] = v[4 + k];
        f.normal[k] = v[7 + k];
    }
    f.radius = v[10];
    f.area = v[11];
    f.plane_index = int32_t(v[12]);
    return f;
}

/** The feature under an object-space point on a merged-mesh triangle; null when there is none. */
JNI_FN(jdoubleArray, measurePick)(JNIEnv *env, jobject, jlong m, jint face, jdouble x, jdouble y, jdouble z)
{
    orca_surface_feature f{};
    orca_result r = orca_measure_pick(ptr<orca_measure>(m), face, x, y, z, &f);
    if (r.code == ORCA_STATUS_NOT_FOUND) {
        orca_result_dispose(&r);
        return nullptr;
    }
    if (!check(env, r))
        return nullptr;
    return feature_array(env, f);
}

/** from[3], to[3], distance, xyz[3], angle, has distance, has xyz, has angle. */
JNI_FN(jdoubleArray, measureBetween)(JNIEnv *env, jobject, jlong m, jdoubleArray a, jdoubleArray b)
{
    const orca_surface_feature fa = feature_of(env, a);
    const orca_surface_feature fb = feature_of(env, b);
    orca_measurement out{};
    if (!check(env, orca_measure_between(ptr<orca_measure>(m), &fa, &fb, &out)))
        return nullptr;
    double v[14];
    for (int k = 0; k < 3; ++k) {
        v[k] = out.from[k];
        v[3 + k] = out.to[k];
        v[7 + k] = out.xyz[k];
    }
    v[6] = out.distance;
    v[10] = out.angle_radians;
    v[11] = out.has_distance;
    v[12] = out.has_xyz;
    v[13] = out.has_angle;
    jdoubleArray arr = env->NewDoubleArray(14);
    if (arr)
        env->SetDoubleArrayRegion(arr, 0, 14, v);
    return arr;
}

// --- calibration (desktop Calibration menu) ----------------------------------------

/**
 * ints: mode, flow pass, flow pattern, print numbers, nozzle resize, flow linear, test model;
 * doubles: start, end, step, VFA layer, frequency start X, end X, start Y, end Y.
 */
JNI_FN(void, calibrationStart)(JNIEnv *env, jobject, jlong pl, jlong pr, jintArray ints, jdoubleArray doubles,
                               jstring shaper, jdoubleArray speeds, jdoubleArray accels, jstring model)
{
    jint i[7] = {};
    jdouble d[8] = {};
    env->GetIntArrayRegion(ints, 0, 7, i);
    env->GetDoubleArrayRegion(doubles, 0, 8, d);
    auto list = [&](jdoubleArray a) {
        std::vector<double> v(static_cast<size_t>(env->GetArrayLength(a)));
        if (!v.empty())
            env->GetDoubleArrayRegion(a, 0, jsize(v.size()), v.data());
        return v;
    };
    const std::vector<double> speed_list = list(speeds);
    const std::vector<double> accel_list = list(accels);
    orca_calibration c{};
    c.mode = i[0];
    c.flow_pass = i[1];
    c.flow_pattern = i[2];
    c.print_numbers = uint8_t(i[3] != 0);
    c.nozzle_based_resize = uint8_t(i[4] != 0);
    c.flow_linear = uint8_t(i[5] != 0);
    c.test_model = i[6];
    c.start = d[0];
    c.end = d[1];
    c.step = d[2];
    c.vfa_layer_height = d[3];
    c.freq_start_x = d[4];
    c.freq_end_x = d[5];
    c.freq_start_y = d[6];
    c.freq_end_y = d[7];
    c.speeds = speed_list.data();
    c.speed_count = uint32_t(speed_list.size());
    c.accelerations = accel_list.data();
    c.acceleration_count = uint32_t(accel_list.size());
    const char *shaper_type = env->GetStringUTFChars(shaper, nullptr);
    c.shaper_type = shaper_type;
    const char *path = model ? env->GetStringUTFChars(model, nullptr) : nullptr;
    orca_revision rev = 0;
    check(env, orca_plater_start_calibration(plater_of(pl), presets_of(pr), &c, path, &rev));
    if (path)
        env->ReleaseStringUTFChars(model, path);
    env->ReleaseStringUTFChars(shaper, shaper_type);
}

/** mode, start, end, step of the document's test. */
JNI_FN(jdoubleArray, calibration)(JNIEnv *env, jobject, jlong pl)
{
    orca_calibration c{};
    if (!check(env, orca_plater_calibration(plater_of(pl), &c)))
        return nullptr;
    const double v[4] = {double(c.mode), c.start, c.end, c.step};
    jdoubleArray out = env->NewDoubleArray(4);
    if (out)
        env->SetDoubleArrayRegion(out, 0, 4, v);
    return out;
}

JNI_FN(void, calibrationClear)(JNIEnv *env, jobject, jlong pl) { check(env, orca_plater_calibration_clear(plater_of(pl))); }

JNI_FN(jint, splitParts)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol)
{
    uint32_t count = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_split_parts(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), &count, &rev)))
        return 0;
    return jint(count);
}

// Mesh statistics: open edges, repaired errors, shells, facets, manifold, repaired.
JNI_FN(jintArray, meshErrors)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol)
{
    orca_mesh_errors e{};
    if (!check(env, orca_plater_mesh_errors(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), &e)))
        return nullptr;
    const jint values[] = {e.open_edges, e.repaired_errors, e.number_of_parts, jint(e.facets), e.manifold, e.repaired};
    jintArray out = env->NewIntArray(6);
    env->SetIntArrayRegion(out, 0, 6, values);
    return out;
}

JNI_FN(jobject, repair)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jboolean keep_painting)
{
    orca_mesh_repair_report r{};
    orca_revision rev = 0;
    if (!check(env, orca_plater_repair(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), keep_painting ? 1 : 0,
                                       &r, &rev)))
        return nullptr;
    const jint counts[] = {jint(r.welded_vertices), jint(r.removed_triangles), jint(r.reversed_shells),
                           jint(r.filled_holes),    jint(r.removed_shells),    jint(r.self_intersections),
                           jint(r.repaired_volumes), jint(r.failed_volumes),   jint(r.modified),
                           jint(r.painting_removed)};
    jintArray jcounts = env->NewIntArray(10);
    env->SetIntArrayRegion(jcounts, 0, 10, counts);
    jstring failures = jstr(env, r.failures);
    orca_mesh_repair_report_dispose(&r);
    jclass report_cls = env->FindClass(cls("RepairReport").c_str());
    jmethodID ctor = env->GetMethodID(report_cls, "<init>", "([ILjava/lang/String;)V");
    return env->NewObject(report_cls, ctor, jcounts, failures);
}

JNI_FN(jlong, meshBoolean)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong source, jlong tool, jint op,
                           jboolean delete_tool, jboolean keep_painting)
{
    orca_volume_id result = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_mesh_boolean(plater_of(pl), orca_object_id(obj), orca_volume_id(source),
                                             orca_volume_id(tool), op, delete_tool ? 1 : 0, keep_painting ? 1 : 0,
                                             &result, &rev)))
        return 0;
    return jlong(result);
}

JNI_FN(void, instanceMirror)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jint axis)
{
    orca_revision rev = 0;
    check(env, orca_plater_instance_mirror(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), axis, &rev));
}

JNI_FN(void, setPrintable)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst, jboolean printable)
{
    orca_revision rev = 0;
    check(env, orca_plater_set_printable(plater_of(pl), orca_object_id(obj), orca_instance_id(inst),
                                         printable ? 1 : 0, &rev));
}

JNI_FN(void, setInstanceCount)(JNIEnv *env, jobject, jlong pl, jlong obj, jint count)
{
    orca_revision rev = 0;
    check(env, orca_plater_set_instance_count(plater_of(pl), orca_object_id(obj), count, &rev));
}

JNI_FN(void, cloneObject)(JNIEnv *env, jobject, jlong pl, jlong obj, jint count)
{
    orca_object_id first = 0;
    orca_revision rev = 0;
    check(env, orca_plater_clone_object(plater_of(pl), orca_object_id(obj), count, &first, &rev));
}

JNI_FN(void, instanceToObject)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong inst)
{
    orca_object_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_instance_to_object(plater_of(pl), orca_object_id(obj), orca_instance_id(inst), &id, &rev));
}

JNI_FN(void, mergeObjects)(JNIEnv *env, jobject, jlong pl, jlongArray ids)
{
    const jsize n = env->GetArrayLength(ids);
    std::vector<jlong> v(static_cast<size_t>(n));
    env->GetLongArrayRegion(ids, 0, n, v.data());
    std::vector<orca_object_id> o(v.begin(), v.end());
    orca_object_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_merge_objects(plater_of(pl), o.data(), n, &id, &rev));
}

JNI_FN(void, convertUnits)(JNIEnv *env, jobject, jlong pl, jlong obj, jint conversion)
{
    orca_object_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_convert_units(plater_of(pl), orca_object_id(obj), conversion, &id, &rev));
}

JNI_FN(void, objectDrop)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_revision rev = 0;
    check(env, orca_plater_object_drop(plater_of(pl), orca_object_id(obj), &rev));
}

JNI_FN(jint, volumeSimplify)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jboolean use_count, jint count,
                             jdouble max_error)
{
    uint32_t tris = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_volume_simplify(plater_of(pl), orca_object_id(obj), orca_volume_id(vol),
                                                use_count ? 1 : 0, uint32_t(count), max_error, &tris, &rev)))
        return 0;
    return jint(tris);
}

JNI_FN(jint, volumeSubdivide)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol)
{
    uint32_t tris = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_volume_subdivide(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), &tris, &rev)))
        return 0;
    return jint(tris);
}

JNI_FN(jint, fillBed)(JNIEnv *env, jobject, jlong pl, jlong p, jlong obj)
{
    uint32_t added = 0;
    orca_revision rev = 0;
    if (!check(env, orca_plater_fill_bed(plater_of(pl), presets_of(p), orca_object_id(obj), 1, &added, &rev)))
        return 0;
    return jint(added);
}

JNI_FN(void, objectExportStl)(JNIEnv *env, jobject, jlong pl, jlong obj, jstring path)
{
    check(env, orca_plater_object_export_stl(plater_of(pl), orca_object_id(obj), utf8(env, path).c_str(), 1));
}

JNI_FN(void, exportStlAll)(JNIEnv *env, jobject, jlong pl, jstring path)
{
    check(env, orca_plater_export_stl_all(plater_of(pl), utf8(env, path).c_str(), 1));
}

/**
 * [thumbnail] is a top-down RGBA8 square of [thumbnailSize] pixels, or null for none.
 * A non-null [machine] writes the project for that printer instead of the selected one.
 */
JNI_FN(void, projectSave)(JNIEnv *env, jobject, jlong pl, jlong p, jstring path, jint thumbnailSize,
                          jbyteArray thumbnail, jstring machine)
{
    std::vector<uint8_t> rgba;
    // A thumbnail of the wrong size is dropped rather than failing the save.
    if (thumbnail && thumbnailSize > 0 && env->GetArrayLength(thumbnail) == jsize(thumbnailSize) * thumbnailSize * 4) {
        rgba.resize(size_t(thumbnailSize) * size_t(thumbnailSize) * 4);
        env->GetByteArrayRegion(thumbnail, 0, jsize(rgba.size()), reinterpret_cast<jbyte *>(rgba.data()));
    }
    const uint32_t size = uint32_t(rgba.empty() ? 0 : thumbnailSize);
    const uint8_t *pixels = rgba.empty() ? nullptr : rgba.data();
    if (machine)
        check(env, orca_project_export_for_machine(plater_of(pl), presets_of(p), utf8(env, machine).c_str(),
                                                   utf8(env, path).c_str(), size, pixels));
    else
        check(env, orca_project_save(plater_of(pl), presets_of(p), utf8(env, path).c_str(), size, pixels));
}

// --- height range modifiers -------------------------------------------------

/** Flat (min z, max z, layer height) triples. */
JNI_FN(jdoubleArray, heightRanges)(JNIEnv *env, jobject, jlong pl, jlong obj)
{
    orca_height_range *ranges = nullptr;
    uint32_t count = 0;
    if (!check(env, orca_plater_object_height_ranges(plater_of(pl), orca_object_id(obj), &ranges, &count)))
        return nullptr;
    std::vector<double> flat;
    flat.reserve(size_t(count) * 3);
    for (uint32_t i = 0; i < count; ++i)
        flat.insert(flat.end(), {ranges[i].min_z, ranges[i].max_z, ranges[i].layer_height});
    orca_height_ranges_dispose(ranges);
    jdoubleArray out = env->NewDoubleArray(jsize(flat.size()));
    if (!flat.empty())
        env->SetDoubleArrayRegion(out, 0, jsize(flat.size()), flat.data());
    return out;
}

JNI_FN(void, setHeightRanges)(JNIEnv *env, jobject, jlong pl, jlong obj, jdoubleArray flat)
{
    const jsize n = env->GetArrayLength(flat);
    std::vector<double> v(static_cast<size_t>(n));
    env->GetDoubleArrayRegion(flat, 0, n, v.data());
    std::vector<orca_height_range> ranges;
    for (size_t i = 0; i + 2 < v.size(); i += 3)
        ranges.push_back({v[i], v[i + 1], v[i + 2]});
    orca_revision rev = 0;
    check(env, orca_plater_object_set_height_ranges(plater_of(pl), orca_object_id(obj), ranges.data(),
                                                     uint32_t(ranges.size()), &rev));
}

// --- plates ------------------------------------------------------------------

JNI_FN(void, plateAdd)(JNIEnv *env, jobject, jlong pl)
{
    orca_plate_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_plate_add(plater_of(pl), &id, &rev));
}

JNI_FN(void, plateDelete)(JNIEnv *env, jobject, jlong pl, jlong plate)
{
    orca_revision rev = 0;
    check(env, orca_plater_plate_delete(plater_of(pl), orca_plate_id(plate), &rev));
}

JNI_FN(void, plateRename)(JNIEnv *env, jobject, jlong pl, jlong plate, jstring name)
{
    orca_revision rev = 0;
    check(env, orca_plater_plate_rename(plater_of(pl), orca_plate_id(plate), utf8(env, name).c_str(), &rev));
}

JNI_FN(void, plateLock)(JNIEnv *env, jobject, jlong pl, jlong plate, jboolean lock)
{
    orca_revision rev = 0;
    check(env, orca_plater_plate_lock(plater_of(pl), orca_plate_id(plate), lock ? 1 : 0, &rev));
}

JNI_FN(void, plateSelect)(JNIEnv *env, jobject, jlong pl, jlong plate)
{
    check(env, orca_plater_plate_select(plater_of(pl), orca_plate_id(plate), nullptr));
}

JNI_FN(void, plateDuplicate)(JNIEnv *env, jobject, jlong pl, jlong plate)
{
    orca_plate_id id = 0;
    orca_revision rev = 0;
    check(env, orca_plater_plate_duplicate(plater_of(pl), orca_plate_id(plate), &id, &rev));
}

JNI_FN(void, plateMoveToFront)(JNIEnv *env, jobject, jlong pl, jlong plate)
{
    orca_revision rev = 0;
    check(env, orca_plater_plate_move_to_front(plater_of(pl), orca_plate_id(plate), &rev));
}

/** [bed type, print sequence, spiral mode, n1, first-layer order..., n2, other-layer order...]. */
JNI_FN(jintArray, plateSettings)(JNIEnv *env, jobject, jlong pl, jlong plate)
{
    orca_plate_settings st{};
    if (!check(env, orca_plater_plate_settings(plater_of(pl), orca_plate_id(plate), &st)))
        return nullptr;
    std::vector<jint> v = {st.bed_type, st.print_sequence, st.spiral_mode, st.first_layer_sequence_count};
    v.insert(v.end(), st.first_layer_sequence, st.first_layer_sequence + std::clamp(st.first_layer_sequence_count, 0, 16));
    v.push_back(st.other_layer_sequence_count);
    v.insert(v.end(), st.other_layer_sequence, st.other_layer_sequence + std::clamp(st.other_layer_sequence_count, 0, 16));
    jintArray out = env->NewIntArray(jsize(v.size()));
    env->SetIntArrayRegion(out, 0, jsize(v.size()), v.data());
    return out;
}

JNI_FN(void, plateSetSettings)(JNIEnv *env, jobject, jlong pl, jlong plate, jintArray values)
{
    const jsize n = env->GetArrayLength(values);
    std::vector<jint> v(static_cast<size_t>(n));
    env->GetIntArrayRegion(values, 0, n, v.data());
    orca_plate_settings st{};
    size_t i = 0;
    auto next = [&]() { return i < v.size() ? v[i++] : 0; };
    st.bed_type = next();
    st.print_sequence = next();
    st.spiral_mode = next();
    st.first_layer_sequence_count = std::clamp(next(), 0, 16);
    for (int k = 0; k < st.first_layer_sequence_count; ++k)
        st.first_layer_sequence[k] = next();
    st.other_layer_sequence_count = std::clamp(next(), 0, 16);
    for (int k = 0; k < st.other_layer_sequence_count; ++k)
        st.other_layer_sequence[k] = next();
    orca_revision rev = 0;
    check(env, orca_plater_plate_set_settings(plater_of(pl), orca_plate_id(plate), &st, &rev));
}

JNI_FN(void, setExtruder)(JNIEnv *env, jobject, jlong pl, jlong obj, jlong vol, jint extruder)
{
    orca_revision rev = 0;
    check(env, orca_plater_set_extruder(plater_of(pl), orca_object_id(obj), orca_volume_id(vol), extruder, &rev));
}
