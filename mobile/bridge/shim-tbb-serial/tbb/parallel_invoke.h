// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors

#pragma once
// Serial TBB shim: tbb::parallel_invoke runs each function in order on the
// calling thread.
namespace tbb {
template <typename... Fs>
void parallel_invoke(const Fs &...fs) { (fs(), ...); }
} // namespace tbb
