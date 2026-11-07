// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors
//
// orcapod-slice: headless command-line slicer over the C bridge.
//
// Loads OrcaSlicer's bundled system profiles, selects a printer (and
// optionally a filament and process), imports one or more models onto the
// plate, and writes G-code. It uses only the public bridge API
// (orca_bridge.h), the same surface the mobile clients call, so it doubles as
// an end-to-end check that the bridge slices on this platform.

#include "orca_bridge.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <system_error>
#include <vector>

namespace fs = std::filesystem;

namespace {

constexpr int32_t kKindMachine  = 0;
constexpr int32_t kKindFilament = 1;
constexpr int32_t kKindProcess  = 2;

// The bed box only drives plate assignment; the engine's own validation
// enforces the printer's printable_height when slicing.
constexpr double kBedBoxHeight = 10000.0;

void usage(FILE *out)
{
    fputs(
        "Usage:\n"
        "  orcapod-slice --profiles DIR --printer NAME [--filament NAME]\n"
        "                [--process NAME] [--arrange] [--data-dir DIR]\n"
        "                -o OUTPUT.gcode MODEL...\n"
        "  orcapod-slice --profiles DIR [--printer NAME] --list KIND\n"
        "\n"
        "  --profiles DIR   OrcaSlicer system profiles (resources/profiles)\n"
        "  --printer NAME   printer preset; its default process and filament\n"
        "                   are used unless overridden\n"
        "  --filament NAME  filament preset\n"
        "  --process NAME   process (print settings) preset\n"
        "  --arrange        arrange all objects instead of centering them\n"
        "  --data-dir DIR   preset data directory to create and use\n"
        "                   (default: a fresh temporary directory)\n"
        "  --list KIND      list printer, filament or process presets\n"
        "                   (filament/process lists follow --printer)\n"
        "  -o FILE          G-code output path\n"
        "  MODEL            STL, OBJ, AMF or 3MF file(s)\n",
        out);
}

// Prints the diagnostic of a failed call and disposes the result.
bool check(orca_result result, const char *what)
{
    const bool ok = result.code == ORCA_STATUS_OK;
    if (!ok)
        fprintf(stderr, "error: %s failed (status %d): %s\n", what, int(result.code),
                result.diagnostic.data ? result.diagnostic.data : "");
    orca_result_dispose(&result);
    return ok;
}

std::string take(orca_owned_string &s)
{
    std::string out = s.data ? std::string(s.data, size_t(s.length)) : std::string();
    orca_owned_string_dispose(&s);
    return out;
}

// The bridge expects <data>/system to hold the vendor profile tree and
// <data>/user/default for user presets.
bool prepare_data_dir(const fs::path &data_dir, const fs::path &profiles)
{
    std::error_code ec;
    fs::create_directories(data_dir / "user" / "default", ec);
    if (ec) {
        fprintf(stderr, "error: cannot create %s: %s\n", data_dir.c_str(), ec.message().c_str());
        return false;
    }
    const fs::path system = data_dir / "system";
    if (fs::is_symlink(system))
        fs::remove(system, ec);
    if (!fs::exists(system)) {
        fs::create_directory_symlink(fs::absolute(profiles), system, ec);
        if (ec) {
            fprintf(stderr, "error: cannot link %s: %s\n", system.c_str(), ec.message().c_str());
            return false;
        }
    }
    return true;
}

bool current_preset(orca_presets *presets, int32_t kind, std::string &out)
{
    orca_owned_string name{};
    if (!check(orca_presets_current(presets, kind, &name), "orca_presets_current"))
        return false;
    out = take(name);
    return true;
}

int list_presets(orca_presets *presets, const std::string &kind_name)
{
    int32_t kind;
    if (kind_name == "printer") kind = kKindMachine;
    else if (kind_name == "filament") kind = kKindFilament;
    else if (kind_name == "process") kind = kKindProcess;
    else {
        fprintf(stderr, "error: --list takes printer, filament or process\n");
        return 2;
    }
    int32_t count = 0;
    if (!check(orca_presets_list_count(presets, kind, &count), "orca_presets_list_count"))
        return 1;
    for (int32_t i = 0; i < count; ++i) {
        orca_preset_entry entry{};
        if (!check(orca_presets_list_entry(presets, kind, i, &entry), "orca_presets_list_entry"))
            return 1;
        if (entry.compatible)
            printf("%.*s\n", int(entry.name.length), entry.name.data);
        orca_preset_entry_dispose(&entry);
    }
    return 0;
}

void print_progress(int32_t percent, const char *message, void *)
{
    fprintf(stderr, "[%3d%%] %s\n", int(percent), message ? message : "");
}

struct Options
{
    std::string profiles, data_dir, printer, filament, process, output, list;
    bool arrange = false;
    std::vector<std::string> models;
};

bool parse(int argc, char **argv, Options &o)
{
    for (int i = 1; i < argc; ++i) {
        const std::string a = argv[i];
        auto value = [&](std::string &dst) {
            if (i + 1 >= argc) {
                fprintf(stderr, "error: %s needs a value\n", a.c_str());
                return false;
            }
            dst = argv[++i];
            return true;
        };
        bool ok = true;
        if (a == "--profiles") ok = value(o.profiles);
        else if (a == "--data-dir") ok = value(o.data_dir);
        else if (a == "--printer") ok = value(o.printer);
        else if (a == "--filament") ok = value(o.filament);
        else if (a == "--process") ok = value(o.process);
        else if (a == "--list") ok = value(o.list);
        else if (a == "-o" || a == "--output") ok = value(o.output);
        else if (a == "--arrange") o.arrange = true;
        else if (a == "-h" || a == "--help") { usage(stdout); exit(0); }
        else if (!a.empty() && a[0] == '-') {
            fprintf(stderr, "error: unknown option %s\n", a.c_str());
            ok = false;
        } else o.models.push_back(a);
        if (!ok) return false;
    }
    if (o.profiles.empty()) {
        fprintf(stderr, "error: --profiles is required\n");
        return false;
    }
    if (o.list.empty() && (o.printer.empty() || o.output.empty() || o.models.empty())) {
        fprintf(stderr, "error: --printer, -o and at least one model are required\n");
        return false;
    }
    return true;
}

int run(const Options &o, orca_presets *presets)
{
    if (!o.printer.empty() &&
        !check(orca_presets_select_machine(presets, o.printer.c_str()), "selecting the printer"))
        return 1;
    if (!o.list.empty())
        return list_presets(presets, o.list);

    std::string machine, filament, process;
    if (!current_preset(presets, kKindMachine, machine) ||
        !current_preset(presets, kKindFilament, filament) ||
        !current_preset(presets, kKindProcess, process))
        return 1;
    if (!o.filament.empty()) filament = o.filament;
    if (!o.process.empty()) process = o.process;
    if (!check(orca_presets_select(presets, machine.c_str(), filament.c_str(), process.c_str()),
               "selecting presets"))
        return 1;
    fprintf(stderr, "printer:  %s\nfilament: %s\nprocess:  %s\n", machine.c_str(),
            filament.c_str(), process.c_str());

    double min_x = 0, min_y = 0, max_x = 0, max_y = 0;
    if (!check(orca_presets_print_volume(presets, &min_x, &min_y, &max_x, &max_y),
               "reading the print volume"))
        return 1;

    orca_plater *plater = nullptr;
    if (!check(orca_plater_new(&plater), "orca_plater_new"))
        return 1;
    int rc = 1;
    do {
        if (!check(orca_plater_set_bed(plater, min_x, min_y, max_x - min_x, max_y - min_y,
                                       kBedBoxHeight, nullptr),
                   "setting the bed"))
            break;
        const double cx = (min_x + max_x) / 2, cy = (min_y + max_y) / 2;
        bool imported = true;
        for (const std::string &model : o.models) {
            orca_object_id first = 0;
            uint32_t count = 0;
            orca_revision rev = 0;
            const std::string what = "importing " + model;
            if (!check(orca_plater_import_model(plater, model.c_str(), cx, cy, &first, &count, &rev),
                       what.c_str())) {
                imported = false;
                break;
            }
        }
        if (!imported)
            break;
        if (o.arrange || o.models.size() > 1) {
            uint32_t unprintable = 0;
            orca_revision rev = 0;
            if (!check(orca_plater_arrange(plater, presets, 1, &unprintable, &rev), "arranging"))
                break;
            if (unprintable > 0) {
                fprintf(stderr, "error: %u object(s) do not fit on the bed\n", unprintable);
                break;
            }
        }
        orca_slice_report report{};
        if (!check(orca_plater_slice_to_gcode_with_presets(plater, presets, o.output.c_str(),
                                                           print_progress, nullptr, nullptr,
                                                           &report),
                   "slicing"))
            break;
        for (uint32_t i = 0; i < report.warning_count; ++i) {
            const orca_slice_warning &w = report.warnings[i];
            fprintf(stderr, "warning: %.*s %.*s\n", int(w.code.length), w.code.data ? w.code.data : "",
                    int(w.message.length), w.message.data ? w.message.data : "");
        }
        printf("wrote %s (%lld bytes, estimated %.0f s)\n", o.output.c_str(),
               static_cast<long long>(report.gcode_bytes), report.time_estimate_s);
        orca_slice_report_dispose(&report);
        rc = 0;
    } while (false);
    orca_plater_free(plater);
    return rc;
}

} // namespace

int main(int argc, char **argv)
{
    Options o;
    if (!parse(argc, argv, o)) {
        usage(stderr);
        return 2;
    }
    if (orca_bridge_abi_version() != ORCA_BRIDGE_ABI_VERSION) {
        fprintf(stderr, "error: bridge ABI %u does not match header ABI %u\n",
                orca_bridge_abi_version(), ORCA_BRIDGE_ABI_VERSION);
        return 1;
    }

    fs::path data_dir = o.data_dir;
    bool temporary = false;
    if (data_dir.empty()) {
        std::string tmpl = (fs::temp_directory_path() / "orcapod-slice-XXXXXX").string();
        if (!mkdtemp(tmpl.data())) {
            perror("error: mkdtemp");
            return 1;
        }
        data_dir = tmpl;
        temporary = true;
    }
    if (!prepare_data_dir(data_dir, o.profiles))
        return 1;

    int rc = 1;
    orca_presets *presets = nullptr;
    if (check(orca_presets_new(data_dir.c_str(), &presets), "loading profiles")) {
        rc = run(o, presets);
        orca_presets_free(presets);
    }
    if (temporary) {
        std::error_code ec;
        fs::remove_all(data_dir, ec);
    }
    return rc;
}
