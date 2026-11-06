// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPad contributors

#pragma once
// OrcaPad adapter shim (sequential tbb stand-in).
#include <thread>
namespace tbb {
namespace this_task_arena {
inline int max_concurrency() { return 1; }
inline bool is_current_thread_registered() { return true; }
// Serial shim: there is no other work to isolate from, so run the body inline.
template <typename F> auto isolate(const F &f) -> decltype(f()) { return f(); }
} // namespace this_task_arena
} // namespace tbb
