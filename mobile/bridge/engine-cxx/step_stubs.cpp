// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPad contributors
// Copyright (c) 2026 OrcaPod contributors

// Loud STEP stubs for builds without OpenCASCADE (ORCAPOD_STEP=stub). The
// default build compiles the real Format/STEP.cpp instead.
#include "libslic3r/Exception.hpp"
#include "libslic3r/Format/STEP.hpp"
#include "libslic3r/Model.hpp"

#include <boost/filesystem/path.hpp>

#include <string>

namespace Slic3r {

[[noreturn]] static void step_unsupported(const char *what) {
    throw Slic3r::RuntimeError(std::string("OrcaPod: ") + what +
                               " not supported in this build (built without OpenCASCADE)");
}

bool load_step(const char *, Model *, bool &, double, double, bool, ImportStepProgressFn,
               StepIsUtf8Fn, long &) {
    step_unsupported("STEP import");
}
Step::Step(boost::filesystem::path, ImportStepProgressFn, StepIsUtf8Fn) {}
Step::Step(std::string, ImportStepProgressFn, StepIsUtf8Fn) {}
Step::~Step() = default;
Step::Step_Status Step::load(bool) { step_unsupported("STEP load"); }
Step::Step_Status Step::mesh(Model *, bool &, bool, double, double, bool) {
    step_unsupported("STEP mesh conversion");
}
unsigned int Step::get_triangle_num(double, double) { step_unsupported("STEP triangle count"); }
void Step::clean_mesh_data() {}

} // namespace Slic3r
