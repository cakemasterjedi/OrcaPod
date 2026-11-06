// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPad contributors
// Copyright (c) 2026 OrcaPod contributors

#pragma once
// Serial TBB shim: partitioner tags are accepted and ignored.
namespace tbb {
class simple_partitioner {};
class auto_partitioner {};
class static_partitioner {};
class affinity_partitioner {};
} // namespace tbb
