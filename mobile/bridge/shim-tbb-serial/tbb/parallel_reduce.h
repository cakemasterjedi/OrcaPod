// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPad contributors
// Copyright (c) 2026 OrcaPod contributors

#pragma once
#include <type_traits>
#include "blocked_range.h"
// OrcaPad adapter shim: serial reduce (sequential tbb stand-in). The
// functional form passes the running value to the body, f(range, init), as
// oneTBB does; a body taking only the range is joined with init instead.
namespace tbb {
template <typename Range, typename Value, typename Func, typename Join>
Value parallel_reduce(const Range &r, Value init, const Func &f, const Join &j) {
    if constexpr (std::is_invocable_v<const Func &, const Range &, Value>)
        return f(r, init);
    else
        return j(init, f(r));
}
} // namespace tbb
