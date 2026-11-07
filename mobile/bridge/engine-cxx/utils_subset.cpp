// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors
//
// The subset of src/libslic3r/utils.cpp that the headless engine closure
// links against. The real utils.cpp cannot be compiled here because it sets
// up Boost.Log sinks and Boost.Locale, which the mobile builds do not ship.
// Each function below is the upstream POSIX implementation, copied without
// behavior changes (logging dropped). When upstream changes one of them,
// copy the change here; format_stubs.cpp holds the rest of the Utils.hpp
// adapters.

#include "libslic3r/Utils.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetCacheFormat.hpp"
#include "libslic3r/Semver.hpp"

#include <boost/filesystem/directory.hpp>
#include <boost/filesystem/exception.hpp>
#include <boost/filesystem/operations.hpp>
#include <boost/filesystem/path.hpp>
#include <boost/nowide/cstdio.hpp>
#include <boost/system/error_code.hpp>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdio>
#include <initializer_list>
#include <iomanip>
#include <iterator>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <system_error>

namespace Slic3r {

std::string format_diameter_to_str(double diameter, int precision)
{
    double candidates[] = {0.2, 0.4, 0.6, 0.8};
    double best = *std::min_element(std::begin(candidates), std::end(candidates),
                                    [diameter](double a, double b) { return std::abs(a - diameter) < std::abs(b - diameter); });
    std::ostringstream oss;
    oss << std::fixed << std::setprecision(precision) << best;
    return oss.str();
}

bool is_path_within_root(const std::string &rel_path, const boost::filesystem::path &root)
{
    auto is_separator = [](char c) { return c == '/' || c == '\\'; };
    if (rel_path.empty() || is_separator(rel_path.front()) || (rel_path.size() > 1 && rel_path[1] == ':'))
        return false;
    if (rel_path.find('\0') != std::string::npos)
        return false;
    for (size_t start = 0; start <= rel_path.size();) {
        size_t end = start;
        while (end < rel_path.size() && !is_separator(rel_path[end]))
            ++end;
        if (rel_path.compare(start, end - start, "..") == 0)
            return false;
        start = end + 1;
    }
    try {
        std::string root_str = boost::filesystem::weakly_canonical(root).string();
        while (!root_str.empty() && (root_str.back() == '/' || root_str.back() == boost::filesystem::path::preferred_separator))
            root_str.pop_back();
        const std::string full_str = boost::filesystem::weakly_canonical(root / rel_path).string();
        return full_str.compare(0, root_str.size(), root_str) == 0 &&
               (full_str.size() == root_str.size() || full_str[root_str.size()] == boost::filesystem::path::preferred_separator);
    } catch (const boost::filesystem::filesystem_error &) {
        return false;
    }
}

static std::error_code write_whole_file(const std::string &path, std::initializer_list<std::string_view> chunks, bool binary)
{
    errno = 0;
    FILE *file = boost::nowide::fopen(path.c_str(), binary ? "wb" : "w");
    if (file == nullptr)
        return std::make_error_code(errno != 0 ? static_cast<std::errc>(errno) : std::errc::io_error);
    bool ok = true;
    for (const std::string_view chunk : chunks)
        ok = ok && std::fwrite(chunk.data(), 1, chunk.size(), file) == chunk.size();
    ok = ok && std::fflush(file) == 0;
    const int err = ok ? 0 : errno;
    ok = std::fclose(file) == 0 && ok;
    if (ok)
        return {};
    return std::make_error_code(err != 0 ? static_cast<std::errc>(err) : std::errc::io_error);
}

static std::error_code write_in_place(const std::string &path, std::initializer_list<std::string_view> chunks, bool binary)
{
    static auto *mutex = new std::mutex();
    std::lock_guard<std::mutex> guard(*mutex);
    return write_whole_file(path, chunks, binary);
}

std::error_code write_file_atomically(const std::string &path, std::initializer_list<std::string_view> chunks, bool binary)
{
    boost::system::error_code bec;
    const boost::filesystem::file_status target = boost::filesystem::symlink_status(path, bec);
    const bool target_exists = !bec && boost::filesystem::exists(target);
    if (target_exists && boost::filesystem::is_symlink(target)) {
        const boost::filesystem::path resolved = boost::filesystem::canonical(path, bec);
        if (!bec && boost::filesystem::is_regular_file(resolved, bec))
            return write_file_atomically(resolved.string(), chunks, binary);
    }
    if (target_exists && !boost::filesystem::is_regular_file(target))
        return write_in_place(path, chunks, binary);

    static std::atomic<unsigned> counter{0};
    const std::string tmp_path = path + "." + std::to_string(get_current_pid()) + "." + std::to_string(counter++) + ".tmp";
    if (const std::error_code ec = write_whole_file(tmp_path, chunks, binary)) {
        boost::nowide::remove(tmp_path.c_str());
        if (!target_exists)
            return ec;
        return write_in_place(path, chunks, binary);
    }
    if (target_exists)
        boost::filesystem::permissions(tmp_path, target.permissions(), bec);
    if (const std::error_code ec = rename_file(tmp_path, path)) {
        boost::nowide::remove(tmp_path.c_str());
        return write_in_place(path, chunks, binary);
    }
    return {};
}

bool is_vendor_installed(const std::string &vendor)
{
    const boost::filesystem::path dir = boost::filesystem::path(data_dir()) / PRESET_SYSTEM_DIR;
    return boost::filesystem::exists(dir / (vendor + ".json")) ||
           VendorCacheFile::usable_version((dir / (vendor + ".opc")).string(), vendor).valid();
}

std::set<std::string> vendor_names_in(const boost::filesystem::path &dir)
{
    std::set<std::string> names;
    for (auto &dir_entry : boost::filesystem::directory_iterator(dir)) {
        const auto &path = dir_entry.path();
        if (Slic3r::is_json_file(path.string()) || path.extension() == ".opc")
            names.insert(path.stem().string());
    }
    return names;
}

boost::filesystem::path extended_path(const boost::filesystem::path &path) { return path; }

} // namespace Slic3r
