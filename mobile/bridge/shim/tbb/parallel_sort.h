// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors

#pragma once
// Serial TBB shim: tbb::parallel_sort is std::sort on the calling thread.
#include <algorithm>

namespace tbb {
template <typename It>
void parallel_sort(It first, It last) { std::sort(first, last); }
template <typename It, typename Compare>
void parallel_sort(It first, It last, const Compare &comp) { std::sort(first, last, comp); }
template <typename Range>
void parallel_sort(Range &range) { std::sort(std::begin(range), std::end(range)); }
template <typename Range, typename Compare>
void parallel_sort(Range &range, const Compare &comp) { std::sort(std::begin(range), std::end(range), comp); }
} // namespace tbb
