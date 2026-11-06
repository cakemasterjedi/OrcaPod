// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 OrcaPod contributors
//
// Linked only into the static orcapod-slice test executable. Bionic requires
// an arm64 executable's TLS segment to be aligned to at least 64 bytes (it
// reserves the first TLS slots for itself), and static executables abort at
// startup otherwise. The segment takes the largest alignment of its members,
// so one 64-byte-aligned thread-local raises it. Shared libraries (the app's
// liborcapod.so) are not affected. Compile with -fno-emulated-tls: below API
// 29 the NDK emulates thread_local, which would leave the segment untouched.
__attribute__((used, aligned(64))) _Thread_local char orcapod_tls_align;
